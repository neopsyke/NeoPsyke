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
import ai.neopsyke.llm.ChatRequestOptions
import ai.neopsyke.llm.ChatResponseFormat
import ai.neopsyke.llm.ChatRole
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

// ── Domain types ──

enum class PlanKind {
    INLINE_EGO,
    DURABLE_WORK_CREATE,
    DURABLE_WORK_REVISE,
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
    val goal: String,
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

        val systemPrompt = buildSystemPrompt(request)
        val userPrompt = buildUserPrompt(request)

        val messages = listOf(
            ChatMessage(ChatRole.SYSTEM, systemPrompt),
            ChatMessage(ChatRole.USER, userPrompt),
        )

        val metadata = ChatCallMetadata(
            actor = "plan_refiner",
            callSite = "plan_refiner_refine",
            cognitiveRole = "planner",
        )

        val response = runtime.call(
            laneId = LaneId.PLAN_REFINER,
            messages = messages,
            metadata = metadata,
            responseFormat = REFINER_RESPONSE_FORMAT,
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

    private fun buildSystemPrompt(request: PlanRefinementRequest): String = buildString {
        appendLine("You are a plan refiner for an autonomous agent.")
        appendLine("Your job: validate and improve the given plan while preserving intent.")
        appendLine()
        appendLine("Philosophy:")
        appendLine("- Preserve intent over literal wording.")
        appendLine("- Do not reject a plan just because it is messy.")
        appendLine("- Repair malformed or partial structure when meaning is still recoverable.")
        appendLine("- Prefer conservative edits over aggressive rewrites.")
        appendLine("- Do not invent new requirements unless strongly implied by the goal, context, or existing steps.")
        appendLine("- If uncertain, keep more of the original plan rather than dropping intent.")
        appendLine("- Return one canonical typed plan every time.")
        appendLine()
        appendLine("Validation rubric:")
        appendLine("1. Achievability: is each step plausibly executable using the available actions?")
        appendLine("2. Non-redundancy: does each step add value beyond runtime facts and prior steps?")
        appendLine("3. Data flow: do step outputs feed forward correctly via requires/produces?")
        appendLine("4. Minimal sufficiency: can steps be merged or dropped without losing capability?")
        appendLine("5. Contract preservation: preserve requires, produces, and maxAttempts unless there is a concrete reason to change them.")

        when (request.terminalPolicy) {
            TerminalPolicy.MUST_END_WITH_USER_DELIVERY ->
                appendLine("6. Terminal policy: the plan MUST end with a step that delivers output to the user.")
            TerminalPolicy.MAY_END_WITH_USER_DELIVERY ->
                appendLine("6. Terminal policy: the plan MAY end with a user delivery step but is not required to.")
            TerminalPolicy.DELIVERY_CONTROLLED_BY_WORK_ITEM ->
                appendLine("6. Terminal policy: user delivery is controlled by the work item runtime; do not add or require a final delivery step.")
        }

        if (request.planKind == PlanKind.DURABLE_WORK_CREATE && request.cronExpression != null) {
            appendLine("7. Scheduling guard: this is a recurring scheduled goal (cron: ${request.cronExpression}). " +
                "Scheduling/registration is handled by the runtime at goal creation time. " +
                "Do NOT add steps to create, register, or schedule the work item — only include steps for the actual work payload.")
        }
        appendLine("${if (request.cronExpression != null) "8" else "7"}. Recoverability: if the plan is malformed or underspecified but the intended meaning is recoverable, repair it instead of rejecting it.")
        appendLine()
        appendLine("Plan kind: ${request.planKind.name.lowercase()}")
        appendLine()
        appendLine("Runtime facts (always available to the executor, do NOT create steps for these):")
        request.runtimeFacts.forEach { (k, v) -> appendLine("- $k: $v") }
        appendLine()
        appendLine("Available actions:")
        request.availableActions.forEach { a -> appendLine("- ${a.actionType}: ${a.description}") }
        appendLine()
        appendLine("Return strict JSON only, matching the response schema.")
        appendLine("If the plan is already good, set refinement_mode to 'unchanged' and return it as-is.")
        appendLine("If you make changes, set refinement_mode to 'llm_rewritten'.")
    }

    private fun buildUserPrompt(request: PlanRefinementRequest): String = buildString {
        appendLine("Goal: ${request.goal}")
        appendLine("Instruction: ${request.instruction}")
        if (request.completionCriteria.isNotBlank()) {
            appendLine("Completion criteria: ${request.completionCriteria}")
        }
        if (!request.userFeedbackHint.isNullOrBlank()) {
            appendLine("User feedback: ${request.userFeedbackHint}")
        }
        if (request.shortTermContextSummary.isNotBlank()) {
            appendLine("Short-term context: ${request.shortTermContextSummary}")
        }
        if (request.longTermMemoryRecall.isNotBlank()) {
            appendLine("Long-term memory recall: ${request.longTermMemoryRecall}")
        }
        if (request.episodicRecall.isNotBlank()) {
            appendLine("Episodic recall: ${request.episodicRecall}")
        }
        if (request.evidenceHints.isNotBlank()) {
            appendLine("Evidence hints: ${request.evidenceHints}")
        }
        appendLine()
        appendLine("Current plan steps:")
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
        appendLine(stepsJson)
    }

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

        val REFINER_RESPONSE_FORMAT = ChatResponseFormat.JsonSchema(
            name = "plan_refinement_result",
            schemaJson = """
                {
                  "type": "object",
                  "additionalProperties": false,
                  "required": ["steps", "dropped_steps", "refinement_mode", "reason"],
                  "properties": {
                    "steps": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "additionalProperties": false,
                        "required": ["id", "description", "acceptance_criteria", "grounding_requirement", "requires", "produces", "max_attempts"],
                        "properties": {
                          "id": { "type": "string" },
                          "description": { "type": "string" },
                          "acceptance_criteria": { "type": "string" },
                          "grounding_requirement": { "type": "string", "enum": ["required", "not_required"] },
                          "requires": { "type": "array", "items": { "type": "string" } },
                          "produces": { "type": "array", "items": { "type": "string" } },
                          "max_attempts": { "type": "integer", "minimum": 1 }
                        }
                      }
                    },
                    "dropped_steps": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "additionalProperties": false,
                        "required": ["original_id", "reason"],
                        "properties": {
                          "original_id": { "type": "string" },
                          "reason": { "type": "string" }
                        }
                      }
                    },
                    "refinement_mode": { "type": "string", "enum": ["unchanged", "llm_rewritten"] },
                    "reason": { "type": "string" }
                  }
                }
            """.trimIndent(),
            strict = true,
        )
    }
}
