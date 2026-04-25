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
import ai.neopsyke.prompt.PromptCatalog

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
    private val promptCatalog: PromptCatalog = PromptCatalog.shared,
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

        val prompt = promptCatalog.renderSections(
            "planner/grounding-classifier",
            mapOf("user_input" to trigger.input.content)
        )
        val schema = promptCatalog.responseFormat("grounding-classification")
        val sections = prompt.sections

        val allocation = PromptBudgetAllocator.allocate(sections, config.maxLlmPromptTokens)
        val messages = allocation.messages

        val attempts = maxOf(1, config.llmRetryAttempts)
        for (attempt in 1..attempts) {
            try {
                val response = runtime.call(
                    laneId = LaneId.GROUNDING_CLASSIFIER,
                    messages = messages,
                    metadata = promptCatalog.metadata(metadata, prompt, schema),
                    responseFormat = schema.format,
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

}
