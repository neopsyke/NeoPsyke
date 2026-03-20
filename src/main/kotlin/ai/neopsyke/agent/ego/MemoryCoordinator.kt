package ai.neopsyke.agent.ego

import mu.KotlinLogging
import ai.neopsyke.agent.actions.ReflectionMemoryRecorder
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.AmbientContext
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.DeliberationState
import ai.neopsyke.agent.model.DialogueTurn
import ai.neopsyke.agent.model.EgoTrigger
import ai.neopsyke.agent.model.DialogueRole
import ai.neopsyke.agent.model.Interlocutor
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.memory.episodic.DeterministicLogbookSummarizer
import ai.neopsyke.agent.memory.episodic.EpisodicEventType
import ai.neopsyke.agent.memory.episodic.Logbook
import ai.neopsyke.agent.memory.episodic.LogbookEntry
import ai.neopsyke.agent.memory.episodic.LogbookNarrative
import ai.neopsyke.agent.memory.episodic.LogbookQuery
import ai.neopsyke.agent.memory.episodic.LogbookRecall
import ai.neopsyke.agent.memory.episodic.LogbookSummarizer
import ai.neopsyke.agent.memory.longterm.Hippocampus
import ai.neopsyke.agent.memory.longterm.LongTermMemoryAdvisor
import ai.neopsyke.agent.memory.longterm.LongTermMemoryAssessmentContext
import ai.neopsyke.agent.memory.longterm.MemoryImprint
import ai.neopsyke.agent.memory.longterm.MemoryRecallQuery
import ai.neopsyke.agent.memory.shortterm.MemoryStats
import ai.neopsyke.agent.memory.shortterm.MemoryStore
import ai.neopsyke.agent.support.DenialReasonClassifier
import ai.neopsyke.agent.support.LlmCallCircuitBreaker
import ai.neopsyke.agent.support.OnTripBehavior
import ai.neopsyke.agent.support.PromptInjectionDefense
import ai.neopsyke.agent.support.TextSecurity
import ai.neopsyke.instrumentation.AgentEvent
import ai.neopsyke.instrumentation.AgentEvents
import ai.neopsyke.instrumentation.AgentInstrumentation
import java.time.Duration
import java.time.Instant
import java.util.Locale

private val logger = KotlinLogging.logger {}

/**
 * Manages short-term and long-term memory operations for the Ego agent loop.
 * Extracted from Ego to separate memory coordination concerns.
 */
class MemoryCoordinator(
    private val hippocampus: Hippocampus,
    private val longTermMemoryAdvisor: LongTermMemoryAdvisor,
    private val config: AgentConfig,
    private val instrumentation: AgentInstrumentation,
    initialMemoryStore: MemoryStore? = null,
    private val logbook: Logbook? = null,
    private val logbookSummarizer: LogbookSummarizer = DeterministicLogbookSummarizer(config.logbook),
    private val runId: String? = null,
) : ReflectionMemoryRecorder {
    private val memoryStoreFactory: () -> MemoryStore = { MemoryStore(config.memory.maxShortTermContextChars) }
    private val sessionMemoryStores: MutableMap<String, MemoryStore> =
        object : LinkedHashMap<String, MemoryStore>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MemoryStore>): Boolean =
                size > MAX_TRACKED_SESSIONS
        }
    private var activeSessionId: String = ConversationContext.DEFAULT_SESSION_ID
    private var activeInterlocutor: Interlocutor = Interlocutor.UNKNOWN

    init {
        // Backward compatibility: inject the initial memory store into the default session.
        if (initialMemoryStore != null) {
            sessionMemoryStores[ConversationContext.DEFAULT_SESSION_ID] = initialMemoryStore
        }
    }

    fun setActiveSession(sessionId: String, interlocutor: Interlocutor = Interlocutor.UNKNOWN) {
        activeSessionId = sessionId
        activeInterlocutor = interlocutor
    }

    private fun activeMemoryStore(): MemoryStore =
        sessionMemoryStores.getOrPut(activeSessionId) { memoryStoreFactory() }

    private val recentLessonFingerprints = ArrayDeque<String>()
    private data class SessionMemoryState(
        var latestShortTermSummary: String = "",
        var latestLongTermRecall: String = "",
        val circuitBreaker: LlmCallCircuitBreaker,
        var explicitIntentAssessmentTriggeredForInput: Boolean = false,
        var lastConsolidationStep: Int = 0,
        val recentImprintFingerprints: ArrayDeque<String> = ArrayDeque(),
        val recentLearningTopics: ArrayDeque<LearningTopicRecord> = ArrayDeque(),
    )

    private data class LearningTopicRecord(
        val fingerprint: String,
        val label: String,
    )

    private val sessionStates: MutableMap<String, SessionMemoryState> =
        object : LinkedHashMap<String, SessionMemoryState>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SessionMemoryState>): Boolean =
                size > MAX_TRACKED_SESSIONS
        }

    private fun activeState(): SessionMemoryState =
        sessionStates.getOrPut(activeSessionId) {
            SessionMemoryState(
                circuitBreaker = LlmCallCircuitBreaker(
                    tripThreshold = config.memory.longTermMemoryParseFallbackDisableAfter,
                    onTripBehavior = OnTripBehavior.DISABLE,
                )
            )
        }

    // --- Short-term memory delegation ---

    fun remember(turn: DialogueTurn) {
        activeMemoryStore().remember(turn)
        if (turn.role == DialogueRole.USER) {
            journalSafe(
                eventType = EpisodicEventType.INPUT_RECEIVED,
                summary = logbookSummarizer.summarizeInput(turn.content, config.logbook.maxSummaryChars),
                keywords = logbookSummarizer.extractKeywords(turn.content),
            )
        }
    }

    fun currentShortTermSummary(): String {
        val memoryTokenBudget = minOf(
            config.memory.maxShortTermContextPromptTokens,
            maxOf(64, config.maxLlmPromptTokens / 3)
        )
        return activeMemoryStore().summaryForPrompt(memoryTokenBudget)
    }

    fun activeMemoryStats(): MemoryStats = activeMemoryStore().stats()

    // --- Long-term memory recall ---

    /**
     * Recalls long-term memory for the given trigger. Stores results internally for later
     * use by [maybeAssessLongTermMemory].
     */
    fun recall(
        trigger: EgoTrigger,
        shortTermSummary: String,
        recentDialogue: List<DialogueTurn>,
        episodicCues: List<String> = emptyList(),
        ambientContext: AmbientContext = AmbientContext(),
    ): String {
        val text = recallMemory(
            trigger = trigger,
            shortTermSummary = shortTermSummary,
            recentDialogue = recentDialogue,
            episodicCues = episodicCues,
            ambientContext = ambientContext,
        )
        val state = activeState()
        state.latestShortTermSummary = shortTermSummary
        state.latestLongTermRecall = text
        return text
    }

    fun recentExactLearningTopics(): List<String> =
        activeState().recentLearningTopics.map { it.label }

    fun recentUsefulActionsOrUpdates(): List<String> {
        val lb = logbook ?: return emptyList()
        return try {
            lb.query(
                LogbookQuery(
                    eventTypes = USEFUL_AMBIENT_EVENT_TYPES,
                    maxResults = MAX_AMBIENT_USEFUL_UPDATES
                )
            ).entries
                .map { entry -> TextSecurity.preview(entry.summary, AMBIENT_EVENT_PREVIEW_CHARS) }
                .filter { it.isNotBlank() }
        } catch (ex: Exception) {
            logger.debug(ex) { "Ambient useful action recall failed." }
            emptyList()
        }
    }

    fun recallLessons(trigger: EgoTrigger, recentDialogue: List<DialogueTurn>): String {
        if (!hippocampus.enabled) return ""
        val cue = buildLessonCue(trigger, recentDialogue)
        if (cue.isBlank()) return ""
        val startedAt = System.nanoTime()
        return try {
            val recall = hippocampus.recall(
                MemoryRecallQuery(
                    cue = cue,
                    recentDialogue = recentDialogue,
                    shortTermContextSummary = currentShortTermSummary(),
                    maxItems = LESSON_RECALL_MAX_ITEMS,
                    maxChars = LESSON_RECALL_MAX_CHARS
                )
            )
            val lessonText = PromptInjectionDefense.asUntrustedDataBlock(
                text = recall.text,
                maxChars = LESSON_RECALL_MAX_CHARS
            )
            instrumentation.emit(
                AgentEvent(
                    type = "lesson_recall",
                    data = mapOf(
                        "hit_count" to recall.hitCount,
                        "latency_ms" to (System.nanoTime() - startedAt) / 1_000_000L,
                        "recall_chars" to lessonText.length,
                        "truncated" to recall.truncated,
                        "recall_text_preview" to lessonText,
                    )
                )
            )
            lessonText
        } catch (ex: Exception) {
            logger.debug(ex) { "Lesson recall failed for cue='${TextSecurity.preview(cue, 120)}'." }
            ""
        }
    }

    // --- Long-term memory assessment / imprint ---

    fun maybeAssessLongTermMemory(
        trigger: String,
        force: Boolean = false,
        latestActionType: ActionType? = null,
        latestActionOutcome: String? = null,
        deliberation: DeliberationState,
        recentDialogue: List<DialogueTurn>,
    ) {
        val sessionState = activeState()
        if (!hippocampus.enabled || !longTermMemoryAdvisor.enabled || sessionState.circuitBreaker.isTripped()) return
        val stepIndex = deliberation.stepIndex
        val explicitIntent = detectExplicitRememberIntent(recentDialogue)
        val forcedByExplicitIntent = !force &&
            !sessionState.explicitIntentAssessmentTriggeredForInput &&
            explicitIntent != null
        val shouldByInterval = !force &&
            !forcedByExplicitIntent &&
            stepIndex > 0 &&
            stepIndex % config.memory.longTermMemoryAssessEverySteps == 0
        if (!force && !forcedByExplicitIntent && !shouldByInterval) return
        val effectiveForce = force || forcedByExplicitIntent
        if (!effectiveForce) {
            val stepsSinceLast = stepIndex - sessionState.lastConsolidationStep
            if (stepsSinceLast in 0 until config.memory.longTermMemoryAssessCooldownSteps) return
        }
        if (stepIndex == sessionState.lastConsolidationStep && sessionState.lastConsolidationStep > 0) return

        val effectiveTrigger = if (forcedByExplicitIntent) EXPLICIT_REMEMBER_INTENT_TRIGGER else trigger
        if (forcedByExplicitIntent) {
            sessionState.explicitIntentAssessmentTriggeredForInput = true
            instrumentation.emit(
                AgentEvent(
                    type = "long_term_memory_explicit_intent_detected",
                    data = mapOf(
                        "trigger" to trigger,
                        "step_index" to stepIndex,
                        "intent_pattern" to explicitIntent?.patternLabel,
                        "latest_user_message_preview" to explicitIntent?.latestUserMessagePreview
                    )
                )
            )
        }

        val context = LongTermMemoryAssessmentContext(
            trigger = effectiveTrigger,
            deliberation = deliberation,
            recentDialogue = recentDialogue,
            shortTermContextSummary = sessionState.latestShortTermSummary.ifBlank { currentShortTermSummary() },
            longTermMemoryRecall = sessionState.latestLongTermRecall,
            metaGuidance = "",
            latestActionType = latestActionType,
            latestActionOutcome = latestActionOutcome
        )

        val decision = try {
            longTermMemoryAdvisor.assess(context)
        } catch (ex: Exception) {
            logger.warn(ex) { "Long-term memory assessment failed." }
            emitMemoryPersistenceSkipped(
                trigger = effectiveTrigger,
                stepIndex = stepIndex,
                reasonCode = REASON_CODE_ASSESSMENT_EXCEPTION,
                reasonDetail = "Advisor assessment failed: ${ex.message ?: ex::class.simpleName ?: "unknown error"}.",
                decision = null
            )
            instrumentation.emit(AgentEvents.warning("Long-term memory assessment failed; skipping this cycle."))
            return
        } finally {
            sessionState.lastConsolidationStep = stepIndex
        }

        instrumentation.emit(
            AgentEvent(
                type = "long_term_memory_assessment",
                data = mapOf(
                    "trigger" to effectiveTrigger,
                    "step_index" to stepIndex,
                    "save" to decision.shouldSave,
                    "confidence" to decision.confidence,
                    "reason" to decision.reason,
                    "summary_preview" to TextSecurity.preview(decision.summary, 180)
                )
            )
        )

        if (decision.parseFallback) {
            val tripped = sessionState.circuitBreaker.recordParseFailure()
            val streak = sessionState.circuitBreaker.streak()
            emitMemoryPersistenceSkipped(
                trigger = effectiveTrigger,
                stepIndex = stepIndex,
                reasonCode = REASON_CODE_PARSE_FALLBACK,
                reasonDetail = "Advisor response parse fallback blocked persistence for this cycle (streak=$streak, disable_after=${config.memory.longTermMemoryParseFallbackDisableAfter}).",
                decision = decision,
                extra = mapOf(
                    "parse_fallback_streak" to streak,
                    "parse_fallback_disable_after" to config.memory.longTermMemoryParseFallbackDisableAfter
                )
            )
            instrumentation.emit(
                AgentEvents.longTermMemoryAssessmentParseFallback(
                    trigger = effectiveTrigger,
                    stepIndex = stepIndex,
                    streak = streak
                )
            )
            if (tripped) {
                instrumentation.emit(
                    AgentEvents.longTermMemoryAssessmentTemporarilyDisabled(
                        trigger = effectiveTrigger,
                        stepIndex = stepIndex,
                        streak = streak,
                        threshold = config.memory.longTermMemoryParseFallbackDisableAfter
                    )
                )
                instrumentation.emit(AgentEvents.warning("Long-term memory assessment disabled for session $activeSessionId after repeated parse fallbacks."))
            }
            return
        } else {
            sessionState.circuitBreaker.recordSuccess()
        }

        if (!decision.shouldSave) {
            emitMemoryPersistenceSkipped(
                trigger = effectiveTrigger,
                stepIndex = stepIndex,
                reasonCode = REASON_CODE_ADVISOR_DECLINED,
                reasonDetail = "Advisor declined persistence: ${decision.reason}.",
                decision = decision
            )
            return
        }
        if (decision.confidence < config.memory.longTermMemoryMinConfidence) {
            val confidence = String.format(Locale.ROOT, "%.3f", decision.confidence)
            val threshold = String.format(Locale.ROOT, "%.3f", config.memory.longTermMemoryMinConfidence)
            emitMemoryPersistenceSkipped(
                trigger = effectiveTrigger,
                stepIndex = stepIndex,
                reasonCode = REASON_CODE_CONFIDENCE_BELOW_THRESHOLD,
                reasonDetail = "Decision confidence $confidence is below configured minimum $threshold.",
                decision = decision,
                extra = mapOf(
                    "confidence" to decision.confidence,
                    "min_confidence" to config.memory.longTermMemoryMinConfidence
                )
            )
            return
        }
        val recallEchoEvaluation = evaluateRecallEcho(decision.summary)
        if (recallEchoEvaluation.isEcho) {
            val overlapLabel = recallEchoEvaluation.overlapRatio?.let { String.format(Locale.ROOT, "%.3f", it) } ?: "n/a"
            val thresholdLabel = String.format(Locale.ROOT, "%.3f", config.memory.longTermMemoryRecallEchoTokenOverlapThreshold)
            emitMemoryPersistenceSkipped(
                trigger = effectiveTrigger,
                stepIndex = stepIndex,
                reasonCode = REASON_CODE_RECALL_ECHO,
                reasonDetail =
                    "Summary matched recalled memory (mode=${recallEchoEvaluation.mode ?: "unknown"}, overlap=$overlapLabel, min_summary_chars=${config.memory.longTermMemoryRecallEchoMinSummaryChars}, min_token_length=${config.memory.longTermMemoryRecallEchoMinTokenLength}, min_token_count=${config.memory.longTermMemoryRecallEchoMinTokenCount}, overlap_threshold=$thresholdLabel).",
                decision = decision,
                extra = mapOf(
                    "echo_mode" to recallEchoEvaluation.mode,
                    "echo_overlap_ratio" to recallEchoEvaluation.overlapRatio,
                    "echo_summary_token_count" to recallEchoEvaluation.summaryTokenCount,
                    "echo_recall_token_count" to recallEchoEvaluation.recallTokenCount,
                    "echo_min_summary_chars" to config.memory.longTermMemoryRecallEchoMinSummaryChars,
                    "echo_min_token_length" to config.memory.longTermMemoryRecallEchoMinTokenLength,
                    "echo_min_token_count" to config.memory.longTermMemoryRecallEchoMinTokenCount,
                    "echo_overlap_threshold" to config.memory.longTermMemoryRecallEchoTokenOverlapThreshold
                )
            )
            return
        }

        val fingerprint = normalizePayload(decision.summary)
        if (sessionState.recentImprintFingerprints.contains(fingerprint)) {
            emitMemoryPersistenceSkipped(
                trigger = effectiveTrigger,
                stepIndex = stepIndex,
                reasonCode = REASON_CODE_DUPLICATE_FINGERPRINT,
                reasonDetail = "Duplicate imprint summary fingerprint matched a recent saved entry.",
                decision = decision
            )
            return
        }

        val contextTags = listOfNotNull(
            "session:$activeSessionId",
            "interlocutor:${activeInterlocutor.id}",
        )
        val imprintStartedAt = System.nanoTime()
        val saved = try {
            hippocampus.imprint(
                MemoryImprint(
                    summary = decision.summary,
                    source = effectiveTrigger,
                    confidence = decision.confidence,
                    tags = contextTags + decision.tags
                )
            )
        } catch (ex: Exception) {
            logger.warn(ex) { "Hippocampus imprint failed." }
            false
        }
        val imprintLatencyMs = (System.nanoTime() - imprintStartedAt) / 1_000_000L
        instrumentation.emit(
            AgentEvent(
                type = "memory_imprint_result",
                data = mapOf(
                    "trigger" to effectiveTrigger,
                    "saved" to saved,
                    "provider" to hippocampus.providerName,
                    "summary_chars" to decision.summary.length,
                    "latency_ms" to imprintLatencyMs,
                    "confidence" to decision.confidence,
                    "tags" to decision.tags
                )
            )
        )
        if (saved) {
            sessionState.recentImprintFingerprints.addLast(fingerprint)
            while (sessionState.recentImprintFingerprints.size > MAX_RECENT_IMPRINT_FINGERPRINTS) {
                sessionState.recentImprintFingerprints.removeFirst()
            }
            journalSafe(
                eventType = EpisodicEventType.MEMORY_IMPRINT,
                summary = decision.summary,
                keywords = decision.tags,
            )
        }
        emitServerMetrics()
    }

    // --- Episodic logbook ---

    /**
     * Records an episodic logbook entry. Called from Ego for events outside the
     * normal memory flow (planner decisions, action outcomes, answers, denials).
     */
    fun journal(
        eventType: EpisodicEventType,
        summary: String,
        actionType: String? = null,
        metadata: Map<String, Any?>? = null,
    ) {
        journalSafe(
            eventType = eventType,
            summary = TextSecurity.preview(summary, config.logbook.maxSummaryChars),
            keywords = logbookSummarizer.extractKeywords(summary),
            actionType = actionType,
            metadata = metadata,
        )
    }

    override fun recordReflection(action: PendingAction, summary: String, keywords: List<String>): Boolean {
        val normalizedSummary = LogbookNarrative.normalizeSummary(EpisodicEventType.SELF_INITIATED, summary)
        if (normalizedSummary.isBlank()) return false

        val normalizedKeywords = keywords
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val tags = buildReflectionTags(action, normalizedKeywords)

        val savedToLongTermMemory = if (hippocampus.enabled) {
            try {
                hippocampus.imprint(
                    MemoryImprint(
                        summary = normalizedSummary,
                        source = "self_initiated_reflection",
                        confidence = REFLECTION_DEFAULT_CONFIDENCE,
                        tags = tags,
                    )
                )
            } catch (ex: Exception) {
                logger.debug(ex) { "Reflection imprint failed for action_type=${action.type.id}." }
                false
            }
        } else {
            false
        }

        if (hippocampus.enabled) {
            if (!savedToLongTermMemory) {
                logger.debug { "Reflection not saved to long-term memory for action_type=${action.type.id}." }
            }
        }

        journalSafe(
            eventType = EpisodicEventType.SELF_INITIATED,
            summary = normalizedSummary,
            keywords = normalizedKeywords,
            actionType = action.type.id,
            metadata = buildReflectionMetadata(action),
        )
        if (savedToLongTermMemory && action.origin.needId == LEARNING_NEED_ID) {
            rememberLearningTopic(summary = normalizedSummary, keywords = normalizedKeywords)
        }
        return savedToLongTermMemory
    }

    fun maybeRecordLesson(
        trigger: String,
        actionType: ActionType?,
        reasonCode: String?,
        reason: String?,
        deniedPayload: String?,
        recentDialogue: List<DialogueTurn>,
        stepIndex: Int,
    ) {
        if (!hippocampus.enabled) return
        val normalizedReason = reason?.trim().orEmpty()
        if (normalizedReason.isBlank()) return
        if (shouldSkipLesson(reasonCode, normalizedReason)) {
            instrumentation.emit(
                AgentEvent(
                    type = "lesson_skipped",
                    data = mapOf(
                        "trigger" to trigger,
                        "step_index" to stepIndex,
                        "reason_code" to reasonCode,
                        "skip_reason" to "technical_or_system_failure"
                    )
                )
            )
            return
        }
        val latestUserTurn = recentDialogue
            .asReversed()
            .firstOrNull { it.role == DialogueRole.USER }
            ?.content
            ?.trim()
            .orEmpty()
        if (latestUserTurn.isBlank()) return

        val lesson = buildLesson(
            latestUserTurn = latestUserTurn,
            actionType = actionType,
            reasonCode = reasonCode,
            reason = normalizedReason,
            deniedPayload = deniedPayload
        )
        val fingerprint = normalizePayload(lesson)
        if (recentLessonFingerprints.contains(fingerprint)) {
            instrumentation.emit(
                AgentEvent(
                    type = "lesson_skipped",
                    data = mapOf(
                        "trigger" to trigger,
                        "step_index" to stepIndex,
                        "reason_code" to reasonCode,
                        "skip_reason" to "duplicate_recent_lesson"
                    )
                )
            )
            return
        }
        val contextTags = listOfNotNull(
            "session:$activeSessionId",
            "interlocutor:${activeInterlocutor.id}",
            "kind:lesson",
            actionType?.name?.lowercase()?.let { "action:$it" },
            reasonCode?.trim()?.takeIf { it.isNotBlank() }?.let { "reason_code:${it.lowercase(Locale.ROOT)}" }
        )
        val saved = try {
            hippocampus.imprint(
                MemoryImprint(
                    summary = lesson,
                    source = "ego_lesson",
                    confidence = LESSON_DEFAULT_CONFIDENCE,
                    tags = contextTags
                )
            )
        } catch (ex: Exception) {
            logger.debug(ex) { "Lesson imprint failed." }
            false
        }
        instrumentation.emit(
            AgentEvent(
                type = "lesson_result",
                data = mapOf(
                    "trigger" to trigger,
                    "step_index" to stepIndex,
                    "saved" to saved,
                    "reason_code" to reasonCode,
                    "action_type" to actionType?.name?.lowercase(),
                    "summary_preview" to TextSecurity.preview(lesson, 180)
                )
            )
        )
        if (!saved) return
        recentLessonFingerprints.addLast(fingerprint)
        while (recentLessonFingerprints.size > MAX_RECENT_LESSON_FINGERPRINTS) {
            recentLessonFingerprints.removeFirst()
        }
        journalSafe(
            eventType = EpisodicEventType.MEMORY_IMPRINT,
            summary = lesson,
            keywords = contextTags,
            actionType = actionType?.name?.lowercase()
        )
    }

    /**
     * Queries the episodic logbook when temporal recall intent is detected in the latest user turn.
     * Returns a compact formatted timeline, or empty string if no intent or no results.
     */
    fun recallEpisodic(trigger: EgoTrigger, recentDialogue: List<DialogueTurn>): String {
        val lb = logbook ?: return ""
        val intent = detectTemporalRecallIntent(recentDialogue) ?: return ""

        instrumentation.emit(
            AgentEvent(
                type = "episodic_recall_intent_detected",
                data = mapOf(
                    "pattern_label" to intent.patternLabel,
                    "start_time" to intent.startTime?.toString(),
                    "end_time" to intent.endTime?.toString(),
                    "keyword_search" to intent.keywordSearch,
                    "session_id_filter" to intent.sessionIdFilter,
                    "interlocutor_id_filter" to intent.interlocutorIdFilter,
                    "user_message_preview" to intent.latestUserMessagePreview,
                )
            )
        )

        val query = LogbookQuery(
            startTime = intent.startTime,
            endTime = intent.endTime,
            keywordSearch = intent.keywordSearch,
            maxResults = config.logbook.episodicRecallMaxResults,
            sessionId = intent.sessionIdFilter,
            interlocutorId = intent.interlocutorIdFilter,
        )

        val recall = try {
            lb.query(query)
        } catch (ex: Exception) {
            logger.debug(ex) { "Episodic logbook query failed for pattern=${intent.patternLabel}." }
            return ""
        }

        if (recall.entries.isEmpty()) return ""

        val formatted = formatEpisodicRecall(recall)

        instrumentation.emit(
            AgentEvent(
                type = "episodic_recall_result",
                data = mapOf(
                    "pattern_label" to intent.patternLabel,
                    "entries_returned" to recall.entries.size,
                    "total_matched" to recall.totalMatched,
                    "truncated" to recall.truncated,
                    "formatted_chars" to formatted.length,
                    "recall_text_preview" to formatted,
                )
            )
        )

        return formatted
    }

    /**
     * Extracts episodic summaries as vector recall cues when temporal intent is detected.
     * Returns summaries from INPUT_RECEIVED and CONTACT_DELIVERED entries, suitable as
     * cues for [Hippocampus.recall].
     */
    fun recallEpisodicAsVectorCues(recentDialogue: List<DialogueTurn>): List<String> {
        val lb = logbook ?: return emptyList()
        val intent = detectTemporalRecallIntent(recentDialogue) ?: return emptyList()

        val query = LogbookQuery(
            startTime = intent.startTime,
            endTime = intent.endTime,
            keywordSearch = intent.keywordSearch,
            eventTypes = VECTOR_CUE_EVENT_TYPES,
            maxResults = config.logbook.episodicRecallMaxResults,
            sessionId = intent.sessionIdFilter,
            interlocutorId = intent.interlocutorIdFilter,
        )

        val recall = try {
            lb.query(query)
        } catch (ex: Exception) {
            logger.debug(ex) { "Episodic vector cue query failed for pattern=${intent.patternLabel}." }
            return emptyList()
        }

        return recall.entries
            .map { it.summary }
            .filter { it.isNotBlank() }
            .take(MAX_EPISODIC_VECTOR_CUES)
    }

    private fun journalSafe(
        eventType: EpisodicEventType,
        summary: String,
        keywords: List<String> = emptyList(),
        actionType: String? = null,
        metadata: Map<String, Any?>? = null,
    ) {
        val lb = logbook ?: return
        val normalizedSummary = TextSecurity.preview(
            LogbookNarrative.normalizeSummary(eventType, summary),
            config.logbook.maxSummaryChars
        )
        if (normalizedSummary.isBlank()) return
        try {
            lb.record(
                LogbookEntry(
                    ts = Instant.now(),
                    eventType = eventType,
                    summary = normalizedSummary,
                    keywords = keywords,
                    actionType = actionType,
                    runId = runId,
                    metadata = metadata,
                    sessionId = activeSessionId,
                    interlocutorId = activeInterlocutor.id,
                )
            )
        } catch (ex: Exception) {
            logger.debug(ex) { "Logbook record failed for event_type=${eventType.dbValue()}." }
        }
    }

    private fun buildReflectionTags(action: PendingAction, keywords: List<String>): List<String> =
        (
            listOfNotNull(
                "session:$activeSessionId",
                "interlocutor:${activeInterlocutor.id}",
                "self_initiated",
                action.origin.needId?.let { "need:$it" },
                action.origin.rootImpulseId?.let { "root_impulse:$it" },
                action.rootInputId?.let { "root_input:$it" },
            ) + keywords
        ).distinct()

    private fun buildReflectionMetadata(action: PendingAction): Map<String, Any?> =
        buildMap {
            put("self_initiated", true)
            action.origin.needId?.let { put("need_id", it) }
            action.origin.rootImpulseId?.let { put("root_impulse_id", it) }
            action.rootInputId?.let { put("root_input_id", it) }
        }

    fun resetForNewInput() {
        sessionStates.values.forEach { state ->
            state.lastConsolidationStep = 0
            state.explicitIntentAssessmentTriggeredForInput = false
        }
    }

    /** Removes the per-session short-term memory store and session state for the given session. */
    fun destroySession(sessionId: String) {
        sessionMemoryStores.remove(sessionId)
        sessionStates.remove(sessionId)
    }

    // --- Private helpers ---

    private fun recallMemory(
        trigger: EgoTrigger,
        shortTermSummary: String,
        recentDialogue: List<DialogueTurn>,
        episodicCues: List<String> = emptyList(),
        ambientContext: AmbientContext = AmbientContext(),
    ): String {
        if (!hippocampus.enabled) return ""
        val triggerLabel = when (trigger) {
            is EgoTrigger.IncomingInput -> "input"
            is EgoTrigger.PendingThoughtInput -> "thought"
            is EgoTrigger.IncomingImpulse -> "impulse"
        }
        val cue = when (trigger) {
            is EgoTrigger.IncomingInput -> buildRecallCue(trigger, recentDialogue, episodicCues).trim()
            is EgoTrigger.IncomingImpulse -> {
                val baseCue = trigger.impulse.prompt.trim()
                buildImpulseRecallCue(baseCue, trigger.impulse.needId, ambientContext)
            }
            is EgoTrigger.PendingThoughtInput -> {
                val query = trigger.thought.longTermMemoryRecallQuery?.trim().orEmpty()
                if (query.isBlank()) {
                    instrumentation.emit(
                        AgentEvents.longTermMemoryRecallSkipped(trigger = triggerLabel, reason = "missing_explicit_query")
                    )
                    return ""
                }
                val normalized = TextSecurity.clamp(query, config.planner.maxThoughtChars)
                instrumentation.emit(
                    AgentEvents.longTermMemoryRecallRequested(
                        trigger = triggerLabel,
                        source = "thought",
                        queryPreview = TextSecurity.preview(normalized, 180)
                    )
                )
                normalized
            }
        }
        if (cue.isBlank()) return ""
        instrumentation.emit(
            AgentEvents.memoryRecallStart(
                trigger = triggerLabel,
                provider = hippocampus.providerName,
                cuePreview = TextSecurity.preview(cue, 180)
            )
        )
        val startedAt = System.nanoTime()
        return try {
            val recall = hippocampus.recall(
                MemoryRecallQuery(
                    cue = cue,
                    recentDialogue = recentDialogue,
                    shortTermContextSummary = shortTermSummary,
                    maxItems = config.memory.longTermMemoryRecallMaxItems,
                    maxChars = config.memory.longTermMemoryRecallMaxChars
                )
            )
            val recallText = PromptInjectionDefense.asUntrustedDataBlock(
                text = recall.text,
                maxChars = config.memory.longTermMemoryRecallMaxChars
            )
            val recallScan = PromptInjectionDefense.scan(recall.text)
            val latencyMs = (System.nanoTime() - startedAt) / 1_000_000L
            val recallRootInputId = when (trigger) {
                is EgoTrigger.IncomingInput -> trigger.input.rootInputId
                is EgoTrigger.PendingThoughtInput -> trigger.thought.rootInputId
                is EgoTrigger.IncomingImpulse -> trigger.impulse.rootImpulseId
            }
            instrumentation.emit(
                AgentEvents.memoryRecallResult(
                    trigger = triggerLabel,
                    provider = recall.provider.ifBlank { hippocampus.providerName },
                    hitCount = recall.hitCount,
                    latencyMs = latencyMs,
                    recallChars = recallText.length,
                    truncated = recall.truncated,
                    recallTextPreview = recallText,
                    rootInputId = recallRootInputId,
                )
            )
            if (recallScan.suspicious) {
                instrumentation.emit(
                    AgentEvents.warning(
                        "Prompt-injection signals detected in long-term memory recall: ${recallScan.signalIds.sorted().joinToString(",")}."
                    )
                )
            }
            emitServerMetrics()
            recallText
        } catch (ex: Exception) {
            val latencyMs = (System.nanoTime() - startedAt) / 1_000_000L
            logger.warn(ex) { "Memory recall failed for trigger=$triggerLabel cue='${TextSecurity.preview(cue, 120)}'." }
            instrumentation.emit(
                AgentEvents.memoryRecallFailure(
                    trigger = triggerLabel,
                    provider = hippocampus.providerName,
                    latencyMs = latencyMs,
                    reason = ex.message ?: "memory recall failed"
                )
            )
            emitServerMetrics()
            ""
        }
    }

    private fun buildRecallCue(
        trigger: EgoTrigger,
        recentDialogue: List<DialogueTurn>,
        episodicCues: List<String> = emptyList(),
    ): String {
        val triggerCue = when (trigger) {
            is EgoTrigger.IncomingInput -> trigger.input.content.trim()
            is EgoTrigger.PendingThoughtInput -> ""
            is EgoTrigger.IncomingImpulse -> trigger.impulse.prompt.trim()
        }
        val recentUserTurn = recentDialogue
            .asReversed()
            .firstOrNull { it.role == DialogueRole.USER }
            ?.content
            ?.trim()
            .orEmpty()
        return listOfNotNull(
            triggerCue.ifBlank { null },
            recentUserTurn.takeIf { it.isNotBlank() && it != triggerCue }?.let { "latest_user_message: $it" },
            episodicCues.takeIf { it.isNotEmpty() }?.let { "temporal_context: ${it.joinToString(" | ")}" },
        ).joinToString(separator = "\n")
    }

    private fun buildImpulseRecallCue(
        baseCue: String,
        needId: String,
        ambientContext: AmbientContext,
    ): String {
        val renderedAmbientContext = ambientContext.render()
        val learningGuidance = if (needId == LEARNING_NEED_ID && ambientContext.recentExactLearningTopics.isNotEmpty()) {
            "Learning freshness guidance: avoid exact repeats from recent_exact_learning_topics, but deeper follow-up exploration remains valid."
        } else {
            null
        }
        return listOfNotNull(
            baseCue.takeIf { it.isNotBlank() },
            renderedAmbientContext.takeIf { it.isNotBlank() }?.let { "Ambient context:\n$it" },
            learningGuidance,
        ).joinToString(separator = "\n")
    }

    private fun rememberLearningTopic(summary: String, keywords: List<String>) {
        val record = buildLearningTopicRecord(summary, keywords) ?: return
        val topics = activeState().recentLearningTopics
        while (topics.removeAll { it.fingerprint == record.fingerprint }) {
            // Keep the most recent occurrence at the tail.
        }
        topics.addLast(record)
        while (topics.size > MAX_RECENT_LEARNING_TOPICS) {
            topics.removeFirst()
        }
    }

    private fun buildLearningTopicRecord(summary: String, keywords: List<String>): LearningTopicRecord? {
        val normalizedKeywords = keywords
            .map { normalizePayload(it) }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
        val fingerprint = if (normalizedKeywords.isNotEmpty()) {
            "keywords:${normalizedKeywords.joinToString("|")}"
        } else {
            val normalizedSummary = normalizePayload(summary)
            if (normalizedSummary.isBlank()) return null
            "summary:$normalizedSummary"
        }
        val label = if (keywords.isNotEmpty()) {
            keywords
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(", ")
                .takeIf { it.isNotBlank() }
                ?: TextSecurity.preview(summary, LEARNING_TOPIC_LABEL_MAX_CHARS)
        } else {
            TextSecurity.preview(summary, LEARNING_TOPIC_LABEL_MAX_CHARS)
        }
        if (label.isBlank()) return null
        return LearningTopicRecord(fingerprint = fingerprint, label = label)
    }

    private fun buildLessonCue(trigger: EgoTrigger, recentDialogue: List<DialogueTurn>): String {
        val latestUserTurn = recentDialogue
            .asReversed()
            .firstOrNull { it.role == DialogueRole.USER }
            ?.content
            ?.trim()
            .orEmpty()
        val deniedContext = when (trigger) {
            is EgoTrigger.IncomingInput -> null
            is EgoTrigger.IncomingImpulse -> null
            is EgoTrigger.PendingThoughtInput -> {
                val thought = trigger.thought
                if (thought.deniedActionType == null && thought.denialReasonCode.isNullOrBlank()) {
                    null
                } else {
                    listOfNotNull(
                        thought.deniedActionType?.name?.lowercase()?.let { "denied_action_type: $it" },
                        thought.denialReasonCode?.trim()?.takeIf { it.isNotBlank() }?.let { "denial_reason_code: $it" },
                        thought.denialReason?.trim()?.takeIf { it.isNotBlank() }?.let { "denial_reason: ${TextSecurity.preview(it, 140)}" }
                    ).joinToString("\n")
                }
            }
        }
        val cueParts = listOfNotNull(
            "LESSON retrieval",
            latestUserTurn.takeIf { it.isNotBlank() }?.let { "latest_user_message: ${TextSecurity.preview(it, 220)}" },
            deniedContext
        )
        return cueParts.joinToString(separator = "\n")
    }

    private fun shouldSkipLesson(reasonCode: String?, reason: String): Boolean {
        val normalizedCode = reasonCode?.trim()?.uppercase(Locale.ROOT).orEmpty()
        if (normalizedCode.startsWith("SYSTEM_")) {
            return true
        }
        if (DenialReasonClassifier.isLikelyTechnical(reasonCode, reason)) {
            return true
        }
        val normalizedReason = reason.lowercase(Locale.ROOT)
        return LESSON_TECHNICAL_TEXT_SIGNALS.any { normalizedReason.contains(it) }
    }

    private fun buildLesson(
        latestUserTurn: String,
        actionType: ActionType?,
        reasonCode: String?,
        reason: String,
        deniedPayload: String?,
    ): String {
        val actionLabel = actionType?.name?.lowercase() ?: "unknown_action"
        val codeLabel = reasonCode?.trim()?.ifBlank { "none" } ?: "none"
        val payloadPreview = deniedPayload
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { TextSecurity.preview(it, LESSON_DENIED_PAYLOAD_PREVIEW_CHARS) }
            ?: "n/a"
        return TextSecurity.clamp(
            "LESSON: For requests like '${TextSecurity.preview(latestUserTurn, LESSON_USER_TURN_PREVIEW_CHARS)}', " +
                "avoid repeating denied $actionLabel actions (reason_code=$codeLabel, reason='${TextSecurity.preview(reason, 140)}', " +
                "payload='$payloadPreview'). Choose a materially different safe step with explicit evidence when needed.",
            LESSON_MAX_CHARS
        )
    }

    private fun normalizePayload(payload: String): String =
        payload.lowercase().replace(Regex("\\s+"), " ").trim()

    private fun evaluateRecallEcho(summary: String): RecallEchoEvaluation {
        val canonicalSummary = canonicalizeForComparison(summary)
        if (canonicalSummary.length < config.memory.longTermMemoryRecallEchoMinSummaryChars) {
            return RecallEchoEvaluation(isEcho = false)
        }
        val canonicalRecall = canonicalizeForComparison(activeState().latestLongTermRecall)
        if (canonicalRecall.isBlank()) return RecallEchoEvaluation(isEcho = false)
        if (canonicalRecall.contains(canonicalSummary)) {
            return RecallEchoEvaluation(
                isEcho = true,
                mode = "containment"
            )
        }

        val summaryTokens = canonicalSummary
            .split(' ')
            .asSequence()
            .map { it.trim() }
            .filter { it.length >= config.memory.longTermMemoryRecallEchoMinTokenLength }
            .toSet()
        if (summaryTokens.size < config.memory.longTermMemoryRecallEchoMinTokenCount) {
            return RecallEchoEvaluation(
                isEcho = false,
                summaryTokenCount = summaryTokens.size
            )
        }

        val recallTokens = canonicalRecall
            .split(' ')
            .asSequence()
            .map { it.trim() }
            .filter { it.length >= config.memory.longTermMemoryRecallEchoMinTokenLength }
            .toSet()
        if (recallTokens.isEmpty()) {
            return RecallEchoEvaluation(
                isEcho = false,
                summaryTokenCount = summaryTokens.size,
                recallTokenCount = 0
            )
        }

        val overlap = summaryTokens.count { it in recallTokens }.toDouble() / summaryTokens.size.toDouble()
        return RecallEchoEvaluation(
            isEcho = overlap >= config.memory.longTermMemoryRecallEchoTokenOverlapThreshold,
            mode = "token_overlap",
            overlapRatio = overlap,
            summaryTokenCount = summaryTokens.size,
            recallTokenCount = recallTokens.size
        )
    }

    private fun canonicalizeForComparison(text: String): String =
        text
            .lowercase(Locale.ROOT)
            .replace(RECALL_ECHO_NON_ALPHANUMERIC_REGEX, " ")
            .replace(RECALL_ECHO_WHITESPACE_REGEX, " ")
            .trim()

    private fun detectExplicitRememberIntent(recentDialogue: List<DialogueTurn>): ExplicitRememberIntent? {
        val latestUserTurn = recentDialogue
            .asReversed()
            .firstOrNull { it.role == DialogueRole.USER }
            ?.content
            ?.trim()
            .orEmpty()
        if (latestUserTurn.isBlank()) return null
        val normalized = normalizePayload(latestUserTurn)
        val matchedPattern = EXPLICIT_REMEMBER_INTENT_PATTERNS.firstOrNull { it.regex.containsMatchIn(normalized) }
            ?: return null
        return ExplicitRememberIntent(
            patternLabel = matchedPattern.label,
            latestUserMessagePreview = TextSecurity.preview(latestUserTurn, EXPLICIT_INTENT_PREVIEW_MAX_CHARS)
        )
    }

    private fun detectTemporalRecallIntent(recentDialogue: List<DialogueTurn>): TemporalRecallIntent? {
        val latestUserTurn = recentDialogue
            .asReversed()
            .firstOrNull { it.role == DialogueRole.USER }
            ?.content
            ?.trim()
            .orEmpty()
        if (latestUserTurn.isBlank()) return null
        val normalized = normalizePayload(latestUserTurn)
        val matchedPattern = TEMPORAL_RECALL_INTENT_PATTERNS.firstOrNull { it.regex.containsMatchIn(normalized) }
            ?: return null

        val now = Instant.now()
        val (startTime, endTime) = resolveTemporalWindow(matchedPattern.label, normalized, now)
        val keywordSearch = extractTopicKeyword(normalized)
        val sessionIdFilter = resolveSessionFilter(normalized)
        val interlocutorIdFilter = resolveInterlocutorFilter(normalized)

        return TemporalRecallIntent(
            patternLabel = matchedPattern.label,
            startTime = startTime,
            endTime = endTime,
            keywordSearch = keywordSearch,
            sessionIdFilter = sessionIdFilter,
            interlocutorIdFilter = interlocutorIdFilter,
            latestUserMessagePreview = TextSecurity.preview(latestUserTurn, TEMPORAL_INTENT_PREVIEW_MAX_CHARS),
        )
    }

    private fun resolveTemporalWindow(
        patternLabel: String,
        normalizedText: String,
        now: Instant,
    ): Pair<Instant?, Instant?> {
        return when (patternLabel) {
            "relative_time_earlier" -> {
                val minutes = when {
                    normalizedText.contains("minutes") -> WINDOW_MINUTES_SHORT
                    normalizedText.contains("hours") -> WINDOW_MINUTES_MEDIUM
                    else -> WINDOW_MINUTES_MEDIUM
                }
                Pair(now.minus(Duration.ofMinutes(minutes)), now)
            }
            "relative_time_period" -> when {
                normalizedText.contains("yesterday") ->
                    Pair(now.minus(Duration.ofHours(WINDOW_HOURS_YESTERDAY_START)), now.minus(Duration.ofHours(WINDOW_HOURS_YESTERDAY_END)))
                normalizedText.contains("last week") ->
                    Pair(now.minus(Duration.ofDays(WINDOW_DAYS_WEEK)), now)
                normalizedText.contains("last hour") ->
                    Pair(now.minus(Duration.ofHours(1)), now)
                normalizedText.contains("last month") ->
                    Pair(now.minus(Duration.ofDays(WINDOW_DAYS_MONTH)), now)
                normalizedText.contains("last night") ->
                    Pair(now.minus(Duration.ofHours(WINDOW_HOURS_LAST_NIGHT_START)), now.minus(Duration.ofHours(WINDOW_HOURS_LAST_NIGHT_END)))
                normalizedText.contains("last session") || normalizedText.contains("last time") ->
                    Pair(now.minus(Duration.ofHours(WINDOW_HOURS_DEFAULT)), now)
                else ->
                    Pair(now.minus(Duration.ofHours(WINDOW_HOURS_DEFAULT)), now)
            }
            "absolute_time_reference" ->
                Pair(now.minus(Duration.ofHours(WINDOW_HOURS_DEFAULT)), now)
            "what_did_i_ask", "what_did_you_do", "what_happened",
            "summarize_session", "topic_recall", "working_on_recall" ->
                Pair(now.minus(Duration.ofHours(WINDOW_HOURS_DEFAULT)), now)
            else -> Pair(null, null)
        }
    }

    private fun extractTopicKeyword(normalizedText: String): String? {
        val aboutMatch = TOPIC_KEYWORD_REGEX.find(normalizedText) ?: return null
        val topic = aboutMatch.groupValues.getOrNull(1)?.trim() ?: return null
        return topic.takeIf { it.length in TOPIC_KEYWORD_MIN_LENGTH..TOPIC_KEYWORD_MAX_LENGTH }
    }

    private fun resolveSessionFilter(normalizedText: String): String? {
        val thisSessionRequested = THIS_SESSION_SCOPE_REGEX.containsMatchIn(normalizedText)
        if (thisSessionRequested) {
            return activeSessionId
        }
        val explicitSessionId = EXPLICIT_SESSION_SCOPE_REGEX
            .find(normalizedText)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() && it !in RESERVED_SESSION_SCOPE_TOKENS }
        return explicitSessionId
    }

    private fun resolveInterlocutorFilter(normalizedText: String): String? {
        val thisInterlocutorRequested = THIS_INTERLOCUTOR_SCOPE_REGEX.containsMatchIn(normalizedText)
        if (thisInterlocutorRequested) {
            return activeInterlocutor.id
        }
        val explicitInterlocutor = EXPLICIT_INTERLOCUTOR_SCOPE_REGEX
            .find(normalizedText)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        return explicitInterlocutor
    }

    private fun formatEpisodicRecall(recall: LogbookRecall): String {
        val lines = recall.entries.map { entry ->
            val ts = entry.ts.toString().substringBefore('.')
            val event = entry.eventType.dbValue()
            val summary = TextSecurity.preview(entry.summary, EPISODIC_RECALL_ENTRY_MAX_CHARS)
            val action = entry.actionType?.let { " [$it]" } ?: ""
            val sessionTag = entry.sessionId?.let { " session:$it" } ?: ""
            val interlocutorTag = entry.interlocutorId?.let { " interlocutor:$it" } ?: ""
            val contextPrefix = if (sessionTag.isNotEmpty() || interlocutorTag.isNotEmpty()) {
                " [${sessionTag.trim()}${interlocutorTag}]"
            } else ""
            "$ts $event$action$contextPrefix: $summary"
        }
        val truncationNote = if (recall.truncated) ", truncated" else ""
        val header = "Episodic timeline (${recall.entries.size} of ${recall.totalMatched} events$truncationNote):"
        val body = lines.joinToString("\n")
        val full = "$header\n$body"
        return TextSecurity.clamp(full, config.logbook.episodicRecallMaxChars)
    }

    private fun emitMemoryPersistenceSkipped(
        trigger: String,
        stepIndex: Int,
        reasonCode: String,
        reasonDetail: String,
        decision: ai.neopsyke.agent.memory.longterm.LongTermMemoryAssessmentDecision?,
        extra: Map<String, Any?> = emptyMap(),
    ) {
        val confidenceLabel = decision?.let { String.format(Locale.ROOT, "%.3f", it.confidence) } ?: "n/a"
        logger.info {
            "long_term_memory.persistence.skipped trigger=$trigger step=$stepIndex reason_code=$reasonCode detail='${TextSecurity.preview(reasonDetail, 220)}' confidence=$confidenceLabel"
        }
        val payload = linkedMapOf<String, Any?>(
            "trigger" to trigger,
            "step_index" to stepIndex,
            "reason_code" to reasonCode,
            "reason_detail" to reasonDetail
        )
        if (decision != null) {
            payload["decision_confidence"] = decision.confidence
            payload["decision_reason"] = decision.reason
            payload["summary_preview"] = TextSecurity.preview(decision.summary, 180)
        }
        payload.putAll(extra)
        instrumentation.emit(
            AgentEvent(
                type = "long_term_memory_persistence_skipped",
                data = payload
            )
        )
    }

    /**
     * Fetches server-side metrics from the memory backend and emits them as an
     * instrumentation event. Called after every recall and imprint operation so
     * the dashboard receives an up-to-date snapshot without polling.
     */
    private fun emitServerMetrics() {
        try {
            val serverMetrics = hippocampus.fetchServerMetrics() ?: return
            instrumentation.emit(
                AgentEvent(
                    type = "memory_server_metrics",
                    data = serverMetrics
                )
            )
        } catch (ex: Exception) {
            logger.debug(ex) { "Failed to emit server-side memory metrics." }
        }
    }

    private data class ExplicitRememberIntent(
        val patternLabel: String,
        val latestUserMessagePreview: String,
    )

    private data class TemporalRecallIntent(
        val patternLabel: String,
        val startTime: Instant?,
        val endTime: Instant?,
        val keywordSearch: String?,
        val sessionIdFilter: String? = null,
        val interlocutorIdFilter: String? = null,
        val latestUserMessagePreview: String,
    )

    private data class RecallEchoEvaluation(
        val isEcho: Boolean,
        val mode: String? = null,
        val overlapRatio: Double? = null,
        val summaryTokenCount: Int = 0,
        val recallTokenCount: Int = 0,
    )

    private data class IntentPattern(
        val label: String,
        val regex: Regex,
    )

    private companion object {
        const val EXPLICIT_REMEMBER_INTENT_TRIGGER: String = "explicit_remember_intent"
        const val EXPLICIT_INTENT_PREVIEW_MAX_CHARS: Int = 180
        const val REASON_CODE_ADVISOR_DECLINED: String = "advisor_declined_save"
        const val REASON_CODE_ASSESSMENT_EXCEPTION: String = "assessment_exception"
        const val REASON_CODE_PARSE_FALLBACK: String = "assessment_parse_fallback"
        const val REASON_CODE_CONFIDENCE_BELOW_THRESHOLD: String = "confidence_below_threshold"
        const val REASON_CODE_RECALL_ECHO: String = "recall_echo_suppression"
        const val REASON_CODE_DUPLICATE_FINGERPRINT: String = "duplicate_recent_fingerprint"
        const val MAX_RECENT_IMPRINT_FINGERPRINTS: Int = 24
        const val MAX_TRACKED_SESSIONS: Int = 128
        const val LESSON_RECALL_MAX_ITEMS: Int = 3
        const val LESSON_RECALL_MAX_CHARS: Int = 800
        const val LESSON_MAX_CHARS: Int = 420
        const val LESSON_USER_TURN_PREVIEW_CHARS: Int = 140
        const val LESSON_DENIED_PAYLOAD_PREVIEW_CHARS: Int = 120
        const val MAX_RECENT_LESSON_FINGERPRINTS: Int = 24
        const val MAX_RECENT_LEARNING_TOPICS: Int = 8
        const val LEARNING_TOPIC_LABEL_MAX_CHARS: Int = 120
        const val LESSON_DEFAULT_CONFIDENCE: Double = 0.72
        const val REFLECTION_DEFAULT_CONFIDENCE: Double = 0.6
        const val LEARNING_NEED_ID: String = "learn-something"
        const val MAX_AMBIENT_USEFUL_UPDATES: Int = 6
        const val AMBIENT_EVENT_PREVIEW_CHARS: Int = 180
        val USEFUL_AMBIENT_EVENT_TYPES: Set<EpisodicEventType> = setOf(
            EpisodicEventType.CONTACT_DELIVERED,
            EpisodicEventType.ACTION_EXECUTED,
            EpisodicEventType.SELF_INITIATED,
        )
        val LESSON_TECHNICAL_TEXT_SIGNALS: Set<String> = setOf(
            "tool error",
            "tool failed",
            "external tool",
            "provider error",
            "model error",
            "parse",
            "json",
            "timeout",
            "timed out",
            "unavailable",
            "transport",
            "llm",
            "http "
        )

        // --- Temporal recall intent constants ---
        const val TEMPORAL_INTENT_PREVIEW_MAX_CHARS: Int = 180
        const val EPISODIC_RECALL_ENTRY_MAX_CHARS: Int = 120
        const val TOPIC_KEYWORD_MIN_LENGTH: Int = 2
        const val TOPIC_KEYWORD_MAX_LENGTH: Int = 80
        const val MAX_EPISODIC_VECTOR_CUES: Int = 5
        const val WINDOW_MINUTES_SHORT: Long = 30
        const val WINDOW_MINUTES_MEDIUM: Long = 120
        const val WINDOW_HOURS_DEFAULT: Long = 24
        const val WINDOW_HOURS_YESTERDAY_START: Long = 48
        const val WINDOW_HOURS_YESTERDAY_END: Long = 24
        const val WINDOW_HOURS_LAST_NIGHT_START: Long = 18
        const val WINDOW_HOURS_LAST_NIGHT_END: Long = 6
        const val WINDOW_DAYS_WEEK: Long = 7
        const val WINDOW_DAYS_MONTH: Long = 30

        val RECALL_ECHO_NON_ALPHANUMERIC_REGEX: Regex = Regex("[^a-z0-9]+")
        val RECALL_ECHO_WHITESPACE_REGEX: Regex = Regex("\\s+")
        val TOPIC_KEYWORD_REGEX: Regex = Regex("""\babout\s+(.+?)(?:\?|$)""")
        val THIS_SESSION_SCOPE_REGEX: Regex = Regex("""\b(this|current)\s+(session|conversation|chat)\b""")
        val EXPLICIT_SESSION_SCOPE_REGEX: Regex = Regex("""\bsession\s*(?:id)?\s*[:=]?\s*([a-z0-9._-]{2,80})\b""")
        val THIS_INTERLOCUTOR_SCOPE_REGEX: Regex = Regex("""\b(this|current)\s+interlocutor\b""")
        val EXPLICIT_INTERLOCUTOR_SCOPE_REGEX: Regex = Regex("""\binterlocutor\s*[:=]?\s*([a-z0-9._-]{2,80})\b""")
        val RESERVED_SESSION_SCOPE_TOKENS: Set<String> = setOf("last", "this", "current", "previous")

        val VECTOR_CUE_EVENT_TYPES: Set<EpisodicEventType> = setOf(
            EpisodicEventType.INPUT_RECEIVED,
            EpisodicEventType.CONTACT_DELIVERED,
        )

        val EXPLICIT_REMEMBER_INTENT_PATTERNS: List<IntentPattern> = listOf(
            IntentPattern(
                label = "remember_directive",
                regex = Regex("""^(?:please\s+)?remember\b""")
            ),
            IntentPattern(
                label = "name_declaration",
                regex = Regex("""\b(my name is|call me)\b""")
            ),
            IntentPattern(
                label = "retention_directive",
                regex = Regex("""\b(don't forget|do not forget)\b""")
            )
        )

        val TEMPORAL_RECALL_INTENT_PATTERNS: List<IntentPattern> = listOf(
            IntentPattern("what_did_i_ask",
                Regex("""\bwhat did (i|we) (ask|say|discuss|talk about|mention|request)\b""")),
            IntentPattern("what_did_you_do",
                Regex("""\bwhat did you (do|say|answer|respond|suggest|recommend)\b""")),
            IntentPattern("what_happened",
                Regex("""\bwhat (happened|was (happening|going on|discussed))\b""")),
            IntentPattern("summarize_session",
                Regex("""\b(summarize|summary of|recap|review)\b.*(session|conversation|work|discussion|chat|history)\b""")),
            IntentPattern("relative_time_earlier",
                Regex("""\b(earlier|previously|before this|a while ago|recently|a (few|couple) (minutes?|hours?|moments?) ago)\b""")),
            IntentPattern("relative_time_period",
                Regex("""\b(yesterday|last (week|hour|night|month|session|time)|today|this (morning|afternoon|evening|week))\b""")),
            IntentPattern("absolute_time_reference",
                Regex("""\b(at \d{1,2}(:\d{2})?\s*(am|pm)?|on (monday|tuesday|wednesday|thursday|friday|saturday|sunday))\b""")),
            IntentPattern("topic_recall",
                Regex("""\b(when did (i|we) (ask|talk|discuss)|what did (i|we) (discuss|say) about)\b""")),
            IntentPattern("working_on_recall",
                Regex("""\bwhat was (i|we) (working on|doing)\b""")),
        )
    }
}
