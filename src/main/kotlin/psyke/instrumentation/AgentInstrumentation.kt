package psyke.instrumentation

import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

interface AgentInstrumentation {
    fun emit(event: AgentEvent)
}

object NoopAgentInstrumentation : AgentInstrumentation {
    override fun emit(event: AgentEvent) {}
}

interface InstrumentationSink : Closeable {
    fun onEvent(event: AgentEvent)

    override fun close() {}
}

class InstrumentationBus(
    sinks: List<InstrumentationSink>,
    criticalSinks: List<InstrumentationSink> = emptyList(),
    queueCapacity: Int = 2_048,
) : AgentInstrumentation, Closeable {
    private val queue = ArrayBlockingQueue<AgentEvent>(queueCapacity)
    private val nextEventId = AtomicLong(1)
    private val droppedEvents = AtomicLong(0)
    private val activeSinks = sinks.toList()
    private val activeCriticalSinks = criticalSinks.toList()
    @Volatile
    private var droppedEventsObserver: ((delta: Long, total: Long) -> Unit)? = null
    @Volatile
    private var running = true
    private val worker = thread(
        name = "psyke-instrumentation-bus",
        isDaemon = true
    ) {
        dispatchLoop()
    }

    override fun emit(event: AgentEvent) {
        val stampedEvent = if (event.id > 0) event else event.copy(id = nextEventId.getAndIncrement())
        activeCriticalSinks.forEach { sink ->
            try {
                sink.onEvent(stampedEvent)
            } catch (_: Exception) {
                // keep instrumentation path robust
            }
        }
        if (queue.offer(stampedEvent)) {
            return
        }

        var droppedDelta = 0L
        if (queue.poll() != null) {
            droppedDelta += 1
        }
        if (!queue.offer(stampedEvent)) {
            droppedDelta += 1
        }
        if (droppedDelta > 0) {
            val totalDropped = droppedEvents.addAndGet(droppedDelta)
            try {
                droppedEventsObserver?.invoke(droppedDelta, totalDropped)
            } catch (_: Exception) {
                // keep instrumentation path robust
            }
        }
    }

    fun setDroppedEventsObserver(observer: ((delta: Long, total: Long) -> Unit)?) {
        droppedEventsObserver = observer
    }

    override fun close() {
        running = false
        worker.join(1_500)
        (activeCriticalSinks + activeSinks).distinct().forEach { sink ->
            try {
                sink.close()
            } catch (_: Exception) {
                // ignore sink shutdown failures
            }
        }
    }

    private fun dispatchLoop() {
        while (running || queue.isNotEmpty()) {
            val event = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
            activeSinks.forEach { sink ->
                try {
                    sink.onEvent(event)
                } catch (_: Exception) {
                    // keep dispatcher alive if one sink fails
                }
            }
        }
    }
}
