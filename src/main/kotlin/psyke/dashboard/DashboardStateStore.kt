package psyke.dashboard

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import psyke.agent.core.ActionType
import psyke.agent.core.PendingAction
import psyke.agent.core.PendingInput
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
    private val chatSessions = linkedMapOf<String, ChatSessionRecord>()
    private val rootInputSessionMap = mutableMapOf<Long, String>()
    private val chatSubscribers = mutableMapOf<String, MutableSet<LinkedBlockingDeque<String>>>()
    private var nextChatMessageId: Long = 1L

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

                "input_queued" -> {
                    val input = event.data["input"] as? PendingInput
                    if (input != null) {
                        val sessionId = resolveSessionIdFromInputSource(input.source)
                        if (sessionId != null) {
                            ensureChatSessionLocked(sessionId = sessionId, title = if (sessionId == DEFAULT_SESSION_ID) "Default" else null)
                            rootInputSessionMap[input.enqueuedAtMs] = sessionId
                            if (input.source.equals("stdin", ignoreCase = true)) {
                                addChatMessageLocked(
                                    sessionId = sessionId,
                                    role = "user",
                                    content = input.content,
                                    source = "stdin",
                                    emitEvent = true
                                )
                            }
                        }
                    }
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
                    val action = event.data["action"] as? PendingAction
                    if (
                        action != null &&
                        action.type == ActionType.ANSWER &&
                        action.rootInputEnqueuedAtMs != null
                    ) {
                        val rootInputEnqueuedAtMs = action.rootInputEnqueuedAtMs
                        val sessionId = rootInputSessionMap[rootInputEnqueuedAtMs]
                        if (sessionId != null) {
                            addChatMessageLocked(
                                sessionId = sessionId,
                                role = "assistant",
                                content = action.payload,
                                source = "agent",
                                emitEvent = true
                            )
                        }
                    }
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

                "task_workspace_destroyed" -> {
                    val rootId = event.data["root_input_enqueued_at_ms"].asLong()
                    if (rootId != null) {
                        rootInputSessionMap.remove(rootId)
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

    fun ensureChatSession(sessionId: String = DEFAULT_SESSION_ID, title: String? = null): ChatSessionSummary {
        synchronized(lock) {
            return toSummary(ensureChatSessionLocked(sessionId = sanitizeSessionId(sessionId), title = title))
        }
    }

    fun createChatSession(title: String? = null): ChatSessionSummary {
        synchronized(lock) {
            val sessionId = generateSessionIdLocked()
            return toSummary(ensureChatSessionLocked(sessionId = sessionId, title = title))
        }
    }

    fun hasChatSession(sessionId: String): Boolean =
        synchronized(lock) {
            chatSessions.containsKey(sanitizeSessionId(sessionId))
        }

    fun chatSessionsJson(): String {
        val payload = synchronized(lock) {
            val sessions = chatSessions.values
                .sortedByDescending { it.updatedAtMs }
                .map { toSessionPayload(it, includeMessages = false) }
            mapOf(
                "generated_at" to System.currentTimeMillis(),
                "count" to sessions.size,
                "sessions" to sessions
            )
        }
        return mapper.writeValueAsString(payload)
    }

    fun chatSessionJson(sessionId: String): String? {
        val payload = synchronized(lock) {
            val session = chatSessions[sanitizeSessionId(sessionId)] ?: return null
            toSessionPayload(session, includeMessages = true)
        }
        return mapper.writeValueAsString(payload)
    }

    fun addUserMessage(sessionId: String, content: String, source: String = "web"): ChatMessage? {
        val sanitizedSessionId = sanitizeSessionId(sessionId)
        val sanitizedContent = content.trim()
        if (sanitizedContent.isBlank()) {
            return null
        }
        return synchronized(lock) {
            if (!chatSessions.containsKey(sanitizedSessionId)) {
                return null
            }
            addChatMessageLocked(
                sessionId = sanitizedSessionId,
                role = "user",
                content = sanitizedContent,
                source = source,
                emitEvent = true
            )
        }
    }

    fun subscribeChat(sessionId: String): DashboardSubscription? {
        val sanitizedSessionId = sanitizeSessionId(sessionId)
        val queue = LinkedBlockingDeque<String>(1_000)
        synchronized(lock) {
            if (!chatSessions.containsKey(sanitizedSessionId)) {
                return null
            }
            val sessionSubscribers = chatSubscribers.getOrPut(sanitizedSessionId) { linkedSetOf() }
            sessionSubscribers.add(queue)
        }
        return DashboardSubscription(queue) {
            synchronized(lock) {
                chatSubscribers[sanitizedSessionId]?.remove(queue)
                if (chatSubscribers[sanitizedSessionId].isNullOrEmpty()) {
                    chatSubscribers.remove(sanitizedSessionId)
                }
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            subscribers.clear()
            chatSubscribers.clear()
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

    private fun ensureChatSessionLocked(sessionId: String, title: String?): ChatSessionRecord {
        val existing = chatSessions[sessionId]
        if (existing != null) {
            val normalizedTitle = normalizeTitle(title) ?: existing.title
            val updated = existing.copy(title = normalizedTitle)
            chatSessions[sessionId] = updated
            return updated
        }
        val now = System.currentTimeMillis()
        val created = ChatSessionRecord(
            sessionId = sessionId,
            title = normalizeTitle(title) ?: defaultTitleForSession(sessionId),
            createdAtMs = now,
            updatedAtMs = now,
            messages = ArrayDeque()
        )
        chatSessions[sessionId] = created
        return created
    }

    private fun addChatMessageLocked(
        sessionId: String,
        role: String,
        content: String,
        source: String,
        emitEvent: Boolean,
    ): ChatMessage {
        val session = ensureChatSessionLocked(sessionId, title = null)
        val now = System.currentTimeMillis()
        val message = ChatMessage(
            id = nextChatMessageId++,
            sessionId = sessionId,
            role = role,
            content = content,
            source = source,
            createdAtMs = now
        )
        if (session.messages.size >= MAX_CHAT_MESSAGES_PER_SESSION) {
            session.messages.removeFirst()
        }
        session.messages.addLast(message)
        chatSessions[sessionId] = session.copy(updatedAtMs = now)
        if (emitEvent) {
            broadcastChatEvent(
                sessionId = sessionId,
                payload = mapOf(
                    "type" to "message",
                    "session_id" to sessionId,
                    "message" to mapOf(
                        "id" to message.id,
                        "role" to message.role,
                        "content" to message.content,
                        "source" to message.source,
                        "created_at_ms" to message.createdAtMs
                    )
                )
            )
        }
        return message
    }

    private fun toSessionPayload(session: ChatSessionRecord, includeMessages: Boolean): Map<String, Any?> =
        mapOf(
            "session_id" to session.sessionId,
            "title" to session.title,
            "created_at_ms" to session.createdAtMs,
            "updated_at_ms" to session.updatedAtMs,
            "message_count" to session.messages.size,
            "last_message_preview" to session.messages.lastOrNull()?.content?.take(120),
            "messages" to if (includeMessages) {
                session.messages.map { message ->
                    mapOf(
                        "id" to message.id,
                        "role" to message.role,
                        "content" to message.content,
                        "source" to message.source,
                        "created_at_ms" to message.createdAtMs
                    )
                }
            } else {
                emptyList<Map<String, Any?>>()
            }
        )

    private fun toSummary(session: ChatSessionRecord): ChatSessionSummary =
        ChatSessionSummary(
            sessionId = session.sessionId,
            title = session.title,
            createdAtMs = session.createdAtMs,
            updatedAtMs = session.updatedAtMs,
            messageCount = session.messages.size
        )

    private fun broadcastChatEvent(sessionId: String, payload: Map<String, Any?>) {
        val payloadJson = mapper.writeValueAsString(payload)
        val sessionSubscribers = chatSubscribers[sessionId] ?: return
        val staleSubscribers = mutableListOf<LinkedBlockingDeque<String>>()
        sessionSubscribers.forEach { queue ->
            if (!queue.offerLast(payloadJson)) {
                queue.pollFirst()
                if (!queue.offerLast(payloadJson)) {
                    staleSubscribers.add(queue)
                }
            }
        }
        sessionSubscribers.removeAll(staleSubscribers.toSet())
        if (sessionSubscribers.isEmpty()) {
            chatSubscribers.remove(sessionId)
        }
    }

    private fun resolveSessionIdFromInputSource(source: String): String? {
        val normalized = source.trim()
        if (normalized.isBlank()) return null
        if (normalized.equals("stdin", ignoreCase = true)) {
            return DEFAULT_SESSION_ID
        }
        if (!normalized.startsWith(CHAT_SOURCE_PREFIX)) {
            return null
        }
        val rawSessionId = normalized.removePrefix(CHAT_SOURCE_PREFIX).trim()
        return sanitizeSessionId(rawSessionId)
    }

    private fun sanitizeSessionId(sessionId: String): String {
        val normalized = sessionId.trim().ifBlank { DEFAULT_SESSION_ID }
        return normalized
            .lowercase()
            .replace(Regex("[^a-z0-9_-]"), "-")
            .replace(Regex("-{2,}"), "-")
            .trim('-')
            .ifBlank { DEFAULT_SESSION_ID }
            .take(MAX_SESSION_ID_CHARS)
    }

    private fun normalizeTitle(title: String?): String? =
        title
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.take(MAX_SESSION_TITLE_CHARS)

    private fun defaultTitleForSession(sessionId: String): String =
        if (sessionId == DEFAULT_SESSION_ID) "Default" else "Conversation $sessionId"

    private fun generateSessionIdLocked(): String {
        var attempts = 0
        while (attempts < MAX_SESSION_ID_GENERATION_ATTEMPTS) {
            attempts += 1
            val candidate = "s-${System.currentTimeMillis().toString(36)}-${(nextChatMessageId + attempts).toString(36)}"
            val sanitized = sanitizeSessionId(candidate)
            if (!chatSessions.containsKey(sanitized)) {
                return sanitized
            }
        }
        return "s-${System.nanoTime().toString(36)}"
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
        const val DEFAULT_SESSION_ID: String = "default"
        const val CHAT_SOURCE_PREFIX: String = "chat:"
        const val MAX_CHAT_MESSAGES_PER_SESSION: Int = 400
        const val MAX_SESSION_ID_CHARS: Int = 64
        const val MAX_SESSION_TITLE_CHARS: Int = 80
        const val MAX_SESSION_ID_GENERATION_ATTEMPTS: Int = 4
    }
}

data class ChatSessionSummary(
    val sessionId: String,
    val title: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val messageCount: Int,
)

data class ChatMessage(
    val id: Long,
    val sessionId: String,
    val role: String,
    val content: String,
    val source: String,
    val createdAtMs: Long,
)

private data class ChatSessionRecord(
    val sessionId: String,
    val title: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val messages: ArrayDeque<ChatMessage>,
)

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
