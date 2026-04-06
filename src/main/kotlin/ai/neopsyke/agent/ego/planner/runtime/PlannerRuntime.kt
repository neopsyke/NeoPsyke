package ai.neopsyke.agent.ego.planner.runtime

import mu.KotlinLogging
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.ego.planner.LaneConfig
import ai.neopsyke.agent.ego.planner.LaneId
import ai.neopsyke.agent.ego.planner.ResolvedLaneConfig
import ai.neopsyke.agent.ego.planner.StructuredOutputMode
import ai.neopsyke.agent.support.LlmCallCircuitBreaker
import ai.neopsyke.agent.support.LlmFailureClassifier
import ai.neopsyke.agent.support.OnTripBehavior
import ai.neopsyke.agent.support.PromptBudgetAllocator
import ai.neopsyke.agent.support.RetryPolicy
import ai.neopsyke.instrumentation.AgentEvent
import ai.neopsyke.instrumentation.AgentEvents
import ai.neopsyke.instrumentation.AgentInstrumentation
import ai.neopsyke.llm.ChatCallMetadata
import ai.neopsyke.llm.ChatCompletion
import ai.neopsyke.llm.ChatMessage
import ai.neopsyke.llm.ChatModelClient
import ai.neopsyke.llm.ChatRequestOptions
import ai.neopsyke.llm.ChatResponseFormat

private val logger = KotlinLogging.logger {}

/**
 * Shared planner runtime: model-call execution with retry, circuit-breaker,
 * strict-to-relaxed schema fallback, and telemetry emission.
 *
 * Extracted from LlmEgoPlanner to be reused across all planner lanes.
 */
class PlannerRuntime(
    private val defaultModelClient: ChatModelClient,
    private val config: AgentConfig,
    private val instrumentation: AgentInstrumentation,
    private val onPlannerNoop: () -> Unit = {},
    private val onPlannerOutputRepaired: () -> Unit = {},
    private val actionPayloadRepair: (ai.neopsyke.agent.model.ActionType, String) -> String = { _, raw -> raw },
) {
    private val circuitBreakers = mutableMapOf<CircuitBreakerKey, LlmCallCircuitBreaker>()

    /**
     * Execute an LLM call for a lane with retry, schema-fallback, and circuit-breaker.
     */
    fun call(
        laneId: LaneId,
        messages: List<ChatMessage>,
        metadata: ChatCallMetadata,
        responseFormat: ChatResponseFormat.JsonSchema,
        temperature: Double? = null,
        maxTokens: Int? = null,
        modelClient: ChatModelClient? = null,
    ): ChatCompletion? {
        val resolved = resolvedConfig(laneId)
        val client = modelClient ?: defaultModelClient
        val effectiveTemp = temperature ?: resolved.temperature
        val effectiveMaxTokens = maxTokens ?: resolved.maxCompletionTokens
        val retryAttempts = RetryPolicy.boundedLlmRetryAttempts(resolved.retryAttempts)

        var response: ChatCompletion? = null
        var lastError: Exception? = null
        var currentFormat: ChatResponseFormat.JsonSchema = responseFormat
        var relaxedSchemaAttempted = false

        for (attempt in 1..retryAttempts) {
            try {
                response = client.chat(
                    messages = messages,
                    options = ChatRequestOptions(
                        temperature = effectiveTemp,
                        maxTokens = effectiveMaxTokens,
                        responseFormat = if (resolved.structuredOutput == StructuredOutputMode.OFF) null else currentFormat,
                        metadata = metadata,
                    )
                )
                break
            } catch (ex: Exception) {
                lastError = ex
                if (!relaxedSchemaAttempted && LlmFailureClassifier.isStructuredOutputSchemaValidationFailure(ex)) {
                    val relaxed = responseFormat.relaxedSchemaJson
                    if (relaxed != null) {
                        currentFormat = ChatResponseFormat.JsonSchema(
                            name = responseFormat.name,
                            schemaJson = relaxed,
                            strict = true,
                        )
                        relaxedSchemaAttempted = true
                        instrumentation.emit(
                            AgentEvents.warning(
                                "Lane ${laneId.configKey} schema validation failed; retrying with relaxed schema."
                            )
                        )
                        continue
                    }
                }
                if (attempt < retryAttempts) {
                    instrumentation.emit(
                        AgentEvents.warning(
                            "Lane ${laneId.configKey} call failed (attempt $attempt/$retryAttempts); retrying."
                        )
                    )
                }
            }
        }
        if (response == null) {
            logger.warn(lastError) { "Lane ${laneId.configKey} call failed for call_site=${metadata.callSite}." }
        }
        return response
    }

    fun resolvedConfig(laneId: LaneId): ResolvedLaneConfig {
        val defaults = config.planner.laneDefaults
        val lane = config.planner.lanes[laneId.configKey] ?: LaneConfig()
        return ResolvedLaneConfig(
            provider = lane.provider ?: defaults.provider,
            model = lane.model ?: defaults.model,
            temperature = lane.temperature ?: defaults.temperature ?: ResolvedLaneConfig.DEFAULT_TEMPERATURE,
            maxCompletionTokens = lane.maxCompletionTokens ?: defaults.maxCompletionTokens
                ?: ResolvedLaneConfig.DEFAULT_MAX_COMPLETION_TOKENS,
            retryAttempts = lane.retryAttempts ?: defaults.retryAttempts
                ?: config.llmRetryAttempts,
            structuredOutput = lane.structuredOutput ?: defaults.structuredOutput
                ?: ResolvedLaneConfig.DEFAULT_STRUCTURED_OUTPUT,
        )
    }

    fun emitPromptBudgetTelemetry(laneId: LaneId, diagnostics: PromptBudgetAllocator.Diagnostics) {
        instrumentation.emit(
            AgentEvent(
                type = "prompt_budget_allocation",
                data = diagnostics.toTelemetryData(callSite = "${laneId.configKey}_prompt"),
            )
        )
    }

    fun recordParseFailure(laneId: LaneId, rootInputId: String?) {
        val key = CircuitBreakerKey(laneId, rootInputId)
        val cb = circuitBreakers.getOrPut(key) {
            LlmCallCircuitBreaker(
                tripThreshold = PARSE_FAILURE_TRIP_THRESHOLD,
                onTripBehavior = OnTripBehavior.BYPASS,
            )
        }
        cb.recordParseFailure()
    }

    fun recordSuccess(laneId: LaneId, rootInputId: String?) {
        val key = CircuitBreakerKey(laneId, rootInputId)
        circuitBreakers[key]?.recordSuccess()
    }

    fun isCircuitOpen(laneId: LaneId, rootInputId: String?): Boolean {
        val key = CircuitBreakerKey(laneId, rootInputId)
        return circuitBreakers[key]?.isTripped() == true
    }

    fun circuitStreak(laneId: LaneId, rootInputId: String?): Int {
        val key = CircuitBreakerKey(laneId, rootInputId)
        return circuitBreakers[key]?.streak() ?: 0
    }

    fun resetAllCircuits(rootInputId: String?) {
        circuitBreakers.keys.removeAll { it.rootInputId == rootInputId }
    }

    fun emitDecision(
        triggerLabel: String,
        decision: ai.neopsyke.agent.model.EgoDecision,
        sessionId: String,
        rootInputId: String?,
    ) {
        when (decision) {
            is ai.neopsyke.agent.model.EgoDecision.EnqueueThought -> {
                instrumentation.emit(
                    AgentEvents.plannerDecision(
                        trigger = triggerLabel,
                        decisionType = "defer",
                        urgency = decision.urgency.name.lowercase(),
                        thought = decision.content,
                        sessionId = sessionId,
                        rootInputId = rootInputId,
                    )
                )
            }
            is ai.neopsyke.agent.model.EgoDecision.FormIntention -> {
                instrumentation.emit(
                    AgentEvents.plannerDecision(
                        trigger = triggerLabel,
                        decisionType = "intention",
                        urgency = decision.urgency.name.lowercase(),
                        intentionKind = decision.intentionKind.name.lowercase(),
                        commitModePreference = decision.commitModePreference.name.lowercase(),
                        actionType = decision.actionType.name.lowercase(),
                        payload = decision.payload,
                        summary = decision.summary,
                        sessionId = sessionId,
                        rootInputId = rootInputId,
                    )
                )
            }
            is ai.neopsyke.agent.model.EgoDecision.EnqueuePlan -> {
                instrumentation.emit(
                    AgentEvents.plannerDecision(
                        trigger = triggerLabel,
                        decisionType = "plan",
                        urgency = decision.urgency.name.lowercase(),
                        thought = decision.goal,
                        reason = "steps=${decision.steps.size}",
                        sessionId = sessionId,
                        rootInputId = rootInputId,
                    )
                )
            }
            is ai.neopsyke.agent.model.EgoDecision.Noop -> {
                onPlannerNoop()
                instrumentation.emit(
                    AgentEvents.plannerDecision(
                        trigger = triggerLabel,
                        decisionType = "noop",
                        reason = decision.reason,
                        sessionId = sessionId,
                        rootInputId = rootInputId,
                    )
                )
            }
        }
    }

    fun onOutputRepaired() = onPlannerOutputRepaired()

    fun repairActionPayload(actionType: ai.neopsyke.agent.model.ActionType, raw: String): String =
        actionPayloadRepair(actionType, raw)

    private data class CircuitBreakerKey(
        val laneId: LaneId,
        val rootInputId: String?,
    )

    companion object {
        const val PARSE_FAILURE_TRIP_THRESHOLD: Int = 3
    }
}
