package psyke.agent.ego

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import psyke.agent.config.AgentConfig
import psyke.agent.model.EgoTrigger
import psyke.agent.model.PlannerContext
import psyke.agent.support.AdaptiveCompletionBudget
import psyke.agent.support.LlmCallCircuitBreaker
import psyke.agent.support.LlmFailureClassifier
import psyke.agent.support.OnTripBehavior
import psyke.agent.support.RetryPolicy
import psyke.agent.support.TextSecurity
import psyke.instrumentation.AgentEvent
import psyke.instrumentation.AgentInstrumentation
import psyke.instrumentation.NoopAgentInstrumentation
import psyke.llm.ChatCallMetadata
import psyke.llm.ChatCompletion
import psyke.llm.ChatMessage
import psyke.llm.ChatModelClient
import psyke.llm.ChatRequestOptions
import psyke.llm.ChatResponseFormat
import psyke.llm.ChatRole
import java.util.Locale

private val logger = KotlinLogging.logger {}

enum class MetaReasonerVerdict {
    CONTINUE,
    CONTINUE_WITH_CONSTRAINTS,
    FINALIZE_NOW,
    REQUEST_TOOL_THEN_FINALIZE
}

data class MetaReasonerAssessment(
    val verdict: MetaReasonerVerdict,
    val confidence: Double,
    val reason: String,
)

interface MetaReasoner {
    val enabled: Boolean
        get() = true

    fun assess(trigger: EgoTrigger, context: PlannerContext): MetaReasonerAssessment
}

object NoopMetaReasoner : MetaReasoner {
    override val enabled: Boolean = false

    override fun assess(trigger: EgoTrigger, context: PlannerContext): MetaReasonerAssessment =
        MetaReasonerAssessment(
            verdict = MetaReasonerVerdict.CONTINUE,
            confidence = 1.0,
            reason = "meta reasoner disabled"
        )
}

class LlmMetaReasoner(
    private val modelClient: ChatModelClient,
    private val config: AgentConfig,
    private val modelTokenWeight: Double = DEFAULT_MODEL_TOKEN_WEIGHT,
    private val modelContextWindow: Int? = null,
    private val fallbackModelClient: ChatModelClient? = null,
    private val instrumentation: AgentInstrumentation = NoopAgentInstrumentation,
) : MetaReasoner {
    private val circuitBreaker = LlmCallCircuitBreaker(
        tripThreshold = PARSE_FAILURE_TRIP_THRESHOLD,
        onTripBehavior = OnTripBehavior.BYPASS,
    )
    private var emptyContentFailureStreak: Int = 0
    private var schemaValidationFailureStreak: Int = 0

    override fun assess(trigger: EgoTrigger, context: PlannerContext): MetaReasonerAssessment {
        if (circuitBreaker.isTripped()) {
            return MetaReasonerAssessment(
                verdict = MetaReasonerVerdict.CONTINUE,
                confidence = 0.2,
                reason = "Meta reasoner circuit breaker tripped; using safe default."
            )
        }
        val messages = buildMessages(trigger, context)
        val completionBudget = resolveCompletionTokenBudget(messages)
        emitPromptBudgetTelemetry(messages = messages, completionBudget = completionBudget)
        val primaryCall = callModel(
            client = modelClient,
            messages = messages,
            completionBudget = completionBudget,
            callSite = CALL_SITE_PRIMARY
        )
        var resolvedResponse = primaryCall.response
        var resolvedError = primaryCall.lastError
        val primaryFailedWithEmptyContent = LlmFailureClassifier.isEmptyContentTransportFailure(primaryCall.lastError)
        val primaryFailedWithSchemaValidation = LlmFailureClassifier.isStructuredOutputSchemaValidationFailure(primaryCall.lastError)
        if (primaryCall.response == null) {
            if (primaryFailedWithEmptyContent) {
                emptyContentFailureStreak += 1
            } else {
                emptyContentFailureStreak = 0
            }
            if (primaryFailedWithSchemaValidation) {
                schemaValidationFailureStreak += 1
            } else {
                schemaValidationFailureStreak = 0
            }
            if (primaryFailedWithEmptyContent || primaryFailedWithSchemaValidation) {
                circuitBreaker.recordFailure()
            }
            val fallbackTriggerReached =
                emptyContentFailureStreak >= EMPTY_CONTENT_FALLBACK_THRESHOLD ||
                    schemaValidationFailureStreak >= SCHEMA_VALIDATION_FALLBACK_THRESHOLD
            if (fallbackTriggerReached && fallbackModelClient != null) {
                val failureKind = if (schemaValidationFailureStreak >= SCHEMA_VALIDATION_FALLBACK_THRESHOLD) {
                    "schema_validation"
                } else {
                    "empty_content"
                }
                logger.warn {
                    "MetaReasoner primary model returned repeated $failureKind failures " +
                        "(empty_streak=$emptyContentFailureStreak, schema_streak=$schemaValidationFailureStreak); trying fallback model."
                }
                val fallbackCall = callModel(
                    client = fallbackModelClient,
                    messages = messages,
                    completionBudget = completionBudget,
                    callSite = CALL_SITE_FALLBACK
                )
                if (fallbackCall.response != null) {
                    resolvedResponse = fallbackCall.response
                    resolvedError = null
                } else {
                    resolvedError = fallbackCall.lastError
                }
            }
        }
        if (resolvedResponse == null) {
            logger.warn(resolvedError) {
                "MetaReasoner call failed after retries " +
                    "(prompt_estimate=${completionBudget.promptEstimate}, completion_budget=${completionBudget.budget}, " +
                    "context_clamped=${completionBudget.contextClamped})."
            }
            return MetaReasonerAssessment(
                verdict = MetaReasonerVerdict.CONTINUE,
                confidence = 0.2,
                reason = "Meta reasoner unavailable."
            )
        }
        emptyContentFailureStreak = 0
        schemaValidationFailureStreak = 0
        val assessment = parseResponse(resolvedResponse.content)
        if (isParseFailureAssessment(assessment)) {
            circuitBreaker.recordParseFailure()
        } else {
            circuitBreaker.recordSuccess()
        }
        return assessment
    }

    private fun callModel(
        client: ChatModelClient,
        messages: List<ChatMessage>,
        completionBudget: CompletionBudgetResolution,
        callSite: String,
    ): ChatAttemptResult {
        var response: ChatCompletion? = null
        var lastError: Exception? = null
        var responseFormat: ChatResponseFormat.JsonSchema = META_REASONER_RESPONSE_FORMAT_STRICT
        var completionTokenBudget = completionBudget.budget
        val completionRetryCap = if (completionBudget.contextClamped) {
            completionBudget.budget
        } else {
            completionBudget.hardMax
        }
        var relaxedSchemaAttempted = false
        var adaptiveTokenRetryAttempted = false
        val retryAttempts = RetryPolicy.boundedLlmRetryAttempts(config.llmRetryAttempts)
        for (attempt in 1..retryAttempts) {
            try {
                response = client.chat(
                    messages = messages,
                    options = ChatRequestOptions(
                        temperature = 0.0,
                        maxTokens = completionTokenBudget,
                        responseFormat = responseFormat,
                        metadata = ChatCallMetadata(
                            actor = "ego",
                            callSite = callSite
                        )
                    )
                )
                break
            } catch (ex: Exception) {
                lastError = ex
                if (!relaxedSchemaAttempted && LlmFailureClassifier.isStructuredOutputSchemaValidationFailure(ex)) {
                    responseFormat = META_REASONER_RESPONSE_FORMAT_RELAXED
                    relaxedSchemaAttempted = true
                    logger.warn {
                        "MetaReasoner call failed for call_site=$callSite due to schema validation; " +
                            "retrying with relaxed schema (reason maxLength removed)."
                    }
                    continue
                }
                if (!adaptiveTokenRetryAttempted && !completionBudget.contextClamped && LlmFailureClassifier.isEmptyContentTransportFailure(ex)) {
                    val bumpedBudget = minOf(
                        completionRetryCap,
                        completionTokenBudget + maxOf(EMPTY_CONTENT_RETRY_MIN_TOKEN_BUMP, completionTokenBudget / EMPTY_CONTENT_RETRY_DIVISOR)
                    )
                    if (bumpedBudget > completionTokenBudget) {
                        logger.warn {
                            "MetaReasoner call failed for call_site=$callSite with empty content; " +
                                "retrying with increased completion budget ($completionTokenBudget -> $bumpedBudget)."
                        }
                        completionTokenBudget = bumpedBudget
                        adaptiveTokenRetryAttempted = true
                        continue
                    }
                }
                if (attempt < retryAttempts) {
                    logger.warn(ex) {
                        "MetaReasoner call failed for call_site=$callSite (attempt $attempt/$retryAttempts); retrying " +
                            "(prompt_estimate=${completionBudget.promptEstimate}, completion_budget=$completionTokenBudget)."
                    }
                }
            }
        }
        return ChatAttemptResult(response = response, lastError = lastError)
    }

    private fun resolveCompletionTokenBudget(messages: List<ChatMessage>): CompletionBudgetResolution {
        val baseBudget = config.metaReasoner.maxTokens
        val hardMaxBudget = maxOf(baseBudget, config.metaReasoner.dynamicCompletionHardMaxTokens)
        if (!config.metaReasoner.dynamicCompletionEnabled) {
            val promptEstimate = AdaptiveCompletionBudget.estimatePromptTokens(messages)
            return CompletionBudgetResolution(
                budget = baseBudget,
                promptEstimate = promptEstimate,
                contextClamped = false,
                hardMax = hardMaxBudget
            )
        }
        val resolution = AdaptiveCompletionBudget.resolveDetailed(
            request = AdaptiveCompletionBudget.Request(
                messages = messages,
                baseMaxTokens = baseBudget,
                hardMaxTokens = hardMaxBudget,
                promptToCompletionRatio = config.metaReasoner.dynamicPromptToCompletionRatio,
                minPromptTokensForScaling = config.metaReasoner.dynamicCompletionMinPromptTokens,
                modelTokenWeight = modelTokenWeight,
                modelContextWindow = modelContextWindow
            )
        )
        if (resolution.contextClamped) {
            logger.warn {
                "MetaReasoner completion budget clamped by context window " +
                    "(prompt_estimate=${resolution.promptEstimate}, budget=${resolution.budget}, context_window=$modelContextWindow)."
            }
        }
        return CompletionBudgetResolution(
            budget = resolution.budget,
            promptEstimate = resolution.promptEstimate,
            contextClamped = resolution.contextClamped,
            hardMax = hardMaxBudget
        )
    }

    private fun emitPromptBudgetTelemetry(
        messages: List<ChatMessage>,
        completionBudget: CompletionBudgetResolution,
    ) {
        val estimatedPromptTokens = completionBudget.promptEstimate
        instrumentation.emit(
            AgentEvent(
                type = "prompt_budget_allocation",
                data = mapOf(
                    "call_site" to META_REASONER_PROMPT_CALL_SITE,
                    "max_tokens" to estimatedPromptTokens,
                    "section_count" to messages.size,
                    "emitted_message_count" to messages.size,
                    "estimated_content_tokens" to estimatedPromptTokens,
                    "estimated_total_cost" to estimatedPromptTokens,
                    "allocated_content_tokens" to estimatedPromptTokens,
                    "allocated_total_cost" to estimatedPromptTokens,
                    "reserved_floor_cost" to 0,
                    "floor_budget_feasible" to true,
                    "single_message_fallback" to false,
                    "degradation_path" to "none",
                    "dropped_section_count" to 0,
                    "floor_violation_count" to 0,
                    "meta_completion_budget" to completionBudget.budget,
                    "meta_completion_hard_max" to completionBudget.hardMax,
                    "meta_context_clamped" to completionBudget.contextClamped
                )
            )
        )
    }

    private fun isParseFailureAssessment(assessment: MetaReasonerAssessment): Boolean {
        val reason = assessment.reason.lowercase()
        return reason.contains("parse fallback") || reason.contains("missing verdict")
    }

    private fun buildMessages(trigger: EgoTrigger, context: PlannerContext): List<ChatMessage> {
        val triggerLabel = when (trigger) {
            is EgoTrigger.IncomingInput -> "input"
            is EgoTrigger.PendingThoughtInput -> "thought"
            is EgoTrigger.IncomingImpulse -> "impulse"
            is EgoTrigger.ProjectWork -> "project-work"
        }
        val triggerText = when (trigger) {
            is EgoTrigger.IncomingInput -> trigger.input.content
            is EgoTrigger.PendingThoughtInput -> trigger.thought.content
            is EgoTrigger.IncomingImpulse -> trigger.impulse.prompt
            is EgoTrigger.ProjectWork -> trigger.workUnit.stepDescription
        }
        val dialogue = context.recentDialogue
            .takeLast(8)
            .joinToString(separator = "\n") { turn ->
                "${turn.role.name.lowercase()}: ${TextSecurity.preview(turn.content, 140)}"
            }
            .ifBlank { "none" }
        val longTermMemoryRecall = context.longTermMemoryRecall.ifBlank { "none" }
        val shortTermContextSummary = context.shortTermContextSummary.ifBlank { "none" }
        val deliberation = context.deliberation
        return listOf(
            ChatMessage(
                role = ChatRole.SYSTEM,
                content = """
                You are MetaReasoner for Ego's thought loop.
                Decide if continued deliberation is productive or stale.
                Return only data that matches the response format schema.
                Use finalize_now when repeated loops or high pressure suggest diminishing returns.
                Use request_tool_then_finalize only if one decisive external action can unlock a better final answer.
                """.trimIndent()
            ),
            ChatMessage(
                role = ChatRole.USER,
                content = """
                Trigger:
                type=$triggerLabel
                content=${TextSecurity.preview(triggerText, 240)}

                Deliberation state:
                step_index=${deliberation.stepIndex}
                decision_pressure=${String.format(Locale.ROOT, "%.3f", deliberation.decisionPressure)}
                stale_streak=${deliberation.staleStreak}
                progress_score=${String.format(Locale.ROOT, "%.3f", deliberation.progressScore)}
                denial_count=${deliberation.denialCount}
                steps_since_new_evidence=${deliberation.stepsSinceNewEvidence}
                repeat_signature_hits=${deliberation.repeatSignatureHits}
                noop_streak=${deliberation.noopStreak}

                Queue:
                pending_inputs=${context.queue.pendingInputCount}
                pending_thoughts=${context.queue.pendingThoughtCount}
                pending_actions=${context.queue.pendingActionCount}

                Long-term memory recall:
                $longTermMemoryRecall

                Short-term context summary:
                $shortTermContextSummary

                Recent dialogue:
                $dialogue
                """.trimIndent()
            )
        )
    }

    private fun parseResponse(raw: String): MetaReasonerAssessment {
        return try {
            val json = TextSecurity.extractJsonObject(raw)
            val payload = mapper.readValue<MetaReasonerPayload>(json)
            if (payload.verdict.isNullOrBlank()) {
                logger.warn {
                    "MetaReasoner response missing required 'verdict' field. response_len=${raw.length} preview='${TextSecurity.preview(raw, 120)}'"
                }
                return MetaReasonerAssessment(
                    verdict = MetaReasonerVerdict.CONTINUE,
                    confidence = 0.2,
                    reason = "Meta reasoner: missing verdict field."
                )
            }
            MetaReasonerAssessment(
                verdict = resolveVerdict(payload.verdict),
                confidence = payload.confidence?.coerceIn(0.0, 1.0) ?: 0.5,
                reason = TextSecurity.clamp(payload.reason?.trim().orEmpty().ifBlank { "No reason provided." }, META_REASONER_REASON_MAX_CHARS)
            )
        } catch (ex: Exception) {
            // Attempt truncation-tolerant extraction before falling back.
            val salvaged = salvageVerdictFromTruncated(raw)
            if (salvaged != null) {
                logger.info {
                    "MetaReasoner response was truncated but verdict salvaged: ${salvaged.verdict}. response_len=${raw.length}"
                }
                return salvaged
            }
            logger.warn(ex) {
                "Failed to parse MetaReasoner response. response_len=${raw.length} preview='${TextSecurity.preview(raw, 120)}'"
            }
            MetaReasonerAssessment(
                verdict = MetaReasonerVerdict.CONTINUE,
                confidence = 0.2,
                reason = "Meta reasoner parse fallback."
            )
        }
    }

    /**
     * Extracts a verdict from a truncated JSON response where the closing brace
     * is missing. For example: `{"verdict":"finalize_now","confidence":0.97,"reason":"Sufficient info to`
     */
    private fun salvageVerdictFromTruncated(raw: String): MetaReasonerAssessment? {
        if (raw.isBlank()) return null
        val verdictMatch = TRUNCATED_VERDICT_REGEX.find(raw) ?: return null
        val verdictStr = verdictMatch.groupValues[1].trim()
        val verdict = resolveVerdict(verdictStr)
        // Only salvage non-continue verdicts — a truncated "continue" adds no value.
        if (verdict == MetaReasonerVerdict.CONTINUE) return null
        val confidence = TRUNCATED_CONFIDENCE_REGEX.find(raw)
            ?.groupValues?.get(1)?.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 0.6
        return MetaReasonerAssessment(
            verdict = verdict,
            confidence = confidence,
            reason = "Verdict salvaged from truncated response."
        )
    }

    private fun resolveVerdict(raw: String?): MetaReasonerVerdict {
        val normalized = raw?.trim()?.lowercase() ?: return MetaReasonerVerdict.CONTINUE
        return MetaReasonerVerdict.entries.firstOrNull {
            it.name.equals(normalized, ignoreCase = true)
        } ?: when (normalized) {
            "continue_with_constraints" -> MetaReasonerVerdict.CONTINUE_WITH_CONSTRAINTS
            "finalize_now" -> MetaReasonerVerdict.FINALIZE_NOW
            "request_tool_then_finalize" -> MetaReasonerVerdict.REQUEST_TOOL_THEN_FINALIZE
            else -> MetaReasonerVerdict.CONTINUE
        }
    }

    private data class MetaReasonerPayload(
        val verdict: String? = null,
        val confidence: Double? = null,
        val reason: String? = null,
    )

    private data class ChatAttemptResult(
        val response: ChatCompletion? = null,
        val lastError: Exception? = null,
    )

    private data class CompletionBudgetResolution(
        val budget: Int,
        val promptEstimate: Int,
        val contextClamped: Boolean,
        val hardMax: Int,
    )

    internal companion object {
        private const val PARSE_FAILURE_TRIP_THRESHOLD: Int = 3
        private const val EMPTY_CONTENT_FALLBACK_THRESHOLD: Int = 2
        private const val SCHEMA_VALIDATION_FALLBACK_THRESHOLD: Int = 2
        private const val EMPTY_CONTENT_RETRY_MIN_TOKEN_BUMP: Int = 64
        private const val EMPTY_CONTENT_RETRY_DIVISOR: Int = 2
        private const val CALL_SITE_PRIMARY: String = "meta_reasoner"
        private const val CALL_SITE_FALLBACK: String = "meta_reasoner_fallback"
        private const val META_REASONER_PROMPT_CALL_SITE: String = "meta_reasoner_prompt"
        private const val META_REASONER_REASON_MAX_CHARS: Int = 180
        private const val DEFAULT_MODEL_TOKEN_WEIGHT: Double = 1.0
        private const val META_REASONER_RESPONSE_SCHEMA_STRICT: String = """
            {
              "type": "object",
              "additionalProperties": false,
              "required": ["verdict", "confidence", "reason"],
              "properties": {
                "verdict": {
                  "type": "string",
                  "enum": ["continue", "continue_with_constraints", "finalize_now", "request_tool_then_finalize"]
                },
                "confidence": {
                  "type": "number",
                  "minimum": 0.0,
                  "maximum": 1.0
                },
                "reason": {
                  "type": "string",
                  "maxLength": 180
                }
              }
            }
        """

        private const val META_REASONER_RESPONSE_SCHEMA_RELAXED: String = """
            {
              "type": "object",
              "additionalProperties": false,
              "required": ["verdict", "confidence", "reason"],
              "properties": {
                "verdict": {
                  "type": "string",
                  "enum": ["continue", "continue_with_constraints", "finalize_now", "request_tool_then_finalize"]
                },
                "confidence": {
                  "type": "number",
                  "minimum": 0.0,
                  "maximum": 1.0
                },
                "reason": {
                  "type": "string"
                }
              }
            }
        """

        val mapper = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

        val META_REASONER_RESPONSE_FORMAT_STRICT: ChatResponseFormat.JsonSchema =
            ChatResponseFormat.JsonSchema(
                name = "meta_reasoner_assessment",
                schemaJson = META_REASONER_RESPONSE_SCHEMA_STRICT,
                strict = true
            )

        val META_REASONER_RESPONSE_FORMAT_RELAXED: ChatResponseFormat.JsonSchema =
            ChatResponseFormat.JsonSchema(
                name = "meta_reasoner_assessment",
                schemaJson = META_REASONER_RESPONSE_SCHEMA_RELAXED,
                strict = true
            )

        /** Matches `"verdict":"<value>"` even if the surrounding JSON is truncated. */
        internal val TRUNCATED_VERDICT_REGEX =
            Regex(""""verdict"\s*:\s*"([^"]+)"""")

        /** Matches `"confidence":<number>` from a truncated JSON fragment. */
        internal val TRUNCATED_CONFIDENCE_REGEX =
            Regex(""""confidence"\s*:\s*([0-9]+(?:\.[0-9]+)?)""")
    }
}
