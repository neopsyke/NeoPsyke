package ai.neopsyke.agent.ego.planner.input

import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.assignments.WorkItemKind
import ai.neopsyke.agent.assignments.StepStatus
import ai.neopsyke.agent.assignments.WorkItemStatus
import ai.neopsyke.agent.ego.planner.PlanRefinementResult
import ai.neopsyke.agent.ego.planner.PlanRefinementRequest
import ai.neopsyke.agent.ego.planner.PlanRefiner
import ai.neopsyke.agent.ego.planner.model.AssignmentRouteTarget
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.AssignmentItemSnapshot
import ai.neopsyke.agent.model.AssignmentPlanStepSnapshot
import ai.neopsyke.agent.model.DialogueRole
import ai.neopsyke.agent.model.DialogueTurn
import ai.neopsyke.agent.model.EgoDecision
import ai.neopsyke.agent.model.EgoTrigger
import ai.neopsyke.agent.model.PendingInput
import ai.neopsyke.agent.model.PlannerContext
import ai.neopsyke.agent.model.QueueSnapshot
import ai.neopsyke.agent.ego.planner.runtime.PlannerRuntime
import ai.neopsyke.instrumentation.NoopAgentInstrumentation
import ai.neopsyke.support.StubChatModelClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AssignmentPlannerTest {

    @Test
    fun `create with contact_channel wires through to payload`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse(
                """{"command":"create","work_item_reference":null,"title":"Weather reminder","instruction":"Send weather forecast","completion_criteria":"Delivered","priority":"medium","cron_expression":"0 8 * * *","contact_channel":"telegram","plan_steps":[{"id":"step1","description":"Fetch weather","acceptance_criteria":"Obtained forecast","grounding_requirement":"required","requires":[],"produces":["forecast"],"max_attempts":3},{"id":"step2","description":"Send to user","acceptance_criteria":"Delivered","grounding_requirement":"not_required","requires":["forecast"],"produces":[],"max_attempts":3}],"assistant_response":null,"reason":null}"""
            )
        }
        val runtime = PlannerRuntime(
            defaultModelClient = llm,
            config = AgentConfig(),
            instrumentation = NoopAgentInstrumentation,
        )
        val planner = AssignmentCommandBuilder(
            runtime = runtime,
            config = AgentConfig(),
            instrumentation = NoopAgentInstrumentation,
            planRefiner = NoopPlanRefiner(),
        )

        val context = PlannerContext(
            recentDialogue = emptyList(),
            queue = QueueSnapshot(0, 0, 0),
            availableContactChannels = setOf("telegram", "dashboard"),
        )

        val decision = planner.plan(
            trigger = EgoTrigger.IncomingInput(PendingInput(id = 1, content = "remind me of weather daily via telegram")),
            context = context,
        )

        val intention = assertIs<EgoDecision.FormIntention>(decision)
        assertEquals(ActionType.ASSIGNMENT_OPERATION, intention.actionType)
        assertTrue(intention.payload.contains(""""command":"create""""))
        assertTrue(intention.payload.contains(""""contact_channel":"telegram""""))
    }

    @Test
    fun `update with contact_channel wires through to payload`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse(
                """{"command":"update","work_item_reference":{"type":"by_position","id":"1","candidates":null,"original_text":null,"resolved_from":null},"title":null,"instruction":null,"completion_criteria":null,"priority":null,"cron_expression":"15 0 * * *","contact_channel":"telegram","plan_steps":null,"assistant_response":null,"reason":null}"""
            )
        }
        val runtime = PlannerRuntime(
            defaultModelClient = llm,
            config = AgentConfig(),
            instrumentation = NoopAgentInstrumentation,
        )
        val planner = AssignmentCommandBuilder(
            runtime = runtime,
            config = AgentConfig(),
            instrumentation = NoopAgentInstrumentation,
            planRefiner = NoopPlanRefiner(),
        )

        val context = PlannerContext(
            recentDialogue = emptyList(),
            queue = QueueSnapshot(0, 0, 0),
            assignmentIndex = mapOf(1 to "assignment-1"),
            availableContactChannels = setOf("telegram", "dashboard"),
        )

        val decision = planner.plan(
            trigger = EgoTrigger.IncomingInput(PendingInput(id = 1, content = "change reminder channel to telegram and time to 00:15")),
            context = context,
        )

        val intention = assertIs<EgoDecision.FormIntention>(decision)
        assertEquals(ActionType.ASSIGNMENT_OPERATION, intention.actionType)
        assertTrue(intention.payload.contains(""""command":"update""""))
        assertTrue(intention.payload.contains(""""contact_channel":"telegram""""))
        assertTrue(intention.payload.contains(""""cron_expression":"15 0 * * *""""))
    }

    @Test
    fun `create without contact_channel produces null in payload`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse(
                """{"command":"create","work_item_reference":null,"title":"Check stocks","instruction":"Check stock prices","completion_criteria":"Price checked","priority":"medium","cron_expression":"0 9 * * 1-5","contact_channel":null,"plan_steps":[{"id":"step1","description":"Fetch stock prices","acceptance_criteria":"Prices obtained","grounding_requirement":"required","requires":[],"produces":["prices"],"max_attempts":3}],"assistant_response":null,"reason":null}"""
            )
        }
        val runtime = PlannerRuntime(
            defaultModelClient = llm,
            config = AgentConfig(),
            instrumentation = NoopAgentInstrumentation,
        )
        val planner = AssignmentCommandBuilder(
            runtime = runtime,
            config = AgentConfig(),
            instrumentation = NoopAgentInstrumentation,
            planRefiner = NoopPlanRefiner(),
        )

        val context = PlannerContext(
            recentDialogue = emptyList(),
            queue = QueueSnapshot(0, 0, 0),
            availableContactChannels = setOf("telegram", "dashboard"),
        )

        val decision = planner.plan(
            trigger = EgoTrigger.IncomingInput(PendingInput(id = 1, content = "check stocks daily at 9am")),
            context = context,
        )

        val intention = assertIs<EgoDecision.FormIntention>(decision)
        assertTrue(intention.payload.contains(""""command":"create""""))
        // contact_channel should be null when not specified
        assertTrue(intention.payload.contains(""""contact_channel":null"""))
    }

    @Test
    fun `planner prompt advertises canonical available contact channels`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse(
                """{"command":"create","work_item_reference":null,"title":"Weather reminder","instruction":"Send weather forecast","completion_criteria":"Delivered","priority":"medium","cron_expression":"0 8 * * *","contact_channel":"dashboard","plan_steps":[{"id":"step1","description":"Fetch weather","acceptance_criteria":"Obtained forecast","grounding_requirement":"required","requires":[],"produces":["forecast"],"max_attempts":3},{"id":"step2","description":"Send to user","acceptance_criteria":"Delivered","grounding_requirement":"not_required","requires":["forecast"],"produces":[],"max_attempts":3}],"assistant_response":null,"reason":null}"""
            )
        }
        val runtime = PlannerRuntime(
            defaultModelClient = llm,
            config = AgentConfig(),
            instrumentation = NoopAgentInstrumentation,
        )
        val planner = AssignmentCommandBuilder(
            runtime = runtime,
            config = AgentConfig(),
            instrumentation = NoopAgentInstrumentation,
            planRefiner = NoopPlanRefiner(),
        )

        val context = PlannerContext(
            recentDialogue = emptyList(),
            queue = QueueSnapshot(0, 0, 0),
            availableContactChannels = setOf("telegram", "dashboard"),
        )

        val decision = planner.plan(
            trigger = EgoTrigger.IncomingInput(PendingInput(id = 1, content = "remind me in the app every morning")),
            context = context,
        )

        val prompt = llm.lastMessages.joinToString("\n") { it.content }
        val intention = assertIs<EgoDecision.FormIntention>(decision)
        assertTrue(prompt.contains("Available delivery channels: dashboard, telegram."))
        assertTrue(prompt.contains("on the dashboard\" or \"in the app\" -> \"dashboard\" if available"))
        assertTrue(intention.payload.contains(""""contact_channel":"dashboard""""))
    }

    @Test
    fun `revise_plan uses resolved work-item snapshot as refinement context`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse(
                """{"command":"revise_plan","work_item_reference":{"type":"by_position","id":"1","candidates":null,"original_text":null,"resolved_from":null},"title":null,"instruction":null,"completion_criteria":null,"priority":null,"cron_expression":null,"plan_steps":null,"assistant_response":null,"reason":"first step keeps failing"}"""
            )
        }
        val runtime = PlannerRuntime(
            defaultModelClient = llm,
            config = AgentConfig(),
            instrumentation = NoopAgentInstrumentation,
        )
        val refiner = CapturingPlanRefiner()
        val planner = AssignmentCommandBuilder(
            runtime = runtime,
            config = AgentConfig(),
            instrumentation = NoopAgentInstrumentation,
            planRefiner = refiner,
        )

        val context = PlannerContext(
            recentDialogue = emptyList(),
            queue = QueueSnapshot(0, 0, 0),
            assignmentIndex = mapOf(1 to "assignment-1"),
            assignmentSnapshots = mapOf(
                "assignment-1" to AssignmentItemSnapshot(
                    workItemId = "assignment-1",
                    title = "Track weather shifts",
                    instruction = "Watch for sharp weather changes in Berlin every morning.",
                    completionCriteria = "User confirms weather update quality is sufficient.",
                    status = WorkItemStatus.ACTIVE,
                    planRevision = 3,
                    failureCountInWindow = 2,
                    latestArtifactSummary = "Previous run found stale source data.",
                    planSteps = listOf(
                        AssignmentPlanStepSnapshot(
                            id = "collect",
                            description = "Collect latest weather snapshots",
                            status = StepStatus.FAILED,
                            acceptanceCriteria = "Snapshots include temperature and alerts",
                            requires = emptySet(),
                            produces = setOf("weather_data"),
                            attempts = 3,
                            maxAttempts = 3,
                        ),
                        AssignmentPlanStepSnapshot(
                            id = "notify",
                            description = "Notify the user with the latest weather change summary",
                            status = StepStatus.PENDING,
                            acceptanceCriteria = "User receives concise summary",
                            requires = setOf("weather_data"),
                            produces = emptySet(),
                            attempts = 0,
                            maxAttempts = 3,
                        ),
                    ),
                )
            ),
        )

        val decision = planner.plan(
            trigger = EgoTrigger.IncomingInput(PendingInput(id = 1, content = "revise assignment 1 plan")),
            context = context,
        )

        val intention = assertIs<EgoDecision.FormIntention>(decision)
        assertEquals(ActionType.ASSIGNMENT_OPERATION, intention.actionType)
        assertTrue(intention.payload.contains(""""command":"revise_plan""""))
        assertTrue(intention.payload.contains(""""id":"collect""""))

        val captured = assertNotNull(refiner.lastRequest)
        assertEquals("Track weather shifts", captured.assignment)
        assertEquals(
            "Watch for sharp weather changes in Berlin every morning.",
            captured.instruction
        )
        assertEquals(
            "User confirms weather update quality is sufficient.",
            captured.completionCriteria
        )
        assertEquals(2, captured.steps.size)
        assertEquals("collect", captured.steps.first().id)
        assertTrue(captured.shortTermContextSummary.contains("Current work-item state:"))
        assertTrue(captured.shortTermContextSummary.contains("plan_revision: 3"))
        assertTrue(captured.shortTermContextSummary.contains("latest_artifact_summary"))
        assertEquals("first step keeps failing", captured.userFeedbackHint)
    }

    @Test
    fun `responsibility planner asks focused clarification and stores intake draft`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {
                  "command":"clarify",
                  "work_item_kind":"RESPONSIBILITY",
                  "work_item_reference":null,
                  "title":"Apartment search",
                  "instruction":null,
                  "completion_criteria":null,
                  "priority":"medium",
                  "cron_expression":null,
                  "contact_channel":"dashboard",
                  "operator_summary":"Keep watching the apartment market in Berlin",
                  "plan_steps":null,
                  "assistant_response":null,
                  "clarification_question":"What neighborhoods, budget, and move-in date should I optimize for?",
                  "responsibility_summary":"Watch for good Berlin apartments and tell me when something materially better appears.",
                  "known_preferences":["Berlin"],
                  "known_constraints":["Need budget"],
                  "known_signals_of_success":["Useful shortlist"],
                  "review_cadence_hint":"daily",
                  "delivery_hint":"dashboard",
                  "open_questions":["Need budget","Need neighborhoods"],
                  "ready_to_create":false,
                  "reason":"missing operating constraints"
                }
                """.trimIndent()
            )
        }
        val runtime = PlannerRuntime(
            defaultModelClient = llm,
            config = AgentConfig(),
            instrumentation = NoopAgentInstrumentation,
        )
        val planner = AssignmentCommandBuilder(runtime, AgentConfig(), NoopAgentInstrumentation, NoopPlanRefiner())

        val decision = planner.plan(
            trigger = EgoTrigger.IncomingInput(PendingInput(id = 1, content = "help me keep an eye on apartments in Berlin")),
            context = PlannerContext(recentDialogue = emptyList(), queue = QueueSnapshot(0, 0, 0), availableContactChannels = setOf("dashboard")),
            target = AssignmentRouteTarget.RESPONSIBILITY,
        )

        val intention = assertIs<EgoDecision.FormIntention>(decision)
        assertEquals(ActionType.CONTACT_USER, intention.actionType)
        assertTrue(intention.payload.contains("budget", ignoreCase = true))

        val secondPrompt = llm.lastMessages.joinToString("\n") { it.content }
        assertTrue(secondPrompt.contains("Responsibilities are ongoing ownership commitments"))
    }

    @Test
    fun `responsibility planner creates responsibility using stored intake draft`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {
                  "command":"clarify",
                  "work_item_kind":"RESPONSIBILITY",
                  "work_item_reference":null,
                  "title":"Apartment search",
                  "instruction":null,
                  "completion_criteria":null,
                  "priority":"medium",
                  "cron_expression":null,
                  "contact_channel":"dashboard",
                  "operator_summary":"Keep watching Berlin apartments",
                  "plan_steps":null,
                  "assistant_response":null,
                  "clarification_question":"What neighborhoods and budget should I use?",
                  "responsibility_summary":"Watch for strong apartment matches in Berlin.",
                  "known_preferences":["Berlin"],
                  "known_constraints":["Need budget"],
                  "known_signals_of_success":["Useful shortlist"],
                  "review_cadence_hint":"daily",
                  "delivery_hint":"dashboard",
                  "open_questions":["Need budget"],
                  "ready_to_create":false,
                  "reason":"missing budget"
                }
                """.trimIndent()
            )
            enqueueRawResponse(
                """
                {
                  "command":"create",
                  "work_item_kind":"RESPONSIBILITY",
                  "work_item_reference":null,
                  "title":"Apartment search",
                  "instruction":"Monitor Berlin apartment listings, prioritize Prenzlauer Berg and Friedrichshain, stay under 2200 EUR, and surface only materially better matches.",
                  "completion_criteria":"User confirms the responsibility is fulfilled or retired.",
                  "priority":"high",
                  "cron_expression":"0 9 * * *",
                  "contact_channel":"dashboard",
                  "operator_summary":"Own Berlin apartment monitoring with budget and neighborhood constraints.",
                  "plan_steps":[
                    {"id":"scan","description":"Scan fresh apartment listings","acceptance_criteria":"Fresh listings reviewed","grounding_requirement":"required","requires":[],"produces":["matches"],"max_attempts":3},
                    {"id":"summarize","description":"Summarize only materially better matches","acceptance_criteria":"Digest prepared","grounding_requirement":"not_required","requires":["matches"],"produces":[],"max_attempts":3}
                  ],
                  "assistant_response":null,
                  "clarification_question":null,
                  "responsibility_summary":"Watch for strong apartment matches in Berlin.",
                  "known_preferences":["Prenzlauer Berg","Friedrichshain"],
                  "known_constraints":["Budget under 2200 EUR"],
                  "known_signals_of_success":["Useful shortlist"],
                  "review_cadence_hint":"daily",
                  "delivery_hint":"dashboard",
                  "open_questions":[],
                  "ready_to_create":true,
                  "reason":"enough detail collected"
                }
                """.trimIndent()
            )
        }
        val runtime = PlannerRuntime(
            defaultModelClient = llm,
            config = AgentConfig(),
            instrumentation = NoopAgentInstrumentation,
        )
        val planner = AssignmentCommandBuilder(runtime, AgentConfig(), NoopAgentInstrumentation, NoopPlanRefiner())
        val context = PlannerContext(
            recentDialogue = emptyList(),
            queue = QueueSnapshot(0, 0, 0),
            availableContactChannels = setOf("dashboard"),
            conversationContext = ai.neopsyke.agent.model.ConversationContext.default(),
        )

        planner.plan(
            trigger = EgoTrigger.IncomingInput(PendingInput(id = 1, content = "help me keep an eye on apartments in Berlin")),
            context = context,
            target = AssignmentRouteTarget.RESPONSIBILITY,
        )
        val decision = planner.plan(
            trigger = EgoTrigger.IncomingInput(PendingInput(id = 2, content = "use Prenzlauer Berg or Friedrichshain and stay under 2200 EUR")),
            context = context.copy(
                recentDialogue = listOf(
                    DialogueTurn(
                        role = DialogueRole.ASSISTANT,
                        content = "What neighborhoods and budget should I use?",
                    )
                )
            ),
            target = AssignmentRouteTarget.RESPONSIBILITY,
        )

        val intention = assertIs<EgoDecision.FormIntention>(decision)
        assertEquals(ActionType.ASSIGNMENT_OPERATION, intention.actionType)
        assertTrue(intention.payload.contains(""""work_item_kind":"RESPONSIBILITY""""))
        assertTrue(intention.payload.contains(""""operator_summary":""""))
        assertTrue(intention.payload.contains("Own Berlin apartment monitoring with budget and neighborhood constraints."))
        assertTrue(intention.payload.contains(""""cron_expression":"0 9 * * *""""))
        assertTrue(intention.payload.contains(""""command":"create""""))
        assertTrue(intention.payload.contains(WorkItemKind.RESPONSIBILITY.name))
    }

    @Test
    fun `responsibility planner does not reuse stale intake draft without clarification continuity`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {
                  "command":"clarify",
                  "work_item_kind":"RESPONSIBILITY",
                  "work_item_reference":null,
                  "title":"Apartment search",
                  "instruction":null,
                  "completion_criteria":null,
                  "priority":"medium",
                  "cron_expression":null,
                  "contact_channel":"dashboard",
                  "operator_summary":"Keep watching Berlin apartments",
                  "plan_steps":null,
                  "assistant_response":null,
                  "clarification_question":"What neighborhoods and budget should I use?",
                  "responsibility_summary":"Watch for strong apartment matches in Berlin.",
                  "known_preferences":["Berlin"],
                  "known_constraints":["Need budget"],
                  "known_signals_of_success":["Useful shortlist"],
                  "review_cadence_hint":"daily",
                  "delivery_hint":"dashboard",
                  "open_questions":["Need budget"],
                  "ready_to_create":false,
                  "reason":"missing budget"
                }
                """.trimIndent()
            )
            enqueueRawResponse(
                """
                {
                  "command":"clarify",
                  "work_item_kind":"RESPONSIBILITY",
                  "work_item_reference":null,
                  "title":"Garden monitoring",
                  "instruction":null,
                  "completion_criteria":null,
                  "priority":"medium",
                  "cron_expression":null,
                  "contact_channel":"dashboard",
                  "operator_summary":"Watch garden watering needs",
                  "plan_steps":null,
                  "assistant_response":null,
                  "clarification_question":"Which plants and watering cadence should I monitor?",
                  "responsibility_summary":"Watch garden watering needs.",
                  "known_preferences":["garden"],
                  "known_constraints":[],
                  "known_signals_of_success":[],
                  "review_cadence_hint":"weekly",
                  "delivery_hint":"dashboard",
                  "open_questions":["Need plant list"],
                  "ready_to_create":false,
                  "reason":"new responsibility needs details"
                }
                """.trimIndent()
            )
        }
        val runtime = PlannerRuntime(
            defaultModelClient = llm,
            config = AgentConfig(),
            instrumentation = NoopAgentInstrumentation,
        )
        val planner = AssignmentCommandBuilder(runtime, AgentConfig(), NoopAgentInstrumentation, NoopPlanRefiner())
        val context = PlannerContext(
            recentDialogue = emptyList(),
            queue = QueueSnapshot(0, 0, 0),
            availableContactChannels = setOf("dashboard"),
            conversationContext = ai.neopsyke.agent.model.ConversationContext.default(),
        )

        planner.plan(
            trigger = EgoTrigger.IncomingInput(PendingInput(id = 1, content = "help me keep an eye on apartments in Berlin")),
            context = context,
            target = AssignmentRouteTarget.RESPONSIBILITY,
        )
        planner.plan(
            trigger = EgoTrigger.IncomingInput(PendingInput(id = 2, content = "help me stay on top of garden watering")),
            context = context,
            target = AssignmentRouteTarget.RESPONSIBILITY,
        )

        val prompt = llm.lastMessages.joinToString("\n") { it.content }
        assertFalse(prompt.contains("Current responsibility intake draft:"))
        assertFalse(prompt.contains("Apartment search"))
        assertFalse(prompt.contains("Need budget"))
    }

    @Test
    fun `review command prefers reviewable responsibility slate numbering`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {
                  "command":"review",
                  "work_item_kind":null,
                  "work_item_reference":{"type":"by_position","id":"1","candidates":null,"original_text":"first responsibility","resolved_from":"reviewable_responsibility_slate"},
                  "title":null,
                  "instruction":null,
                  "completion_criteria":null,
                  "priority":null,
                  "cron_expression":null,
                  "contact_channel":null,
                  "operator_summary":null,
                  "plan_steps":null,
                  "assistant_response":null,
                  "clarification_question":null,
                  "responsibility_summary":null,
                  "known_preferences":[],
                  "known_constraints":[],
                  "known_signals_of_success":[],
                  "review_cadence_hint":null,
                  "delivery_hint":null,
                  "open_questions":[],
                  "ready_to_create":false,
                  "reason":"be useful"
                }
                """.trimIndent()
            )
        }
        val runtime = PlannerRuntime(
            defaultModelClient = llm,
            config = AgentConfig(),
            instrumentation = NoopAgentInstrumentation,
        )
        val planner = AssignmentCommandBuilder(runtime, AgentConfig(), NoopAgentInstrumentation, NoopPlanRefiner())

        val decision = planner.plan(
            trigger = EgoTrigger.IncomingInput(PendingInput(id = 1, content = "review the first responsibility")),
            context = PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0),
                assignmentIndex = mapOf(1 to "task-1"),
                reviewableResponsibilitySummary = "1. Apartment search",
                reviewableResponsibilityIndex = mapOf(1 to "resp-1"),
            ),
            target = AssignmentRouteTarget.GENERIC,
        )

        val intention = assertIs<EgoDecision.FormIntention>(decision)
        assertEquals(ActionType.ASSIGNMENT_OPERATION, intention.actionType)
        assertTrue(intention.payload.contains(""""command":"review""""))
        assertTrue(intention.payload.contains(""""work_item_id":"resp-1""""))
        assertTrue(intention.payload.contains(""""reason":"be useful""""))
    }

    private class NoopPlanRefiner : PlanRefiner {
        override fun refine(request: PlanRefinementRequest): PlanRefinementResult =
            PlanRefinementResult(steps = request.steps, reason = "noop")
    }

    private class CapturingPlanRefiner : PlanRefiner {
        var lastRequest: PlanRefinementRequest? = null

        override fun refine(request: PlanRefinementRequest): PlanRefinementResult {
            lastRequest = request
            return PlanRefinementResult(
                steps = request.steps,
                reason = "noop",
            )
        }
    }
}
