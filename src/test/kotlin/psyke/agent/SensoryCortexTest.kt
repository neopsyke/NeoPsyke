package psyke.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import psyke.agent.model.ConversationContext
import psyke.agent.model.Interlocutor
import psyke.agent.config.InterlocutorResolver
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
        val input = assertIs<psyke.agent.cortex.sensory.SensorySignal.InputReceived>(signal).input
        assertEquals(InputPriority.HIGH, input.priority)
        assertEquals("stdin", input.source)
    }

    @Test
    fun `sensory cortex sanitizes input content and keeps explicit priority`() = runBlocking {
        val source = SensoryInputSource {
            psyke.agent.cortex.sensory.SensorySignal.InputReceived(
                SensoryInput(
                    content = "  ${"x".repeat(50)}  ",
                    priority = InputPriority.LOW,
                    source = "webhook"
                )
            )
        }
        val cortex = SensoryCortex(
            config = AgentConfig(planner = PlannerConfig(maxInputChars = 12)),
            source = source
        )

        val signal = cortex.nextSignal()
        val input = assertIs<psyke.agent.cortex.sensory.SensorySignal.InputReceived>(signal).input
        assertEquals("x".repeat(12), input.content)
        assertEquals(InputPriority.LOW, input.priority)
        assertEquals("webhook", input.source)
    }

    @Test
    fun `sensory cortex resolves unknown interlocutor and derives session from chat source`() = runBlocking {
        val source = SensoryInputSource {
            psyke.agent.cortex.sensory.SensorySignal.InputReceived(
                SensoryInput(
                    content = "hello",
                    source = "chat:session-42",
                    conversationContext = ConversationContext.default()
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
        val input = assertIs<psyke.agent.cortex.sensory.SensorySignal.InputReceived>(signal).input
        assertEquals("session-42", input.conversationContext.sessionId)
        assertEquals("Victor", input.conversationContext.interlocutor.id)
    }

    @Test
    fun `sensory cortex preserves explicit session and interlocutor`() = runBlocking {
        val explicitContext = ConversationContext(
            sessionId = "explicit-session",
            interlocutor = Interlocutor.named("Alice")
        )
        val source = SensoryInputSource {
            psyke.agent.cortex.sensory.SensorySignal.InputReceived(
                SensoryInput(
                    content = "hello",
                    source = "chat:ignored-session",
                    conversationContext = explicitContext
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
        val input = assertIs<psyke.agent.cortex.sensory.SensorySignal.InputReceived>(signal).input
        assertEquals("explicit-session", input.conversationContext.sessionId)
        assertEquals("Alice", input.conversationContext.interlocutor.id)
    }

    @Test
    fun `async stdin control-only mode ignores text input and only emits exit`() = runBlocking {
        val scope = testScope()
        val scriptedInputs = ArrayDeque(listOf("hello from terminal", "exit"))
        val controlMessages = mutableListOf<String>()
        val source = psyke.agent.cortex.sensory.AsyncSensoryInputSource(
            includeStdin = true,
            emitStdinClosedSignal = false,
            pollTimeoutMs = 10L,
            stdinMode = psyke.agent.cortex.sensory.AsyncSensoryInputSource.StdinMode.CONTROL_ONLY,
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
            var exitSignal: psyke.agent.cortex.sensory.SensorySignal? = null
            for (@Suppress("unused") attempt in 1..100) {
                val signal = source.nextSignal()
                if (signal is psyke.agent.cortex.sensory.SensorySignal.ExitRequested) {
                    exitSignal = signal
                    break
                }
                if (signal is psyke.agent.cortex.sensory.SensorySignal.InputReceived) {
                    throw AssertionError("Control-only stdin must not emit InputReceived signals.")
                }
            }
            val exit = assertIs<psyke.agent.cortex.sensory.SensorySignal.ExitRequested>(exitSignal)
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
