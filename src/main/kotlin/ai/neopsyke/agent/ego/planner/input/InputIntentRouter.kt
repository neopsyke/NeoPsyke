package ai.neopsyke.agent.ego.planner.input

import com.fasterxml.jackson.annotation.JsonProperty
import mu.KotlinLogging
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.ego.planner.HierarchicalEgoPlanner
import ai.neopsyke.agent.ego.planner.LaneId
import ai.neopsyke.agent.ego.planner.model.InputRoute
import ai.neopsyke.agent.ego.planner.prompt.SharedPromptSections
import ai.neopsyke.agent.ego.planner.runtime.PlannerRuntime
import ai.neopsyke.agent.ego.planner.runtime.StructuredOutputHandler
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.EgoTrigger
import ai.neopsyke.agent.model.PlannerContext
import ai.neopsyke.agent.support.PromptBudgetAllocator
import ai.neopsyke.instrumentation.AgentEvents
import ai.neopsyke.instrumentation.AgentInstrumentation
import ai.neopsyke.llm.ChatResponseFormat
import ai.neopsyke.llm.ChatRole

private val logger = KotlinLogging.logger {}

/**
 * L2 semantic router: classifies fresh user input into one of the InputRoute variants.
 * Returns a typed route, not a final action decision.
 */
class InputIntentRouter(
    private val runtime: PlannerRuntime,
    private val config: AgentConfig,
    private val instrumentation: AgentInstrumentation,
) {
    fun route(trigger: EgoTrigger.IncomingInput, context: PlannerContext): InputRoute {
        val rootInputId = trigger.input.rootInputId
        val sessionId = context.conversationContext.sessionId
        val metadata = HierarchicalEgoPlanner.plannerChatMetadata(
            trigger = trigger,
            callSite = "input_intent_router",
            sessionId = sessionId,
            rootInputId = rootInputId,
        )

        val goalsAvailable = ActionType.DURABLE_WORK_OPERATION in context.availableActions
        val routeOptions = buildRouteOptions(goalsAvailable)
        val actionSummary = context.availableActions.map { it.id }.sorted().joinToString(", ")

        val sections = listOfNotNull(
            PromptBudgetAllocator.Section(
                key = "router_system",
                role = ChatRole.SYSTEM,
                band = PromptBudgetAllocator.Band.REQUIRED_CORE,
                importance = PromptBudgetAllocator.Importance.HIGH,
                floorTokens = 48,
                content = """
                    You are an intent classifier for an action planner.
                    Given a user input, classify the intent into exactly one route type.
                    Return STRICT JSON only.
                    Routes:
                    $routeOptions
                    Rules:
                    - direct_response: the request can be answered directly from current context without external tools.
                    - general_action: the request requires one explicit action (search, fetch, contact, etc.).
                    - multi_step_task: the request requires multiple sequential stages.
                    - goal: the user wants to create, manage, or interact with persistent goals (create, list, pause, resume, delete, update, etc.).
                    - clarification: you cannot confidently distinguish between materially different routes.
                    - noop: no actionable intent detected.
                    Available actions: $actionSummary
                    Do not default to general_action when the route is genuinely ambiguous.
                    If uncertain between materially different routes, return clarification.
                """.trimIndent()
            ),
            PromptBudgetAllocator.Section(
                key = "router_schema",
                role = ChatRole.SYSTEM,
                band = PromptBudgetAllocator.Band.REQUIRED_CORE,
                importance = PromptBudgetAllocator.Importance.HIGH,
                floorTokens = 24,
                content = """
                    JSON schema:
                    {"route":"$routeOptions","reasoning":"short explanation"}
                """.trimIndent()
            ),
            SharedPromptSections.recentDialogueSection(context),
            SharedPromptSections.shortTermSummarySection(context),
            SharedPromptSections.actionAvailabilitySection(context),
            PromptBudgetAllocator.Section(
                key = "router_trigger",
                role = ChatRole.USER,
                band = PromptBudgetAllocator.Band.REQUIRED_CORE,
                importance = PromptBudgetAllocator.Importance.HIGH,
                floorTokens = 24,
                content = "User input:\n${trigger.input.content}"
            )
        )

        val allocation = PromptBudgetAllocator.allocate(sections, config.maxLlmPromptTokens)
        runtime.emitPromptBudgetTelemetry(LaneId.INPUT_INTENT_ROUTER, allocation.diagnostics)

        val response = runtime.call(
            laneId = LaneId.INPUT_INTENT_ROUTER,
            messages = allocation.messages,
            metadata = metadata,
            responseFormat = ROUTER_RESPONSE_FORMAT,
        )

        if (response == null) {
            instrumentation.emit(AgentEvents.warning("InputIntentRouter call failed; defaulting to GeneralAction."))
            return InputRoute.GeneralAction(reasoning = "Router unavailable; fallback to general action.")
        }

        val payload = StructuredOutputHandler.parseWithRepair<RouterPayload>(response.content)
        if (payload == null) {
            instrumentation.emit(AgentEvents.warning("InputIntentRouter response non-parseable; defaulting to GeneralAction."))
            return InputRoute.GeneralAction(reasoning = "Router parse failure; fallback to general action.")
        }

        val reasoning = payload.reasoning?.trim().orEmpty()
        return when (payload.route?.trim()?.lowercase()) {
            "direct_response" -> InputRoute.DirectResponse(reasoning)
            "general_action" -> InputRoute.GeneralAction(reasoning)
            "multi_step_task" -> InputRoute.MultiStepTask(reasoning)
            "durable_work", "goal", "goal_creation", "goal_management" ->
                if (goalsAvailable) InputRoute.DurableWork(reasoning)
                else InputRoute.GeneralAction("Durable work unavailable; routing as general action.")
            "clarification" -> InputRoute.ClarificationNeeded(reasoning.ifBlank { "Could you clarify what you'd like me to do?" })
            "noop" -> InputRoute.Noop(reasoning.ifBlank { "No actionable intent." })
            else -> InputRoute.GeneralAction(reasoning.ifBlank { "Unrecognized route; fallback to general action." })
        }
    }

    private fun buildRouteOptions(goalsAvailable: Boolean): String {
        val routes = mutableListOf(
            "direct_response", "general_action", "multi_step_task"
        )
        if (goalsAvailable) {
            routes.add("durable_work")
        }
        routes.add("clarification")
        routes.add("noop")
        return routes.joinToString("|")
    }

    private data class RouterPayload(
        val route: String? = null,
        val reasoning: String? = null,
    )

    private companion object {
        val ROUTER_RESPONSE_FORMAT = ChatResponseFormat.JsonSchema(
            name = "input_intent_route",
            schemaJson = """
                {
                  "type": "object",
                  "additionalProperties": false,
                  "required": ["route", "reasoning"],
                  "properties": {
                    "route": { "type": "string" },
                    "reasoning": { "type": ["string", "null"] }
                  }
                }
            """.trimIndent(),
            strict = true,
        )
    }
}
