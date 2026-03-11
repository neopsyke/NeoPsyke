package psyke.dashboard

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import psyke.agent.core.ActionType
import psyke.agent.core.ConversationContext
import psyke.agent.core.Interlocutor
import psyke.agent.core.PendingAction
import psyke.agent.core.PendingInput
import psyke.agent.core.QueueState
import psyke.instrumentation.AgentEvent
import psyke.instrumentation.InstrumentationSink
import psyke.metrics.MetricsSnapshot
import java.io.Closeable
import kotlin.math.max

class DashboardStateStore(
    private val maxEvents: Int = 2000,
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
    private var taskVerifierTotal: Long = 0
    private var taskVerifierAllow: Long = 0
    private var taskVerifierDeny: Long = 0
    private var taskVerifierRequiresEvidence: Long = 0
    private var taskVerifierGracefulAllow: Long = 0
    private val taskVerifierByReasonCode = mutableMapOf<String, Long>()
    private val taskVerifierByIntent = mutableMapOf<String, Long>()
    private val taskVerifierByVolatility = mutableMapOf<String, Long>()
    private var promptBudgetAllocationsTotal: Long = 0
    private var promptBudgetFallbackCount: Long = 0
    private var promptBudgetFloorViolationEvents: Long = 0
    private var promptBudgetDroppedSectionsTotal: Long = 0
    private val promptBudgetByCallSite = mutableMapOf<String, Long>()
    private val promptBudgetByDegradationPath = mutableMapOf<String, Long>()
    private val workspaceSnapshots = ArrayDeque<WorkspaceSnapshotRecord>()
    private val latestWorkspaceSnapshotByRoot = mutableMapOf<String, WorkspaceSnapshotRecord>()
    private val phaseTimings = ArrayDeque<Map<String, Any?>>()
    private var heapMetrics: Map<String, Any?>? = null
    private val subscribers = mutableSetOf<Channel<String>>()
    private val chatSessions = linkedMapOf<String, ChatSessionRecord>()
    private val rootInputSessionMap = mutableMapOf<String, String>()
    private val chatSubscribers = mutableMapOf<String, MutableSet<Channel<String>>>()
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
                        val sessionId = input.conversationContext.sessionId
                        val interlocutor = input.conversationContext.interlocutor
                        ensureChatSessionLocked(
                            sessionId = sessionId,
                            title = if (sessionId == DEFAULT_SESSION_ID) "Default" else null,
                            interlocutor = interlocutor
                        )
                        rootInputSessionMap[input.rootInputId] = sessionId
                        if (input.source.equals("stdin", ignoreCase = true)) {
                            addChatMessageLocked(
                                sessionId = sessionId,
                                role = "user",
                                content = input.content,
                                source = "stdin",
                                interlocutorName = interlocutor.displayName(),
                                emitEvent = true
                            )
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
                        action.rootInputId != null
                    ) {
                        val rootInputId = action.rootInputId ?: return
                        val sessionId = rootInputSessionMap[rootInputId]
                        if (sessionId != null) {
                            addChatMessageLocked(
                                sessionId = sessionId,
                                role = "assistant",
                                content = action.payload,
                                source = "agent",
                                emitEvent = true
                            )
                        }
                        rootInputSessionMap.remove(rootInputId)
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

                "task_verifier_review" -> {
                    taskVerifierTotal += 1
                    val allow = (event.data["allow"] as? Boolean) ?: true
                    if (allow) {
                        taskVerifierAllow += 1
                    } else {
                        taskVerifierDeny += 1
                    }
                    val requiresEvidence = (event.data["requires_external_evidence"] as? Boolean) ?: false
                    if (requiresEvidence) {
                        taskVerifierRequiresEvidence += 1
                    }
                    val reasonCode = event.data["reason_code"]?.toString()?.ifBlank { null }
                    if (reasonCode != null) {
                        taskVerifierByReasonCode[reasonCode] = (taskVerifierByReasonCode[reasonCode] ?: 0L) + 1L
                        if (allow && reasonCode == "TASK_EVIDENCE_UNAVAILABLE_GRACEFUL") {
                            taskVerifierGracefulAllow += 1
                        }
                    }
                    val intentCategory = event.data["intent_category"]?.toString()?.ifBlank { null }
                    if (intentCategory != null) {
                        taskVerifierByIntent[intentCategory] = (taskVerifierByIntent[intentCategory] ?: 0L) + 1L
                    }
                    val volatilityLevel = event.data["volatility_level"]?.toString()?.ifBlank { null }
                    if (volatilityLevel != null) {
                        taskVerifierByVolatility[volatilityLevel] = (taskVerifierByVolatility[volatilityLevel] ?: 0L) + 1L
                    }
                }

                "prompt_budget_allocation" -> {
                    promptBudgetAllocationsTotal += 1
                    val callSite = event.data["call_site"]?.toString()?.ifBlank { "unknown" } ?: "unknown"
                    promptBudgetByCallSite[callSite] = (promptBudgetByCallSite[callSite] ?: 0L) + 1L

                    val degradationPath = event.data["degradation_path"]?.toString()?.ifBlank { "none" } ?: "none"
                    promptBudgetByDegradationPath[degradationPath] =
                        (promptBudgetByDegradationPath[degradationPath] ?: 0L) + 1L

                    val fallback = (event.data["single_message_fallback"] as? Boolean) ?: false
                    if (fallback) {
                        promptBudgetFallbackCount += 1
                    }

                    val floorViolationCount = (event.data["floor_violation_count"] as? Number)?.toLong() ?: 0L
                    if (floorViolationCount > 0) {
                        promptBudgetFloorViolationEvents += 1
                    }

                    val droppedSectionCount = (event.data["dropped_section_count"] as? Number)?.toLong() ?: 0L
                    promptBudgetDroppedSectionsTotal += droppedSectionCount
                }

                "phase_timings" -> {
                    if (phaseTimings.size >= MAX_PHASE_TIMINGS) {
                        phaseTimings.removeFirst()
                    }
                    phaseTimings.addLast(event.data)
                }

                "heap_snapshot" -> {
                    heapMetrics = event.data
                }

                "task_workspace_destroyed" -> {
                    val rootId = event.data["root_input_id"].asString()
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
                taskVerifierStats = taskVerifierStatsMap(),
                promptBudgetStats = promptBudgetStatsMap(),
                recentEvents = events.toList().sortedBy { it.id },
                phaseTimings = phaseTimings.toList(),
                heapMetrics = heapMetrics,
                storeStats = mapOf(
                    "event_count" to events.size,
                    "max_events" to maxEvents,
                    "workspace_snapshot_count" to workspaceSnapshots.size,
                    "chat_session_count" to chatSessions.size,
                    "subscriber_count" to subscribers.size,
                ),
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
                        "root_input_id" to snapshot.rootInputId,
                        "root_input_received_at_ms" to snapshot.rootInputReceivedAtMs,
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

    fun workspaceSnapshotJson(rootInputId: String, version: Long? = null): String? {
        val payload = synchronized(lock) {
            pruneWorkspaceSnapshotsLocked()
            val record = if (version == null) {
                latestWorkspaceSnapshotByRoot[rootInputId]
            } else {
                workspaceSnapshots.lastOrNull {
                    it.rootInputId == rootInputId && it.version == version
                }
            } ?: return null
            val versions = workspaceSnapshots
                .asSequence()
                .filter { it.rootInputId == rootInputId }
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
                "root_input_id" to record.rootInputId,
                "root_input_received_at_ms" to record.rootInputReceivedAtMs,
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

    fun subscribe(): DashboardFlowSubscription {
        val channel = Channel<String>(SUBSCRIBER_CHANNEL_CAPACITY)
        synchronized(lock) {
            subscribers.add(channel)
        }
        return DashboardFlowSubscription(channel) {
            synchronized(lock) {
                subscribers.remove(channel)
            }
            channel.close()
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

    fun resolveSessionForRootInput(rootInputId: String): String? =
        synchronized(lock) { rootInputSessionMap[rootInputId] }

    fun subscribeChat(sessionId: String): DashboardFlowSubscription? {
        val sanitizedSessionId = sanitizeSessionId(sessionId)
        val channel = Channel<String>(SUBSCRIBER_CHANNEL_CAPACITY)
        synchronized(lock) {
            if (!chatSessions.containsKey(sanitizedSessionId)) {
                return null
            }
            val sessionSubscribers = chatSubscribers.getOrPut(sanitizedSessionId) { linkedSetOf() }
            sessionSubscribers.add(channel)
        }
        return DashboardFlowSubscription(channel) {
            synchronized(lock) {
                chatSubscribers[sanitizedSessionId]?.remove(channel)
                if (chatSubscribers[sanitizedSessionId].isNullOrEmpty()) {
                    chatSubscribers.remove(sanitizedSessionId)
                }
            }
            channel.close()
        }
    }

    override fun close() {
        synchronized(lock) {
            subscribers.forEach { it.close() }
            subscribers.clear()
            chatSubscribers.values.forEach { set -> set.forEach { it.close() } }
            chatSubscribers.clear()
        }
    }

    private fun instrumentationHealthMap(): Map<String, Any?> =
        buildMap {
            put("dropped_events", droppedEvents)
            put("queue_saturation_events", queueSaturationEvents)
            put("queue_saturation_by_type", queueSaturationByType.toMap())
        }

    private fun taskVerifierStatsMap(): Map<String, Any?> {
        val denyRate = if (taskVerifierTotal > 0) {
            taskVerifierDeny.toDouble() / taskVerifierTotal.toDouble()
        } else {
            0.0
        }
        val gracefulAllowRate = if (taskVerifierTotal > 0) {
            taskVerifierGracefulAllow.toDouble() / taskVerifierTotal.toDouble()
        } else {
            0.0
        }
        return buildMap {
            put("total_reviews", taskVerifierTotal)
            put("allow_count", taskVerifierAllow)
            put("deny_count", taskVerifierDeny)
            put("deny_rate", denyRate)
            put("requires_evidence_count", taskVerifierRequiresEvidence)
            put("graceful_allow_count", taskVerifierGracefulAllow)
            put("graceful_allow_rate", gracefulAllowRate)
            put("by_reason_code", taskVerifierByReasonCode.toMap())
            put("by_intent_category", taskVerifierByIntent.toMap())
            put("by_volatility_level", taskVerifierByVolatility.toMap())
        }
    }

    private fun promptBudgetStatsMap(): Map<String, Any?> {
        val fallbackRate = if (promptBudgetAllocationsTotal > 0) {
            promptBudgetFallbackCount.toDouble() / promptBudgetAllocationsTotal.toDouble()
        } else {
            0.0
        }
        return buildMap {
            put("total_allocations", promptBudgetAllocationsTotal)
            put("single_message_fallback_count", promptBudgetFallbackCount)
            put("single_message_fallback_rate", fallbackRate)
            put("floor_violation_events", promptBudgetFloorViolationEvents)
            put("dropped_sections_total", promptBudgetDroppedSectionsTotal)
            put("by_call_site", promptBudgetByCallSite.toMap())
            put("by_degradation_path", promptBudgetByDegradationPath.toMap())
        }
    }

    private fun broadcastToSubscribers(payloadJson: String) {
        val staleSubscribers = mutableListOf<Channel<String>>()
        subscribers.forEach { channel ->
            val result = channel.trySend(payloadJson)
            if (result.isFailure && result.isClosed) {
                staleSubscribers.add(channel)
            } else if (result.isFailure) {
                // Buffer full — drop oldest and retry
                channel.tryReceive()
                channel.trySend(payloadJson)
            }
        }
        subscribers.removeAll(staleSubscribers.toSet())
    }

    private fun ensureChatSessionLocked(
        sessionId: String,
        title: String?,
        interlocutor: Interlocutor? = null,
    ): ChatSessionRecord {
        val existing = chatSessions[sessionId]
        if (existing != null) {
            val normalizedTitle = normalizeTitle(title) ?: existing.title
            val updatedInterlocutor = interlocutor ?: existing.interlocutor
            val updated = existing.copy(title = normalizedTitle, interlocutor = updatedInterlocutor)
            chatSessions[sessionId] = updated
            return updated
        }
        val now = System.currentTimeMillis()
        val created = ChatSessionRecord(
            sessionId = sessionId,
            title = normalizeTitle(title) ?: defaultTitleForSession(sessionId),
            createdAtMs = now,
            updatedAtMs = now,
            messages = ArrayDeque(),
            interlocutor = interlocutor
        )
        chatSessions[sessionId] = created
        return created
    }

    private fun addChatMessageLocked(
        sessionId: String,
        role: String,
        content: String,
        source: String,
        interlocutorName: String? = null,
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
            createdAtMs = now,
            interlocutor = interlocutorName
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
                        "created_at_ms" to message.createdAtMs,
                        "interlocutor" to message.interlocutor
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
            "interlocutor" to session.interlocutor?.let {
                mapOf("id" to it.id, "label" to it.label, "display_name" to it.displayName())
            },
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
                        "created_at_ms" to message.createdAtMs,
                        "interlocutor" to message.interlocutor
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
        val staleSubscribers = mutableListOf<Channel<String>>()
        sessionSubscribers.forEach { channel ->
            val result = channel.trySend(payloadJson)
            if (result.isFailure && result.isClosed) {
                staleSubscribers.add(channel)
            } else if (result.isFailure) {
                channel.tryReceive()
                channel.trySend(payloadJson)
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
        val rootInputId = data["root_input_id"].asString() ?: return
        val rootInputReceivedAtMs = data["root_input_received_at_ms"].asLong() ?: 0L
        val version = data["version"].asLong() ?: return
        val updatedAtMs = data["updated_at_ms"].asLong() ?: System.currentTimeMillis()
        val record = WorkspaceSnapshotRecord(
            rootInputId = rootInputId,
            rootInputReceivedAtMs = rootInputReceivedAtMs,
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
            it.rootInputId == rootInputId && it.version == version
        }
        if (sameVersionIndex >= 0) {
            workspaceSnapshots.removeAt(sameVersionIndex)
        }
        workspaceSnapshots.addLast(record)
        pruneWorkspaceSnapshotsLocked()
    }

    private fun captureWorkspaceHead(data: Map<String, Any?>) {
        val rootInputId = data["root_input_id"].asString() ?: return
        val rootInputReceivedAtMs = data["root_input_received_at_ms"].asLong() ?: 0L
        val version = data["version"].asLong() ?: return
        val updatedAtMs = data["updated_at_ms"].asLong() ?: System.currentTimeMillis()
        val sameVersionIndex = workspaceSnapshots.indexOfFirst {
            it.rootInputId == rootInputId && it.version == version
        }
        if (sameVersionIndex >= 0) {
            return
        }
        workspaceSnapshots.addLast(
            WorkspaceSnapshotRecord(
                rootInputId = rootInputId,
                rootInputReceivedAtMs = rootInputReceivedAtMs,
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
            val current = latestWorkspaceSnapshotByRoot[record.rootInputId]
            if (
                current == null ||
                record.version > current.version ||
                (record.version == current.version && record.updatedAtMs >= current.updatedAtMs)
            ) {
                latestWorkspaceSnapshotByRoot[record.rootInputId] = record
            }
        }
    }

    private fun Any?.asLong(): Long? =
        when (this) {
            is Number -> this.toLong()
            is String -> this.toLongOrNull()
            else -> null
        }

    private fun Any?.asString(): String? =
        this?.toString()?.trim()?.takeIf { it.isNotBlank() }

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
        val rootInputId: String,
        val rootInputReceivedAtMs: Long,
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
        const val SUBSCRIBER_CHANNEL_CAPACITY: Int = 1_000
        const val MAX_PHASE_TIMINGS: Int = 200
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
    val interlocutor: String? = null,
)

private data class ChatSessionRecord(
    val sessionId: String,
    val title: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val messages: ArrayDeque<ChatMessage>,
    val interlocutor: Interlocutor? = null,
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
    val taskVerifierStats: Map<String, Any?> = emptyMap(),
    val promptBudgetStats: Map<String, Any?> = emptyMap(),
    val recentEvents: List<AgentEvent>,
    val phaseTimings: List<Map<String, Any?>> = emptyList(),
    val heapMetrics: Map<String, Any?>? = null,
    val storeStats: Map<String, Any?> = emptyMap(),
)

class DashboardFlowSubscription(
    private val channel: ReceiveChannel<String>,
    private val onClose: () -> Unit,
) : Closeable {
    fun asFlow(): Flow<String> = channel.receiveAsFlow()

    suspend fun receive(): String = channel.receive()

    suspend fun receiveCatching() = channel.receiveCatching()

    override fun close() {
        onClose()
    }
}
