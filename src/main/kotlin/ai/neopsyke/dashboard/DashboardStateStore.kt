package ai.neopsyke.dashboard

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.CognitiveThreadSnapshot
import ai.neopsyke.agent.model.CognitiveThreadStatus
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.Interlocutor
import ai.neopsyke.agent.model.Intention
import ai.neopsyke.agent.model.IntentionKind
import ai.neopsyke.agent.model.Opportunity
import ai.neopsyke.agent.model.OpportunityKind
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.model.PendingInput
import ai.neopsyke.agent.model.QueueState
import ai.neopsyke.instrumentation.AgentEvent
import ai.neopsyke.instrumentation.InstrumentationSink
import ai.neopsyke.metrics.MetricsSnapshot
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

class DashboardStateStore(
    private val maxEvents: Int = 2000,
    private val maxScratchpadSnapshots: Int = 120,
    private val scratchpadSnapshotTtlMs: Long = 15 * 60 * 1000L,
) : InstrumentationSink {
    private sealed interface TransportMessage {
        data class InstrumentationEvent(val event: AgentEvent) : TransportMessage
        data class ChatEvent(val sessionId: String, val payload: Map<String, Any?>) : TransportMessage
        data class ActionControlEvent(val payload: Map<String, Any?>) : TransportMessage
    }

    private data class EventSubscriber(
        val channel: Channel<String>,
        val eventFilter: ((AgentEvent) -> Boolean)? = null,
    ) {
        fun accepts(event: AgentEvent): Boolean = eventFilter?.invoke(event) ?: true
    }

    private val mapper = jacksonObjectMapper().findAndRegisterModules()
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
    private var groundingGateTotal: Long = 0
    private var groundingGateAllow: Long = 0
    private var groundingGateDeny: Long = 0
    private var groundingGateGroundingRequired: Long = 0
    private var groundingGateEvidenceGathered: Long = 0
    private var groundingGateEvidenceFailedTechnically: Long = 0
    private var groundingGateEvidenceUnavailable: Long = 0
    private val groundingGateByReasonCode = mutableMapOf<String, Long>()
    private var promptBudgetAllocationsTotal: Long = 0
    private var promptBudgetFallbackCount: Long = 0
    private var promptBudgetFloorViolationEvents: Long = 0
    private var promptBudgetDroppedSectionsTotal: Long = 0
    private val promptBudgetByCallSite = mutableMapOf<String, Long>()
    private val promptBudgetByDegradationPath = mutableMapOf<String, Long>()
    private val scratchpadSnapshots = ArrayDeque<ScratchpadSnapshotRecord>()
    private val latestScratchpadSnapshotByRoot = mutableMapOf<String, ScratchpadSnapshotRecord>()
    private val liveThreadsById = linkedMapOf<String, CognitiveThreadSnapshot>()
    private val terminalThreadsById = linkedMapOf<String, CognitiveThreadSnapshot>()
    private val threadIdByRootInput = mutableMapOf<String, String>()
    private val phaseTimings = ArrayDeque<Map<String, Any?>>()
    private var heapMetrics: Map<String, Any?>? = null
    private val subscribers = mutableSetOf<EventSubscriber>()
    private val chatSessions = linkedMapOf<String, ChatSessionRecord>()
    private val rootInputSessionMap = mutableMapOf<String, String>()
    private val chatSubscribers = mutableMapOf<String, MutableSet<Channel<String>>>()
    private val actionControlSubscribers = mutableSetOf<Channel<String>>()
    private var nextChatMessageId: Long = 1L
    private val runEpochSec: Long = System.currentTimeMillis() / 1000
    private val sessionSequenceCounters = mutableMapOf<String, AtomicLong>()
    private val plannerStructuredOutputModes = mutableMapOf<PlannerStructuredOutputScope, String>()
    private val transportScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("dashboard-transport"))
    private val transportChannel = Channel<TransportMessage>(capacity = TRANSPORT_CHANNEL_CAPACITY)
    private val transportJob: Job = transportScope.launch {
        transportLoop()
    }

    override fun onEvent(event: AgentEvent) {
        var eventForBroadcast: AgentEvent? = null
        var actionControlPayload: Map<String, Any?>? = null
        synchronized(lock) {
            val effectiveEvent = enrichPlannerStructuredOutputModeLocked(event)
            val isDebugScratchpadSnapshot = event.type == "scratchpad_debug_snapshot"
            if (isDebugScratchpadSnapshot) {
                captureScratchpadSnapshot(event.data)
            } else {
                if (event.type == "scratchpad_head") {
                    captureScratchpadHead(event.data)
                }
                if (events.size >= max(50, maxEvents)) {
                    events.removeFirst()
                }
                events.addLast(effectiveEvent)
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

                "impulse_processing" -> {
                    currentProcessing = mapOf(
                        "kind" to "impulse",
                        "item" to (event.data["need_id"] ?: "")
                    )
                    // Route Id-originated answers to the default session so the user sees them.
                    val rootImpulseId = event.data["root_impulse_id"]?.toString()
                    if (rootImpulseId != null) {
                        ensureChatSessionLocked(sessionId = DEFAULT_SESSION_ID, title = "Default")
                        rootInputSessionMap[rootImpulseId] = DEFAULT_SESSION_ID
                    }
                }

                "continuation_processing" -> {
                    currentProcessing = mapOf(
                        "kind" to "continuation",
                        "item" to (event.data["continuation"] ?: "")
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
                        action.type == ActionType.CONTACT_USER &&
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

                "grounding_gate_review" -> {
                    groundingGateTotal += 1
                    val allow = (event.data["allow"] as? Boolean) ?: true
                    if (allow) {
                        groundingGateAllow += 1
                    } else {
                        groundingGateDeny += 1
                    }
                    if ((event.data["grounding_required"] as? Boolean) == true) {
                        groundingGateGroundingRequired += 1
                    }
                    if ((event.data["evidence_gathered"] as? Boolean) == true) {
                        groundingGateEvidenceGathered += 1
                    }
                    if ((event.data["evidence_failed_technically"] as? Boolean) == true) {
                        groundingGateEvidenceFailedTechnically += 1
                    }
                    if ((event.data["evidence_unavailable"] as? Boolean) == true) {
                        groundingGateEvidenceUnavailable += 1
                    }
                    val reasonCode = event.data["reason_code"]?.toString()?.ifBlank { null }
                    if (reasonCode != null) {
                        groundingGateByReasonCode[reasonCode] = (groundingGateByReasonCode[reasonCode] ?: 0L) + 1L
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

                "cognitive_thread_updated" -> {
                    mergeThreadSnapshotLocked(event.data)
                }

                "opportunity_enqueued" -> {
                    mergeOpportunityLocked(event.data)
                }

                "intention_processing" -> {
                    mergeIntentionLocked(event.data)
                }

                "intention_transition" -> {
                    mergeIntentionTransitionLocked(event.data)
                }

                "scratchpad_destroyed" -> {
                    val rootId = event.data["root_input_id"].asString()
                    if (rootId != null) {
                        rootInputSessionMap.remove(rootId)
                    }
                }
            }

            if (!isDebugScratchpadSnapshot) {
                if (subscribers.any { it.accepts(effectiveEvent) }) {
                    eventForBroadcast = effectiveEvent
                }
                if (
                    effectiveEvent.type in ACTION_CONTROL_STREAM_EVENT_TYPES &&
                    actionControlSubscribers.isNotEmpty()
                ) {
                    actionControlPayload = mapOf(
                        "type" to effectiveEvent.type,
                        "ts" to effectiveEvent.ts,
                        "data" to effectiveEvent.data,
                    )
                }
            }
        }
        if (eventForBroadcast != null) {
            enqueueTransport(TransportMessage.InstrumentationEvent(eventForBroadcast!!))
        }
        if (actionControlPayload != null) {
            enqueueTransport(TransportMessage.ActionControlEvent(actionControlPayload!!))
        }
    }

    private fun enrichPlannerStructuredOutputModeLocked(event: AgentEvent): AgentEvent {
        recordPlannerStructuredOutputModeLocked(event)
        if (event.type != "planner_decision") {
            return event
        }
        val scope = plannerScopeForPlannerDecision(event.data) ?: return event
        val mode = plannerStructuredOutputModes.remove(scope) ?: return event
        return event.copy(data = event.data + ("structured_output_mode" to mode))
    }

    private fun recordPlannerStructuredOutputModeLocked(event: AgentEvent) {
        if (event.type != "llm_call") {
            return
        }
        val scope = plannerScopeForLlmCall(event.data) ?: return
        val mode = event.data["structured_output_mode"].asString() ?: return
        plannerStructuredOutputModes[scope] = mode
    }

    private fun plannerScopeForLlmCall(data: Map<String, Any?>): PlannerStructuredOutputScope? {
        val actor = data["actor"].asString()?.lowercase() ?: return null
        if (actor != "ego") return null
        val callSite = normalizePlannerCallSite(data["call_site"].asString()) ?: return null
        val sessionId = data["session_id"].asString() ?: return null
        val rootInputId = data["root_input_id"].asString()
        return PlannerStructuredOutputScope(sessionId = sessionId, rootInputId = rootInputId, callSite = callSite)
    }

    private fun plannerScopeForPlannerDecision(data: Map<String, Any?>): PlannerStructuredOutputScope? {
        val callSite = normalizePlannerCallSite(data["trigger"].asString()) ?: return null
        val sessionId = data["session_id"].asString() ?: return null
        val rootInputId = data["root_input_id"].asString()
        return PlannerStructuredOutputScope(sessionId = sessionId, rootInputId = rootInputId, callSite = callSite)
    }

    private fun normalizePlannerCallSite(raw: String?): String? {
        val callSite = raw?.trim()?.lowercase()?.ifEmpty { return null } ?: return null
        return when {
            callSite in PLANNER_CALL_SITES -> callSite
            callSite.endsWith("_json_retry") -> callSite.removeSuffix("_json_retry").takeIf { it in PLANNER_CALL_SITES }
            callSite.endsWith("_truncation_retry") -> callSite.removeSuffix("_truncation_retry").takeIf { it in PLANNER_CALL_SITES }
            else -> null
        }
    }

    fun snapshotJson(
        eventsLimit: Int = DEFAULT_SNAPSHOT_EVENTS_LIMIT,
        includeHeavyEvents: Boolean = false,
    ): String {
        val snapshot = synchronized(lock) {
            pruneScratchpadSnapshotsLocked()
            val boundedEventLimit = eventsLimit.coerceIn(0, maxEvents.coerceAtLeast(0))
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
                groundingGateStats = groundingGateStatsMap(),
                promptBudgetStats = promptBudgetStatsMap(),
                cognitiveThreads = threadSnapshotsLocked(includeTerminal = true, limit = DEFAULT_THREAD_SNAPSHOT_LIMIT),
                recentEvents = snapshotRecentEventsLocked(
                    eventsLimit = boundedEventLimit,
                    includeHeavyEvents = includeHeavyEvents
                ),
                phaseTimings = phaseTimings.toList(),
                heapMetrics = heapMetrics,
                storeStats = mapOf(
                    "event_count" to events.size,
                    "max_events" to maxEvents,
                    "scratchpad_snapshot_count" to scratchpadSnapshots.size,
                    "chat_session_count" to chatSessions.size,
                    "subscriber_count" to subscribers.size,
                ),
            )
        }
        return mapper.writeValueAsString(snapshot)
    }

    fun threadIndexJson(includeTerminal: Boolean = false, limit: Int = DEFAULT_THREAD_SNAPSHOT_LIMIT): String {
        val payload = synchronized(lock) {
            val items = threadSnapshotsLocked(includeTerminal = includeTerminal, limit = limit)
            mapOf(
                "generated_at" to System.currentTimeMillis(),
                "count" to items.size,
                "include_terminal" to includeTerminal,
                "items" to items
            )
        }
        return mapper.writeValueAsString(payload)
    }

    fun threadSnapshotJson(threadId: String): String? {
        val payload = synchronized(lock) {
            threadSnapshotLocked(threadId) ?: return null
        }
        return mapper.writeValueAsString(payload)
    }

    private fun snapshotRecentEventsLocked(eventsLimit: Int, includeHeavyEvents: Boolean): List<AgentEvent> {
        if (eventsLimit <= 0) {
            return emptyList()
        }
        val filtered = if (includeHeavyEvents) {
            events.toList()
        } else {
            events.asSequence()
                .filterNot { SNAPSHOT_HEAVY_EVENT_TYPES.contains(it.type) }
                .toList()
        }
        val ordered = filtered.sortedBy { it.id }
        return if (ordered.size <= eventsLimit) ordered else ordered.takeLast(eventsLimit)
    }

    private fun threadSnapshotsLocked(includeTerminal: Boolean, limit: Int): List<CognitiveThreadSnapshot> {
        val live = liveThreadsById.values.asSequence()
        val terminal = if (includeTerminal) {
            terminalThreadsById.values.asSequence()
        } else {
            emptySequence<CognitiveThreadSnapshot>()
        }
        return (live + terminal)
            .sortedByDescending { it.thread.lastUpdatedAt }
            .take(limit.coerceAtLeast(0))
            .toList()
    }

    private fun threadSnapshotLocked(threadId: String): CognitiveThreadSnapshot? =
        liveThreadsById[threadId] ?: terminalThreadsById[threadId]

    fun scratchpadIndexJson(): String {
        val payload = synchronized(lock) {
            pruneScratchpadSnapshotsLocked()
            val items = latestScratchpadSnapshotByRoot.values
                .sortedByDescending { it.updatedAtMs }
                .map { snapshot ->
                    mapOf(
                        "root_input_id" to snapshot.rootInputId,
                        "root_input_received_at_ms" to snapshot.rootInputReceivedAtMs,
                        "version" to snapshot.version,
                        "updated_at_ms" to snapshot.updatedAtMs,
                        "update_type" to snapshot.updateType,
                        "goal_preview" to snapshot.goal.take(SCRATCHPAD_GOAL_PREVIEW_CHARS),
                        "section_count" to snapshot.sectionCount,
                        "evidence_count" to snapshot.evidenceCount,
                        "scratchpad_confidence" to snapshot.scratchpadConfidence,
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

    fun scratchpadSnapshotJson(rootInputId: String, version: Long? = null): String? {
        val payload = synchronized(lock) {
            pruneScratchpadSnapshotsLocked()
            val record = if (version == null) {
                latestScratchpadSnapshotByRoot[rootInputId]
            } else {
                scratchpadSnapshots.lastOrNull {
                    it.rootInputId == rootInputId && it.version == version
                }
            } ?: return null
            val versions = scratchpadSnapshots
                .asSequence()
                .filter { it.rootInputId == rootInputId }
                .sortedByDescending { it.version }
                .take(SCRATCHPAD_VERSION_LIST_LIMIT)
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
                "scratchpad_confidence" to record.scratchpadConfidence,
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
        var healthEventForBroadcast: AgentEvent? = null
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
            if (subscribers.any { it.accepts(healthEvent) }) {
                healthEventForBroadcast = healthEvent
            }
        }
        if (healthEventForBroadcast != null) {
            enqueueTransport(TransportMessage.InstrumentationEvent(healthEventForBroadcast!!))
        }
    }

    fun subscribe(eventFilter: ((AgentEvent) -> Boolean)? = null): DashboardFlowSubscription {
        val channel = Channel<String>(SUBSCRIBER_CHANNEL_CAPACITY)
        val registration = EventSubscriber(channel = channel, eventFilter = eventFilter)
        synchronized(lock) {
            subscribers.add(registration)
        }
        return DashboardFlowSubscription(channel) {
            synchronized(lock) {
                subscribers.remove(registration)
            }
            channel.close()
        }
    }

    fun ensureChatSession(
        sessionId: String = DEFAULT_SESSION_ID,
        title: String? = null,
        interlocutor: Interlocutor? = null,
    ): ChatSessionSummary {
        synchronized(lock) {
            return toSummary(
                ensureChatSessionLocked(
                    sessionId = sanitizeSessionId(sessionId),
                    title = title,
                    interlocutor = interlocutor,
                )
            )
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

    fun eventIdForMessage(message: ChatMessage): String =
        "dashboard-chat:$runEpochSec-${message.id}"

    fun addUserMessage(sessionId: String, content: String, source: String = "web"): ChatMessage? {
        return addChatMessage(
            sessionId = sessionId,
            role = "user",
            content = content,
            source = source,
        )
    }

    fun addAssistantMessage(
        sessionId: String,
        content: String,
        source: String = "agent",
        interlocutorName: String? = null,
    ): ChatMessage? =
        addChatMessage(
            sessionId = sessionId,
            role = "assistant",
            content = content,
            source = source,
            interlocutorName = interlocutorName,
        )

    private fun addChatMessage(
        sessionId: String,
        role: String,
        content: String,
        source: String,
        interlocutorName: String? = null,
    ): ChatMessage? {
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
                role = role,
                content = sanitizedContent,
                source = source,
                interlocutorName = interlocutorName,
                emitEvent = true
            )
        }
    }

    fun resolveSessionForRootInput(rootInputId: String): String? =
        synchronized(lock) { rootInputSessionMap[rootInputId] }

    fun clearSessionForRootInput(rootInputId: String) {
        synchronized(lock) {
            rootInputSessionMap.remove(rootInputId)
        }
    }

    fun publishActionControlUpdate(type: String, data: Map<String, Any?> = emptyMap()) {
        enqueueTransport(
            TransportMessage.ActionControlEvent(
                payload = mapOf(
                    "type" to type,
                    "ts" to System.currentTimeMillis(),
                    "data" to data,
                )
            )
        )
    }

    fun nextSequenceNumber(sessionId: String): Long {
        synchronized(lock) {
            return sessionSequenceCounters
                .getOrPut(sanitizeSessionId(sessionId)) { AtomicLong(0) }
                .incrementAndGet()
        }
    }

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

    fun hasActiveChatSubscriber(sessionId: String): Boolean =
        synchronized(lock) { !chatSubscribers[sanitizeSessionId(sessionId)].isNullOrEmpty() }

    fun subscribeActionControl(): DashboardFlowSubscription {
        val channel = Channel<String>(SUBSCRIBER_CHANNEL_CAPACITY)
        synchronized(lock) {
            actionControlSubscribers.add(channel)
        }
        return DashboardFlowSubscription(channel) {
            synchronized(lock) {
                actionControlSubscribers.remove(channel)
            }
            channel.close()
        }
    }

    fun chatMessagePayload(message: ChatMessage): Map<String, Any?> =
        mapOf(
            "id" to message.id,
            "role" to message.role,
            "content" to message.content,
            "source" to message.source,
            "created_at_ms" to message.createdAtMs,
            "interlocutor" to message.interlocutor,
            "sequence" to message.sequence
        )

    override fun close() {
        transportChannel.close()
        @Suppress("BlockingMethodInNonBlockingContext")
        kotlinx.coroutines.runBlocking {
            transportJob.join()
        }
        transportScope.cancel()
        synchronized(lock) {
            subscribers.forEach { it.channel.close() }
            subscribers.clear()
            chatSubscribers.values.forEach { set -> set.forEach { it.close() } }
            chatSubscribers.clear()
            actionControlSubscribers.forEach { it.close() }
            actionControlSubscribers.clear()
        }
    }

    private fun instrumentationHealthMap(): Map<String, Any?> =
        buildMap {
            put("dropped_events", droppedEvents)
            put("queue_saturation_events", queueSaturationEvents)
            put("queue_saturation_by_type", queueSaturationByType.toMap())
        }

    private fun groundingGateStatsMap(): Map<String, Any?> {
        val denyRate = if (groundingGateTotal > 0) {
            groundingGateDeny.toDouble() / groundingGateTotal.toDouble()
        } else {
            0.0
        }
        return buildMap {
            put("total_reviews", groundingGateTotal)
            put("allow_count", groundingGateAllow)
            put("deny_count", groundingGateDeny)
            put("deny_rate", denyRate)
            put("grounding_required_count", groundingGateGroundingRequired)
            put("evidence_gathered_count", groundingGateEvidenceGathered)
            put("evidence_failed_technically_count", groundingGateEvidenceFailedTechnically)
            put("evidence_unavailable_count", groundingGateEvidenceUnavailable)
            put("by_reason_code", groundingGateByReasonCode.toMap())
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

    private fun broadcastToSubscribers(event: AgentEvent, payloadJson: String) {
        val subscriberSnapshot = synchronized(lock) { subscribers.toList() }
        if (subscriberSnapshot.isEmpty()) {
            return
        }
        val staleSubscribers = mutableListOf<EventSubscriber>()
        subscriberSnapshot.forEach { subscriber ->
            if (!subscriber.accepts(event)) {
                return@forEach
            }
            val result = subscriber.channel.trySend(payloadJson)
            if (result.isFailure && result.isClosed) {
                staleSubscribers.add(subscriber)
            } else if (result.isFailure) {
                // Buffer full — drop oldest and retry
                subscriber.channel.tryReceive()
                val retryResult = subscriber.channel.trySend(payloadJson)
                if (retryResult.isFailure && retryResult.isClosed) {
                    staleSubscribers.add(subscriber)
                }
            }
        }
        if (staleSubscribers.isNotEmpty()) {
            synchronized(lock) {
                subscribers.removeAll(staleSubscribers.toSet())
            }
        }
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
        val sequence = sessionSequenceCounters
            .getOrPut(sessionId) { AtomicLong(0) }
            .incrementAndGet()
        val message = ChatMessage(
            id = nextChatMessageId++,
            sessionId = sessionId,
            role = role,
            content = content,
            source = source,
            createdAtMs = now,
            interlocutor = interlocutorName,
            sequence = sequence
        )
        if (session.messages.size >= MAX_CHAT_MESSAGES_PER_SESSION) {
            session.messages.removeFirst()
        }
        session.messages.addLast(message)
        chatSessions[sessionId] = session.copy(updatedAtMs = now)
        if (emitEvent) {
            enqueueTransport(
                TransportMessage.ChatEvent(
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
                            "interlocutor" to message.interlocutor,
                            "sequence" to message.sequence
                        )
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
            "is_read_only" to session.sessionId.startsWith("id:"),
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
                        "interlocutor" to message.interlocutor,
                        "sequence" to message.sequence
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

    private fun enqueueTransport(message: TransportMessage) {
        val result = transportChannel.trySend(message)
        if (result.isSuccess) {
            return
        }
        transportChannel.tryReceive()
        transportChannel.trySend(message)
    }

    private suspend fun transportLoop() {
        for (message in transportChannel) {
            when (message) {
                is TransportMessage.InstrumentationEvent -> {
                    val payloadJson = mapper.writeValueAsString(message.event)
                    broadcastToSubscribers(message.event, payloadJson)
                }
                is TransportMessage.ChatEvent -> {
                    val payloadJson = mapper.writeValueAsString(message.payload)
                    broadcastChatPayload(message.sessionId, payloadJson)
                }
                is TransportMessage.ActionControlEvent -> {
                    val payloadJson = mapper.writeValueAsString(message.payload)
                    broadcastActionControlPayload(payloadJson)
                }
            }
        }
    }

    private fun broadcastChatPayload(sessionId: String, payloadJson: String) {
        val sessionSubscribers = synchronized(lock) { chatSubscribers[sessionId]?.toSet() } ?: return
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
        if (staleSubscribers.isNotEmpty()) {
            synchronized(lock) {
                chatSubscribers[sessionId]?.removeAll(staleSubscribers.toSet())
                if (chatSubscribers[sessionId].isNullOrEmpty()) {
                    chatSubscribers.remove(sessionId)
                }
            }
        }
    }

    private fun broadcastActionControlPayload(payloadJson: String) {
        val subscriberSnapshot = synchronized(lock) { actionControlSubscribers.toSet() }
        if (subscriberSnapshot.isEmpty()) {
            return
        }
        val staleSubscribers = mutableListOf<Channel<String>>()
        subscriberSnapshot.forEach { channel ->
            val result = channel.trySend(payloadJson)
            if (result.isFailure && result.isClosed) {
                staleSubscribers.add(channel)
            } else if (result.isFailure) {
                channel.tryReceive()
                val retryResult = channel.trySend(payloadJson)
                if (retryResult.isFailure && retryResult.isClosed) {
                    staleSubscribers.add(channel)
                }
            }
        }
        if (staleSubscribers.isNotEmpty()) {
            synchronized(lock) {
                actionControlSubscribers.removeAll(staleSubscribers.toSet())
            }
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

    private fun mergeThreadSnapshotLocked(data: Map<String, Any?>) {
        val providedSnapshot = data["thread_snapshot"] as? CognitiveThreadSnapshot
        val snapshot = providedSnapshot ?: return
        val threadId = snapshot.thread.id
        val rootInputId = data["root_input_id"].asString()
        if (rootInputId != null) {
            threadIdByRootInput[rootInputId] = threadId
        }
        storeThreadSnapshotLocked(snapshot)
    }

    private fun mergeOpportunityLocked(data: Map<String, Any?>) {
        val threadId = resolveThreadIdLocked(data) ?: return
        val current = threadSnapshotLocked(threadId) ?: return
        val rootInputId = data["root_input_id"].asString()
        val opportunity = Opportunity(
            id = data["opportunity_id"].asString() ?: return,
            cognitiveThreadId = threadId,
            kind = data["opportunity_kind"].asOpportunityKind() ?: OpportunityKind.RESPOND,
            summary = data["summary"]?.toString().orEmpty(),
            salience = 0.0,
            createdAt = current.thread.lastUpdatedAt,
            conversationContext = current.thread.conversationContext,
            securityContext = current.thread.securityContext,
            rootStimulusId = rootInputId,
            goalId = current.thread.goalId,
            goalRunId = current.thread.goalRunId,
            allowedIntentions = data["allowed_intentions"].asIntentionKindSet(),
            allowedCommitModes = data["allowed_commit_modes"].asCommitModeSet(),
            availableActions = data["available_actions"].asActionTypeSet(),
            dispatchableActions = data["dispatchable_actions"].asActionTypeSet(),
            metadata = (data["opportunity_metadata"] as? Map<*, *>)
                ?.entries
                ?.mapNotNull { (key, value) ->
                    val normalizedKey = key?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    normalizedKey to value?.toString().orEmpty()
                }
                ?.toMap()
                ?: current.latestOpportunity?.metadata
                ?: current.thread.metadata,
        )
        storeThreadSnapshotLocked(current.copy(latestOpportunity = opportunity))
    }

    private fun mergeIntentionLocked(data: Map<String, Any?>) {
        val threadId = resolveThreadIdLocked(data) ?: return
        val current = threadSnapshotLocked(threadId) ?: return
        val intention = Intention(
            id = data["intention_id"].asString() ?: return,
            cognitiveThreadId = threadId,
            kind = data["intention_kind"].asIntentionKind() ?: IntentionKind.PREPARE,
            summary = data["summary"]?.toString()?.ifBlank { null }
                ?: data["action_type"]?.toString()
                ?: current.latestIntention?.summary
                ?: current.thread.title,
            createdAt = current.thread.lastUpdatedAt,
            conversationContext = current.thread.conversationContext,
            commitMode = current.latestIntention?.commitMode ?: ai.neopsyke.agent.model.CommitMode.NOT_APPLICABLE,
            rootStimulusId = data["root_input_id"].asString() ?: current.thread.rootStimulusId,
            goalId = current.thread.goalId,
            goalRunId = current.thread.goalRunId,
            metadata = current.thread.metadata,
        )
        storeThreadSnapshotLocked(current.copy(latestIntention = intention))
    }

    private fun mergeIntentionTransitionLocked(data: Map<String, Any?>) {
        val threadId = resolveThreadIdLocked(data) ?: return
        val current = threadSnapshotLocked(threadId) ?: return
        val stage = data["stage"]?.toString()?.trim().orEmpty()
        val kind = data["intention_kind"].asIntentionKind() ?: current.latestIntention?.kind ?: IntentionKind.PREPARE
        val summary = listOfNotNull(
            data["summary"]?.toString()?.trim()?.takeIf { it.isNotBlank() },
            current.latestIntention?.summary,
            stage.takeIf { it.isNotBlank() }?.replace('_', ' ')
        ).firstOrNull().orEmpty()
        val intention = Intention(
            id = data["intention_id"].asString() ?: current.latestIntention?.id ?: return,
            cognitiveThreadId = threadId,
            kind = kind,
            summary = summary,
            createdAt = current.latestIntention?.createdAt ?: current.thread.lastUpdatedAt,
            conversationContext = current.thread.conversationContext,
            commitMode = data["commit_mode"].asCommitMode()
                ?: current.latestIntention?.commitMode
                ?: ai.neopsyke.agent.model.CommitMode.NOT_APPLICABLE,
            rootStimulusId = current.thread.rootStimulusId,
            goalId = current.thread.goalId,
            goalRunId = current.thread.goalRunId,
            metadata = current.thread.metadata,
        )
        storeThreadSnapshotLocked(current.copy(latestIntention = intention))
    }

    private fun resolveThreadIdLocked(data: Map<String, Any?>): String? {
        val direct = data["thread_id"].asString()
        if (direct != null) {
            return direct
        }
        val rootInputId = data["root_input_id"].asString() ?: return null
        return threadIdByRootInput[rootInputId]
    }

    private fun storeThreadSnapshotLocked(snapshot: CognitiveThreadSnapshot) {
        val threadId = snapshot.thread.id
        if (snapshot.thread.status.isTerminalThreadStatus()) {
            liveThreadsById.remove(threadId)
            terminalThreadsById[threadId] = snapshot
            trimThreadSnapshotsLocked(terminalThreadsById, MAX_TERMINAL_THREAD_SNAPSHOTS)
        } else {
            terminalThreadsById.remove(threadId)
            liveThreadsById[threadId] = snapshot
            trimThreadSnapshotsLocked(liveThreadsById, MAX_LIVE_THREAD_SNAPSHOTS)
        }
    }

    private fun trimThreadSnapshotsLocked(
        target: LinkedHashMap<String, CognitiveThreadSnapshot>,
        limit: Int,
    ) {
        while (target.size > limit) {
            val iterator = target.entries.iterator()
            if (!iterator.hasNext()) {
                return
            }
            iterator.next()
            iterator.remove()
        }
    }

    private fun captureScratchpadSnapshot(data: Map<String, Any?>) {
        val rootInputId = data["root_input_id"].asString() ?: return
        val rootInputReceivedAtMs = data["root_input_received_at_ms"].asLong() ?: 0L
        val version = data["version"].asLong() ?: return
        val updatedAtMs = data["updated_at_ms"].asLong() ?: System.currentTimeMillis()
        val record = ScratchpadSnapshotRecord(
            rootInputId = rootInputId,
            rootInputReceivedAtMs = rootInputReceivedAtMs,
            version = version,
            updatedAtMs = updatedAtMs,
            updateType = data["update_type"]?.toString().orEmpty(),
            goal = data["goal"]?.toString().orEmpty(),
            sectionCount = data["section_count"].asInt(),
            evidenceCount = data["evidence_count"].asInt(),
            scratchpadConfidence = data["scratchpad_confidence"].asDouble(),
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
        val sameVersionIndex = scratchpadSnapshots.indexOfFirst {
            it.rootInputId == rootInputId && it.version == version
        }
        if (sameVersionIndex >= 0) {
            scratchpadSnapshots.removeAt(sameVersionIndex)
        }
        scratchpadSnapshots.addLast(record)
        pruneScratchpadSnapshotsLocked()
    }

    private fun captureScratchpadHead(data: Map<String, Any?>) {
        val rootInputId = data["root_input_id"].asString() ?: return
        val rootInputReceivedAtMs = data["root_input_received_at_ms"].asLong() ?: 0L
        val version = data["version"].asLong() ?: return
        val updatedAtMs = data["updated_at_ms"].asLong() ?: System.currentTimeMillis()
        val sameVersionIndex = scratchpadSnapshots.indexOfFirst {
            it.rootInputId == rootInputId && it.version == version
        }
        if (sameVersionIndex >= 0) {
            return
        }
        scratchpadSnapshots.addLast(
            ScratchpadSnapshotRecord(
                rootInputId = rootInputId,
                rootInputReceivedAtMs = rootInputReceivedAtMs,
                version = version,
                updatedAtMs = updatedAtMs,
                updateType = data["update_type"]?.toString().orEmpty(),
                goal = data["goal_preview"]?.toString().orEmpty(),
                sectionCount = data["section_count"].asInt(),
                evidenceCount = data["evidence_count"].asInt(),
                scratchpadConfidence = data["scratchpad_confidence"].asDouble(),
                bytesEstimate = data["bytes_estimate"].asInt(),
                sections = emptyList(),
                evidence = emptyList()
            )
        )
        pruneScratchpadSnapshotsLocked()
    }

    private fun pruneScratchpadSnapshotsLocked() {
        val ttlMs = scratchpadSnapshotTtlMs.coerceAtLeast(0L)
        val now = System.currentTimeMillis()
        if (ttlMs > 0L) {
            while (scratchpadSnapshots.isNotEmpty() && (now - scratchpadSnapshots.first().updatedAtMs) > ttlMs) {
                scratchpadSnapshots.removeFirst()
            }
        }
        while (scratchpadSnapshots.size > max(10, maxScratchpadSnapshots)) {
            scratchpadSnapshots.removeFirst()
        }
        latestScratchpadSnapshotByRoot.clear()
        scratchpadSnapshots.forEach { record ->
            val current = latestScratchpadSnapshotByRoot[record.rootInputId]
            if (
                current == null ||
                record.version > current.version ||
                (record.version == current.version && record.updatedAtMs >= current.updatedAtMs)
            ) {
                latestScratchpadSnapshotByRoot[record.rootInputId] = record
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

    private fun Any?.asOpportunityKind(): OpportunityKind? =
        this?.toString()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.uppercase()
            ?.let { raw -> OpportunityKind.entries.firstOrNull { it.name == raw } }

    private fun Any?.asIntentionKind(): IntentionKind? =
        this?.toString()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.uppercase()
            ?.let { raw -> IntentionKind.entries.firstOrNull { it.name == raw } }

    private fun Any?.asCommitMode(): ai.neopsyke.agent.model.CommitMode? =
        this?.toString()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.uppercase()
            ?.let { raw -> ai.neopsyke.agent.model.CommitMode.entries.firstOrNull { it.name == raw } }

    private fun Any?.asIntentionKindSet(): Set<IntentionKind> =
        (this as? List<*>)
            ?.mapNotNull { it.asIntentionKind() }
            ?.toSet()
            ?: emptySet()

    private fun Any?.asCommitModeSet(): Set<ai.neopsyke.agent.model.CommitMode> =
        (this as? List<*>)
            ?.mapNotNull { it.asCommitMode() }
            ?.toSet()
            ?: emptySet()

    private fun Any?.asActionTypeSet(): Set<ai.neopsyke.agent.model.ActionType> =
        (this as? List<*>)
            ?.mapNotNull { entry -> ai.neopsyke.agent.model.ActionType.fromRaw(entry?.toString()) }
            ?.toSet()
            ?: emptySet()

    private fun CognitiveThreadStatus.isTerminalThreadStatus(): Boolean =
        this == CognitiveThreadStatus.RESOLVED || this == CognitiveThreadStatus.FAILED

    private data class ScratchpadSnapshotRecord(
        val rootInputId: String,
        val rootInputReceivedAtMs: Long,
        val version: Long,
        val updatedAtMs: Long,
        val updateType: String,
        val goal: String,
        val sectionCount: Int,
        val evidenceCount: Int,
        val scratchpadConfidence: Double,
        val bytesEstimate: Int,
        val sections: List<Map<String, Any?>>,
        val evidence: List<String>,
    )

    private data class PlannerStructuredOutputScope(
        val sessionId: String,
        val rootInputId: String?,
        val callSite: String,
    )

    private companion object {
        const val SCRATCHPAD_GOAL_PREVIEW_CHARS: Int = 140
        const val SCRATCHPAD_VERSION_LIST_LIMIT: Int = 25
        const val DEFAULT_SESSION_ID: String = "default"
        const val CHAT_SOURCE_PREFIX: String = "chat:"
        const val DEFAULT_SNAPSHOT_EVENTS_LIMIT: Int = 300
        const val MAX_CHAT_MESSAGES_PER_SESSION: Int = 400
        const val MAX_SESSION_ID_CHARS: Int = 64
        const val MAX_SESSION_TITLE_CHARS: Int = 80
        const val MAX_SESSION_ID_GENERATION_ATTEMPTS: Int = 4
        const val SUBSCRIBER_CHANNEL_CAPACITY: Int = 1_000
        const val TRANSPORT_CHANNEL_CAPACITY: Int = 2_048
        const val MAX_PHASE_TIMINGS: Int = 200
        const val DEFAULT_THREAD_SNAPSHOT_LIMIT: Int = 100
        const val MAX_LIVE_THREAD_SNAPSHOTS: Int = 256
        const val MAX_TERMINAL_THREAD_SNAPSHOTS: Int = 512
        val PLANNER_CALL_SITES: Set<String> = setOf("input", "continuation", "feedback", "goal_work", "impulse")
        val ACTION_CONTROL_STREAM_EVENT_TYPES: Set<String> = setOf(
            "action_staged",
            "action_executed",
            "action_denied",
        )
        val SNAPSHOT_HEAVY_EVENT_TYPES: Set<String> = setOf(
            "llm_raw_response",
        )
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
    val sequence: Long = 0,
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
    val groundingGateStats: Map<String, Any?> = emptyMap(),
    val promptBudgetStats: Map<String, Any?> = emptyMap(),
    val cognitiveThreads: List<CognitiveThreadSnapshot> = emptyList(),
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

    internal fun asReceiveChannel(): ReceiveChannel<String> = channel

    suspend fun receive(): String = channel.receive()

    suspend fun receiveCatching() = channel.receiveCatching()

    override fun close() {
        onClose()
    }
}
