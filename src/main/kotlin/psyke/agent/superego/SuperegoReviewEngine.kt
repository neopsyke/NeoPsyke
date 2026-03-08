package psyke.agent.superego

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import psyke.agent.core.AgentConfig
import psyke.agent.core.GateDecision
import psyke.agent.core.PendingAction
import psyke.agent.support.AdaptiveCompletionBudget
import psyke.agent.support.RetryPolicy
import psyke.agent.support.TextSecurity
import psyke.instrumentation.AgentEvent
import psyke.instrumentation.AgentEvents
import psyke.instrumentation.AgentInstrumentation
import psyke.llm.ChatCallMetadata
import psyke.llm.ChatCompletion
import psyke.llm.ChatMessage
import psyke.llm.ChatModelClient
import psyke.llm.ChatRequestOptions
import psyke.llm.ChatRole

private val logger = KotlinLogging.logger {}

internal interface SuperegoReviewEngine {
    fun review(action: PendingAction, messages: List<ChatMessage>): GateDecision
}

internal class SingleStageSuperegoReviewEngine(
    private val modelClient: ChatModelClient,
    private val config: AgentConfig,
    private val modelTokenWeight: Double,
    private val modelContextWindow: Int? = null,
    private val instrumentation: AgentInstrumentation,
    private val stageLabel: String,
    private val callSiteBase: String,
) : SuperegoReviewEngine {
    override fun review(action: PendingAction, messages: List<ChatMessage>): GateDecision =
        reviewDetailed(action, messages).decision

    fun reviewDetailed(action: PendingAction, messages: List<ChatMessage>): SuperegoStageOutcome {
        val completionTokenBudget = resolveCompletionTokenBudget(messages)
        var response: ChatCompletion? = null
        var lastError: Exception? = null
        val retryAttempts = RetryPolicy.boundedLlmRetryAttempts(config.planner.llmRetryAttempts)
        for (attempt in 1..retryAttempts) {
            try {
                response = modelClient.chat(
                    messages = messages,
                    options = ChatRequestOptions(
                        temperature = 0.0,
                        maxTokens = completionTokenBudget,
                        metadata = ChatCallMetadata(
                            actor = "superego",
                            callSite = callSiteBase,
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
                            "Superego($stageLabel) call failed (attempt $attempt/$retryAttempts); retrying."
                        )
                    )
                }
            }
        }

        if (response == null) {
            logger.warn(lastError) {
                "Superego($stageLabel) review call failed for action=${action.id} type=${action.type}."
            }
            instrumentation.emit(
                AgentEvents.warning("Superego($stageLabel) call failed; denying action by default.")
            )
            return SuperegoStageOutcome(
                decision = GateDecision(
                    allow = false,
                    reason = "Superego unavailable due to model error.",
                    reasonCode = REASON_CODE_TECH_MODEL_UNAVAILABLE
                ),
                confidence = 0.0,
                policyRisk = SuperegoPolicyRisk.HIGH,
                parseFailed = true,
                technicalFallback = true
            )
        }

        var parseResult = parseResponse(response.content, emitWarning = false)
        if (parseResult.parseFailed) {
            instrumentation.emit(
                AgentEvents.warning(
                    "Superego($stageLabel) response was non-parseable; requesting strict JSON retry."
                )
            )
            val retryResponse = requestStrictJsonRetry(
                messages = messages,
                action = action,
                completionTokenBudget = completionTokenBudget
            )
            if (retryResponse == null) {
                instrumentation.emit(
                    AgentEvents.warning("Superego($stageLabel) strict JSON retry call failed; denying action by default.")
                )
            } else {
                parseResult = parseResponse(retryResponse.content, emitWarning = true)
            }
            if (parseResult.parseFailed) {
                instrumentation.emit(
                    AgentEvents.warning("Superego($stageLabel) response remained non-parseable after strict JSON retry.")
                )
            }
        }
        return parseResult
    }

    private fun resolveCompletionTokenBudget(messages: List<ChatMessage>): Int {
        val baseBudget = config.superego.maxCompletionTokens
        if (!config.superego.dynamicCompletionEnabled) {
            return baseBudget
        }
        val resolution = AdaptiveCompletionBudget.resolveDetailed(
            request = AdaptiveCompletionBudget.Request(
                messages = messages,
                baseMaxTokens = baseBudget,
                hardMaxTokens = config.superego.dynamicCompletionHardMaxTokens,
                promptToCompletionRatio = config.superego.dynamicPromptToCompletionRatio,
                minPromptTokensForScaling = config.superego.dynamicCompletionMinPromptTokens,
                modelTokenWeight = modelTokenWeight,
                modelContextWindow = modelContextWindow
            )
        )
        if (resolution.contextClamped) {
            logger.warn {
                "Superego($stageLabel) completion budget clamped by context window " +
                    "(prompt_estimate=${resolution.promptEstimate}, budget=${resolution.budget}, context_window=$modelContextWindow)."
            }
        }
        return resolution.budget
    }

    private fun parseResponse(raw: String, emitWarning: Boolean): SuperegoStageOutcome {
        return try {
            val json = TextSecurity.extractJsonObject(raw)
            val payload = mapper.readValue<SuperegoResponse>(json)
            if (payload.allow == null) {
                logger.warn {
                    "Superego($stageLabel) response missing required 'allow' field. response_len=${raw.length} preview='${TextSecurity.preview(raw, 120)}'"
                }
                return SuperegoStageOutcome(
                    decision = GateDecision(
                        allow = false,
                        reason = "Superego response missing required field.",
                        reasonCode = REASON_CODE_TECH_MISSING_REQUIRED_FIELD
                    ),
                    confidence = 0.0,
                    policyRisk = SuperegoPolicyRisk.HIGH,
                    parseFailed = false,
                    technicalFallback = true
                )
            }
            val allow = payload.allow == true
            val normalizedReasonCode = normalizeReasonCode(payload.reasonCode)
            val decision = GateDecision(
                allow = allow,
                reason = if (allow) {
                    ""
                } else {
                    TextSecurity.clamp(payload.reason?.trim().orEmpty(), MAX_DENY_REASON_CHARS)
                        .ifBlank { "No reason supplied." }
                },
                reasonCode = if (allow) null else normalizedReasonCode ?: REASON_CODE_POLICY_LLM_DENY
            )
            val confidence = payload.confidence?.coerceIn(0.0, 1.0) ?: DEFAULT_MISSING_CONFIDENCE
            val policyRisk = SuperegoPolicyRisk.parse(payload.policyRisk)
                ?: if (allow) SuperegoPolicyRisk.LOW else SuperegoPolicyRisk.MEDIUM
            SuperegoStageOutcome(
                decision = decision,
                confidence = confidence,
                policyRisk = policyRisk,
                parseFailed = false,
                technicalFallback = isTechnicalFallback(decision.reasonCode)
            )
        } catch (ex: Exception) {
            logger.warn(ex) {
                "Failed to parse Superego($stageLabel) response. response_len=${raw.length} preview='${TextSecurity.preview(raw, 120)}'"
            }
            if (emitWarning) {
                instrumentation.emit(AgentEvents.warning("Failed to parse Superego($stageLabel) response."))
            }
            SuperegoStageOutcome(
                decision = GateDecision(
                    allow = false,
                    reason = "Superego response could not be parsed.",
                    reasonCode = REASON_CODE_TECH_PARSE_ERROR
                ),
                confidence = 0.0,
                policyRisk = SuperegoPolicyRisk.HIGH,
                parseFailed = true,
                technicalFallback = true
            )
        }
    }

    private fun requestStrictJsonRetry(
        messages: List<ChatMessage>,
        action: PendingAction,
        completionTokenBudget: Int,
    ): ChatCompletion? {
        val retryMessages = messages + ChatMessage(
            role = ChatRole.USER,
            content = """
                Your previous output was not valid JSON.
                Reply with STRICT JSON only using one schema:
                {"allow":true,"confidence":0.0-1.0,"policy_risk":"low|medium|high"}
                or
                {"allow":false,"reason":"<=${MAX_DENY_REASON_CHARS} chars","reason_code":"TECH_*|POLICY_* optional","confidence":0.0-1.0,"policy_risk":"low|medium|high"}
                Do not include markdown, prose, code fences, or extra keys.
            """.trimIndent()
        )
        return try {
            modelClient.chat(
                messages = retryMessages,
                options = ChatRequestOptions(
                    temperature = 0.0,
                    maxTokens = completionTokenBudget,
                    metadata = ChatCallMetadata(
                        actor = "superego",
                        callSite = "${callSiteBase}_json_retry",
                        actionType = action.type.name.lowercase()
                    )
                )
            )
        } catch (ex: Exception) {
            logger.warn(ex) {
                "Superego($stageLabel) strict JSON retry call failed for action=${action.id} type=${action.type}."
            }
            null
        }
    }

    private fun isTechnicalFallback(reasonCode: String?): Boolean =
        reasonCode?.startsWith("TECH_") == true

    private data class SuperegoResponse(
        val allow: Boolean? = null,
        val reason: String? = null,
        @field:JsonProperty("reason_code")
        val reasonCode: String? = null,
        val confidence: Double? = null,
        @field:JsonProperty("policy_risk")
        val policyRisk: String? = null,
    )

    companion object {
        private const val MAX_DENY_REASON_CHARS: Int = 180
        private const val DEFAULT_MISSING_CONFIDENCE: Double = 0.5
        private const val REASON_CODE_TECH_MODEL_UNAVAILABLE: String = "TECH_MODEL_UNAVAILABLE"
        private const val REASON_CODE_TECH_PARSE_ERROR: String = "TECH_PARSE_ERROR"
        private const val REASON_CODE_TECH_MISSING_REQUIRED_FIELD: String = "TECH_MISSING_REQUIRED_FIELD"
        private const val REASON_CODE_POLICY_LLM_DENY: String = "POLICY_LLM_DENY"

        private val mapper = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

        private fun normalizeReasonCode(raw: String?): String? =
            raw?.trim()
                ?.uppercase()
                ?.replace(Regex("[^A-Z0-9_]+"), "_")
                ?.trim('_')
                ?.ifBlank { null }
    }
}

internal class TwoStageSuperegoReviewEngine(
    private val primary: SingleStageSuperegoReviewEngine,
    private val escalation: SingleStageSuperegoReviewEngine,
    private val config: AgentConfig,
    private val instrumentation: AgentInstrumentation,
) : SuperegoReviewEngine {
    override fun review(action: PendingAction, messages: List<ChatMessage>): GateDecision {
        val primaryOutcome = primary.reviewDetailed(action, messages)
        emitStageEvent(stage = "primary", escalated = false, outcome = primaryOutcome)

        val escalationReason = resolveEscalationReason(primaryOutcome) ?: return primaryOutcome.decision
        instrumentation.emit(
            AgentEvents.warning("Superego two-stage escalation triggered: $escalationReason")
        )

        val escalationOutcome = escalation.reviewDetailed(action, messages)
        emitStageEvent(stage = "escalation", escalated = true, outcome = escalationOutcome)
        return escalationOutcome.decision
    }

    private fun resolveEscalationReason(outcome: SuperegoStageOutcome): String? {
        if (outcome.technicalFallback) {
            return "technical_fallback"
        }
        if (!outcome.decision.allow && outcome.decision.reasonCode == REASON_CODE_POLICY_REDUNDANT) {
            return "policy_redundant_deny"
        }
        if (outcome.confidence < config.superego.twoStageLowConfidenceThreshold) {
            return "low_confidence"
        }
        return when (outcome.policyRisk) {
            SuperegoPolicyRisk.HIGH -> "policy_risk_high"
            SuperegoPolicyRisk.MEDIUM ->
                if (config.superego.twoStageEscalateOnMediumPolicyRisk) "policy_risk_medium" else null

            SuperegoPolicyRisk.LOW -> null
        }
    }

    private companion object {
        private const val REASON_CODE_POLICY_REDUNDANT: String = "POLICY_REDUNDANT"
    }

    private fun emitStageEvent(stage: String, escalated: Boolean, outcome: SuperegoStageOutcome) {
        instrumentation.emit(
            AgentEvent(
                type = "superego_two_stage_review",
                data = mapOf(
                    "stage" to stage,
                    "escalated" to escalated,
                    "allow" to outcome.decision.allow,
                    "reason_code" to outcome.decision.reasonCode,
                    "confidence" to outcome.confidence,
                    "policy_risk" to outcome.policyRisk.wireValue,
                    "parse_failed" to outcome.parseFailed,
                    "technical_fallback" to outcome.technicalFallback
                )
            )
        )
    }
}

internal data class SuperegoStageOutcome(
    val decision: GateDecision,
    val confidence: Double,
    val policyRisk: SuperegoPolicyRisk,
    val parseFailed: Boolean,
    val technicalFallback: Boolean,
)

internal enum class SuperegoPolicyRisk(val wireValue: String) {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high");

    companion object {
        fun parse(raw: String?): SuperegoPolicyRisk? {
            val normalized = raw?.trim()?.lowercase().orEmpty()
            if (normalized.isBlank()) return null
            return entries.firstOrNull { it.wireValue == normalized }
        }
    }
}
