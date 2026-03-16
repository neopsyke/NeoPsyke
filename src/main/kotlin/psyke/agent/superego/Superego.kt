package psyke.agent.superego

import psyke.agent.actions.ActionRegistry
import psyke.agent.model.ActionType
import psyke.agent.config.AgentConfig
import psyke.agent.model.DialogueRole
import psyke.agent.model.GateDecision
import psyke.agent.model.PendingAction
import psyke.agent.model.SuperegoContext
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
    private val actionRegistry: ActionRegistry = ActionRegistry.empty(),
    private val modelTokenWeight: Double = DEFAULT_MODEL_TOKEN_WEIGHT,
    private val modelContextWindow: Int? = null,
    private val modelReasoningOverhead: Double = DEFAULT_REASONING_OVERHEAD,
    private val escalationModelClient: ChatModelClient? = null,
    private val escalationModelTokenWeight: Double = DEFAULT_MODEL_TOKEN_WEIGHT,
    private val escalationModelContextWindow: Int? = null,
    private val escalationModelReasoningOverhead: Double = DEFAULT_REASONING_OVERHEAD,
    private val policy: SuperegoPolicy = SuperegoPolicy,
    private val instrumentation: AgentInstrumentation = NoopAgentInstrumentation,
) {
    private val deterministicConscience = SuperegoDeterministicConscience(config, actionRegistry)
    private val primaryEngine: SingleStageSuperegoReviewEngine = buildPrimaryEngine()
    private val reviewEngine: SuperegoReviewEngine = buildReviewEngine(primaryEngine)

    fun review(action: PendingAction, context: SuperegoContext): GateDecision {
        val resolvedDirectives = policy.forAction(action.type, actionRegistry, origin = context.origin).all
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

        val promptAllocation = buildMessages(action, context, resolvedDirectives)
        instrumentation.emit(
            AgentEvent(
                type = "prompt_budget_allocation",
                data = promptAllocation.diagnostics.toTelemetryData(callSite = "superego_prompt"),
            )
        )
        val messages = promptAllocation.messages
        val effectiveEngine = when {
            action.type == ActionType.CONTACT_USER && config.superego.twoStageSkipForContactUserActions -> primaryEngine
            action.type == ActionType.WEB_SEARCH && config.superego.twoStageSkipForWebSearchActions -> primaryEngine
            else -> reviewEngine
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
            reasoningOverheadMultiplier = modelReasoningOverhead,
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
            reasoningOverheadMultiplier = escalationModelReasoningOverhead,
            instrumentation = instrumentation,
            stageLabel = "escalation",
            callSiteBase = "action_review_escalated",
            tripThreshold = SingleStageSuperegoReviewEngine.ESCALATION_TRIP_THRESHOLD
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
    ): PromptBudgetAllocator.AllocationResult {
        val directivesBlock = directives.joinToString(separator = "\n") { "- $it" }
        val lastUserTurn = context.recentDialogue.lastOrNull { it.role == DialogueRole.USER }?.content ?: "none"
        val shortTermContextSummary = context.shortTermContextSummary.ifBlank { "none" }
        return PromptBudgetAllocator.allocate(
            sections = listOf(
                PromptBudgetAllocator.Section(
                    key = "superego_system_instructions",
                    role = ChatRole.SYSTEM,
                    band = PromptBudgetAllocator.Band.REQUIRED_CORE,
                    importance = PromptBudgetAllocator.Importance.HIGH,
                    floorTokens = 30,
                    content = """
                        You are Superego, a strict gatekeeper for actions.
                        Return only data that matches the response format schema.
                        If action violates directives or is unsafe, deny it.
                        Include confidence and policy_risk to support escalation checks.
                    """.trimIndent()
                ),
                PromptBudgetAllocator.Section(
                    key = "superego_directives",
                    role = ChatRole.SYSTEM,
                    band = PromptBudgetAllocator.Band.REQUIRED_CONTEXT,
                    importance = PromptBudgetAllocator.Importance.HIGH,
                    floorTokens = 26,
                    content = """
                        Directives:
                        $directivesBlock
                    """.trimIndent()
                ),
                PromptBudgetAllocator.Section(
                    key = "superego_candidate_action",
                    role = ChatRole.USER,
                    band = PromptBudgetAllocator.Band.REQUIRED_CORE,
                    importance = PromptBudgetAllocator.Importance.HIGH,
                    floorTokens = 24,
                    content = """
                        Candidate action:
                        type=${action.type.name.lowercase()}
                        urgency=${action.urgency.name.lowercase()}
                        summary=${action.summary}
                        payload=${action.payload}
                    """.trimIndent()
                ),
                PromptBudgetAllocator.Section(
                    key = "superego_last_user_message",
                    role = ChatRole.USER,
                    band = PromptBudgetAllocator.Band.REQUIRED_CORE,
                    floorTokens = 10,
                    content = """
                        Last user message:
                        $lastUserTurn
                    """.trimIndent()
                ),
                PromptBudgetAllocator.Section(
                    key = "superego_short_term_summary",
                    role = ChatRole.USER,
                    band = PromptBudgetAllocator.Band.REQUIRED_CONTEXT,
                    floorTokens = 12,
                    content = """
                        Short-term context summary:
                        $shortTermContextSummary
                    """.trimIndent()
                )
            ),
            maxTokens = config.maxLlmPromptTokens
        )
    }

    companion object {
        private const val DEFAULT_MODEL_TOKEN_WEIGHT: Double = 1.0
        private const val DEFAULT_REASONING_OVERHEAD: Double = 1.0
        private const val MAX_DENY_REASON_CHARS: Int = 180
    }
}
