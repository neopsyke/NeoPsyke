package psyke.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SensoryCortexTest {
    @Test
    fun `sensory input defaults to medium priority`() {
        val input = SensoryInput(content = "hello")

        assertEquals(InputPriority.MEDIUM, input.priority)
    }

    @Test
    fun `stdin source emits highest-priority input`() {
        val source = StdinSensoryInputSource(
            readLineFn = { "hello from stdin" },
            prompt = {}
        )

        val signal = source.nextSignal()
        val input = assertIs<SensorySignal.InputReceived>(signal).input
        assertEquals(InputPriority.HIGH, input.priority)
        assertEquals("stdin", input.source)
    }

    @Test
    fun `sensory cortex sanitizes input content and keeps explicit priority`() {
        val source = SensoryInputSource {
            SensorySignal.InputReceived(
                SensoryInput(
                    content = "  ${"x".repeat(50)}  ",
                    priority = InputPriority.LOW,
                    source = "webhook"
                )
            )
        }
        val cortex = SensoryCortex(
            config = AgentConfig(maxInputChars = 12),
            source = source
        )

        val signal = cortex.nextSignal()
        val input = assertIs<SensorySignal.InputReceived>(signal).input
        assertEquals("x".repeat(12), input.content)
        assertEquals(InputPriority.LOW, input.priority)
        assertEquals("webhook", input.source)
    }
}
