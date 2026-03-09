package psyke.instrumentation

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong

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
    scope: CoroutineScope,
    queueCapacity: Int = 2_048,
) : AgentInstrumentation, Closeable {
    private val channel = Channel<AgentEvent>(capacity = queueCapacity)
    private val nextEventId = AtomicLong(1)
    private val droppedEvents = AtomicLong(0)
    private val activeSinks = java.util.concurrent.CopyOnWriteArrayList<InstrumentationSink>(sinks)
    private val activeCriticalSinks = criticalSinks.toList()
    @Volatile
    private var droppedEventsObserver: ((delta: Long, total: Long) -> Unit)? = null
    private val dispatchJob: Job = scope.launch(CoroutineName("psyke-instrumentation-bus")) {
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
        val sendResult = channel.trySend(stampedEvent)
        if (sendResult.isSuccess) {
            return
        }

        // Channel full — drop oldest and retry (mirrors previous ArrayBlockingQueue behavior)
        var droppedDelta = 0L
        val polled = channel.tryReceive()
        if (polled.isSuccess) {
            droppedDelta += 1
        }
        if (channel.trySend(stampedEvent).isFailure) {
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

    fun addSink(sink: InstrumentationSink) {
        activeSinks.add(sink)
    }

    fun setDroppedEventsObserver(observer: ((delta: Long, total: Long) -> Unit)?) {
        droppedEventsObserver = observer
    }

    override fun close() {
        channel.close()
        // dispatchLoop will drain remaining items then exit when channel is closed and empty
        @Suppress("BlockingMethodInNonBlockingContext")
        kotlinx.coroutines.runBlocking {
            dispatchJob.join()
        }
        (activeCriticalSinks + activeSinks).distinct().forEach { sink ->
            try {
                sink.close()
            } catch (_: Exception) {
                // ignore sink shutdown failures
            }
        }
    }

    private suspend fun dispatchLoop() {
        for (event in channel) {
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
