package ai.neopsyke.agent.ego.planner.input

import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.durablework.StepStatus
import ai.neopsyke.agent.durablework.WorkItemStatus
import ai.neopsyke.agent.ego.planner.PlanRefinementResult
import ai.neopsyke.agent.ego.planner.PlanRefinementRequest
import ai.neopsyke.agent.ego.planner.PlanRefiner
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.DurableWorkItemSnapshot
import ai.neopsyke.agent.model.DurableWorkPlanStepSnapshot
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DurableWorkPlannerTest {

    @Test
    fun `create with contact_channel wires through to payload`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse(
                """{"operation":"create","work_item_reference":null,"title":"Weather reminder","instruction":"Send weather forecast","completion_criteria":"Delivered","priority":"medium","cron_expression":"0 8 * * *","contact_channel":"telegram","plan_steps":[{"id":"step1","description":"Fetch weather","acceptance_criteria":"Obtained forecast","grounding_requirement":"required","requires":[],"produces":["forecast"],"max_attempts":3},{"id":"step2","description":"Send to user","acceptance_criteria":"Delivered","grounding_requirement":"not_required","requires":["forecast"],"produces":[],"max_attempts":3}],"assistant_response":null,"reason":null}"""
            )
        }
        val runtime = PlannerRuntime(
            defaultModelClient = llm,
            config = AgentConfig(),
            instrumentation = NoopAgentInstrumentation,
        )
        val planner = WorkPlanBuilder(
            runtime = runtime,
            config = AgentConfig(),
            instrumentation = NoopAgentInstrumentation,
            planRefiner = NoopPlanRefiner(),
        )

        val context = PlannerContext(
            recentDialogue = emptyList(),
            queue = QueueSnapshot(0, 0, 0),
        )

        val decision = planner.plan(
            trigger = EgoTrigger.IncomingInput(PendingInput(id = 1, content = "remind me of weather daily via telegram")),
            context = context,
        )

        val intention = assertIs<EgoDecision.FormIntention>(decision)
        assertEquals(ActionType.DURABLE_WORK_OPERATION, intention.actionType)
        assertTrue(intention.payload.contains(""""command":"create""""))
        assertTrue(intention.payload.contains(""""contact_channel":"telegram""""))
    }

    @Test
    fun `update with contact_channel wires through to payload`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse(
                """{"operation":"update","work_item_reference":{"type":"by_position","id":"1","candidates":null,"original_text":null,"resolved_from":null},"title":null,"instruction":null,"completion_criteria":null,"priority":null,"cron_expression":"15 0 * * *","contact_channel":"telegram","plan_steps":null,"assistant_response":null,"reason":null}"""
            )
        }
        val runtime = PlannerRuntime(
            defaultModelClient = llm,
            config = AgentConfig(),
            instrumentation = NoopAgentInstrumentation,
        )
        val planner = WorkPlanBuilder(
            runtime = runtime,
            config = AgentConfig(),
            instrumentation = NoopAgentInstrumentation,
            planRefiner = NoopPlanRefiner(),
        )

        val context = PlannerContext(
            recentDialogue = emptyList(),
            queue = QueueSnapshot(0, 0, 0),
            goalIndex = mapOf(1 to "goal-1"),
        )

        val decision = planner.plan(
            trigger = EgoTrigger.IncomingInput(PendingInput(id = 1, content = "change reminder channel to telegram and time to 00:15")),
            context = context,
        )

        val intention = assertIs<EgoDecision.FormIntention>(decision)
        assertEquals(ActionType.DURABLE_WORK_OPERATION, intention.actionType)
        assertTrue(intention.payload.contains(""""command":"update""""))
        assertTrue(intention.payload.contains(""""contact_channel":"telegram""""))
        assertTrue(intention.payload.contains(""""cron_expression":"15 0 * * *""""))
    }

    @Test
    fun `create without contact_channel produces null in payload`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse(
                """{"operation":"create","work_item_reference":null,"title":"Check stocks","instruction":"Check stock prices","completion_criteria":"Price checked","priority":"medium","cron_expression":"0 9 * * 1-5","contact_channel":null,"plan_steps":[{"id":"step1","description":"Fetch stock prices","acceptance_criteria":"Prices obtained","grounding_requirement":"required","requires":[],"produces":["prices"],"max_attempts":3}],"assistant_response":null,"reason":null}"""
            )
        }
        val runtime = PlannerRuntime(
            defaultModelClient = llm,
            config = AgentConfig(),
            instrumentation = NoopAgentInstrumentation,
        )
        val planner = WorkPlanBuilder(
            runtime = runtime,
            config = AgentConfig(),
            instrumentation = NoopAgentInstrumentation,
            planRefiner = NoopPlanRefiner(),
        )

        val context = PlannerContext(
            recentDialogue = emptyList(),
            queue = QueueSnapshot(0, 0, 0),
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
    fun `revise_plan uses resolved work-item snapshot as refinement context`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse(
                """{"operation":"revise_plan","work_item_reference":{"type":"by_position","id":"1","candidates":null,"original_text":null,"resolved_from":null},"title":null,"instruction":null,"completion_criteria":null,"priority":null,"cron_expression":null,"plan_steps":null,"assistant_response":null,"reason":"first step keeps failing"}"""
            )
        }
        val runtime = PlannerRuntime(
            defaultModelClient = llm,
            config = AgentConfig(),
            instrumentation = NoopAgentInstrumentation,
        )
        val refiner = CapturingPlanRefiner()
        val planner = WorkPlanBuilder(
            runtime = runtime,
            config = AgentConfig(),
            instrumentation = NoopAgentInstrumentation,
            planRefiner = refiner,
        )

        val context = PlannerContext(
            recentDialogue = emptyList(),
            queue = QueueSnapshot(0, 0, 0),
            goalIndex = mapOf(1 to "goal-1"),
            goalSnapshots = mapOf(
                "goal-1" to DurableWorkItemSnapshot(
                    workItemId = "goal-1",
                    title = "Track weather shifts",
                    instruction = "Watch for sharp weather changes in Berlin every morning.",
                    completionCriteria = "User confirms weather update quality is sufficient.",
                    status = WorkItemStatus.ACTIVE,
                    planRevision = 3,
                    failureCountInWindow = 2,
                    latestArtifactSummary = "Previous run found stale source data.",
                    planSteps = listOf(
                        DurableWorkPlanStepSnapshot(
                            id = "collect",
                            description = "Collect latest weather snapshots",
                            status = StepStatus.FAILED,
                            acceptanceCriteria = "Snapshots include temperature and alerts",
                            requires = emptySet(),
                            produces = setOf("weather_data"),
                            attempts = 3,
                            maxAttempts = 3,
                        ),
                        DurableWorkPlanStepSnapshot(
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
            trigger = EgoTrigger.IncomingInput(PendingInput(id = 1, content = "revise goal 1 plan")),
            context = context,
        )

        val intention = assertIs<EgoDecision.FormIntention>(decision)
        assertEquals(ActionType.DURABLE_WORK_OPERATION, intention.actionType)
        assertTrue(intention.payload.contains(""""command":"revise_plan""""))
        assertTrue(intention.payload.contains(""""id":"collect""""))

        val captured = assertNotNull(refiner.lastRequest)
        assertEquals("Track weather shifts", captured.goal)
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

