package ai.neopsyke.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import ai.neopsyke.agent.cortex.sensory.CognitiveCueMetadata
import ai.neopsyke.agent.cortex.sensory.CognitiveSignal
import ai.neopsyke.agent.cortex.sensory.RuntimeControlSignal
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.Interlocutor
import ai.neopsyke.agent.model.RootInputIds
import ai.neopsyke.agent.model.StimulusEnvelope
import ai.neopsyke.agent.model.StimulusFamily
import ai.neopsyke.agent.config.InterlocutorResolver
import ai.neopsyke.agent.model.StimulusTrustLevel
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SensoryCortexTest {
    private fun testScope() = CoroutineScope(SupervisorJob())

    @Test
    fun `sensory input defaults to medium priority`() {
        val input = SensoryInput(content = "hello")

        assertEquals(InputPriority.MEDIUM, input.priority)
    }

    @Test
    fun `stdin source emits highest-priority input`() = runBlocking {
        val source = StdinSensoryInputSource(
            readLineFn = { "hello from stdin" },
            prompt = {}
        )

        val signal = source.nextSignal()
        val stimulus = assertIs<CognitiveSignal.StimulusReceived>(signal).stimulus
        assertEquals(InputPriority.HIGH.name, stimulus.metadata["priority"])
        assertEquals("stdin", stimulus.source)
    }

    @Test
    fun `sensory cortex sanitizes input content and keeps explicit priority`() = runBlocking {
        val source = SensoryInputSource {
            CognitiveSignal.StimulusReceived(
                StimulusEnvelope(
                    id = RootInputIds.next(),
                    family = StimulusFamily.LINGUISTIC,
                    source = "webhook",
                    content = "  ${"x".repeat(50)}  ",
                    receivedAt = Instant.now(),
                    trustLevel = StimulusTrustLevel.DEFAULT,
                    metadata = mapOf("priority" to InputPriority.LOW.name),
                )
            )
        }
        val cortex = SensoryCortex(
            config = AgentConfig(planner = PlannerConfig(maxInputChars = 12)),
            source = source
        )

        val signal = cortex.nextSignal()
        val stimulus = assertIs<CognitiveSignal.StimulusReceived>(signal).stimulus
        assertEquals("x".repeat(12), stimulus.content)
        assertEquals(InputPriority.LOW.name, stimulus.metadata["priority"])
        assertEquals("webhook", stimulus.source)
    }

    @Test
    fun `sensory cortex resolves unknown interlocutor and derives session from chat source`() = runBlocking {
        val source = SensoryInputSource {
            CognitiveSignal.StimulusReceived(
                StimulusEnvelope(
                    id = RootInputIds.next(),
                    family = StimulusFamily.LINGUISTIC,
                    source = "chat:session-42",
                    content = "hello",
                    receivedAt = Instant.now(),
                    conversationContext = ConversationContext.default(),
                    trustLevel = StimulusTrustLevel.DEFAULT,
                    metadata = mapOf("priority" to InputPriority.MEDIUM.name),
                )
            )
        }
        val resolver = object : InterlocutorResolver {
            override fun resolve(source: String, metadata: Map<String, Any>?): Interlocutor =
                Interlocutor.named("Victor")
        }
        val cortex = SensoryCortex(
            config = AgentConfig(),
            source = source,
            interlocutorResolver = resolver
        )

        val signal = cortex.nextSignal()
        val stimulus = assertIs<CognitiveSignal.StimulusReceived>(signal).stimulus
        assertEquals("session-42", stimulus.conversationContext.sessionId)
        assertEquals("Victor", stimulus.conversationContext.interlocutor.id)
    }

    @Test
    fun `sensory cortex preserves explicit session and interlocutor`() = runBlocking {
        val explicitContext = ConversationContext(
            sessionId = "explicit-session",
            interlocutor = Interlocutor.named("Alice")
        )
        val source = SensoryInputSource {
            CognitiveSignal.StimulusReceived(
                StimulusEnvelope(
                    id = RootInputIds.next(),
                    family = StimulusFamily.LINGUISTIC,
                    source = "chat:ignored-session",
                    content = "hello",
                    receivedAt = Instant.now(),
                    conversationContext = explicitContext,
                    trustLevel = StimulusTrustLevel.DEFAULT,
                    metadata = mapOf("priority" to InputPriority.MEDIUM.name),
                )
            )
        }
        val resolver = object : InterlocutorResolver {
            override fun resolve(source: String, metadata: Map<String, Any>?): Interlocutor =
                Interlocutor.named("Victor")
        }
        val cortex = SensoryCortex(
            config = AgentConfig(),
            source = source,
            interlocutorResolver = resolver
        )

        val signal = cortex.nextSignal()
        val stimulus = assertIs<CognitiveSignal.StimulusReceived>(signal).stimulus
        assertEquals("explicit-session", stimulus.conversationContext.sessionId)
        assertEquals("Alice", stimulus.conversationContext.interlocutor.id)
    }

    @Test
    fun `notifyImpulseReady injects id cue stimulus into the channel`() = runBlocking {
        val scope = testScope()
        val source = ai.neopsyke.agent.cortex.sensory.AsyncSignalSource(
            includeStdin = false,
            emitStdinClosedSignal = false,
            pollTimeoutMs = 10L,
            scope = scope
        )
        try {
            val offered = source.notifyImpulseReady()
            assertTrue(offered, "notifyImpulseReady should succeed on an empty channel")

            val signal = source.nextSignal()
            val stimulus = assertIs<CognitiveSignal.StimulusReceived>(signal).stimulus
            assertEquals(StimulusFamily.CUE, stimulus.family)
            assertEquals(CognitiveCueMetadata.CUE_TYPE_ID_IMPULSE_READY, stimulus.metadata[CognitiveCueMetadata.METADATA_CUE_TYPE])
        } finally {
            source.close()
        }
    }

    @Test
    fun `id cue passes through SensoryCortex as cue stimulus`() = runBlocking {
        val scope = testScope()
        val source = ai.neopsyke.agent.cortex.sensory.AsyncSignalSource(
            includeStdin = false,
            emitStdinClosedSignal = false,
            pollTimeoutMs = 10L,
            scope = scope
        )
        val cortex = SensoryCortex(
            config = AgentConfig(),
            source = source
        )
        try {
            source.notifyImpulseReady()
            val signal = cortex.nextSignal()
            val stimulus = assertIs<CognitiveSignal.StimulusReceived>(signal).stimulus
            assertEquals(StimulusFamily.CUE, stimulus.family)
            assertEquals(CognitiveCueMetadata.CUE_TYPE_ID_IMPULSE_READY, stimulus.metadata[CognitiveCueMetadata.METADATA_CUE_TYPE])
        } finally {
            source.close()
        }
    }

    @Test
    fun `async stdin control-only mode ignores text input and only emits exit`() = runBlocking {
        val scope = testScope()
        val scriptedInputs = ArrayDeque(listOf("hello from terminal", "exit"))
        val controlMessages = mutableListOf<String>()
        val source = ai.neopsyke.agent.cortex.sensory.AsyncSignalSource(
            includeStdin = true,
            emitStdinClosedSignal = false,
            pollTimeoutMs = 10L,
            stdinMode = ai.neopsyke.agent.cortex.sensory.AsyncSignalSource.StdinMode.CONTROL_ONLY,
            readLineFn = {
                synchronized(scriptedInputs) {
                    scriptedInputs.removeFirstOrNull()
                }
            },
            prompt = {},
            controlOutput = controlMessages::add,
            scope = scope
        )
        try {
            var exitSignal: RuntimeControlSignal? = null
            for (@Suppress("unused") attempt in 1..100) {
                val signal = source.nextSignal()
                if (signal is RuntimeControlSignal.ExitRequested) {
                    exitSignal = signal
                    break
                }
                if (signal is CognitiveSignal.StimulusReceived) {
                    throw AssertionError("Control-only stdin must not emit cognitive stimuli.")
                }
            }
            val exit = assertIs<RuntimeControlSignal.ExitRequested>(exitSignal)
            assertEquals("stdin", exit.source)
            assertTrue(
                actual = controlMessages.any { it.contains("Unknown command 'hello from terminal'") },
                message = "Expected control output to report unknown control command."
            )
        } finally {
            source.close()
        }
    }
}
