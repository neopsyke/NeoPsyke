package ai.neopsyke.agent.ego

import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.DeliberationState
import ai.neopsyke.agent.model.EgoDecision
import ai.neopsyke.agent.model.PendingAction
import kotlin.math.max
import kotlin.math.min

class DeliberationProgressMonitor(
    private val signatureWindow: Int = 10,
) {
    private var state: DeliberationState = DeliberationState()
    private val recentDecisionSignatures = ArrayDeque<String>()

    fun reset() {
        state = DeliberationState()
        recentDecisionSignatures.clear()
    }

    fun snapshot(): DeliberationState = state

    fun startStep(): DeliberationState {
        state = state.copy(
            stepIndex = state.stepIndex + 1,
            stepsSinceNewEvidence = state.stepsSinceNewEvidence + 1,
            progressScore = (state.progressScore * 0.92).coerceAtLeast(0.0)
        )
        recomputePressure()
        return state
    }

    fun onPlannerDecision(decision: EgoDecision) {
        var staleStreak = state.staleStreak
        var progressScore = state.progressScore
        var repeatHits = state.repeatSignatureHits
        var noopStreak = state.noopStreak
        var modelErrorStreak = state.modelErrorStreak

        val signature = decisionSignature(decision)
        if (isRepeatedSignature(signature)) {
            repeatHits += 1
            staleStreak += 1
        }
        rememberSignature(signature)

        when (decision) {
            is EgoDecision.ProposeAction -> {
                staleStreak = max(0, staleStreak - 1)
                progressScore += 0.07
                noopStreak = 0
                modelErrorStreak = max(0, modelErrorStreak - 1)
            }

            is EgoDecision.EnqueueThought -> {
                staleStreak = max(0, staleStreak - 1)
                progressScore += 0.03
                noopStreak = 0
                modelErrorStreak = max(0, modelErrorStreak - 1)
            }

            is EgoDecision.EnqueuePlan -> {
                staleStreak = max(0, staleStreak - 1)
                progressScore += 0.05
                noopStreak = 0
                modelErrorStreak = max(0, modelErrorStreak - 1)
            }

            is EgoDecision.Noop -> {
                val modelErrorNoop = decision.reason.contains("model error", ignoreCase = true)
                staleStreak += if (modelErrorNoop) 2 else 1
                progressScore = max(0.0, progressScore - if (modelErrorNoop) 0.07 else 0.03)
                noopStreak += if (modelErrorNoop) 2 else 1
                modelErrorStreak = if (modelErrorNoop) modelErrorStreak + 1 else max(0, modelErrorStreak - 1)
            }
        }

        state = state.copy(
            staleStreak = staleStreak,
            progressScore = progressScore.coerceIn(0.0, 1.5),
            repeatSignatureHits = repeatHits,
            noopStreak = noopStreak,
            modelErrorStreak = modelErrorStreak
        )
        recomputePressure()
    }

    fun onActionDenied() {
        state = state.copy(
            staleStreak = state.staleStreak + 1,
            denialCount = state.denialCount + 1,
            progressScore = max(0.0, state.progressScore - 0.04)
        )
        recomputePressure()
    }

    fun onActionExecuted(action: PendingAction, observedEvidence: Boolean = true) {
        val isEvidenceAction = action.requiresFollowUpThought ||
            action.type == ActionType.WEB_SEARCH ||
            action.type == ActionType.MCP_TIME ||
            action.type == ActionType.WEBSITE_FETCH
        state = if (isEvidenceAction) {
            if (observedEvidence) {
                state.copy(
                    staleStreak = max(0, state.staleStreak - 2),
                    stepsSinceNewEvidence = 0,
                    progressScore = (state.progressScore + 0.20).coerceAtMost(1.5),
                    noopStreak = 0,
                    modelErrorStreak = max(0, state.modelErrorStreak - 1)
                )
            } else {
                state.copy(
                    staleStreak = state.staleStreak + 1,
                    progressScore = max(0.0, state.progressScore - 0.06)
                )
            }
        } else {
            state.copy(
                staleStreak = max(0, state.staleStreak - 1),
                progressScore = (state.progressScore + 0.08).coerceAtMost(1.5),
                noopStreak = 0,
                modelErrorStreak = max(0, state.modelErrorStreak - 1)
            )
        }
        recomputePressure()
    }

    fun onRepeatedDeniedAction() {
        state = state.copy(
            staleStreak = state.staleStreak + 2,
            repeatSignatureHits = state.repeatSignatureHits + 1,
            progressScore = max(0.0, state.progressScore - 0.06)
        )
        recomputePressure()
    }

    fun onTaskFailure() {
        state = state.copy(
            staleStreak = state.staleStreak + 1,
            progressScore = max(0.0, state.progressScore - 0.02)
        )
        recomputePressure()
    }

    private fun decisionSignature(decision: EgoDecision): String =
        when (decision) {
            is EgoDecision.EnqueueThought -> {
                "thought:${normalize(decision.content)}"
            }

            is EgoDecision.ProposeAction -> {
                "action:${decision.actionType.name.lowercase()}:${normalize(decision.payload)}"
            }

            is EgoDecision.EnqueuePlan -> {
                "plan:${normalize(decision.goal)}:steps=${decision.steps.size}"
            }

            is EgoDecision.Noop -> {
                "noop:${normalize(decision.reason)}"
            }
        }

    private fun normalize(text: String): String =
        text.lowercase().replace(Regex("\\s+"), " ").trim().take(120)

    private fun isRepeatedSignature(signature: String): Boolean =
        recentDecisionSignatures.contains(signature)

    private fun rememberSignature(signature: String) {
        recentDecisionSignatures.addLast(signature)
        while (recentDecisionSignatures.size > signatureWindow) {
            recentDecisionSignatures.removeFirst()
        }
    }

    private fun recomputePressure() {
        val stepPressure = if (state.stepIndex <= 14) 0.0 else (state.stepIndex - 14) * 0.018
        val stalePressure = state.staleStreak * 0.09
        val denialPressure = state.denialCount * 0.07
        val repeatPressure = state.repeatSignatureHits * 0.08
        val noopPressure = state.noopStreak * 0.06
        val modelErrorPressure = state.modelErrorStreak * 0.10
        val evidenceGapPressure = min(state.stepsSinceNewEvidence, 18) * 0.02
        val progressRelief = state.progressScore * 0.16
        val raw = 0.10 +
            stepPressure +
            stalePressure +
            denialPressure +
            repeatPressure +
            noopPressure +
            modelErrorPressure +
            evidenceGapPressure -
            progressRelief
        state = state.copy(decisionPressure = raw.coerceIn(0.0, 1.0))
    }
}
