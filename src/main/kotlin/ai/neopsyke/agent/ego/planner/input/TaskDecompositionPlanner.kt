package ai.neopsyke.agent.ego.planner.input

import com.fasterxml.jackson.annotation.JsonProperty
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.ego.planner.HierarchicalEgoPlanner
import ai.neopsyke.agent.ego.planner.LaneId
import ai.neopsyke.agent.ego.planner.prompt.SharedPromptSections
import ai.neopsyke.agent.ego.planner.runtime.PlannerRuntime
import ai.neopsyke.agent.ego.planner.runtime.StructuredOutputHandler
import ai.neopsyke.agent.model.EgoDecision
import ai.neopsyke.agent.model.EgoTrigger
import ai.neopsyke.agent.model.PlannerContext
import ai.neopsyke.agent.model.Urgency
import ai.neopsyke.agent.support.PromptBudgetAllocator
import ai.neopsyke.agent.support.TextSecurity
import ai.neopsyke.instrumentation.AgentInstrumentation
import ai.neopsyke.prompt.PromptCatalog

/**
 * L2 sub-planner: decomposes multi-step tasks into ordered plan steps.
 * Emits typed PlanDecomposition via EgoDecision.EnqueuePlan.
 */
class TaskDecompositionPlanner(
    private val runtime: PlannerRuntime,
    private val config: AgentConfig,
    private val instrumentation: AgentInstrumentation,
    private val promptCatalog: PromptCatalog = PromptCatalog.shared,
) {
    fun plan(trigger: EgoTrigger.IncomingInput, context: PlannerContext): EgoDecision {
        val metadata = HierarchicalEgoPlanner.plannerChatMetadata(
            trigger = trigger,
            callSite = "task_decomposition",
            sessionId = context.conversationContext.sessionId,
            rootInputId = trigger.input.rootInputId,
        )

        val prompt = promptCatalog.renderSections(
            "planner/task-decomposition",
            mapOf("max_plan_steps" to config.planner.maxPlanSteps.toString())
        )
        val schema = promptCatalog.responseFormat("task-decomposition")
        val sections = listOfNotNull(
            *prompt.sections.toTypedArray(),
            SharedPromptSections.recentDialogueSection(context),
            SharedPromptSections.shortTermSummarySection(context),
            SharedPromptSections.actionAvailabilitySection(context),
            SharedPromptSections.evidenceHintsSection(context),
            SharedPromptSections.groundingRequirementSection(context),
            SharedPromptSections.triggerSection(trigger),
        )

        val allocation = PromptBudgetAllocator.allocate(sections, config.maxLlmPromptTokens)
        runtime.emitPromptBudgetTelemetry(LaneId.TASK_DECOMPOSITION, allocation.diagnostics)

        val response = runtime.call(
            laneId = LaneId.TASK_DECOMPOSITION,
            messages = allocation.messages,
            metadata = promptCatalog.metadata(metadata, prompt, schema),
            responseFormat = schema.format,
        )

        if (response == null) {
            return EgoDecision.Noop("TaskDecompositionPlanner unavailable.")
        }

        val payload = StructuredOutputHandler.parseWithRepair<TaskDecompPayload>(response.content)
        if (payload == null) {
            return EgoDecision.Noop("TaskDecompositionPlanner parse failure.")
        }

        val assignment = payload.assignment?.trim().orEmpty()
        val steps = payload.steps
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.take(config.planner.maxPlanSteps)
            ?.map { TextSecurity.clamp(it, config.planner.maxPlanStepDescriptionChars) }
            .orEmpty()

        if (assignment.isBlank() || steps.isEmpty()) {
            return EgoDecision.Noop("TaskDecompositionPlanner returned plan with missing assignment or empty steps.")
        }

        return EgoDecision.EnqueuePlan(
            urgency = Urgency.fromRaw(payload.urgency),
            assignment = TextSecurity.clamp(assignment, config.planner.maxThoughtChars),
            steps = steps,
        )
    }

    private data class TaskDecompPayload(
        val assignment: String? = null,
        val steps: List<String>? = null,
        val urgency: String? = null,
    )

}
