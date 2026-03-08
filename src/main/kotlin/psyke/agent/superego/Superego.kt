package psyke.agent.superego

import psyke.agent.core.ActionType
import psyke.agent.core.AgentConfig
import psyke.agent.core.DialogueRole
import psyke.agent.core.GateDecision
import psyke.agent.core.PendingAction
import psyke.agent.core.SuperegoContext
import psyke.agent.support.PromptBudgetAllocator
import psyke.agent.support.TextSecurity
import psyke.instrumentation.AgentEvent
import psyke.instrumentation.AgentEvents
import psyke.instrumentation.AgentInstrumentation
import psyke.instrumentation.NoopAgentInstrumentation
import psyke.llm.ChatMessage
import psyke.llm.ChatModelClient
import psyke.llm.ChatRole

class Superego(
    private val modelClient: ChatModelClient,
    private val config: AgentConfig,
    private val modelTokenWeight: Double = DEFAULT_MODEL_TOKEN_WEIGHT,
    private val modelContextWindow: Int? = null,
    private val escalationModelClient: ChatModelClient? = null,
    private val escalationModelTokenWeight: Double = DEFAULT_MODEL_TOKEN_WEIGHT,
    private val escalationModelContextWindow: Int? = null,
    private val policy: SuperegoPolicy = SuperegoPolicy,
    private val instrumentation: AgentInstrumentation = NoopAgentInstrumentation,
) {
    private val deterministicConscience = SuperegoDeterministicConscience(config)
    private val primaryEngine: SingleStageSuperegoReviewEngine = buildPrimaryEngine()
    private val reviewEngine: SuperegoReviewEngine = buildReviewEngine(primaryEngine)

    fun review(action: PendingAction, context: SuperegoContext): GateDecision {
        val resolvedDirectives = policy.forAction(action.type).all
        val lastUserTurn = context.recentDialogue.lastOrNull { it.role == DialogueRole.USER }?.content ?: "none"
        instrumentation.emit(
            AgentEvents.superegoReviewInput(
                action = action,
                directives = resolvedDirectives,
                lastUserMessage = lastUserTurn
            )
        )
        val deterministicDecision = deterministicConscience.review(action, context)
        if (!deterministicDecision.allow) {
            val reason = TextSecurity.clamp(
                deterministicDecision.reason.ifBlank { "Deterministic policy denied action." },
                MAX_DENY_REASON_CHARS
            )
            instrumentation.emit(
                AgentEvent(
                    type = "superego_deterministic_review",
                    data = mapOf(
                        "action_id" to action.id,
                        "allow" to false,
                        "rule_id" to deterministicDecision.ruleId,
                        "reason_code" to deterministicDecision.reasonCode,
                        "reason" to reason
                    )
                )
            )
            instrumentation.emit(
                AgentEvents.superegoReviewOutput(
                    actionId = action.id,
                    allow = false,
                    reason = reason,
                    reasonCode = deterministicDecision.reasonCode
                )
            )
            return GateDecision(
                allow = false,
                reason = reason,
                reasonCode = deterministicDecision.reasonCode
            )
        }
        instrumentation.emit(
            AgentEvent(
                type = "superego_deterministic_review",
                data = mapOf(
                    "action_id" to action.id,
                    "allow" to true
                )
            )
        )

        val messages = buildMessages(action, context, resolvedDirectives)
        val effectiveEngine = if (action.type == ActionType.ANSWER && config.superego.twoStageSkipForAnswerActions) {
            primaryEngine
        } else {
            reviewEngine
        }
        val decision = effectiveEngine.review(action, messages)
        instrumentation.emit(
            AgentEvents.superegoReviewOutput(
                actionId = action.id,
                allow = decision.allow,
                reason = decision.reason,
                reasonCode = decision.reasonCode
            )
        )
        return decision
    }

    private fun buildPrimaryEngine(): SingleStageSuperegoReviewEngine =
        SingleStageSuperegoReviewEngine(
            modelClient = modelClient,
            config = config,
            modelTokenWeight = modelTokenWeight,
            modelContextWindow = modelContextWindow,
            instrumentation = instrumentation,
            stageLabel = "primary",
            callSiteBase = "action_review"
        )

    private fun buildReviewEngine(primary: SingleStageSuperegoReviewEngine): SuperegoReviewEngine {
        val escalationClient = escalationModelClient
        if (!config.superego.twoStageReviewEnabled || escalationClient == null) {
            return primary
        }
        if (escalationClient.modelName.equals(modelClient.modelName, ignoreCase = true)) {
            instrumentation.emit(
                AgentEvents.warning(
                    "Superego two-stage review is enabled but uses the same model for both stages; falling back to single-stage review."
                )
            )
            return primary
        }
        val escalation = SingleStageSuperegoReviewEngine(
            modelClient = escalationClient,
            config = config,
            modelTokenWeight = escalationModelTokenWeight,
            modelContextWindow = escalationModelContextWindow,
            instrumentation = instrumentation,
            stageLabel = "escalation",
            callSiteBase = "action_review_escalated"
        )
        return TwoStageSuperegoReviewEngine(
            primary = primary,
            escalation = escalation,
            config = config,
            instrumentation = instrumentation
        )
    }

    private fun buildMessages(
        action: PendingAction,
        context: SuperegoContext,
        directives: List<String>,
    ): List<ChatMessage> {
        val directivesBlock = directives.joinToString(separator = "\n") { "- $it" }
        val lastUserTurn = context.recentDialogue.lastOrNull { it.role == DialogueRole.USER }?.content ?: "none"
        val shortTermContextSummary = context.shortTermContextSummary.ifBlank { "none" }
        return PromptBudgetAllocator.allocate(
            sections = listOf(
                PromptBudgetAllocator.Section(
                    role = ChatRole.SYSTEM,
                    priority = PromptBudgetAllocator.Priority.MANDATORY,
                    required = true,
                    minTokens = 30,
                    content = """
                        You are Superego, a strict gatekeeper for actions.
                        Return only data that matches the response format schema.
                        If action violates directives or is unsafe, deny it.
                        Include confidence and policy_risk to support escalation checks.
                    """.trimIndent()
                ),
                PromptBudgetAllocator.Section(
                    role = ChatRole.SYSTEM,
                    priority = PromptBudgetAllocator.Priority.IMPORTANT,
                    minTokens = 26,
                    content = """
                        Directives:
                        $directivesBlock
                    """.trimIndent()
                ),
                PromptBudgetAllocator.Section(
                    role = ChatRole.USER,
                    priority = PromptBudgetAllocator.Priority.MANDATORY,
                    required = true,
                    minTokens = 24,
                    content = """
                        Candidate action:
                        type=${action.type.name.lowercase()}
                        urgency=${action.urgency.name.lowercase()}
                        summary=${action.summary}
                        payload=${action.payload}
                    """.trimIndent()
                ),
                PromptBudgetAllocator.Section(
                    role = ChatRole.USER,
                    priority = PromptBudgetAllocator.Priority.MANDATORY,
                    required = true,
                    minTokens = 10,
                    content = """
                        Last user message:
                        $lastUserTurn
                    """.trimIndent()
                ),
                PromptBudgetAllocator.Section(
                    role = ChatRole.USER,
                    priority = PromptBudgetAllocator.Priority.IMPORTANT,
                    minTokens = 12,
                    content = """
                        Short-term context summary:
                        $shortTermContextSummary
                    """.trimIndent()
                )
            ),
            maxTokens = config.planner.maxPromptTokens
        )
    }

    companion object {
        private const val DEFAULT_MODEL_TOKEN_WEIGHT: Double = 1.0
        private const val MAX_DENY_REASON_CHARS: Int = 180
    }
}
