package psyke.dashboard

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import psyke.agent.QueueState
import psyke.instrumentation.AgentEvent
import psyke.instrumentation.InstrumentationSink
import psyke.metrics.MetricsSnapshot
import java.io.Closeable
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import kotlin.math.max

class DashboardStateStore(
    private val maxEvents: Int = 500,
) : InstrumentationSink {
    private val mapper = jacksonObjectMapper()
    private val lock = Any()
    private val events = ArrayDeque<AgentEvent>()
    private var loopStatus: String = "idle"
    private var loopMessage: String? = null
    private var loopStep: Int = 0
    private var currentTaskType: String? = null
    private var currentProcessing: Map<String, Any?>? = null
    private var queues: QueueState = QueueState(emptyList(), emptyList(), emptyList())
    private var lastSuperegoInput: Map<String, Any?>? = null
    private var lastSuperegoOutput: Map<String, Any?>? = null
    private var limits: Map<String, Any?> = emptyMap()
    private var metrics: MetricsSnapshot? = null
    private val subscribers = mutableSetOf<LinkedBlockingDeque<String>>()

    override fun onEvent(event: AgentEvent) {
        val payloadJson: String
        synchronized(lock) {
            if (events.size >= max(50, maxEvents)) {
                events.removeFirst()
            }
            events.addLast(event)

            when (event.type) {
                "loop_status" -> {
                    loopStatus = event.data["status"]?.toString() ?: loopStatus
                    loopMessage = event.data["message"]?.toString()
                }

                "loop_step" -> {
                    loopStep = (event.data["step"] as? Number)?.toInt() ?: loopStep
                    currentTaskType = event.data["task_type"]?.toString()
                }

                "queue_snapshot" -> {
                    val nextQueues = event.data["queues"] as? QueueState
                    if (nextQueues != null) {
                        queues = nextQueues
                    }
                }

                "input_processing" -> {
                    currentProcessing = mapOf(
                        "kind" to "input",
                        "item" to (event.data["input"] ?: "")
                    )
                }

                "thought_processing" -> {
                    currentProcessing = mapOf(
                        "kind" to "thought",
                        "item" to (event.data["thought"] ?: "")
                    )
                }

                "action_review_requested" -> {
                    currentProcessing = mapOf(
                        "kind" to "action_review",
                        "item" to (event.data["action"] ?: "")
                    )
                }

                "action_executed" -> {
                    currentProcessing = mapOf(
                        "kind" to "action_execute",
                        "item" to (event.data["action"] ?: ""),
                        "outcome" to event.data["outcome_summary"]
                    )
                }

                "superego_input" -> {
                    lastSuperegoInput = event.data
                }

                "superego_output" -> {
                    lastSuperegoOutput = event.data
                }

                "limits_config" -> {
                    @Suppress("UNCHECKED_CAST")
                    limits = (event.data["limits"] as? Map<String, Any?>).orEmpty()
                }

                "metrics_snapshot" -> {
                    metrics = event.data["metrics"] as? MetricsSnapshot
                }
            }

            payloadJson = mapper.writeValueAsString(event)
            val staleSubscribers = mutableListOf<LinkedBlockingDeque<String>>()
            subscribers.forEach { queue ->
                if (!queue.offerLast(payloadJson)) {
                    queue.pollFirst()
                    if (!queue.offerLast(payloadJson)) {
                        staleSubscribers.add(queue)
                    }
                }
            }
            subscribers.removeAll(staleSubscribers.toSet())
        }
    }

    fun snapshotJson(): String {
        val snapshot = synchronized(lock) {
            DashboardSnapshot(
                generatedAt = System.currentTimeMillis(),
                loopStatus = loopStatus,
                loopMessage = loopMessage,
                loopStep = loopStep,
                currentTaskType = currentTaskType,
                currentProcessing = currentProcessing,
                queues = queues,
                lastSuperegoInput = lastSuperegoInput,
                lastSuperegoOutput = lastSuperegoOutput,
                limits = limits,
                metrics = metrics,
                recentEvents = events.toList().sortedBy { it.id }
            )
        }
        return mapper.writeValueAsString(snapshot)
    }

    fun subscribe(): DashboardSubscription {
        val queue = LinkedBlockingDeque<String>(1_000)
        synchronized(lock) {
            subscribers.add(queue)
        }
        return DashboardSubscription(queue) {
            synchronized(lock) {
                subscribers.remove(queue)
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            subscribers.clear()
        }
    }
}

data class DashboardSnapshot(
    val generatedAt: Long,
    val loopStatus: String,
    val loopMessage: String?,
    val loopStep: Int,
    val currentTaskType: String?,
    val currentProcessing: Map<String, Any?>?,
    val queues: QueueState,
    val lastSuperegoInput: Map<String, Any?>?,
    val lastSuperegoOutput: Map<String, Any?>?,
    val limits: Map<String, Any?>,
    val metrics: MetricsSnapshot?,
    val recentEvents: List<AgentEvent>,
)

class DashboardSubscription(
    private val queue: LinkedBlockingDeque<String>,
    private val onClose: () -> Unit,
) : Closeable {
    fun poll(timeoutMs: Long = 30_000): String? = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)

    override fun close() {
        onClose()
    }
}
