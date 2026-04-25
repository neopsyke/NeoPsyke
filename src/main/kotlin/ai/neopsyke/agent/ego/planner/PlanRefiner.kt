package ai.neopsyke.agent.ego.planner

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import ai.neopsyke.agent.ego.planner.runtime.PlannerRuntime
import ai.neopsyke.agent.support.RetryPolicy
import ai.neopsyke.instrumentation.AgentEvent
import ai.neopsyke.instrumentation.AgentInstrumentation
import ai.neopsyke.llm.ChatCallMetadata
import ai.neopsyke.llm.ChatMessage
import ai.neopsyke.prompt.PromptCatalog
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

// ── Domain types ──

enum class PlanKind {
    INLINE_EGO,
    ASSIGNMENT_CREATE,
    ASSIGNMENT_REVISE,
}

enum class TerminalPolicy {
    MUST_END_WITH_USER_DELIVERY,
    MAY_END_WITH_USER_DELIVERY,
    DELIVERY_CONTROLLED_BY_WORK_ITEM,
}

data class PlanStepCandidate(
    val id: String,
    val description: String,
    val acceptanceCriteria: String = "",
    val groundingRequirement: String = "not_required",
    val requires: Set<String> = emptySet(),
    val produces: Set<String> = emptySet(),
    val maxAttempts: Int = 3,
)

data class ActionSummary(
    val actionType: String,
    val description: String,
)

data class PlanRefinementRequest(
    val planKind: PlanKind,
    val terminalPolicy: TerminalPolicy,
    val assignment: String,
    val instruction: String,
    val completionCriteria: String = "",
    val steps: List<PlanStepCandidate>,
    val availableActions: List<ActionSummary>,
    val runtimeFacts: Map<String, String>,
    val recentDialogue: List<String> = emptyList(),
    val shortTermContextSummary: String = "",
    val longTermMemoryRecall: String = "",
    val episodicRecall: String = "",
    val evidenceHints: String = "",
    val userFeedbackHint: String? = null,
    val cronExpression: String? = null,
)

enum class PlanRefinementMode {
    UNCHANGED,
    LLM_REWRITTEN,
}

data class DroppedStep(
    val originalId: String,
    val reason: String,
)

data class PlanRefinementResult(
    val steps: List<PlanStepCandidate>,
    val droppedSteps: List<DroppedStep> = emptyList(),
    val refinementMode: PlanRefinementMode = PlanRefinementMode.UNCHANGED,
    val reason: String = "",
)

// ── Interface ──

interface PlanRefiner {
    fun refine(request: PlanRefinementRequest): PlanRefinementResult
}

// ── NoopPlanRefiner ──

class NoopPlanRefiner : PlanRefiner {
    override fun refine(request: PlanRefinementRequest): PlanRefinementResult =
        PlanRefinementResult(
            steps = request.steps,
            refinementMode = PlanRefinementMode.UNCHANGED,
            reason = "Refinement disabled.",
        )
}

// ── LlmPlanRefiner ──

class LlmPlanRefiner(
    private val runtime: PlannerRuntime,
    private val instrumentation: AgentInstrumentation,
    private val promptCatalog: PromptCatalog = PromptCatalog.shared,
) : PlanRefiner {

    override fun refine(request: PlanRefinementRequest): PlanRefinementResult {
        val originalSteps = request.steps
        if (originalSteps.isEmpty()) {
            return PlanRefinementResult(
                steps = originalSteps,
                refinementMode = PlanRefinementMode.UNCHANGED,
                reason = "Empty plan; nothing to refine.",
            )
        }

        val prompt = buildPrompt(request)
        val messages = prompt.sections.map { ChatMessage(role = it.role, content = it.content) }
        val schema = promptCatalog.responseFormat("plan-refinement-result")

        val metadata = ChatCallMetadata(
            actor = "plan_refiner",
            callSite = "plan_refiner_refine",
            cognitiveRole = "planner",
        )

        val response = runtime.call(
            laneId = LaneId.PLAN_REFINER,
            messages = messages,
            metadata = promptCatalog.metadata(metadata, prompt, schema),
            responseFormat = schema.format,
            temperature = 0.0,
        )

        if (response == null) {
            emitFallback("llm_unavailable", request)
            return fallbackResult(originalSteps, "LLM unavailable; keeping original plan.")
        }

        val parsed = try {
            mapper.readValue<RefinerResponsePayload>(response.content)
        } catch (ex: Exception) {
            logger.warn(ex) { "Plan refiner response parse failure; falling back to original plan." }
            emitFallback("parse_failure", request)
            return fallbackResult(originalSteps, "Parse failure; keeping original plan.")
        }

        val refinedSteps = parsed.steps
            ?.mapNotNull { step ->
                val desc = step.description?.trim().orEmpty()
                if (desc.isBlank()) null
                else PlanStepCandidate(
                    id = step.id?.trim().orEmpty().ifBlank { "" },
                    description = desc,
                    acceptanceCriteria = step.acceptanceCriteria?.trim().orEmpty(),
                    groundingRequirement = step.groundingRequirement?.trim()?.lowercase() ?: "not_required",
                    requires = step.requires.orEmpty().toSet(),
                    produces = step.produces.orEmpty().toSet(),
                    maxAttempts = (step.maxAttempts ?: DEFAULT_MAX_ATTEMPTS).coerceIn(1, MAX_STEP_ATTEMPTS),
                )
            }
            .orEmpty()

        if (refinedSteps.isEmpty()) {
            logger.warn { "Plan refiner returned empty steps; falling back to original plan." }
            emitFallback("empty_refined_steps", request)
            return fallbackResult(originalSteps, "Refiner returned empty steps; keeping original plan.")
        }

        val droppedSteps = parsed.droppedSteps
            ?.mapNotNull { d ->
                val id = d.originalId?.trim().orEmpty()
                if (id.isBlank()) null else DroppedStep(id, d.reason?.trim().orEmpty())
            }
            .orEmpty()

        val mode = when (parsed.refinementMode?.trim()?.lowercase()) {
            "llm_rewritten" -> PlanRefinementMode.LLM_REWRITTEN
            "unchanged" -> PlanRefinementMode.UNCHANGED
            else -> PlanRefinementMode.LLM_REWRITTEN
        }

        val result = PlanRefinementResult(
            steps = refinedSteps,
            droppedSteps = droppedSteps,
            refinementMode = mode,
            reason = parsed.reason?.trim().orEmpty(),
        )
        logger.debug {
            "Plan refinement completed: plan_kind=${request.planKind.name.lowercase()} " +
                "mode=${mode.name.lowercase()} " +
                "original_steps=${request.steps.size} refined_steps=${refinedSteps.size} " +
                "dropped_steps=${droppedSteps.size} reason='${result.reason}'"
        }
        return result
    }

    private fun buildPrompt(request: PlanRefinementRequest): PromptCatalog.RenderedPrompt {
        val terminalPolicyRule = when (request.terminalPolicy) {
            TerminalPolicy.MUST_END_WITH_USER_DELIVERY ->
                "Terminal policy: the plan MUST end with a step that delivers output to the user."
            TerminalPolicy.MAY_END_WITH_USER_DELIVERY ->
                "Terminal policy: the plan MAY end with a user delivery step but is not required to."
            TerminalPolicy.DELIVERY_CONTROLLED_BY_WORK_ITEM ->
                "Terminal policy: user delivery is controlled by the work item runtime; do not add or require a final delivery step."
        }
        val schedulingGuard = if (request.planKind == PlanKind.ASSIGNMENT_CREATE && request.cronExpression != null) {
            "7. Scheduling guard: this is a recurring scheduled assignment (cron: ${request.cronExpression}). " +
                "Scheduling/registration is handled by the runtime at assignment creation time. " +
                "Do NOT add steps to create, register, or schedule the work item; only include steps for the actual work payload."
        } else {
            ""
        }
        val stepsJson = mapper.writeValueAsString(request.steps.map { step ->
            mapOf(
                "id" to step.id,
                "description" to step.description,
                "acceptance_criteria" to step.acceptanceCriteria,
                "grounding_requirement" to step.groundingRequirement,
                "requires" to step.requires,
                "produces" to step.produces,
                "max_attempts" to step.maxAttempts,
            )
        })
        return promptCatalog.renderSections(
            "planner/plan-refiner",
            mapOf(
                "plan_kind" to request.planKind.name.lowercase(),
                "terminal_policy_rule" to terminalPolicyRule,
                "scheduling_guard" to schedulingGuard,
                "recoverability_rule_number" to if (schedulingGuard.isBlank()) "7" else "8",
                "runtime_facts" to request.runtimeFacts.entries.joinToString("\n") { (k, v) -> "- $k: $v" }.ifBlank { "none" },
                "available_actions" to request.availableActions.joinToString("\n") { "- ${it.actionType}: ${it.description}" }.ifBlank { "none" },
                "assignment" to request.assignment,
                "instruction" to request.instruction,
                "completion_criteria_block" to request.completionCriteria.toPromptBlock("Completion criteria"),
                "user_feedback_block" to request.userFeedbackHint.orEmpty().toPromptBlock("User feedback"),
                "short_term_context_block" to request.shortTermContextSummary.toPromptBlock("Short-term context"),
                "long_term_memory_block" to request.longTermMemoryRecall.toPromptBlock("Long-term memory recall"),
                "episodic_recall_block" to request.episodicRecall.toPromptBlock("Episodic recall"),
                "evidence_hints_block" to request.evidenceHints.toPromptBlock("Evidence hints"),
                "steps_json" to stepsJson,
            )
        )
    }

    private fun String.toPromptBlock(label: String): String =
        takeIf { it.isNotBlank() }?.let { "$label: $it" }.orEmpty()

    private fun fallbackResult(originalSteps: List<PlanStepCandidate>, reason: String): PlanRefinementResult =
        PlanRefinementResult(
            steps = originalSteps,
            refinementMode = PlanRefinementMode.UNCHANGED,
            reason = reason,
        )

    private fun emitFallback(reason: String, request: PlanRefinementRequest) {
        instrumentation.emit(
            AgentEvent(
                type = "plan_refinement_fallback",
                data = mapOf(
                    "reason" to reason,
                    "plan_kind" to request.planKind.name.lowercase(),
                    "original_step_count" to request.steps.size,
                )
            )
        )
    }

    // ── JSON response model ──

    private data class RefinerResponsePayload(
        val steps: List<RefinerStepPayload>? = null,
        @param:JsonProperty("dropped_steps")
        val droppedSteps: List<RefinerDroppedStepPayload>? = null,
        @param:JsonProperty("refinement_mode")
        val refinementMode: String? = null,
        val reason: String? = null,
    )

    private data class RefinerStepPayload(
        val id: String? = null,
        val description: String? = null,
        @param:JsonProperty("acceptance_criteria")
        val acceptanceCriteria: String? = null,
        @param:JsonProperty("grounding_requirement")
        val groundingRequirement: String? = null,
        val requires: List<String>? = null,
        val produces: List<String>? = null,
        @param:JsonProperty("max_attempts")
        val maxAttempts: Int? = null,
    )

    private data class RefinerDroppedStepPayload(
        @param:JsonProperty("original_id")
        val originalId: String? = null,
        val reason: String? = null,
    )

    companion object {
        const val DEFAULT_MAX_ATTEMPTS: Int = 3
        const val MAX_STEP_ATTEMPTS: Int = 10

        private val mapper = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }
}
