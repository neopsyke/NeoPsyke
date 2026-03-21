package ai.neopsyke.agent.id

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import ai.neopsyke.agent.actions.ReflectionMemoryRecorder
import ai.neopsyke.agent.actions.websearch.WebSearchActionHandler
import ai.neopsyke.agent.actions.websearch.WebSearchEngine
import ai.neopsyke.agent.actions.websearch.WebSearchResult
import ai.neopsyke.agent.actions.NoopReflectionMemoryRecorder
import ai.neopsyke.support.buildTestEgo
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.EgoDecision
import ai.neopsyke.agent.model.EgoTrigger
import ai.neopsyke.agent.model.OriginSource
import ai.neopsyke.agent.model.ActionEffect
import ai.neopsyke.agent.model.PendingImpulse
import ai.neopsyke.agent.model.PendingThought
import ai.neopsyke.agent.config.PlannerConfig
import ai.neopsyke.agent.model.PlannerContext
import ai.neopsyke.agent.model.Urgency
import ai.neopsyke.agent.cortex.motor.MotorCortex
import ai.neopsyke.agent.ego.Ego
import ai.neopsyke.agent.superego.Superego
import ai.neopsyke.support.RecordingInstrumentation
import ai.neopsyke.support.StubChatModelClient
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
                    is EgoTrigger.GoalWork -> EgoDecision.Noop("ignore goal work in test")
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
            .mapNotNull { it.data["action"] as? ai.neopsyke.agent.model.PendingAction }
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
                    is EgoTrigger.GoalWork -> EgoDecision.Noop("ignore goal work in test")
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
                    is EgoTrigger.GoalWork -> EgoDecision.Noop("ignore goal work in test")
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
                    is EgoTrigger.GoalWork -> EgoDecision.Noop("ignore goal work in test")
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

    @Test
    fun `reflect impulse only satisfies id need when memory save succeeds`() {
        val instrumentation = RecordingInstrumentation()
        lateinit var ego: Ego
        val planner = object : Ego.Planner {
            override fun decide(trigger: EgoTrigger, context: PlannerContext): EgoDecision =
                when (trigger) {
                    is EgoTrigger.IncomingInput -> EgoDecision.Noop("ignore user test input")
                    is EgoTrigger.IncomingImpulse -> EgoDecision.ProposeAction(
                        urgency = Urgency.MEDIUM,
                        actionType = ActionType.REFLECT,
                        payload = """{"summary":"I learned something durable","keywords":["learning"]}""",
                        summary = "persist insight"
                    )
                    is EgoTrigger.GoalWork -> EgoDecision.Noop("ignore goal work in test")
                    is EgoTrigger.PendingThoughtInput -> EgoDecision.Noop("done")
                }
        }
        val config = AgentConfig(
            planner = PlannerConfig(
                maxLoopStepsPerInput = 8,
                maxThoughtPasses = 1
            )
        )
        ego = buildTestEgo(
            planner = planner,
            superego = Superego(
                modelClient = StubChatModelClient().apply { enqueueRawResponse("""{"allow":true}""") },
                config = config,
                instrumentation = instrumentation,
            ),
            motorCortex = buildMotorCortex(
                reflectionMemoryRecorder = object : ReflectionMemoryRecorder {
                    override fun recordReflection(
                        action: ai.neopsyke.agent.model.PendingAction,
                        summary: String,
                        keywords: List<String>,
                    ): Boolean = false
                }
            ),
            config = config,
            instrumentation = instrumentation,
        )
        val idModule = buildIdModule(
            instrumentation = instrumentation,
            needConfig = NeedConfig(
                description = "learn",
                growthRate = 0.1,
                satisfactionDecay = 0.8,
                cooldownPulses = 0,
                prompt = "Learn something worth remembering.",
                convergence = ConvergenceMode.INTERNALIZE,
                satisfactionEffectsAnyOf = setOf(ActionEffect.DURABLE_MEMORY_SAVED),
                responseCurve = ResponseCurveConfig(type = "linear"),
            ),
            enqueueImpulse = { impulse -> ego.enqueueImpulse(impulse, maxPendingImpulses = 1) },
        )
        ego.setId(idModule)

        val need = idModule.needs["be-useful"]!!
        idModule.pulse()
        assertTrue(need.inFlight)
        assertEquals(0.1, need.value, 1e-9)
        runAgentWithInput(ego, "kickoff\nexit\n")

        assertEquals(0, instrumentation.events.count { it.type == "id_impulse_completed" })
        assertEquals(1, instrumentation.events.count { it.type == "id_impulse_denied" })
        assertEquals(0.1, need.value, 1e-9, "Failed reflection save must not decay the need")
    }

    @Test
    fun `learn need is not satisfied by evidence gathering alone`() {
        val instrumentation = RecordingInstrumentation()
        lateinit var ego: Ego
        val planner = object : Ego.Planner {
            override fun decide(trigger: EgoTrigger, context: PlannerContext): EgoDecision =
                when (trigger) {
                    is EgoTrigger.IncomingInput -> EgoDecision.Noop("ignore user test input")
                    is EgoTrigger.IncomingImpulse -> EgoDecision.ProposeAction(
                        urgency = Urgency.MEDIUM,
                        actionType = ActionType.WEB_SEARCH,
                        payload = "interesting new topic",
                        summary = "gather evidence"
                    )
                    is EgoTrigger.GoalWork -> EgoDecision.Noop("ignore goal work in test")
                    is EgoTrigger.PendingThoughtInput -> EgoDecision.Noop("done")
                }
        }
        val config = AgentConfig(
            planner = PlannerConfig(
                maxLoopStepsPerInput = 8,
                maxThoughtPasses = 1
            )
        )
        ego = buildTestEgo(
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
                description = "learn",
                growthRate = 0.1,
                satisfactionDecay = 0.8,
                cooldownPulses = 0,
                prompt = "Learn something worth remembering.",
                convergence = ConvergenceMode.INTERNALIZE,
                satisfactionEffectsAnyOf = setOf(ActionEffect.DURABLE_MEMORY_SAVED),
                responseCurve = ResponseCurveConfig(type = "linear"),
            ),
            enqueueImpulse = { impulse -> ego.enqueueImpulse(impulse, maxPendingImpulses = 1) },
        )
        ego.setId(idModule)

        val need = idModule.needs["be-useful"]!!
        idModule.pulse()
        assertTrue(need.inFlight)
        assertEquals(0.1, need.value, 1e-9)
        runAgentWithInput(ego, "kickoff\nexit\n")

        assertEquals(0, instrumentation.events.count { it.type == "id_impulse_completed" })
        assertEquals(1, instrumentation.events.count { it.type == "id_impulse_denied" })
        assertEquals(0.1, need.value, 1e-9, "Evidence gathering alone must not decay the learn need")
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
        enqueueImpulse: (PendingImpulse) -> Boolean = { false },
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
            enqueueImpulse = enqueueImpulse,
            hasPendingWork = { false },
        )

    private fun buildMotorCortex(
        reflectionMemoryRecorder: ReflectionMemoryRecorder = NoopReflectionMemoryRecorder,
    ): MotorCortex {
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
            reflectionMemoryRecorder = reflectionMemoryRecorder,
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
