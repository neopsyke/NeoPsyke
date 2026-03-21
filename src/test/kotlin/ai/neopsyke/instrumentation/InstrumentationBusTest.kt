package ai.neopsyke.instrumentation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import ai.neopsyke.support.RecordingSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InstrumentationBusTest {
    private fun testScope() = CoroutineScope(SupervisorJob())

    @Test
    fun `bus dispatches events and stamps incremental ids`() {
        val sink = RecordingSink(expected = 3)
        InstrumentationBus(sinks = listOf(sink), scope = testScope(), queueCapacity = 16).use { bus ->
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
        InstrumentationBus(sinks = listOf(sink), scope = testScope(), queueCapacity = 4).use { bus ->
            bus.emit(AgentEvent(id = 55, type = "preset"))
            assertTrue(sink.await())
        }
        assertEquals(55L, sink.events.single().id)
    }

    @Test
    fun `bus reports dropped events when queue overflows`() {
        val sink = RecordingSink(expected = 1)
        var totalDropped = 0L
        InstrumentationBus(sinks = listOf(sink), scope = testScope(), queueCapacity = 1).use { bus ->
            bus.setDroppedEventsObserver { _, total ->
                totalDropped = total
            }
            repeat(2_000) { idx ->
                bus.emit(AgentEvent(type = "evt-$idx"))
            }
            assertTrue(sink.await())
        }
        assertTrue(totalDropped > 0)
    }

    @Test
    fun `critical sinks receive all events even when async queue overflows`() {
        val asyncSink = RecordingSink(expected = 1)
        val criticalSink = RecordingSink()
        var totalDropped = 0L
        val totalEvents = 2_000

        InstrumentationBus(
            sinks = listOf(asyncSink),
            criticalSinks = listOf(criticalSink),
            scope = testScope(),
            queueCapacity = 1
        ).use { bus ->
            bus.setDroppedEventsObserver { _, total ->
                totalDropped = total
            }
            repeat(totalEvents) { idx ->
                bus.emit(AgentEvent(type = "evt-$idx"))
            }
            assertTrue(asyncSink.await())
        }

        assertTrue(totalDropped > 0)
        assertEquals(totalEvents, synchronized(criticalSink.events) { criticalSink.events.size })
    }
}
