package psyke.agent.id

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import psyke.agent.actions.websearch.WebSearchActionHandler
import psyke.agent.actions.websearch.WebSearchEngine
import psyke.agent.actions.websearch.WebSearchResult
import psyke.agent.actions.NoopReflectionMemoryRecorder
import psyke.support.buildTestEgo
import psyke.agent.model.ActionType
import psyke.agent.config.AgentConfig
import psyke.agent.model.ConversationContext
import psyke.agent.model.EgoDecision
import psyke.agent.model.EgoTrigger
import psyke.agent.model.OriginSource
import psyke.agent.model.PendingImpulse
import psyke.agent.model.PendingThought
import psyke.agent.config.PlannerConfig
import psyke.agent.model.PlannerContext
import psyke.agent.model.Urgency
import psyke.agent.cortex.motor.MotorCortex
import psyke.agent.ego.Ego
import psyke.agent.superego.Superego
import psyke.support.RecordingInstrumentation
import psyke.support.StubChatModelClient
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        val ego = buildTestEgo(
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
            .mapNotNull { it.data["action"] as? psyke.agent.model.PendingAction }
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
    fun `id internalize constraints persist on follow-up thoughts`() {
        val instrumentation = RecordingInstrumentation()
        var followUpHasContactDispatchable: Boolean? = null
        var followUpHasContactDefinition: Boolean? = null
        var followUpConvergence: ConvergenceMode? = null

        val planner = object : Ego.Planner {
            override fun decide(trigger: EgoTrigger, context: PlannerContext): EgoDecision =
                when (trigger) {
                    is EgoTrigger.IncomingInput -> EgoDecision.Noop("ignore user test input")
                    is EgoTrigger.IncomingImpulse -> EgoDecision.ProposeAction(
                        urgency = Urgency.MEDIUM,
                        actionType = ActionType.WEB_SEARCH,
                        payload = "emerging learning topics",
                        summary = "gather evidence"
                    )
                    is EgoTrigger.PendingThoughtInput -> {
                        if (trigger.thought.originActionType == ActionType.WEB_SEARCH) {
                            followUpHasContactDispatchable = ActionType.CONTACT_USER in context.dispatchableActions
                            followUpHasContactDefinition = context.actionDefinitions.any { it.actionType == ActionType.CONTACT_USER }
                            followUpConvergence = context.idState?.convergence
                        }
                        EgoDecision.Noop("done")
                    }
                }
        }

        val config = AgentConfig(
            planner = PlannerConfig(
                maxLoopStepsPerInput = 12,
                maxThoughtPasses = 2
            )
        )
        val ego = buildTestEgo(
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
        val idModule = buildIdModule(
            instrumentation = instrumentation,
            needConfig = NeedConfig(
                description = "test",
                growthRate = 0.1,
                cooldownPulses = 0,
                prompt = "Be useful.",
                convergence = ConvergenceMode.INTERNALIZE,
                allowEscalation = false,
                responseCurve = ResponseCurveConfig(type = "linear"),
            ),
        )
        ego.setId(idModule)

        val queued = ego.enqueueImpulse(
            PendingImpulse(
                id = 1,
                needId = "be-useful",
                prompt = "Find something useful to learn.",
                urgency = 0.9,
                rawValue = 0.9,
                rootImpulseId = "impulse-root-follow-up-internalize",
                conversationContext = idModule.conversationContext,
            ),
            maxPendingImpulses = 1
        )
        assertTrue(queued)

        runAgentWithInput(ego, "kickoff\nexit\n")

        assertNotNull(followUpHasContactDispatchable, "Expected a WEB_SEARCH follow-up thought to be processed")
        assertFalse(followUpHasContactDispatchable == true, "follow-up dispatchable actions must exclude contact_user")
        assertFalse(followUpHasContactDefinition == true, "follow-up action definitions must exclude contact_user")
        assertEquals(ConvergenceMode.INTERNALIZE, followUpConvergence)
    }

    @Test
    fun `id internalize constraints persist on impulse plan step thoughts`() {
        val instrumentation = RecordingInstrumentation()
        var planStepHasContactDispatchable: Boolean? = null
        var planStepHasContactDefinition: Boolean? = null
        var planStepConvergence: ConvergenceMode? = null

        val planner = object : Ego.Planner {
            override fun decide(trigger: EgoTrigger, context: PlannerContext): EgoDecision =
                when (trigger) {
                    is EgoTrigger.IncomingInput -> EgoDecision.Noop("ignore user test input")
                    is EgoTrigger.IncomingImpulse -> EgoDecision.EnqueuePlan(
                        urgency = Urgency.MEDIUM,
                        goal = "learn",
                        steps = listOf("collect insight")
                    )
                    is EgoTrigger.PendingThoughtInput -> {
                        if (trigger.thought.planContext != null) {
                            planStepHasContactDispatchable = ActionType.CONTACT_USER in context.dispatchableActions
                            planStepHasContactDefinition = context.actionDefinitions.any { it.actionType == ActionType.CONTACT_USER }
                            planStepConvergence = context.idState?.convergence
                        }
                        EgoDecision.Noop("done")
                    }
                }
        }

        val config = AgentConfig(
            planner = PlannerConfig(
                maxLoopStepsPerInput = 12,
                maxThoughtPasses = 2
            )
        )
        val ego = buildTestEgo(
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
        val idModule = buildIdModule(
            instrumentation = instrumentation,
            needConfig = NeedConfig(
                description = "test",
                growthRate = 0.1,
                cooldownPulses = 0,
                prompt = "Be useful.",
                convergence = ConvergenceMode.INTERNALIZE,
                allowEscalation = false,
                responseCurve = ResponseCurveConfig(type = "linear"),
            ),
        )
        ego.setId(idModule)

        val queued = ego.enqueueImpulse(
            PendingImpulse(
                id = 1,
                needId = "be-useful",
                prompt = "Plan a learning step.",
                urgency = 0.9,
                rawValue = 0.9,
                rootImpulseId = "impulse-root-plan-internalize",
                conversationContext = idModule.conversationContext,
            ),
            maxPendingImpulses = 1
        )
        assertTrue(queued)

        runAgentWithInput(ego, "kickoff\nexit\n")

        assertNotNull(planStepHasContactDispatchable, "Expected an impulse-origin plan step thought to be processed")
        assertFalse(planStepHasContactDispatchable == true, "plan-step dispatchable actions must exclude contact_user")
        assertFalse(planStepHasContactDefinition == true, "plan-step action definitions must exclude contact_user")
        assertEquals(ConvergenceMode.INTERNALIZE, planStepConvergence)
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
        val ego = buildTestEgo(
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

    private fun buildIdModule(
        instrumentation: RecordingInstrumentation,
        needConfig: NeedConfig = NeedConfig(
            description = "test",
            growthRate = 0.1,
            cooldownPulses = 0,
            prompt = "Be useful.",
            responseCurve = ResponseCurveConfig(type = "linear"),
        ),
    ): Id =
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
                    "be-useful" to needConfig,
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
            reflectionMemoryRecorder = NoopReflectionMemoryRecorder,
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
