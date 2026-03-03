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
        val shouldByInterval = !force &&
            stepIndex > 0 &&
            stepIndex % config.memory.longTermMemoryAssessEverySteps == 0
        if (!force && !shouldByInterval) return
        if (!force) {
            val stepsSinceLast = stepIndex - lastConsolidationStep
            if (stepsSinceLast in 0 until config.memory.longTermMemoryAssessCooldownSteps) return
        }
        if (stepIndex == lastConsolidationStep && lastConsolidationStep > 0) return

        val context = LongTermMemoryAssessmentContext(
            trigger = trigger,
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
            instrumentation.emit(AgentEvents.warning("Long-term memory assessment failed; skipping this cycle."))
            return
        } finally {
            lastConsolidationStep = stepIndex
        }

        instrumentation.emit(
            AgentEvent(
                type = "long_term_memory_assessment",
                data = mapOf(
                    "trigger" to trigger,
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
            instrumentation.emit(
                AgentEvents.longTermMemoryAssessmentParseFallback(
                    trigger = trigger,
                    stepIndex = stepIndex,
                    streak = parseFallbackStreak
                )
            )
            if (parseFallbackStreak >= config.memory.longTermMemoryParseFallbackDisableAfter) {
                assessmentTemporarilyDisabled = true
                instrumentation.emit(
                    AgentEvents.longTermMemoryAssessmentTemporarilyDisabled(
                        trigger = trigger,
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

        if (!decision.shouldSave) return
        if (decision.confidence < config.memory.longTermMemoryMinConfidence) {
            instrumentation.emit(
                AgentEvents.warning(
                    "Long-term memory persistence skipped: confidence ${String.format(Locale.ROOT, "%.2f", decision.confidence)} below threshold."
                )
            )
            return
        }

        val fingerprint = normalizePayload(decision.summary)
        if (recentImprintFingerprints.contains(fingerprint)) {
            instrumentation.emit(AgentEvents.warning("Long-term memory persistence skipped: duplicate imprint summary."))
            return
        }

        val imprintStartedAt = System.nanoTime()
        val saved = try {
            hippocampus.imprint(
                MemoryImprint(
                    summary = decision.summary,
                    source = trigger,
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
                    "trigger" to trigger,
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
    }

    fun resetForNewInput() {
        lastConsolidationStep = 0
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
}
