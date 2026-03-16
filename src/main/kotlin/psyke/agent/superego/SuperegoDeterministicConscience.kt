package psyke.agent.superego

import psyke.agent.actions.ActionRegistry
import psyke.agent.model.ActionType
import psyke.agent.config.AgentConfig
import psyke.agent.model.OriginSource
import psyke.agent.model.PendingAction
import psyke.agent.model.SuperegoContext
import java.util.Locale

/**
 * The superego's ethical/moral gate decision on whether the agent should be
 * *allowed* to take an action.
 *
 * This is distinct from [psyke.agent.actions.ActionDeterministicReview], which
 * is the plugin's conscious-level validity check ("is this payload well-formed
 * and safe?"). The superego maps plugin decisions into this type, framing the
 * verdict in ethical/moral terms. The two types are intentionally separate to
 * preserve the psychoanalytic metaphor: the plugin validates, the superego
 * judges.
 */
internal data class SuperegoDeterministicDecision(
    val allow: Boolean,
    val reason: String = "",
    val ruleId: String? = null,
    val reasonCode: String? = null,
)

/**
 * Deterministic gate for hard-deny policy checks and schema validation.
 *
 * This gate runs before the LLM superego judgment and is authoritative.
 *
 * Shape validation (blank/too-long summary, oversized payload) is handled
 * here, then the decision is delegated to the plugin's `deterministicReview`.
 * Actions without a registered plugin are allowed by default.
 */
internal class SuperegoDeterministicConscience(
    private val config: AgentConfig,
    private val actionRegistry: ActionRegistry,
) {
    fun review(action: PendingAction, context: SuperegoContext): SuperegoDeterministicDecision {
        return try {
            validateActionShape(action)?.let { return it }
            validateIdOriginPolicy(action, context)?.let { return it }

            // Delegate to the plugin's deterministic review when available.
            actionRegistry.deterministicReview(action = action, context = context, config = config)
                ?.let { pluginDecision ->
                    return mapPluginDecision(pluginDecision)
                }

            // No plugin registered for this action type — allow by default.
            allow()
        } catch (_: Exception) {
            deny(
                ruleId = "deterministic_review_failed",
                reason = "Deterministic superego checks failed unexpectedly; denying by default."
            )
        }
    }

    private fun validateIdOriginPolicy(
        action: PendingAction,
        context: SuperegoContext,
    ): SuperegoDeterministicDecision? {
        val origin = context.origin ?: return null
        if (origin.source != OriginSource.ID) return null

        if (action.type !in ID_ALLOWED_ACTIONS) {
            return deny(
                "id_origin_action_not_allowed",
                "Id-origin action '${action.type.id}' is not in deterministic internal allowlist."
            )
        }
        return null
    }

    private fun mapPluginDecision(
        decision: psyke.agent.actions.ActionDeterministicReview,
    ): SuperegoDeterministicDecision {
        if (decision.allow) {
            return allow()
        }
        val ruleId = decision.ruleId ?: "action_policy_denied"
        val reasonCode = decision.reasonCode ?: "POLICY_${normalizeRuleId(ruleId)}"
        val reason = decision.reason.ifBlank { "Deterministic policy denied action." }
        return SuperegoDeterministicDecision(
            allow = false,
            reason = "$reason [rule:$ruleId]",
            ruleId = ruleId,
            reasonCode = reasonCode
        )
    }

    private fun validateActionShape(action: PendingAction): SuperegoDeterministicDecision? {
        if (action.summary.isBlank()) {
            return deny("summary_blank", "Action summary is required for deterministic review.")
        }
        if (action.summary.length > config.maxActionSummaryChars) {
            return deny(
                "summary_too_long",
                "Action summary exceeds ${config.maxActionSummaryChars} chars."
            )
        }
        if (action.payload.length > config.maxActionPayloadChars) {
            return deny(
                "payload_too_long",
                "Action payload exceeds ${config.maxActionPayloadChars} chars."
            )
        }
        return null
    }

    private fun allow(): SuperegoDeterministicDecision = SuperegoDeterministicDecision(allow = true)

    private fun deny(ruleId: String, reason: String): SuperegoDeterministicDecision =
        SuperegoDeterministicDecision(
            allow = false,
            reason = "$reason [rule:$ruleId]",
            ruleId = ruleId,
            reasonCode = "POLICY_${normalizeRuleId(ruleId)}"
        )

    private fun normalizeRuleId(ruleId: String): String =
        ruleId.uppercase(Locale.ROOT)
            .replace(Regex("[^A-Z0-9]+"), "_")
            .trim('_')
            .ifBlank { "DETERMINISTIC_DENY" }

    companion object {
        private val ID_ALLOWED_ACTIONS: Set<ActionType> = setOf(
            ActionType.WEB_SEARCH,
            ActionType.WEBSITE_FETCH,
            ActionType.MCP_TIME,
            ActionType.RESOLUTION_DRAFT,
            ActionType.CONTACT_USER,
            ActionType.REFLECT,
        )
    }
}
