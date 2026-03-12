package psyke.agent.id

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import psyke.agent.actions.websearch.WebSearchActionHandler
import psyke.agent.actions.websearch.WebSearchEngine
import psyke.agent.actions.websearch.WebSearchResult
import psyke.agent.core.ActionType
import psyke.agent.core.AgentConfig
import psyke.agent.core.ConversationContext
import psyke.agent.core.EgoDecision
import psyke.agent.core.EgoTrigger
import psyke.agent.core.OriginSource
import psyke.agent.core.PendingImpulse
import psyke.agent.core.PendingThought
import psyke.agent.core.PlannerConfig
import psyke.agent.core.PlannerContext
import psyke.agent.core.Urgency
import psyke.agent.cortex.motor.MotorCortex
import psyke.agent.ego.Ego
import psyke.agent.superego.Superego
import psyke.support.RecordingInstrumentation
import psyke.support.StubChatModelClient
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IdEgoLifecycleIntegrationTest {

    @Test
    fun `impulse lifecycle waits for all branches and keeps id origin in downstream thoughts and actions`() {
        val instrumentation = RecordingInstrumentation()
        val planner = object : Ego.Planner {
            override fun decide(trigger: EgoTrigger, context: PlannerContext): EgoDecision =
                when (trigger) {
                    is EgoTrigger.IncomingInput -> EgoDecision.Noop("ignore user test input")
                    is EgoTrigger.IncomingImpulse -> EgoDecision.EnqueuePlan(
                        urgency = Urgency.HIGH,
                        goal = "Evaluate two branches",
                        steps = listOf("noop branch", "search branch")
                    )
                    is EgoTrigger.PendingThoughtInput -> decisionForThought(trigger.thought)
                }

            private fun decisionForThought(thought: PendingThought): EgoDecision =
                when {
                    thought.planContext?.stepIndex == 0 -> EgoDecision.Noop("drop this branch")
                    thought.planContext?.stepIndex == 1 -> EgoDecision.ProposeAction(
                        urgency = Urgency.HIGH,
                        actionType = ActionType.WEB_SEARCH,
                        payload = "official pricing",
                        summary = "search for pricing"
                    )
                    thought.originActionType == ActionType.WEB_SEARCH -> EgoDecision.Noop("search completed")
                    else -> EgoDecision.Noop("done")
                }
        }
        val config = AgentConfig(
            planner = PlannerConfig(
                maxLoopStepsPerInput = 24,
                maxThoughtPasses = 1
            )
        )
        val ego = Ego(
            planner = planner,
            superego = Superego(
                modelClient = StubChatModelClient().apply { enqueueRawResponse("""{"allow":true}""") },
                config = config,
                instrumentation = instrumentation,
            ),
            motorCortex = buildMotorCortex(),
            config = config,
            instrumentation = instrumentation,
        )
        val idModule = buildIdModule(instrumentation)
        ego.setId(idModule)

        val rootImpulseId = "impulse-root-parallel"
        val impulseQueued = ego.enqueueImpulse(
            PendingImpulse(
                id = 1,
                needId = "be-useful",
                prompt = "Find something useful to do.",
                urgency = 0.9,
                rawValue = 0.9,
                rootImpulseId = rootImpulseId,
                conversationContext = idModule.conversationContext,
            ),
            maxPendingImpulses = 1
        )
        assertTrue(impulseQueued)

        runAgentWithInput(ego, "kickoff\nexit\n")

        val idAccepted = instrumentation.events.filter { it.type == "id_impulse_accepted" }
        val idDenied = instrumentation.events.filter { it.type == "id_impulse_denied" }
        val idCompleted = instrumentation.events.filter { it.type == "id_impulse_completed" }
        assertEquals(1, idAccepted.size, "Impulse should be accepted once")
        assertEquals(0, idDenied.size, "No denial should be emitted while another valid branch is processed")
        assertEquals(1, idCompleted.size, "Impulse should complete once")
        assertEquals(true, idCompleted.single().data["success"])

        val lifecycleFinalized = instrumentation.events
            .firstOrNull { it.type == "impulse_lifecycle_finalized" && it.data["root_impulse_id"] == rootImpulseId }
        assertNotNull(lifecycleFinalized)
        assertEquals("accepted", lifecycleFinalized.data["result"])

        val idOriginAction = instrumentation.events
            .mapNotNull { it.data["action"] as? psyke.agent.core.PendingAction }
            .firstOrNull { it.rootInputId == rootImpulseId && it.type == ActionType.WEB_SEARCH }
        assertNotNull(idOriginAction, "Expected a WEB_SEARCH action for the impulse root")
        assertEquals(OriginSource.ID, idOriginAction.origin.source)

        val followUpThought = instrumentation.events
            .mapNotNull { it.data["thought"] as? PendingThought }
            .firstOrNull {
                it.rootInputId == rootImpulseId &&
                    it.originActionType == ActionType.WEB_SEARCH
            }
        assertNotNull(followUpThought, "Expected follow-up thought from WEB_SEARCH branch")
        assertEquals(OriginSource.ID, followUpThought.origin.source)
    }

    @Test
    fun `impulse lifecycle returns denied when no executable work is produced`() {
        val instrumentation = RecordingInstrumentation()
        val planner = object : Ego.Planner {
            override fun decide(trigger: EgoTrigger, context: PlannerContext): EgoDecision =
                when (trigger) {
                    is EgoTrigger.IncomingInput -> EgoDecision.Noop("ignore user test input")
                    is EgoTrigger.IncomingImpulse -> EgoDecision.Noop("no useful action available")
                    is EgoTrigger.PendingThoughtInput -> EgoDecision.Noop("done")
                }
        }
        val config = AgentConfig(
            planner = PlannerConfig(
                maxLoopStepsPerInput = 8,
                maxThoughtPasses = 1
            )
        )
        val ego = Ego(
            planner = planner,
            superego = Superego(
                modelClient = StubChatModelClient().apply { enqueueRawResponse("""{"allow":true}""") },
                config = config,
                instrumentation = instrumentation,
            ),
            motorCortex = buildMotorCortex(),
            config = config,
            instrumentation = instrumentation,
        )
        val idModule = buildIdModule(instrumentation)
        ego.setId(idModule)

        val rootImpulseId = "impulse-root-noop"
        val impulseQueued = ego.enqueueImpulse(
            PendingImpulse(
                id = 1,
                needId = "be-useful",
                prompt = "Try to do something.",
                urgency = 0.9,
                rawValue = 0.9,
                rootImpulseId = rootImpulseId,
                conversationContext = ConversationContext(
                    sessionId = Id.SESSION_ID,
                    interlocutor = Id.INTERLOCUTOR,
                ),
            ),
            maxPendingImpulses = 1
        )
        assertTrue(impulseQueued)

        runAgentWithInput(ego, "kickoff\nexit\n")

        val idDenied = instrumentation.events.filter { it.type == "id_impulse_denied" }
        val idCompleted = instrumentation.events.filter { it.type == "id_impulse_completed" }
        assertEquals(1, idDenied.size, "Noop impulse should be denied")
        assertEquals(0, idCompleted.size, "Noop impulse should not complete as success")

        val lifecycleFinalized = instrumentation.events
            .firstOrNull { it.type == "impulse_lifecycle_finalized" && it.data["root_impulse_id"] == rootImpulseId }
        assertNotNull(lifecycleFinalized)
        assertEquals("denied", lifecycleFinalized.data["result"])
    }

    private fun buildIdModule(instrumentation: RecordingInstrumentation): Id =
        Id(
            config = IdConfig(
                enabled = true,
                pulseIntervalMs = 1000,
                triggerThreshold = 0.0,
                thresholdOnUrgency = true,
                maxConsecutiveDenials = 5,
                backoffPulses = 10,
                maxInFlightPulses = 20,
                maxPendingImpulses = 1,
                needs = mapOf(
                    "be-useful" to NeedConfig(
                        description = "test",
                        growthRate = 0.1,
                        cooldownPulses = 0,
                        prompt = "Be useful.",
                        responseCurve = ResponseCurveConfig(type = "linear"),
                    ),
                ),
            ),
            instrumentation = instrumentation,
            scope = CoroutineScope(Dispatchers.Unconfined),
            enqueueImpulse = { false },
            hasPendingWork = { false },
        )

    private fun buildMotorCortex(): MotorCortex {
        val search = object : WebSearchEngine {
            override fun search(query: String, maxResults: Int): WebSearchResult =
                WebSearchResult(
                    summary = "found",
                    snippets = listOf("result")
                )
        }
        return MotorCortex(
            webSearchActionHandler = WebSearchActionHandler(engine = search),
            output = {},
        )
    }

    private fun runAgentWithInput(agent: Ego, stdinContent: String) {
        val previousIn = System.`in`
        try {
            System.setIn(ByteArrayInputStream(stdinContent.toByteArray()))
            runBlocking { agent.runInteractive() }
        } finally {
            System.setIn(previousIn)
        }
    }
}
