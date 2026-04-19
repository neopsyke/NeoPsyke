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
import ai.neopsyke.llm.ChatResponseFormat
import ai.neopsyke.llm.ChatRole

/**
 * L2 sub-planner: decomposes multi-step tasks into ordered plan steps.
 * Emits typed PlanDecomposition via EgoDecision.EnqueuePlan.
 */
class TaskDecompositionPlanner(
    private val runtime: PlannerRuntime,
    private val config: AgentConfig,
    private val instrumentation: AgentInstrumentation,
) {
    fun plan(trigger: EgoTrigger.IncomingInput, context: PlannerContext): EgoDecision {
        val metadata = HierarchicalEgoPlanner.plannerChatMetadata(
            trigger = trigger,
            callSite = "task_decomposition",
            sessionId = context.conversationContext.sessionId,
            rootInputId = trigger.input.rootInputId,
        )

        val sections = listOfNotNull(
            PromptBudgetAllocator.Section(
                key = "task_decomp_system",
                role = ChatRole.SYSTEM,
                band = PromptBudgetAllocator.Band.REQUIRED_CORE,
                importance = PromptBudgetAllocator.Importance.MEDIUM,
                floorTokens = 36,
                content = """
                    You are a task decomposition planner. The user's request requires multiple sequential stages.
                    Return STRICT JSON only.
                    Decompose the task into ordered steps. Each step is a concise directive (<=120 chars).
                    Maximum ${config.planner.maxPlanSteps} steps.
                    The planner will re-evaluate each step when it is reached.
                    Do not decompose simple tasks into unnecessary steps.
                    Each step should represent a meaningful unit of work.
                """.trimIndent()
            ),
            PromptBudgetAllocator.Section(
                key = "task_decomp_schema",
                role = ChatRole.SYSTEM,
                band = PromptBudgetAllocator.Band.REQUIRED_CORE,
                floorTokens = 16,
                content = """
                    JSON schema:
                    {"assignment":"overall assignment","steps":["step 1 directive","step 2 directive"],"urgency":"low|medium|high"}
                """.trimIndent()
            ),
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
            metadata = metadata,
            responseFormat = TASK_DECOMP_FORMAT,
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

    private companion object {
        val TASK_DECOMP_FORMAT = ChatResponseFormat.JsonSchema(
            name = "task_decomposition",
            schemaJson = """
                {
                  "type": "object",
                  "additionalProperties": false,
                  "required": ["assignment", "steps", "urgency"],
                  "properties": {
                    "assignment": { "type": ["string", "null"], "maxLength": 600 },
                    "steps": { "type": ["array", "null"], "items": { "type": "string", "maxLength": 120 }, "maxItems": 6 },
                    "urgency": { "type": ["string", "null"], "enum": ["low", "medium", "high", null] }
                  }
                }
            """.trimIndent(),
            strict = true,
            relaxedSchemaJson = """
                {
                  "type": "object",
                  "additionalProperties": false,
                  "required": ["assignment", "steps", "urgency"],
                  "properties": {
                    "assignment": { "type": ["string", "null"] },
                    "steps": { "type": ["array", "null"], "items": { "type": "string" } },
                    "urgency": { "type": ["string", "null"], "enum": ["low", "medium", "high", null] }
                  }
                }
            """.trimIndent(),
        )
    }
}
