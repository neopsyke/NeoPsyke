package ai.neopsyke.agent.ego

import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.DialogueRole
import ai.neopsyke.agent.model.DialogueTurn
import ai.neopsyke.agent.model.PendingAction
import java.util.Locale

internal data class DecisionVerifierContext(
    val recentDialogue: List<DialogueTurn> = emptyList(),
    val externalEvidence: DeliberationEngine.ExternalEvidenceProgress? = null,
    val availableActions: Set<ActionType> = emptySet(),
    val dispatchableActions: Set<ActionType> = emptySet(),
    val evidenceActionTypes: Set<ActionType> = emptySet(),
    val latestUserTurn: String = "",
)

internal enum class TaskIntentCategory {
    VOLATILE_FACT,
    STABLE_FACT,
    TRANSFORMATION,
    PERSONAL_MEMORY,
    SUBJECTIVE_ADVICE,
    STATIC_REASONING,
    UNKNOWN,
}

internal enum class VolatilityLevel {
    HIGH,
    LOW,
    NONE,
}

internal data class DecisionVerifierAssessment(
    val intentCategory: TaskIntentCategory,
    val volatilityLevel: VolatilityLevel,
    val volatilityScore: Int,
    val requiresExternalEvidence: Boolean,
    val evidenceActionsAvailable: Boolean,
    val evidenceActionsDispatchable: Boolean,
    val hadSuccessfulEvidence: Boolean,
    val hadExternalFailures: Boolean,
)

internal data class DecisionVerifierDecision(
    val allow: Boolean,
    val reason: String = "",
    val reasonCode: String? = null,
    val assessment: DecisionVerifierAssessment? = null,
)

internal interface DecisionVerifier {
    fun review(action: PendingAction, context: DecisionVerifierContext): DecisionVerifierDecision
}

internal object NoopDecisionVerifier : DecisionVerifier {
    override fun review(action: PendingAction, context: DecisionVerifierContext): DecisionVerifierDecision =
        DecisionVerifierDecision(allow = true)
}

internal class DeterministicDecisionVerifier : DecisionVerifier {
    override fun review(action: PendingAction, context: DecisionVerifierContext): DecisionVerifierDecision {
        if (action.type != ActionType.CONTACT_USER || action.isFallbackExplanation) {
            return DecisionVerifierDecision(allow = true)
        }
        if (isForcedTerminalAnswer(action.summary)) {
            return DecisionVerifierDecision(allow = true)
        }

        val latestUserTurn = context.latestUserTurn.ifBlank {
            context.recentDialogue
                .asReversed()
                .firstOrNull { it.role == DialogueRole.USER }
                ?.content
                .orEmpty()
        }
        val classification = classifyTaskIntent(
            latestUserTurn = latestUserTurn,
            answerPayload = action.payload
        )
        val evidence = context.externalEvidence
        val evidenceActionsAvailable = context.evidenceActionTypes.any { it in context.availableActions }
        val evidenceActionsDispatchable = context.evidenceActionTypes.any { it in context.dispatchableActions }
        val assessment = DecisionVerifierAssessment(
            intentCategory = classification.intentCategory,
            volatilityLevel = classification.volatilityLevel,
            volatilityScore = classification.volatilityScore,
            requiresExternalEvidence = classification.requiresExternalEvidence,
            evidenceActionsAvailable = evidenceActionsAvailable,
            evidenceActionsDispatchable = evidenceActionsDispatchable,
            hadSuccessfulEvidence = evidence?.hadSuccessfulEvidence == true,
            hadExternalFailures = evidence?.hadExternalFailures == true
        )

        if (!assessment.requiresExternalEvidence) {
            return DecisionVerifierDecision(allow = true, assessment = assessment)
        }

        if (evidence?.hadSuccessfulEvidence == true) {
            return DecisionVerifierDecision(allow = true, assessment = assessment)
        }

        val evidenceUnavailable = !assessment.evidenceActionsAvailable || !assessment.evidenceActionsDispatchable
        if (evidenceUnavailable) {
            return DecisionVerifierDecision(
                allow = true,
                reason = "Verification-sensitive request detected, but external evidence actions are unavailable; allowing graceful answer path without enforced tool call.",
                reasonCode = REASON_CODE_TASK_EVIDENCE_UNAVAILABLE_GRACEFUL,
                assessment = assessment
            )
        }
        if (evidence?.hadExternalFailures == true) {
            return DecisionVerifierDecision(
                allow = false,
                reason = "Verification-sensitive answer requires successful external evidence; only failures were observed.",
                reasonCode = REASON_CODE_TECH_EXTERNAL_EVIDENCE_FAILURE,
                assessment = assessment
            )
        }
        return DecisionVerifierDecision(
            allow = false,
            reason = "Verification-sensitive request requires at least one successful external evidence action before final answer.",
            reasonCode = REASON_CODE_TASK_EVIDENCE_REQUIRED,
            assessment = assessment
        )
    }

    private data class TaskClassification(
        val intentCategory: TaskIntentCategory,
        val volatilityLevel: VolatilityLevel,
        val volatilityScore: Int,
        val requiresExternalEvidence: Boolean,
    )

    private fun classifyTaskIntent(latestUserTurn: String, answerPayload: String): TaskClassification {
        val joined = buildString {
            append(latestUserTurn.lowercase(Locale.ROOT))
            append('\n')
            append(answerPayload.lowercase(Locale.ROOT))
        }
        if (containsAny(joined, transformationSignals)) {
            return TaskClassification(
                intentCategory = TaskIntentCategory.TRANSFORMATION,
                volatilityLevel = VolatilityLevel.NONE,
                volatilityScore = 0,
                requiresExternalEvidence = false
            )
        }
        if (containsAny(joined, personalMemorySignals)) {
            return TaskClassification(
                intentCategory = TaskIntentCategory.PERSONAL_MEMORY,
                volatilityLevel = VolatilityLevel.NONE,
                volatilityScore = 0,
                requiresExternalEvidence = false
            )
        }
        if (containsAny(joined, subjectiveAdviceSignals)) {
            return TaskClassification(
                intentCategory = TaskIntentCategory.SUBJECTIVE_ADVICE,
                volatilityLevel = VolatilityLevel.NONE,
                volatilityScore = 0,
                requiresExternalEvidence = false
            )
        }

        val volatilityScore = volatilityScore(joined)
        val volatilityLevel = when {
            volatilityScore >= HIGH_VOLATILITY_SCORE -> VolatilityLevel.HIGH
            volatilityScore >= LOW_VOLATILITY_SCORE -> VolatilityLevel.LOW
            else -> VolatilityLevel.NONE
        }
        if (volatilityScore >= VOLATILE_EVIDENCE_REQUIRED_SCORE) {
            return TaskClassification(
                intentCategory = TaskIntentCategory.VOLATILE_FACT,
                volatilityLevel = volatilityLevel,
                volatilityScore = volatilityScore,
                requiresExternalEvidence = true
            )
        }
        if (containsAny(joined, staticReasoningSignals)) {
            return TaskClassification(
                intentCategory = TaskIntentCategory.STATIC_REASONING,
                volatilityLevel = volatilityLevel,
                volatilityScore = volatilityScore,
                requiresExternalEvidence = false
            )
        }
        if (containsAny(joined, factualQuerySignals)) {
            return TaskClassification(
                intentCategory = TaskIntentCategory.STABLE_FACT,
                volatilityLevel = volatilityLevel,
                volatilityScore = volatilityScore,
                requiresExternalEvidence = false
            )
        }
        return TaskClassification(
            intentCategory = TaskIntentCategory.UNKNOWN,
            volatilityLevel = volatilityLevel,
            volatilityScore = volatilityScore,
            requiresExternalEvidence = volatilityScore >= VOLATILE_EVIDENCE_REQUIRED_SCORE
        )
    }

    private fun volatilityScore(text: String): Int {
        val recency = countSignals(text, recencySignals)
        val dynamicDomain = countSignals(text, dynamicDomainSignals)
        val dateSensitivity = dateSensitiveRegex.find(text)?.value?.isNotBlank() == true
        var score = 0
        if (recency > 0) {
            score += 2
        }
        if (dynamicDomain > 0) {
            score += 2
        }
        if (dateSensitivity) {
            score += 2
        }
        if (recency > 0 && dynamicDomain > 0) {
            score += 1
        }
        return score
    }

    private fun containsAny(text: String, signals: Set<String>): Boolean =
        signals.any { text.contains(it) }

    private fun countSignals(text: String, signals: Set<String>): Int =
        signals.count { text.contains(it) }

    private fun isForcedTerminalAnswer(summary: String): Boolean {
        val normalized = summary.trim().lowercase(Locale.ROOT)
        return normalized.contains("forced terminal answer")
    }

    private companion object {
        private const val REASON_CODE_TASK_EVIDENCE_REQUIRED: String = "TASK_EVIDENCE_REQUIRED"
        private const val REASON_CODE_TECH_EXTERNAL_EVIDENCE_FAILURE: String = "TECH_EXTERNAL_EVIDENCE_FAILURE"
        private const val REASON_CODE_TASK_EVIDENCE_UNAVAILABLE_GRACEFUL: String =
            "TASK_EVIDENCE_UNAVAILABLE_GRACEFUL"

        private const val LOW_VOLATILITY_SCORE: Int = 2
        private const val HIGH_VOLATILITY_SCORE: Int = 4
        private const val VOLATILE_EVIDENCE_REQUIRED_SCORE: Int = 3

        private val recencySignals: Set<String> = setOf(
            "latest",
            "current",
            "today",
            "now",
            "right now",
            "recent",
            "up-to-date",
            "up to date",
            "as of",
            "this week",
            "this month",
        )
        private val dynamicDomainSignals: Set<String> = setOf(
            "price",
            "pricing",
            "quote",
            "news",
            "schedule",
            "score",
            "scores",
            "ranking",
            "weather",
            "forecast",
            "version",
            "release",
            "law",
            "regulation",
            "regulatory",
            "stock",
            "exchange rate",
            "interest rate",
            "ceo",
            "president",
            "election",
            "availability",
        )
        private val transformationSignals: Set<String> = setOf(
            "rewrite",
            "rephrase",
            "paraphrase",
            "summarize",
            "summarise",
            "translate",
            "format",
            "proofread",
            "grammar",
            "convert",
            "shorten",
            "expand",
        )
        private val personalMemorySignals: Set<String> = setOf(
            "my name",
            "remember that",
            "remember my",
            "what is my",
            "i told you",
            "for future",
            "my preference",
            "my favorite",
            "my favourite",
            "about me",
            "what did i ask",
            "did i ask",
        )
        private val subjectiveAdviceSignals: Set<String> = setOf(
            "should i",
            "recommend",
            "opinion",
            "best for me",
            "advice",
            "help me decide",
            "which is better",
            "pros and cons",
        )
        private val staticReasoningSignals: Set<String> = setOf(
            "explain",
            "why",
            "how does",
            "derive",
            "prove",
            "calculate",
            "compute",
            "algorithm",
            "refactor",
            "debug",
            "math",
            "code",
        )
        private val factualQuerySignals: Set<String> = setOf(
            "who",
            "what",
            "when",
            "where",
            "which",
            "define",
            "history",
            "capital",
            "founded",
        )
        private val dateSensitiveRegex = Regex(
            "\\b(20\\d{2}|\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}|yesterday|tomorrow|next week|next month|effective)\\b"
        )
    }
}

