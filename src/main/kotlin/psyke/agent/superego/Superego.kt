package psyke.agent.superego

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import psyke.agent.core.AgentConfig
import psyke.agent.core.DialogueRole
import psyke.agent.core.GateDecision
import psyke.agent.core.PendingAction
import psyke.agent.core.SuperegoContext
import psyke.agent.support.PromptBudgetAllocator
import psyke.agent.support.RetryPolicy
import psyke.agent.support.TextSecurity
import psyke.instrumentation.AgentEvent
import psyke.instrumentation.AgentEvents
import psyke.instrumentation.AgentInstrumentation
import psyke.instrumentation.NoopAgentInstrumentation
import psyke.llm.ChatCallMetadata
import psyke.llm.ChatMessage
import psyke.llm.ChatModelClient
import psyke.llm.ChatRequestOptions
import psyke.llm.ChatRole

private val logger = KotlinLogging.logger {}

class Superego(
    private val modelClient: ChatModelClient,
    private val config: AgentConfig,
    private val policy: SuperegoPolicy = SuperegoPolicy,
    private val instrumentation: AgentInstrumentation = NoopAgentInstrumentation,
) {
    private val deterministicConscience = SuperegoDeterministicConscience(config)

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
        var response = null as psyke.llm.ChatCompletion?
        var lastError: Exception? = null
        val retryAttempts = RetryPolicy.boundedLlmRetryAttempts(config.planner.llmRetryAttempts)
        for (attempt in 1..retryAttempts) {
            try {
                response = modelClient.chat(
                    messages = messages,
                    options = ChatRequestOptions(
                        temperature = 0.0,
                        // Keep response budget independent from prompt/directive growth.
                        maxTokens = config.superego.maxCompletionTokens,
                        metadata = ChatCallMetadata(
                            actor = "superego",
                            callSite = "action_review",
                            actionType = action.type.name.lowercase()
                        )
                    )
                )
                break
            } catch (ex: Exception) {
                lastError = ex
                if (attempt < retryAttempts) {
                    instrumentation.emit(
                        AgentEvents.warning(
                            "Superego call failed (attempt $attempt/$retryAttempts); retrying."
                        )
                    )
                }
            }
        }
        if (response == null) {
            logger.warn(lastError) { "Superego review call failed for action=${action.id} type=${action.type}." }
            instrumentation.emit(AgentEvents.warning("Superego call failed; denying action by default."))
            return GateDecision(
                allow = false,
                reason = "Superego unavailable due to model error.",
                reasonCode = REASON_CODE_TECH_MODEL_UNAVAILABLE
            )
        }
        val resolvedResponse = response
        var parseResult = parseResponse(resolvedResponse.content, emitWarning = false)
        if (parseResult.parseFailed) {
            instrumentation.emit(
                AgentEvents.warning("Superego response was non-parseable; requesting strict JSON retry.")
            )
            val retryResponse = requestStrictJsonRetry(messages = messages, action = action)
            if (retryResponse == null) {
                instrumentation.emit(
                    AgentEvents.warning("Superego strict JSON retry call failed; denying action by default.")
                )
            } else {
                parseResult = parseResponse(retryResponse.content, emitWarning = true)
            }
            if (parseResult.parseFailed) {
                instrumentation.emit(
                    AgentEvents.warning("Superego response remained non-parseable after strict JSON retry.")
                )
            }
        }
        val decision = parseResult.decision
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

    private fun parseResponse(raw: String, emitWarning: Boolean): ParsedSuperegoDecision {
        return try {
            val json = TextSecurity.extractJsonObject(raw)
            val payload = mapper.readValue<SuperegoResponse>(json)
            if (payload.allow == null) {
                logger.warn {
                    "Superego response missing required 'allow' field. response_len=${raw.length} preview='${TextSecurity.preview(raw, 120)}'"
                }
                return ParsedSuperegoDecision(
                    decision = GateDecision(
                        allow = false,
                        reason = "Superego response missing required field.",
                        reasonCode = REASON_CODE_TECH_MISSING_REQUIRED_FIELD
                    ),
                    parseFailed = false
                )
            }
            val allow = payload.allow == true
            val reason = TextSecurity.clamp(payload.reason?.trim().orEmpty(), MAX_DENY_REASON_CHARS)
            val normalizedReasonCode = normalizeReasonCode(payload.reasonCode)
            ParsedSuperegoDecision(
                decision = GateDecision(
                    allow = allow,
                    reason = if (allow) "" else reason.ifBlank { "No reason supplied." },
                    reasonCode = if (allow) null else normalizedReasonCode ?: REASON_CODE_POLICY_LLM_DENY
                ),
                parseFailed = false
            )
        } catch (ex: Exception) {
            logger.warn(ex) {
                "Failed to parse Superego response. response_len=${raw.length} preview='${TextSecurity.preview(raw, 120)}'"
            }
            if (emitWarning) {
                instrumentation.emit(AgentEvents.warning("Failed to parse Superego response."))
            }
            ParsedSuperegoDecision(
                decision = GateDecision(
                    allow = false,
                    reason = "Superego response could not be parsed.",
                    reasonCode = REASON_CODE_TECH_PARSE_ERROR
                ),
                parseFailed = true
            )
        }
    }

    private fun requestStrictJsonRetry(
        messages: List<ChatMessage>,
        action: PendingAction,
    ): psyke.llm.ChatCompletion? {
        val retryMessages = messages + ChatMessage(
            role = ChatRole.USER,
            content = """
                Your previous output was not valid JSON.
                Reply with STRICT JSON only using exactly one schema:
                {"allow":true}
                or
                {"allow":false,"reason":"<=${MAX_DENY_REASON_CHARS} chars","reason_code":"TECH_*|POLICY_* optional"}
                Do not include markdown, prose, code fences, or extra keys.
            """.trimIndent()
        )
        return try {
            modelClient.chat(
                messages = retryMessages,
                options = ChatRequestOptions(
                    temperature = 0.0,
                    maxTokens = config.superego.maxCompletionTokens,
                    metadata = ChatCallMetadata(
                        actor = "superego",
                        callSite = "action_review_json_retry",
                        actionType = action.type.name.lowercase()
                    )
                )
            )
        } catch (ex: Exception) {
            logger.warn(ex) { "Superego strict JSON retry call failed for action=${action.id} type=${action.type}." }
            null
        }
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
                    minTokens = 28,
                    content = """
                        You are Superego, a strict gatekeeper for actions.
                        Return STRICT JSON only.
                        If action violates directives or is unsafe, deny it.
                    """.trimIndent()
                ),
                PromptBudgetAllocator.Section(
                    role = ChatRole.SYSTEM,
                    priority = PromptBudgetAllocator.Priority.IMPORTANT,
                    minTokens = 24,
                    content = """
                        Directives:
                        $directivesBlock

                        JSON schema:
                        - If allowed: {"allow": true}
                        - If denied: {"allow": false, "reason":"<=${MAX_DENY_REASON_CHARS} chars", "reason_code":"TECH_*|POLICY_* optional"}
                        Keep output minimal JSON only.
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

    private data class SuperegoResponse(
        val allow: Boolean? = null,
        val reason: String? = null,
        @field:JsonProperty("reason_code")
        val reasonCode: String? = null,
    )

    private data class ParsedSuperegoDecision(
        val decision: GateDecision,
        val parseFailed: Boolean,
    )

    companion object {
        private const val MAX_DENY_REASON_CHARS: Int = 180
        private const val REASON_CODE_TECH_MODEL_UNAVAILABLE: String = "TECH_MODEL_UNAVAILABLE"
        private const val REASON_CODE_TECH_PARSE_ERROR: String = "TECH_PARSE_ERROR"
        private const val REASON_CODE_TECH_MISSING_REQUIRED_FIELD: String = "TECH_MISSING_REQUIRED_FIELD"
        private const val REASON_CODE_POLICY_LLM_DENY: String = "POLICY_LLM_DENY"

        val mapper = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    private fun normalizeReasonCode(raw: String?): String? =
        raw?.trim()
            ?.uppercase()
            ?.replace(Regex("[^A-Z0-9_]+"), "_")
            ?.trim('_')
            ?.ifBlank { null }
}
