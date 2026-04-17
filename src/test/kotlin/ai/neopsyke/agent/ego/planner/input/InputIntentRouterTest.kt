package ai.neopsyke.agent.ego.planner.input

import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.ego.planner.model.DurableWorkRouteTarget
import ai.neopsyke.agent.ego.planner.model.InputRoute
import ai.neopsyke.agent.ego.planner.runtime.PlannerRuntime
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.EgoTrigger
import ai.neopsyke.agent.model.PendingInput
import ai.neopsyke.agent.model.PlannerContext
import ai.neopsyke.agent.model.QueueSnapshot
import ai.neopsyke.instrumentation.NoopAgentInstrumentation
import ai.neopsyke.support.StubChatModelClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class InputIntentRouterTest {

    @Test
    fun `durable work route parses explicit responsibility target`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponseForCallSite(
                "input_intent_router",
                """{"route":"durable_work","durable_work_target":"responsibility","reasoning":"ongoing ownership"}"""
            )
        }
        val router = InputIntentRouter(
            runtime = PlannerRuntime(
                defaultModelClient = llm,
                config = AgentConfig(),
                instrumentation = NoopAgentInstrumentation,
            ),
            config = AgentConfig(),
            instrumentation = NoopAgentInstrumentation,
        )

        val route = router.route(
            trigger = EgoTrigger.IncomingInput(PendingInput(id = 1, content = "help me own apartment hunting")),
            context = PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0),
                availableActions = setOf(ActionType.DURABLE_WORK_OPERATION),
            ),
        )

        val durable = assertIs<InputRoute.DurableWork>(route)
        assertEquals(DurableWorkRouteTarget.RESPONSIBILITY, durable.target)
    }

    @Test
    fun `legacy goal creation alias defaults to recurrent task target`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponseForCallSite(
                "input_intent_router",
                """{"route":"goal_creation","reasoning":"legacy durable work alias"}"""
            )
        }
        val router = InputIntentRouter(
            runtime = PlannerRuntime(
                defaultModelClient = llm,
                config = AgentConfig(),
                instrumentation = NoopAgentInstrumentation,
            ),
            config = AgentConfig(),
            instrumentation = NoopAgentInstrumentation,
        )

        val route = router.route(
            trigger = EgoTrigger.IncomingInput(PendingInput(id = 1, content = "set up a recurring reminder")),
            context = PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0),
                availableActions = setOf(ActionType.DURABLE_WORK_OPERATION),
            ),
        )

        val durable = assertIs<InputRoute.DurableWork>(route)
        assertEquals(DurableWorkRouteTarget.RECURRENT_TASK, durable.target)
    }
}
