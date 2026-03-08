package psyke.agent.ego

import psyke.agent.core.ActionType
import psyke.agent.core.DialogueRole
import psyke.agent.core.DialogueTurn
import psyke.agent.core.PendingAction
import java.util.Locale

internal data class TaskVerifierContext(
    val recentDialogue: List<DialogueTurn>,
    val externalEvidence: DeliberationEngine.ExternalEvidenceProgress? = null,
)

internal data class TaskVerifierDecision(
    val allow: Boolean,
    val reason: String = "",
    val reasonCode: String? = null,
)

internal interface TaskVerifier {
    fun review(action: PendingAction, context: TaskVerifierContext): TaskVerifierDecision
}

internal object NoopTaskVerifier : TaskVerifier {
    override fun review(action: PendingAction, context: TaskVerifierContext): TaskVerifierDecision =
        TaskVerifierDecision(allow = true)
}

internal class DeterministicTaskVerifier : TaskVerifier {
    override fun review(action: PendingAction, context: TaskVerifierContext): TaskVerifierDecision {
        if (action.type != ActionType.ANSWER || action.isFallbackExplanation) {
            return TaskVerifierDecision(allow = true)
        }
        if (isForcedTerminalAnswer(action.summary)) {
            return TaskVerifierDecision(allow = true)
        }

        val latestUserTurn = context.recentDialogue
            .asReversed()
            .firstOrNull { it.role == DialogueRole.USER }
            ?.content
            .orEmpty()
        val needsFreshEvidence = requiresFreshEvidence(
            latestUserTurn = latestUserTurn,
            answerPayload = action.payload
        )
        if (!needsFreshEvidence) {
            return TaskVerifierDecision(allow = true)
        }

        val evidence = context.externalEvidence
        if (evidence?.hadSuccessfulEvidence == true) {
            return TaskVerifierDecision(allow = true)
        }
        if (evidence?.hadExternalFailures == true) {
            return TaskVerifierDecision(
                allow = false,
                reason = "Verification-sensitive answer requires successful external evidence; only failures were observed.",
                reasonCode = REASON_CODE_TECH_EXTERNAL_EVIDENCE_FAILURE
            )
        }
        return TaskVerifierDecision(
            allow = false,
            reason = "Verification-sensitive request requires at least one successful external evidence action before final answer.",
            reasonCode = REASON_CODE_TASK_EVIDENCE_REQUIRED
        )
    }

    private fun requiresFreshEvidence(latestUserTurn: String, answerPayload: String): Boolean {
        val joined = buildString {
            append(latestUserTurn.lowercase(Locale.ROOT))
            append('\n')
            append(answerPayload.lowercase(Locale.ROOT))
        }
        return verificationSensitiveSignals.any { joined.contains(it) }
    }

    private fun isForcedTerminalAnswer(summary: String): Boolean {
        val normalized = summary.trim().lowercase(Locale.ROOT)
        return normalized.contains("forced terminal answer")
    }

    private companion object {
        private const val REASON_CODE_TASK_EVIDENCE_REQUIRED: String = "TASK_EVIDENCE_REQUIRED"
        private const val REASON_CODE_TECH_EXTERNAL_EVIDENCE_FAILURE: String = "TECH_EXTERNAL_EVIDENCE_FAILURE"
        private val verificationSensitiveSignals: Set<String> = setOf(
            "latest",
            "current",
            "today",
            "now",
            "price",
            "pricing",
            "quote",
            "news",
            "schedule",
            "score",
            "ranking",
            "weather",
            "version",
            "release",
            "law",
            "regulation",
            "stock",
            "exchange rate",
            "interest rate",
        )
    }
}
