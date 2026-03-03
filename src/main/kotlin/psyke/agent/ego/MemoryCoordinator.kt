package psyke.agent.ego

import mu.KotlinLogging
import psyke.agent.core.AgentConfig
import psyke.agent.core.ActionType
import psyke.agent.core.DeliberationState
import psyke.agent.core.DialogueTurn
import psyke.agent.core.EgoTrigger
import psyke.agent.core.DialogueRole
import psyke.agent.memory.longterm.Hippocampus
import psyke.agent.memory.longterm.LongTermMemoryAdvisor
import psyke.agent.memory.longterm.LongTermMemoryAssessmentContext
import psyke.agent.memory.longterm.MemoryImprint
import psyke.agent.memory.longterm.MemoryRecallQuery
import psyke.agent.memory.shortterm.MemoryStore
import psyke.agent.support.PromptInjectionDefense
import psyke.agent.support.TextSecurity
import psyke.instrumentation.AgentEvent
import psyke.instrumentation.AgentEvents
import psyke.instrumentation.AgentInstrumentation
import java.util.Locale

private val logger = KotlinLogging.logger {}

/**
 * Manages short-term and long-term memory operations for the Ego agent loop.
 * Extracted from Ego to separate memory coordination concerns.
 */
internal class MemoryCoordinator(
    private val hippocampus: Hippocampus,
    private val longTermMemoryAdvisor: LongTermMemoryAdvisor,
    private val config: AgentConfig,
    private val instrumentation: AgentInstrumentation,
    private val memoryStore: MemoryStore,
) {
    private var lastConsolidationStep: Int = 0
    private var latestShortTermSummary: String = ""
    private var latestLongTermRecall: String = ""
    private var parseFallbackStreak: Int = 0
    private var assessmentTemporarilyDisabled: Boolean = false
    private var explicitIntentAssessmentTriggeredForInput: Boolean = false
    private val recentImprintFingerprints = ArrayDeque<String>()

    // --- Short-term memory delegation ---

    fun remember(turn: DialogueTurn) = memoryStore.remember(turn)

    fun currentShortTermSummary(): String {
        val memoryTokenBudget = minOf(
            config.memory.maxShortTermContextPromptTokens,
            maxOf(64, config.planner.maxPromptTokens / 3)
        )
        return memoryStore.summaryForPrompt(memoryTokenBudget)
    }

    // --- Long-term memory recall ---

    /**
     * Recalls long-term memory for the given trigger. Stores results internally for later
     * use by [maybeAssessLongTermMemory].
     */
    fun recall(trigger: EgoTrigger, shortTermSummary: String, recentDialogue: List<DialogueTurn>): String {
        val text = recallMemory(trigger, shortTermSummary, recentDialogue)
        latestShortTermSummary = shortTermSummary
        latestLongTermRecall = text
        return text
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
        if (!hippocampus.enabled || !longTermMemoryAdvisor.enabled || assessmentTemporarilyDisabled) return
        val stepIndex = deliberation.stepIndex
        val explicitIntent = detectExplicitRememberIntent(recentDialogue)
        val forcedByExplicitIntent = !force &&
            !explicitIntentAssessmentTriggeredForInput &&
            explicitIntent != null
        val shouldByInterval = !force &&
            !forcedByExplicitIntent &&
            stepIndex > 0 &&
            stepIndex % config.memory.longTermMemoryAssessEverySteps == 0
        if (!force && !forcedByExplicitIntent && !shouldByInterval) return
        val effectiveForce = force || forcedByExplicitIntent
        if (!effectiveForce) {
            val stepsSinceLast = stepIndex - lastConsolidationStep
            if (stepsSinceLast in 0 until config.memory.longTermMemoryAssessCooldownSteps) return
        }
        if (stepIndex == lastConsolidationStep && lastConsolidationStep > 0) return

        val effectiveTrigger = if (forcedByExplicitIntent) EXPLICIT_REMEMBER_INTENT_TRIGGER else trigger
        if (forcedByExplicitIntent) {
            explicitIntentAssessmentTriggeredForInput = true
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
            shortTermContextSummary = latestShortTermSummary.ifBlank { currentShortTermSummary() },
            longTermMemoryRecall = latestLongTermRecall,
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
            lastConsolidationStep = stepIndex
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
            parseFallbackStreak += 1
            emitMemoryPersistenceSkipped(
                trigger = effectiveTrigger,
                stepIndex = stepIndex,
                reasonCode = REASON_CODE_PARSE_FALLBACK,
                reasonDetail = "Advisor response parse fallback blocked persistence for this cycle (streak=$parseFallbackStreak, disable_after=${config.memory.longTermMemoryParseFallbackDisableAfter}).",
                decision = decision,
                extra = mapOf(
                    "parse_fallback_streak" to parseFallbackStreak,
                    "parse_fallback_disable_after" to config.memory.longTermMemoryParseFallbackDisableAfter
                )
            )
            instrumentation.emit(
                AgentEvents.longTermMemoryAssessmentParseFallback(
                    trigger = effectiveTrigger,
                    stepIndex = stepIndex,
                    streak = parseFallbackStreak
                )
            )
            if (parseFallbackStreak >= config.memory.longTermMemoryParseFallbackDisableAfter) {
                assessmentTemporarilyDisabled = true
                instrumentation.emit(
                    AgentEvents.longTermMemoryAssessmentTemporarilyDisabled(
                        trigger = effectiveTrigger,
                        stepIndex = stepIndex,
                        streak = parseFallbackStreak,
                        threshold = config.memory.longTermMemoryParseFallbackDisableAfter
                    )
                )
                instrumentation.emit(AgentEvents.warning("Long-term memory assessment disabled for this run after repeated parse fallbacks."))
            }
            return
        } else {
            parseFallbackStreak = 0
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
        if (recentImprintFingerprints.contains(fingerprint)) {
            emitMemoryPersistenceSkipped(
                trigger = effectiveTrigger,
                stepIndex = stepIndex,
                reasonCode = REASON_CODE_DUPLICATE_FINGERPRINT,
                reasonDetail = "Duplicate imprint summary fingerprint matched a recent saved entry.",
                decision = decision
            )
            return
        }

        val imprintStartedAt = System.nanoTime()
        val saved = try {
            hippocampus.imprint(
                MemoryImprint(
                    summary = decision.summary,
                    source = effectiveTrigger,
                    confidence = decision.confidence,
                    tags = decision.tags
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
            recentImprintFingerprints.addLast(fingerprint)
            while (recentImprintFingerprints.size > 24) {
                recentImprintFingerprints.removeFirst()
            }
        }
        emitServerMetrics()
    }

    fun resetForNewInput() {
        lastConsolidationStep = 0
        explicitIntentAssessmentTriggeredForInput = false
    }

    // --- Private helpers ---

    private fun recallMemory(
        trigger: EgoTrigger,
        shortTermSummary: String,
        recentDialogue: List<DialogueTurn>,
    ): String {
        if (!hippocampus.enabled) return ""
        val triggerLabel = when (trigger) {
            is EgoTrigger.IncomingInput -> "input"
            is EgoTrigger.PendingThoughtInput -> "thought"
        }
        val cue = when (trigger) {
            is EgoTrigger.IncomingInput -> buildRecallCue(trigger, recentDialogue).trim()
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
            instrumentation.emit(
                AgentEvents.memoryRecallResult(
                    trigger = triggerLabel,
                    provider = recall.provider.ifBlank { hippocampus.providerName },
                    hitCount = recall.hitCount,
                    latencyMs = latencyMs,
                    recallChars = recallText.length,
                    truncated = recall.truncated
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

    private fun buildRecallCue(trigger: EgoTrigger, recentDialogue: List<DialogueTurn>): String {
        val triggerCue = when (trigger) {
            is EgoTrigger.IncomingInput -> trigger.input.content.trim()
            is EgoTrigger.PendingThoughtInput -> ""
        }
        val recentUserTurn = recentDialogue
            .asReversed()
            .firstOrNull { it.role == DialogueRole.USER }
            ?.content
            ?.trim()
            .orEmpty()
        return listOfNotNull(
            triggerCue.ifBlank { null },
            recentUserTurn.takeIf { it.isNotBlank() && it != triggerCue }?.let { "latest_user_message: $it" }
        ).joinToString(separator = "\n")
    }

    private fun normalizePayload(payload: String): String =
        payload.lowercase().replace(Regex("\\s+"), " ").trim()

    private fun evaluateRecallEcho(summary: String): RecallEchoEvaluation {
        val canonicalSummary = canonicalizeForComparison(summary)
        if (canonicalSummary.length < config.memory.longTermMemoryRecallEchoMinSummaryChars) {
            return RecallEchoEvaluation(isEcho = false)
        }
        val canonicalRecall = canonicalizeForComparison(latestLongTermRecall)
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

    private fun emitMemoryPersistenceSkipped(
        trigger: String,
        stepIndex: Int,
        reasonCode: String,
        reasonDetail: String,
        decision: psyke.agent.memory.longterm.LongTermMemoryAssessmentDecision?,
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

        val RECALL_ECHO_NON_ALPHANUMERIC_REGEX: Regex = Regex("[^a-z0-9]+")
        val RECALL_ECHO_WHITESPACE_REGEX: Regex = Regex("\\s+")

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
    }
}
