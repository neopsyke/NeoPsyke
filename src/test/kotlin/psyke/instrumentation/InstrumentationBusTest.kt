package psyke.instrumentation

import psyke.support.RecordingSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InstrumentationBusTest {
    @Test
    fun `bus dispatches events and stamps incremental ids`() {
        val sink = RecordingSink(expected = 3)
        InstrumentationBus(sinks = listOf(sink), queueCapacity = 16).use { bus ->
            bus.emit(AgentEvent(type = "a"))
            bus.emit(AgentEvent(type = "b"))
            bus.emit(AgentEvent(type = "c"))
            assertTrue(sink.await())
        }

        val ids = sink.events.sortedBy { it.id }.map { it.id }
        assertEquals(listOf(1L, 2L, 3L), ids)
    }

    @Test
    fun `bus preserves pre-stamped event ids`() {
        val sink = RecordingSink(expected = 1)
        InstrumentationBus(sinks = listOf(sink), queueCapacity = 4).use { bus ->
            bus.emit(AgentEvent(id = 55, type = "preset"))
            assertTrue(sink.await())
        }
        assertEquals(55L, sink.events.single().id)
    }
}
