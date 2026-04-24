package ai.neopsyke.agent.ego.planner.input

import com.fasterxml.jackson.annotation.JsonProperty
import mu.KotlinLogging
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.ego.planner.LaneId
import ai.neopsyke.agent.ego.planner.model.InputRoute
import ai.neopsyke.agent.ego.planner.runtime.PlannerRuntime
import ai.neopsyke.agent.ego.planner.runtime.StructuredOutputHandler
import ai.neopsyke.agent.model.EgoTrigger
import ai.neopsyke.agent.model.GroundingMetadata
import ai.neopsyke.agent.model.GroundingRequirement
import ai.neopsyke.agent.model.GroundingSource
import ai.neopsyke.agent.model.PlannerContext
import ai.neopsyke.agent.support.PromptBudgetAllocator
import ai.neopsyke.instrumentation.AgentEvent
import ai.neopsyke.instrumentation.AgentInstrumentation
import ai.neopsyke.llm.ChatCallMetadata
import ai.neopsyke.llm.ChatResponseFormat
import ai.neopsyke.llm.ChatRole

private val logger = KotlinLogging.logger {}

/**
 * Classifies whether a user input requires grounding (fresh external evidence)
 * before delivering a final answer.
 *
 * Uses a two-tier approach:
 * 1. Deterministic pre-filter on typed [InputRoute] variant (zero LLM cost).
 * 2. Lightweight LLM call for ambiguous routes (DirectResponse, GeneralAction, MultiStepTask).
 *
 * Fail-open behavior: classifier failures are coerced to [GroundingRequirement.NOT_REQUIRED].
 * This is a provisional graceful-degradation choice; it may be tightened if production
 * telemetry shows the fail-open behavior is too permissive.
 */
class GroundingClassifier(
    private val runtime: PlannerRuntime,
    private val config: AgentConfig,
    private val instrumentation: AgentInstrumentation,
) {

    /**
     * Classify grounding requirement for an input trigger given the resolved route.
     * Must be called after [InputIntentRouter.route] and before L2 sub-planner dispatch.
     */
    fun classify(
        route: InputRoute,
        trigger: EgoTrigger.IncomingInput,
        context: PlannerContext,
    ): GroundingMetadata {
        val rootInputId = trigger.input.rootInputId
        val sessionId = context.conversationContext.sessionId

        instrumentation.emit(
            AgentEvent(
                type = "grounding_classification_started",
                data = mapOf(
                    "root_input_id" to rootInputId,
                    "session_id" to sessionId,
                    "route" to route::class.simpleName?.lowercase(),
                )
            )
        )

        val prefilterResult = prefilter(route)
        if (prefilterResult != null) {
            instrumentation.emit(
                AgentEvent(
                    type = "grounding_classification_resolved",
                    data = mapOf(
                        "root_input_id" to rootInputId,
                        "session_id" to sessionId,
                        "requirement" to prefilterResult.requirement.name.lowercase(),
                        "source" to prefilterResult.source.name.lowercase(),
                        "route" to route::class.simpleName?.lowercase(),
                    )
                )
            )
            return prefilterResult
        }

        // Ambiguous route: call LLM classifier
        return classifyViaLlm(trigger, context, route)
    }

    /**
     * Deterministic pre-filter on typed InputRoute variant.
     * Returns null for ambiguous routes that need LLM classification.
     */
    internal fun prefilter(route: InputRoute): GroundingMetadata? =
        when (route) {
            is InputRoute.Assignment -> GroundingMetadata.NOT_REQUIRED_PREFILTER
            is InputRoute.ClarificationNeeded -> GroundingMetadata.NOT_REQUIRED_PREFILTER
            is InputRoute.Noop -> GroundingMetadata.NOT_REQUIRED_PREFILTER
            is InputRoute.DirectResponse -> null
            is InputRoute.GeneralAction -> null
            is InputRoute.MultiStepTask -> null
        }

    private fun classifyViaLlm(
        trigger: EgoTrigger.IncomingInput,
        context: PlannerContext,
        route: InputRoute,
    ): GroundingMetadata {
        val rootInputId = trigger.input.rootInputId
        val sessionId = context.conversationContext.sessionId
        val metadata = ChatCallMetadata(
            actor = "ego",
            cognitiveRole = "grounding_classifier",
            callSite = "grounding_classifier",
            trigger = "grounding_classifier",
            sessionId = sessionId,
            rootInputId = rootInputId,
        )

        val sections = listOf(
            PromptBudgetAllocator.Section(
                key = "grounding_system",
                role = ChatRole.SYSTEM,
                band = PromptBudgetAllocator.Band.REQUIRED_CORE,
                importance = PromptBudgetAllocator.Importance.HIGH,
                floorTokens = 40,
                content = """
                    You are a grounding classifier. Given a user request, determine whether
                    answering it requires fetching up-to-date information from external sources
                    (web search, APIs, live data feeds).

                    Answer true ONLY if the user is asking for facts that change over time and
                    cannot be answered from general knowledge alone. Examples of grounding-required:
                    current weather, live prices, today's news, recent events, real-time scores.

                    Answer false for: opinions, reasoning, explanations, transformations, memory
                    recall, assignment management, static facts, creative tasks, coding help.

                    Return STRICT JSON only: {"grounding_required": true} or {"grounding_required": false}
                """.trimIndent()
            ),
            PromptBudgetAllocator.Section(
                key = "grounding_input",
                role = ChatRole.USER,
                band = PromptBudgetAllocator.Band.REQUIRED_CORE,
                importance = PromptBudgetAllocator.Importance.HIGH,
                floorTokens = 10,
                content = trigger.input.content,
            ),
        )

        val allocation = PromptBudgetAllocator.allocate(sections, config.maxLlmPromptTokens)
        val messages = allocation.messages
        val responseFormat = ChatResponseFormat.JsonSchema(
            name = "grounding_classification",
            schemaJson = GROUNDING_SCHEMA,
            strict = true,
        )

        val attempts = maxOf(1, config.llmRetryAttempts)
        for (attempt in 1..attempts) {
            try {
                val response = runtime.call(
                    laneId = LaneId.GROUNDING_CLASSIFIER,
                    messages = messages,
                    metadata = metadata,
                    responseFormat = responseFormat,
                ) ?: continue
                val parsed = StructuredOutputHandler.parseWithRepair<GroundingClassificationResponse>(response.content)
                if (parsed?.groundingRequired != null) {
                    val requirement = if (parsed.groundingRequired) {
                        GroundingRequirement.REQUIRED
                    } else {
                        GroundingRequirement.NOT_REQUIRED
                    }
                    val result = GroundingMetadata(requirement, GroundingSource.INPUT_CLASSIFIER)
                    instrumentation.emit(
                        AgentEvent(
                            type = "grounding_classification_resolved",
                            data = mapOf(
                                "root_input_id" to rootInputId,
                                "session_id" to sessionId,
                                "requirement" to result.requirement.name.lowercase(),
                                "source" to result.source.name.lowercase(),
                                "route" to route::class.simpleName?.lowercase(),
                            )
                        )
                    )
                    return result
                }
                logger.warn { "grounding_classifier: missing grounding_required field in response (attempt $attempt/$attempts)" }
            } catch (ex: Exception) {
                if (attempt < attempts) {
                    logger.warn(ex) { "grounding_classifier: LLM call failed (attempt $attempt/$attempts), retrying" }
                } else {
                    logger.warn(ex) { "grounding_classifier: LLM call failed after $attempts attempts" }
                }
            }
        }

        // Fail-open: coerce to NOT_REQUIRED. This is provisional and may be tightened later.
        val fallback = GroundingMetadata(GroundingRequirement.NOT_REQUIRED, GroundingSource.INPUT_CLASSIFIER)
        instrumentation.emit(
            AgentEvent(
                type = "grounding_classification_fallback_not_required",
                data = mapOf(
                    "root_input_id" to rootInputId,
                    "session_id" to sessionId,
                    "route" to route::class.simpleName?.lowercase(),
                    "fallback_reason" to "classifier_failure_after_${attempts}_attempts",
                )
            )
        )
        instrumentation.emit(
            AgentEvent(
                type = "grounding_classification_resolved",
                data = mapOf(
                    "root_input_id" to rootInputId,
                    "session_id" to sessionId,
                    "requirement" to fallback.requirement.name.lowercase(),
                    "source" to fallback.source.name.lowercase(),
                    "route" to route::class.simpleName?.lowercase(),
                    "fallback_reason" to "classifier_failure",
                )
            )
        )
        return fallback
    }

    private data class GroundingClassificationResponse(
        @param:JsonProperty("grounding_required") val groundingRequired: Boolean?,
    )

    private companion object {
        private val GROUNDING_SCHEMA: String = """
            {
              "type": "object",
              "properties": {
                "grounding_required": { "type": "boolean" }
              },
              "required": ["grounding_required"],
              "additionalProperties": false
            }
        """.trimIndent()
    }
}
