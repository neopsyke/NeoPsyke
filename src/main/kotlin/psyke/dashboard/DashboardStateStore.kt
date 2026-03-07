package psyke.dashboard

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import psyke.agent.core.QueueState
import psyke.instrumentation.AgentEvent
import psyke.instrumentation.InstrumentationSink
import psyke.metrics.MetricsSnapshot
import java.io.Closeable
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import kotlin.math.max

class DashboardStateStore(
    private val maxEvents: Int = 500,
    private val maxWorkspaceSnapshots: Int = 120,
    private val workspaceSnapshotTtlMs: Long = 15 * 60 * 1000L,
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
    private var actionCapabilities: List<Map<String, Any?>> = emptyList()
    private var limits: Map<String, Any?> = emptyMap()
    private var metrics: MetricsSnapshot? = null
    private var droppedEvents: Long = 0
    private var queueSaturationEvents: Long = 0
    private val queueSaturationByType = mutableMapOf<String, Long>()
    private val workspaceSnapshots = ArrayDeque<WorkspaceSnapshotRecord>()
    private val latestWorkspaceSnapshotByRoot = mutableMapOf<Long, WorkspaceSnapshotRecord>()
    private val subscribers = mutableSetOf<LinkedBlockingDeque<String>>()

    override fun onEvent(event: AgentEvent) {
        var payloadJson: String? = null
        synchronized(lock) {
            val isDebugWorkspaceSnapshot = event.type == "task_workspace_debug_snapshot"
            if (isDebugWorkspaceSnapshot) {
                captureWorkspaceSnapshot(event.data)
            } else {
                if (event.type == "task_workspace_head") {
                    captureWorkspaceHead(event.data)
                }
                if (events.size >= max(50, maxEvents)) {
                    events.removeFirst()
                }
                events.addLast(event)
            }

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

                "action_capabilities" -> {
                    @Suppress("UNCHECKED_CAST")
                    actionCapabilities = (event.data["statuses"] as? List<Map<String, Any?>>).orEmpty()
                }

                "limits_config" -> {
                    @Suppress("UNCHECKED_CAST")
                    limits = (event.data["limits"] as? Map<String, Any?>).orEmpty()
                }

                "metrics_snapshot" -> {
                    metrics = event.data["metrics"] as? MetricsSnapshot
                }

                "queue_saturation" -> {
                    queueSaturationEvents += 1
                    val queueType = event.data["queue_type"]?.toString()?.ifBlank { "unknown" } ?: "unknown"
                    val current = queueSaturationByType[queueType] ?: 0L
                    queueSaturationByType[queueType] = current + 1
                }

                "instrumentation_health" -> {
                    val dropped = (event.data["dropped_events"] as? Number)?.toLong()
                    if (dropped != null) {
                        droppedEvents = max(droppedEvents, dropped)
                    }
                }
            }

            if (!isDebugWorkspaceSnapshot) {
                payloadJson = mapper.writeValueAsString(event)
            }
            payloadJson?.let { broadcastToSubscribers(it) }
        }
    }

    fun snapshotJson(): String {
        val snapshot = synchronized(lock) {
            pruneWorkspaceSnapshotsLocked()
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
                actionCapabilities = actionCapabilities,
                limits = limits,
                metrics = metrics,
                instrumentationHealth = instrumentationHealthMap(),
                recentEvents = events.toList().sortedBy { it.id }
            )
        }
        return mapper.writeValueAsString(snapshot)
    }

    fun workspaceIndexJson(): String {
        val payload = synchronized(lock) {
            pruneWorkspaceSnapshotsLocked()
            val items = latestWorkspaceSnapshotByRoot.values
                .sortedByDescending { it.updatedAtMs }
                .map { snapshot ->
                    mapOf(
                        "root_input_enqueued_at_ms" to snapshot.rootInputEnqueuedAtMs,
                        "version" to snapshot.version,
                        "updated_at_ms" to snapshot.updatedAtMs,
                        "update_type" to snapshot.updateType,
                        "goal_preview" to snapshot.goal.take(WORKSPACE_GOAL_PREVIEW_CHARS),
                        "section_count" to snapshot.sectionCount,
                        "evidence_count" to snapshot.evidenceCount,
                        "workspace_confidence" to snapshot.workspaceConfidence,
                        "bytes_estimate" to snapshot.bytesEstimate
                    )
                }
            mapOf(
                "generated_at" to System.currentTimeMillis(),
                "count" to items.size,
                "items" to items
            )
        }
        return mapper.writeValueAsString(payload)
    }

    fun workspaceSnapshotJson(rootInputEnqueuedAtMs: Long, version: Long? = null): String? {
        val payload = synchronized(lock) {
            pruneWorkspaceSnapshotsLocked()
            val record = if (version == null) {
                latestWorkspaceSnapshotByRoot[rootInputEnqueuedAtMs]
            } else {
                workspaceSnapshots.lastOrNull {
                    it.rootInputEnqueuedAtMs == rootInputEnqueuedAtMs && it.version == version
                }
            } ?: return null
            val versions = workspaceSnapshots
                .asSequence()
                .filter { it.rootInputEnqueuedAtMs == rootInputEnqueuedAtMs }
                .sortedByDescending { it.version }
                .take(WORKSPACE_VERSION_LIST_LIMIT)
                .map {
                    mapOf(
                        "version" to it.version,
                        "updated_at_ms" to it.updatedAtMs,
                        "update_type" to it.updateType
                    )
                }
                .toList()
            mapOf(
                "root_input_enqueued_at_ms" to record.rootInputEnqueuedAtMs,
                "version" to record.version,
                "updated_at_ms" to record.updatedAtMs,
                "update_type" to record.updateType,
                "goal" to record.goal,
                "section_count" to record.sectionCount,
                "evidence_count" to record.evidenceCount,
                "workspace_confidence" to record.workspaceConfidence,
                "bytes_estimate" to record.bytesEstimate,
                "sections" to record.sections,
                "evidence" to record.evidence,
                "versions" to versions
            )
        }
        return mapper.writeValueAsString(payload)
    }

    fun recordDroppedEvents(totalDroppedEvents: Long) {
        if (totalDroppedEvents < 0) {
            return
        }
        val payloadJson: String
        synchronized(lock) {
            droppedEvents = max(droppedEvents, totalDroppedEvents)
            val nextId = (events.maxOfOrNull { it.id } ?: 0L) + 1L
            val healthEvent = AgentEvent(
                id = nextId,
                type = "instrumentation_health",
                data = instrumentationHealthMap()
            )
            if (events.size >= max(50, maxEvents)) {
                events.removeFirst()
            }
            events.addLast(healthEvent)
            payloadJson = mapper.writeValueAsString(healthEvent)
            broadcastToSubscribers(payloadJson)
        }
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

    private fun instrumentationHealthMap(): Map<String, Any?> =
        buildMap {
            put("dropped_events", droppedEvents)
            put("queue_saturation_events", queueSaturationEvents)
            put("queue_saturation_by_type", queueSaturationByType.toMap())
        }

    private fun broadcastToSubscribers(payloadJson: String) {
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

    private fun captureWorkspaceSnapshot(data: Map<String, Any?>) {
        val rootInputEnqueuedAtMs = data["root_input_enqueued_at_ms"].asLong() ?: return
        val version = data["version"].asLong() ?: return
        val updatedAtMs = data["updated_at_ms"].asLong() ?: System.currentTimeMillis()
        val record = WorkspaceSnapshotRecord(
            rootInputEnqueuedAtMs = rootInputEnqueuedAtMs,
            version = version,
            updatedAtMs = updatedAtMs,
            updateType = data["update_type"]?.toString().orEmpty(),
            goal = data["goal"]?.toString().orEmpty(),
            sectionCount = data["section_count"].asInt(),
            evidenceCount = data["evidence_count"].asInt(),
            workspaceConfidence = data["workspace_confidence"].asDouble(),
            bytesEstimate = data["bytes_estimate"].asInt(),
            sections = (data["sections"] as? List<*>)
                ?.mapNotNull { item ->
                    val section = item as? Map<*, *> ?: return@mapNotNull null
                    section.entries.associate { (k, v) -> k.toString() to v }
                }
                .orEmpty(),
            evidence = (data["evidence"] as? List<*>)
                ?.mapNotNull { item -> item?.toString() }
                .orEmpty()
        )
        val sameVersionIndex = workspaceSnapshots.indexOfFirst {
            it.rootInputEnqueuedAtMs == rootInputEnqueuedAtMs && it.version == version
        }
        if (sameVersionIndex >= 0) {
            workspaceSnapshots.removeAt(sameVersionIndex)
        }
        workspaceSnapshots.addLast(record)
        pruneWorkspaceSnapshotsLocked()
    }

    private fun captureWorkspaceHead(data: Map<String, Any?>) {
        val rootInputEnqueuedAtMs = data["root_input_enqueued_at_ms"].asLong() ?: return
        val version = data["version"].asLong() ?: return
        val updatedAtMs = data["updated_at_ms"].asLong() ?: System.currentTimeMillis()
        val sameVersionIndex = workspaceSnapshots.indexOfFirst {
            it.rootInputEnqueuedAtMs == rootInputEnqueuedAtMs && it.version == version
        }
        if (sameVersionIndex >= 0) {
            return
        }
        workspaceSnapshots.addLast(
            WorkspaceSnapshotRecord(
                rootInputEnqueuedAtMs = rootInputEnqueuedAtMs,
                version = version,
                updatedAtMs = updatedAtMs,
                updateType = data["update_type"]?.toString().orEmpty(),
                goal = data["goal_preview"]?.toString().orEmpty(),
                sectionCount = data["section_count"].asInt(),
                evidenceCount = data["evidence_count"].asInt(),
                workspaceConfidence = data["workspace_confidence"].asDouble(),
                bytesEstimate = data["bytes_estimate"].asInt(),
                sections = emptyList(),
                evidence = emptyList()
            )
        )
        pruneWorkspaceSnapshotsLocked()
    }

    private fun pruneWorkspaceSnapshotsLocked() {
        val ttlMs = workspaceSnapshotTtlMs.coerceAtLeast(0L)
        val now = System.currentTimeMillis()
        if (ttlMs > 0L) {
            while (workspaceSnapshots.isNotEmpty() && (now - workspaceSnapshots.first().updatedAtMs) > ttlMs) {
                workspaceSnapshots.removeFirst()
            }
        }
        while (workspaceSnapshots.size > max(10, maxWorkspaceSnapshots)) {
            workspaceSnapshots.removeFirst()
        }
        latestWorkspaceSnapshotByRoot.clear()
        workspaceSnapshots.forEach { record ->
            val current = latestWorkspaceSnapshotByRoot[record.rootInputEnqueuedAtMs]
            if (
                current == null ||
                record.version > current.version ||
                (record.version == current.version && record.updatedAtMs >= current.updatedAtMs)
            ) {
                latestWorkspaceSnapshotByRoot[record.rootInputEnqueuedAtMs] = record
            }
        }
    }

    private fun Any?.asLong(): Long? =
        when (this) {
            is Number -> this.toLong()
            is String -> this.toLongOrNull()
            else -> null
        }

    private fun Any?.asInt(): Int =
        when (this) {
            is Number -> this.toInt()
            is String -> this.toIntOrNull() ?: 0
            else -> 0
        }

    private fun Any?.asDouble(): Double =
        when (this) {
            is Number -> this.toDouble()
            is String -> this.toDoubleOrNull() ?: 0.0
            else -> 0.0
        }

    private data class WorkspaceSnapshotRecord(
        val rootInputEnqueuedAtMs: Long,
        val version: Long,
        val updatedAtMs: Long,
        val updateType: String,
        val goal: String,
        val sectionCount: Int,
        val evidenceCount: Int,
        val workspaceConfidence: Double,
        val bytesEstimate: Int,
        val sections: List<Map<String, Any?>>,
        val evidence: List<String>,
    )

    private companion object {
        const val WORKSPACE_GOAL_PREVIEW_CHARS: Int = 140
        const val WORKSPACE_VERSION_LIST_LIMIT: Int = 25
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
    val actionCapabilities: List<Map<String, Any?>> = emptyList(),
    val limits: Map<String, Any?>,
    val metrics: MetricsSnapshot?,
    val instrumentationHealth: Map<String, Any?> = emptyMap(),
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
