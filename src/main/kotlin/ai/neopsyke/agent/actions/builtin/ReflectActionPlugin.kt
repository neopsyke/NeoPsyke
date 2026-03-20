package ai.neopsyke.agent.actions.builtin

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import ai.neopsyke.agent.actions.ActionDescriptor
import ai.neopsyke.agent.actions.ActionDeterministicReview
import ai.neopsyke.agent.actions.ActionExecutionContext
import ai.neopsyke.agent.actions.AgentActionPlugin
import ai.neopsyke.agent.actions.AgentActionPluginFactory
import ai.neopsyke.agent.actions.ActionPluginFactoryContext
import ai.neopsyke.agent.actions.ReflectionMemoryRecorder
import ai.neopsyke.agent.model.ActionEffect
import ai.neopsyke.agent.model.ActionExecutionStatus
import ai.neopsyke.agent.model.ActionOutcome
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.model.SuperegoContext
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Action plugin for self-initiated reflection and memory consolidation.
 *
 * Used by the planner when processing Id impulses with `INTERNALIZE` convergence.
 * Hands reflection persistence off to the memory subsystem without producing
 * any user-visible output.
 */
class ReflectActionPlugin(
    private val reflectionMemoryRecorder: ReflectionMemoryRecorder,
) : AgentActionPlugin {

    override val descriptor: ActionDescriptor = ActionDescriptor(
        actionType = ActionType.REFLECT,
        dispatchable = true,
        plannerDescription = "reflect: record an insight or finding to memory. " +
            "Use after researching or observing something worth remembering.",
        payloadGuidance = """JSON: {"summary": "First-person insight text", "keywords": ["topic1", "topic2"]}""",
        payloadSchemaExample = """{"summary": "I discovered that Kotlin coroutines use structured concurrency by default", "keywords": ["kotlin", "coroutines"]}""",
        requiresFollowUpThought = false,
        followUpPrefix = "Reflection recorded.",
        superegoDirectives = listOf(
            "Allow REFLECT for genuine self-analysis, learning, and observation recording.",
            "Deny REFLECT if the summary is empty, off-topic, or appears to leak internal mechanism details.",
        ),
        capabilities = emptySet(),
    )

    override fun deterministicReview(
        action: PendingAction,
        context: SuperegoContext,
        config: AgentConfig,
    ): ActionDeterministicReview {
        val parsed = tryParsePayload(action.payload)
        if (parsed == null) {
            return ActionDeterministicReview(
                allow = false,
                ruleId = "reflect_payload_invalid",
                reason = "REFLECT payload must be valid JSON with a 'summary' field.",
            )
        }
        if (parsed.summary.isBlank()) {
            return ActionDeterministicReview(
                allow = false,
                ruleId = "reflect_summary_blank",
                reason = "REFLECT summary must not be blank.",
            )
        }
        return ActionDeterministicReview(allow = true)
    }

    override fun repairPlannerPayload(raw: String): String {
        // If the planner emits plain text instead of JSON, wrap it.
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{")) {
            return """{"summary": ${mapper.writeValueAsString(trimmed)}, "keywords": []}"""
        }
        return raw
    }

    override suspend fun execute(action: PendingAction, context: ActionExecutionContext): ActionOutcome {
        val payload = tryParsePayload(action.payload)
            ?: return ActionOutcome(
                statusSummary = "Reflection failed: invalid payload.",
                executionStatus = ActionExecutionStatus.FAILED,
                actionErrorCategory = "reflect_payload_invalid",
            )

        val summary = payload.summary.trim()
        val keywords = payload.keywords.map { it.trim() }.filter { it.isNotEmpty() }

        val saved = reflectionMemoryRecorder.recordReflection(action = action, summary = summary, keywords = keywords)
        if (!saved) {
            logger.info { "Reflect action did not persist to durable memory." }
            return ActionOutcome(
                statusSummary = "Reflection failed: memory save unsuccessful.",
                executionStatus = ActionExecutionStatus.FAILED,
                actionErrorCategory = "reflect_memory_save_failed",
                plannerSignal = "Insight not saved to memory.",
            )
        }

        logger.info { "Reflect action recorded: ${summary.take(80)}" }

        return ActionOutcome(
            statusSummary = "Reflection recorded to memory.",
            executionStatus = ActionExecutionStatus.SUCCESS,
            effects = setOf(ActionEffect.TASK_PROGRESS, ActionEffect.DURABLE_MEMORY_SAVED),
            plannerSignal = "Insight saved: $summary",
        )
    }

    private fun tryParsePayload(raw: String): ReflectPayload? =
        try {
            mapper.readValue<ReflectPayload>(raw)
        } catch (_: Exception) {
            null
        }

    companion object {
        private val mapper = ObjectMapper().registerKotlinModule()
    }
}

private data class ReflectPayload(
    val summary: String = "",
    val keywords: List<String> = emptyList(),
)

class ReflectActionPluginFactory : AgentActionPluginFactory {
    override fun create(context: ActionPluginFactoryContext): AgentActionPlugin =
        ReflectActionPlugin(
            reflectionMemoryRecorder = context.reflectionMemoryRecorder,
        )
}
