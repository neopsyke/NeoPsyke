package ai.neopsyke.agent.ego

import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.GroundingMetadata
import ai.neopsyke.agent.model.GroundingRequirement
import ai.neopsyke.agent.model.PendingAction

internal data class DecisionVerifierContext(
    val externalEvidence: DeliberationEngine.ExternalEvidenceProgress? = null,
    val availableActions: Set<ActionType> = emptySet(),
    val dispatchableActions: Set<ActionType> = emptySet(),
    val evidenceActionTypes: Set<ActionType> = emptySet(),
    val groundingTechnicalFailureBudgetExceeded: Boolean = false,
)

internal data class DecisionVerifierDecision(
    val allow: Boolean,
    val reason: String = "",
    val reasonCode: String? = null,
    val groundingRequired: Boolean = false,
    val evidenceGathered: Boolean = false,
    val evidenceFailedTechnically: Boolean = false,
    val evidenceUnavailable: Boolean = false,
    val forcedTerminal: Boolean = false,
)

internal interface DecisionVerifier {
    fun review(action: PendingAction, context: DecisionVerifierContext): DecisionVerifierDecision
}

internal object NoopDecisionVerifier : DecisionVerifier {
    override fun review(action: PendingAction, context: DecisionVerifierContext): DecisionVerifierDecision =
        DecisionVerifierDecision(allow = true)
}

/**
 * Grounding gate: enforces that evidence was gathered when required.
 *
 * This is a safety net against planner non-compliance or tool failures,
 * not the primary classification mechanism. Classification happens at
 * input intake via [GroundingClassifier].
 *
 * Reads:
 * - [PendingAction.groundingMetadata] (carried typed metadata)
 * - [PendingAction.isForcedTerminal] (typed forced-terminal marker)
 * - [DeliberationEngine.ExternalEvidenceProgress] (typed evidence state)
 * - evidence action availability/dispatchability
 */
internal class GroundingGate : DecisionVerifier {
    override fun review(action: PendingAction, context: DecisionVerifierContext): DecisionVerifierDecision {
        if (action.type != ActionType.CONTACT_USER || action.isFallbackExplanation) {
            return DecisionVerifierDecision(allow = true)
        }

        val grounding = action.groundingMetadata ?: GroundingMetadata.NOT_REQUIRED_PREFILTER
        val groundingRequired = grounding.requirement == GroundingRequirement.REQUIRED
        val evidence = context.externalEvidence
        val evidenceGathered = evidence?.hadSuccessfulEvidence == true
        val evidenceFailedTechnically = evidence?.hadExternalFailures == true
        val evidenceActionsAvailable = context.evidenceActionTypes.any { it in context.availableActions }
        val evidenceActionsDispatchable = context.evidenceActionTypes.any { it in context.dispatchableActions }
        val evidenceUnavailable = !evidenceActionsAvailable || !evidenceActionsDispatchable
        val forcedTerminal = action.isForcedTerminal

        fun decision(allow: Boolean, reason: String = "", reasonCode: String? = null) =
            DecisionVerifierDecision(
                allow = allow,
                reason = reason,
                reasonCode = reasonCode,
                groundingRequired = groundingRequired,
                evidenceGathered = evidenceGathered,
                evidenceFailedTechnically = evidenceFailedTechnically,
                evidenceUnavailable = evidenceUnavailable,
                forcedTerminal = forcedTerminal,
            )

        // Non-grounded requests pass through.
        if (!groundingRequired) {
            return decision(allow = true)
        }

        // Evidence gathered: allow.
        if (evidenceGathered) {
            return decision(allow = true)
        }

        // Evidence tools unavailable: graceful degradation.
        if (evidenceUnavailable) {
            return decision(
                allow = true,
                reason = "Grounding required but evidence actions are unavailable; allowing graceful answer path.",
                reasonCode = REASON_CODE_GROUNDING_EVIDENCE_UNAVAILABLE_GRACEFUL,
            )
        }

        // Technical evidence failures.
        if (evidenceFailedTechnically) {
            if (forcedTerminal && context.groundingTechnicalFailureBudgetExceeded) {
                // Bounded failure budget exhausted: allow degraded best-effort answer.
                // The disclaimer is attached by DeliberationEngine.maybeForceTerminalAnswer().
                return decision(
                    allow = true,
                    reason = "Grounding required; forced terminal answer after bounded technical evidence failures. " +
                        "Answer must include an explicit verification-failure disclaimer.",
                    reasonCode = REASON_CODE_GROUNDING_EVIDENCE_UNAVAILABLE_GRACEFUL,
                )
            }
            return decision(
                allow = false,
                reason = if (forcedTerminal) {
                    "Grounding required; forced terminal answer attempted before technical failure budget was exhausted. " +
                        "Retry evidence gathering first."
                } else {
                    "Grounding required; evidence gathering failed technically. Retry evidence gathering."
                },
                reasonCode = REASON_CODE_TECH_GROUNDING_EVIDENCE_FAILURE,
            )
        }

        // Forced terminal with no evidence attempt: deny (must gather evidence first).
        // No evidence attempted at all.
        return decision(
            allow = false,
            reason = "Grounding required; no evidence gathered yet. Dispatch an evidence-gathering action first.",
            reasonCode = REASON_CODE_GROUNDING_EVIDENCE_REQUIRED,
        )
    }

    companion object {
        const val REASON_CODE_GROUNDING_EVIDENCE_REQUIRED: String = "GROUNDING_EVIDENCE_REQUIRED"
        const val REASON_CODE_TECH_GROUNDING_EVIDENCE_FAILURE: String = "TECH_GROUNDING_EVIDENCE_FAILURE"
        const val REASON_CODE_GROUNDING_EVIDENCE_UNAVAILABLE_GRACEFUL: String =
            "GROUNDING_EVIDENCE_UNAVAILABLE_GRACEFUL"
    }
}
