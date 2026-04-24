package ai.neopsyke.agent.ego.planner.input

import com.fasterxml.jackson.annotation.JsonProperty
import mu.KotlinLogging
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.ego.planner.HierarchicalEgoPlanner
import ai.neopsyke.agent.ego.planner.LaneId
import ai.neopsyke.agent.ego.planner.model.AssignmentRouteTarget
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

        val assignmentsAvailable = ActionType.ASSIGNMENT_OPERATION in context.availableActions
        val routeOptions = buildRouteOptions(assignmentsAvailable)
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
                    - assignment: the user wants to create, manage, or interact with persistent recurrent tasks or responsibilities.
                    - If route=assignment, you MUST also set assignment_target:
                      - generic: shared lifecycle work like list, status, pause, resume, review, complete, retire, delete, delete_all, reprioritize.
                      - recurrent_task: create/update/revise for reminders, recurring searches, scheduled monitoring, and other repeated structured tasks.
                      - responsibility: create/update/revise for broad ongoing ownership where the agent may need multi-turn intake before creating the item.
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
                    {"route":"$routeOptions","assignment_target":"generic|recurrent_task|responsibility|null","reasoning":"short explanation"}
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
        val normalizedRoute = payload.route?.trim()?.lowercase()
        return when (normalizedRoute) {
            "direct_response" -> InputRoute.DirectResponse(reasoning)
            "general_action" -> InputRoute.GeneralAction(reasoning)
            "multi_step_task" -> InputRoute.MultiStepTask(reasoning)
            "assignment", "assignment_creation", "assignment_management" ->
                if (assignmentsAvailable) {
                    InputRoute.Assignment(
                        reasoning = reasoning,
                        target = parseAssignmentTarget(normalizedRoute, payload.assignmentTarget),
                    )
                }
                else InputRoute.GeneralAction("Assignment unavailable; routing as general action.")
            "clarification" -> InputRoute.ClarificationNeeded(reasoning.ifBlank { "Could you clarify what you'd like me to do?" })
            "noop" -> InputRoute.Noop(reasoning.ifBlank { "No actionable intent." })
            else -> InputRoute.GeneralAction(reasoning.ifBlank { "Unrecognized route; fallback to general action." })
        }
    }

    private fun buildRouteOptions(assignmentsAvailable: Boolean): String {
        val routes = mutableListOf(
            "direct_response", "general_action", "multi_step_task"
        )
        if (assignmentsAvailable) {
            routes.add("assignment")
        }
        routes.add("clarification")
        routes.add("noop")
        return routes.joinToString("|")
    }

    private data class RouterPayload(
        val route: String? = null,
        @param:JsonProperty("assignment_target")
        val assignmentTarget: String? = null,
        val reasoning: String? = null,
    )

    private fun parseAssignmentTarget(route: String?, raw: String?): AssignmentRouteTarget =
        when {
            route == "assignment_creation" -> AssignmentRouteTarget.RECURRENT_TASK
            route == "assignment_management" -> AssignmentRouteTarget.GENERIC
            else -> when (raw?.trim()?.lowercase()) {
                "recurrent_task", "assignment_creation" -> AssignmentRouteTarget.RECURRENT_TASK
                "responsibility" -> AssignmentRouteTarget.RESPONSIBILITY
                else -> AssignmentRouteTarget.GENERIC
            }
        }

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
                    "assignment_target": { "type": ["string", "null"] },
                    "reasoning": { "type": ["string", "null"] }
                  }
                }
            """.trimIndent(),
            strict = true,
        )
    }
}
