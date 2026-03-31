package ai.neopsyke.agent

import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.config.PlannerConfig
import ai.neopsyke.agent.cortex.motor.MotorCortex
import ai.neopsyke.agent.cortex.motor.actions.ActionDescriptor
import ai.neopsyke.agent.cortex.motor.actions.ActionExecutionContext
import ai.neopsyke.agent.cortex.motor.actions.ActionRegistry
import ai.neopsyke.agent.cortex.motor.actions.AgentActionPlugin
import ai.neopsyke.agent.cortex.motor.actions.NoopReflectionMemoryRecorder
import ai.neopsyke.agent.cortex.motor.actions.RoutedConversationOutputGateway
import ai.neopsyke.agent.cortex.motor.actions.async.AsyncActionHandle
import ai.neopsyke.agent.cortex.motor.actions.async.AsyncActionWait
import ai.neopsyke.agent.cortex.motor.actions.async.AsyncResumeMode
import ai.neopsyke.agent.cortex.motor.actions.plugin.builtin.ContactUserActionPlugin
import ai.neopsyke.agent.cortex.motor.actions.websearch.WebSearchActionHandler
import ai.neopsyke.agent.cortex.motor.actions.websearch.WebSearchEngine
import ai.neopsyke.agent.cortex.motor.actions.websearch.WebSearchResult
import ai.neopsyke.agent.cortex.sensory.ActionFeedbackCue
import ai.neopsyke.agent.cortex.sensory.CognitiveSignal
import ai.neopsyke.agent.cortex.sensory.RuntimeControlSignal
import ai.neopsyke.agent.cortex.sensory.SensoryCortex
import ai.neopsyke.agent.cortex.sensory.Signal
import ai.neopsyke.agent.cortex.sensory.SignalSource
import ai.neopsyke.agent.model.ActionExecutionStatus
import ai.neopsyke.agent.model.ActionOutcome
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.ConversationSecurityContexts
import ai.neopsyke.agent.model.CommitMode
import ai.neopsyke.agent.model.EgoDecision
import ai.neopsyke.agent.model.EgoTrigger
import ai.neopsyke.agent.model.InputPriority
import ai.neopsyke.agent.model.IntentionKind
import ai.neopsyke.agent.model.Interlocutor
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.model.PlannerContext
import ai.neopsyke.agent.model.Provenances
import ai.neopsyke.agent.model.RootInputIds
import ai.neopsyke.agent.model.StimulusEnvelope
import ai.neopsyke.agent.model.StimulusFamily
import ai.neopsyke.agent.model.StimulusTrustLevel
import ai.neopsyke.agent.model.Urgency
import ai.neopsyke.agent.superego.Superego
import ai.neopsyke.support.RecordingInstrumentation
import ai.neopsyke.support.StubChatModelClient
import ai.neopsyke.support.buildTestEgo
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class EgoAsyncFeedbackIntegrationTest {
    @Test
    fun `ordinary async wait suspends until external feedback resumes the thread`() = runBlocking {
        val source = QueueSignalSource()
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val pendingThoughts = mutableListOf<String>()
        val feedbackSignals = mutableListOf<String>()
        val context = ConversationContext(
            sessionId = "async-user-session",
            interlocutor = Interlocutor.named("async-user"),
            security = ConversationSecurityContexts.ownerDirect(
                provider = "web",
                channelId = "async-user-session",
            ),
        )
        val planner = object : ai.neopsyke.agent.ego.Ego.Planner {
            override fun decide(trigger: EgoTrigger, context: PlannerContext): EgoDecision =
                when (trigger) {
                    is EgoTrigger.IncomingInput -> EgoDecision.FormIntention(
                        urgency = Urgency.MEDIUM,
                        intentionKind = IntentionKind.OBSERVE,
                        actionType = ActionType("async_test"),
                        payload = """{"operation_id":"async-op-1"}""",
                        summary = "start async test operation",
                    )

                    is EgoTrigger.PendingThoughtInput -> {
                        pendingThoughts += trigger.thought.content
                        EgoDecision.Noop("unexpected deferred continuation")
                    }

                    is EgoTrigger.ActionFeedback -> {
                        feedbackSignals += trigger.feedback.cue.feedbackContent
                        if (trigger.feedback.cue.feedbackContent.contains("download finished")) {
                            EgoDecision.FormIntention(
                                urgency = Urgency.MEDIUM,
                                intentionKind = IntentionKind.OBSERVE,
                                actionType = ActionType.CONTACT_USER,
                                payload = "async user done",
                                summary = "report async completion",
                            )
                        } else {
                            EgoDecision.Noop("waiting for completion feedback")
                        }
                    }

                    else -> EgoDecision.Noop("ignore non-conversation triggers")
                }
        }
        val config = AgentConfig(
            planner = PlannerConfig(maxLoopStepsPerInput = 8, maxThoughtPasses = 2),
        )
        val agent = buildTestEgo(
            planner = planner,
            superego = allowAllSuperego(config, instrumentation),
            motorCortex = buildAsyncMotorCortex(outputs),
            config = config,
            instrumentation = instrumentation,
            sensoryCortex = SensoryCortex(config, source),
        )

        val loop = launch { agent.runInteractive() }
        try {
            source.offerInput("start async task", conversationContext = context)

            val waitingFeedback = waitForValue("waiting action feedback") {
                instrumentation.events.firstOrNull {
                    it.type == "action_feedback_emitted" && it.data["execution_status"] == "waiting"
                }
            }
            val rootInputId = waitingFeedback.data["root_input_id"] as? String
                ?: fail("Waiting feedback was missing root_input_id.")
            val waitingIntegrated = waitForValue("waiting feedback integration") {
                instrumentation.events.firstOrNull {
                    it.type == "action_feedback_integrated" &&
                        it.data["root_input_id"] == rootInputId &&
                        it.data["execution_status"] == "waiting"
                }
            }
            assertEquals(false, waitingIntegrated.data["continuation_required"])
            waitFor("thread wait update") {
                instrumentation.events.any {
                    it.type == "cognitive_thread_updated" &&
                        it.data["root_input_id"] == rootInputId &&
                        it.data["thread_status"] == "waiting"
                }
            }
            assertTrue(outputs.isEmpty(), "No user-visible answer should be emitted while the thread is waiting.")
            assertTrue(
                pendingThoughts.isEmpty(),
                "WAITING should not enqueue a planner continuation before external feedback arrives.",
            )

            source.offerActionFeedback(
                ActionFeedbackCue(
                    rootInputId = rootInputId,
                    actionType = ActionType("async_test"),
                    actionSummary = "start async test operation",
                    feedbackContent = "download finished",
                    statusSummary = "download finished",
                    plannerSignal = "download finished",
                    executionStatus = ActionExecutionStatus.SUCCESS,
                    conversationContext = context,
                )
            )

            waitFor("feedback opportunity enqueue") {
                instrumentation.events.any {
                    it.type == "opportunity_enqueued" &&
                        it.data["source"] == "feedback" &&
                        it.data["root_input_id"] == rootInputId
                }
            }
            waitFor("async completion answer") {
                outputs == listOf("ego> async user done")
            }
            assertEquals(listOf("ego> async user done"), outputs)
            assertTrue(pendingThoughts.isEmpty(), "Feedback re-entry should not be translated into a deferred thought.")
            assertEquals(listOf("download finished"), feedbackSignals)
        } finally {
            source.offer(RuntimeControlSignal.ExitRequested("test"))
            loop.join()
        }
    }

    @Test
    fun `successful feedback without replanning resolves the thread`() = runBlocking {
        val source = QueueSignalSource()
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val pendingThoughts = mutableListOf<String>()
        val context = ConversationContext(
            sessionId = "feedback-resolution-session",
            interlocutor = Interlocutor.named("resolution-user"),
            security = ConversationSecurityContexts.ownerDirect(
                provider = "web",
                channelId = "feedback-resolution-session",
            ),
        )
        val planner = object : ai.neopsyke.agent.ego.Ego.Planner {
            override fun decide(trigger: EgoTrigger, context: PlannerContext): EgoDecision =
                when (trigger) {
                    is EgoTrigger.IncomingInput -> EgoDecision.FormIntention(
                        urgency = Urgency.MEDIUM,
                        intentionKind = IntentionKind.OBSERVE,
                        actionType = ActionType("background_success"),
                        payload = """{"mode":"background"}""",
                        summary = "run background success action",
                    )

                    is EgoTrigger.PendingThoughtInput -> {
                        pendingThoughts += trigger.thought.content
                        EgoDecision.Noop("unexpected deferred continuation")
                    }

                    is EgoTrigger.ActionFeedback -> EgoDecision.Noop("background success should resolve without replanning")

                    else -> EgoDecision.Noop("ignore non-conversation triggers")
                }
        }
        val backgroundPlugin = object : AgentActionPlugin {
            override val descriptor: ActionDescriptor = ActionDescriptor(
                actionType = ActionType("background_success"),
                dispatchable = true,
                plannerDescription = "background_success: complete a background action without needing synthesis.",
                payloadGuidance = """JSON: {"mode":"background"}""",
                requiresFollowUpThought = false,
            )

            override suspend fun execute(action: PendingAction, context: ActionExecutionContext): ActionOutcome =
                ActionOutcome(
                    statusSummary = "Background action completed.",
                    executionStatus = ActionExecutionStatus.SUCCESS,
                    plannerSignal = "background action completed",
                )
        }
        val contactPlugin = ContactUserActionPlugin(
            conversationOutput = RoutedConversationOutputGateway(
                fallbackOutput = { text -> outputs += text }
            )
        )
        val config = AgentConfig(
            planner = PlannerConfig(maxLoopStepsPerInput = 8, maxThoughtPasses = 2),
        )
        val agent = buildTestEgo(
            planner = planner,
            superego = allowAllSuperego(config, instrumentation),
            motorCortex = MotorCortex(ActionRegistry.fromPlugins(listOf(contactPlugin, backgroundPlugin))),
            config = config,
            instrumentation = instrumentation,
            sensoryCortex = SensoryCortex(config, source),
        )

        val loop = launch { agent.runInteractive() }
        try {
            source.offerInput("run background task", conversationContext = context)

            val resolvedUpdate = waitForValue("resolved thread update") {
                instrumentation.events.firstOrNull {
                    it.type == "cognitive_thread_updated" &&
                        it.data["thread_status"] == "resolved" &&
                        it.data["reason"] == "feedback_terminal_resolved"
                }
            }
            assertEquals("resolved", resolvedUpdate.data["thread_status"])
            assertTrue(outputs.isEmpty(), "Successful background feedback should not synthesize a user answer by itself.")
            assertTrue(pendingThoughts.isEmpty(), "Resolved feedback should not enqueue deferred continuation work.")
        } finally {
            source.offer(RuntimeControlSignal.ExitRequested("test"))
            loop.join()
        }
    }

    @Test
    fun `runtime blocks planner actions outside the current opportunity and requests an alternative`() = runBlocking {
        val source = QueueSignalSource()
        val instrumentation = RecordingInstrumentation()
        val outputs = mutableListOf<String>()
        val retryThoughts = mutableListOf<String>()
        val context = ConversationContext(
            sessionId = "opportunity-guard-session",
            interlocutor = Interlocutor.named("guard-user"),
            security = ConversationSecurityContexts.ownerDirect(
                provider = "web",
                channelId = "opportunity-guard-session",
            ),
        )
        val planner = object : ai.neopsyke.agent.ego.Ego.Planner {
            override fun decide(trigger: EgoTrigger, context: PlannerContext): EgoDecision =
                when (trigger) {
                    is EgoTrigger.IncomingInput -> EgoDecision.FormIntention(
                        urgency = Urgency.MEDIUM,
                        intentionKind = IntentionKind.PREPARE,
                        commitModePreference = CommitMode.APPROVAL_BACKED,
                        actionType = ActionType.GOAL_OPERATION,
                        payload = """{"operation":"create"}""",
                        summary = "illegal control-plane action",
                    )

                    is EgoTrigger.PendingThoughtInput -> {
                        retryThoughts += trigger.thought.content
                        EgoDecision.FormIntention(
                            urgency = Urgency.MEDIUM,
                            intentionKind = IntentionKind.OBSERVE,
                            actionType = ActionType.CONTACT_USER,
                            payload = "safe alternative",
                            summary = "fallback response",
                        )
                    }

                    is EgoTrigger.ActionFeedback -> EgoDecision.Noop("ignore feedback in opportunity guard test")

                    else -> EgoDecision.Noop("ignore non-conversation triggers")
                }
        }
        val config = AgentConfig(
            planner = PlannerConfig(maxLoopStepsPerInput = 8, maxThoughtPasses = 2),
        )
        val agent = buildTestEgo(
            planner = planner,
            superego = allowAllSuperego(config, instrumentation),
            motorCortex = buildConversationMotorCortex(outputs),
            config = config,
            instrumentation = instrumentation,
            sensoryCortex = SensoryCortex(config, source),
        )

        val loop = launch { agent.runInteractive() }
        try {
            source.offerInput("do something hidden", conversationContext = context)

            waitFor("runtime action guard answer") {
                outputs == listOf("ego> safe alternative")
            }
            val blockedEvent = waitForValue("planner decision blocked event") {
                instrumentation.events.firstOrNull { it.type == "planner_decision_blocked" }
            }

            assertEquals("goal_operation", blockedEvent.data["action_type"])
            assertEquals("ACTION_TYPE_NOT_AVAILABLE", blockedEvent.data["reason_code"])
            assertTrue(retryThoughts.single().contains("Action 'goal_operation' is not available"))
            assertTrue(
                instrumentation.events.none {
                    it.type == "action_proposed" && it.data["action_type"] == "goal_operation"
                }
            )
        } finally {
            source.offer(RuntimeControlSignal.ExitRequested("test"))
            loop.join()
        }
    }

    private fun buildAsyncMotorCortex(outputs: MutableList<String>): MotorCortex {
        val asyncPlugin = object : AgentActionPlugin {
            override val descriptor: ActionDescriptor = ActionDescriptor(
                actionType = ActionType("async_test"),
                dispatchable = true,
                plannerDescription = "async_test: start a test async operation and return a durable handle.",
                payloadGuidance = """JSON: {"operation_id":"async-op-1"}""",
                requiresFollowUpThought = false,
            )

            override suspend fun execute(action: PendingAction, context: ActionExecutionContext): ActionOutcome =
                ActionOutcome(
                    statusSummary = "Async operation started.",
                    executionStatus = ActionExecutionStatus.WAITING,
                    asyncWait = AsyncActionWait(
                        handles = listOf(
                            AsyncActionHandle(
                                providerType = "test_async",
                                providerId = "provider-1",
                                operationId = "async-op-1",
                                resumeMode = AsyncResumeMode.EVENT,
                                correlationKey = "async-op-1",
                                timeoutAt = Instant.now().plusSeconds(5),
                            )
                        ),
                        summary = "Waiting for async completion.",
                    ),
                )
        }
        val contactPlugin = ContactUserActionPlugin(
            conversationOutput = RoutedConversationOutputGateway(
                fallbackOutput = { text -> outputs += text }
            )
        )
        return MotorCortex(ActionRegistry.fromPlugins(listOf(contactPlugin, asyncPlugin)))
    }

    private fun buildConversationMotorCortex(outputs: MutableList<String>): MotorCortex =
        MotorCortex(
            webSearchActionHandler = WebSearchActionHandler(
                engine = object : WebSearchEngine {
                    override fun search(query: String, maxResults: Int): WebSearchResult =
                        WebSearchResult(summary = "unused", snippets = emptyList())
                }
            ),
            output = { text -> outputs += text },
            reflectionMemoryRecorder = NoopReflectionMemoryRecorder,
        )

    private fun allowAllSuperego(
        config: AgentConfig,
        instrumentation: RecordingInstrumentation,
    ): Superego {
        val client = StubChatModelClient().apply {
            repeat(4) { enqueueRawResponse("""{"allow":true}""") }
        }
        return Superego(
            modelClient = client,
            config = config,
            instrumentation = instrumentation,
        )
    }

    private suspend fun waitFor(
        label: String,
        timeoutMs: Long = 3_000,
        condition: () -> Boolean,
    ) {
        waitForValue(label, timeoutMs) {
            condition().takeIf { it }
        }
    }

    private suspend fun <T> waitForValue(
        label: String,
        timeoutMs: Long = 3_000,
        supplier: () -> T?,
    ): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            supplier()?.let { return it }
            delay(10)
        }
        fail("Timed out waiting for $label.")
    }

    private class QueueSignalSource : SignalSource {
        private val signals = Channel<Signal>(Channel.UNLIMITED)

        fun offer(signal: Signal) {
            signals.trySend(signal).getOrThrow()
        }

        fun offerInput(
            content: String,
            conversationContext: ConversationContext,
            priority: InputPriority = InputPriority.HIGH,
        ) {
            offer(
                CognitiveSignal.StimulusReceived(
                    StimulusEnvelope(
                        id = RootInputIds.next(),
                        family = StimulusFamily.LINGUISTIC,
                        source = "web",
                        content = content,
                        receivedAt = Instant.now(),
                        conversationContext = conversationContext,
                        trustLevel = StimulusTrustLevel.DEFAULT,
                        provenance = Provenances.trustedMessage(
                            provider = "web",
                            sourceRef = conversationContext.sessionId,
                        ),
                        metadata = mapOf("priority" to priority.name),
                    )
                )
            )
        }

        fun offerActionFeedback(cue: ActionFeedbackCue) {
            offer(CognitiveSignal.StimulusReceived(cue.toStimulus()))
        }

        override suspend fun nextSignal(): Signal = signals.receive()
    }
}
