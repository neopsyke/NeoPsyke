package psyke.agent.actions.builtin

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import psyke.agent.actions.ActionDescriptor
import psyke.agent.actions.ActionDeterministicReview
import psyke.agent.actions.ActionExecutionContext
import psyke.agent.actions.AgentActionPlugin
import psyke.agent.actions.AgentActionPluginFactory
import psyke.agent.actions.ActionPluginFactoryContext
import psyke.agent.actions.ReflectionMemoryRecorder
import psyke.agent.model.ActionOutcome
import psyke.agent.model.ActionType
import psyke.agent.config.AgentConfig
import psyke.agent.model.PendingAction
import psyke.agent.model.SuperegoContext
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
                actionErrorCategory = "reflect_payload_invalid",
            )

        val summary = payload.summary.trim()
        val keywords = payload.keywords.map { it.trim() }.filter { it.isNotEmpty() }

        reflectionMemoryRecorder.recordReflection(action = action, summary = summary, keywords = keywords)

        logger.info { "Reflect action recorded: ${summary.take(80)}" }

        return ActionOutcome(
            statusSummary = "Reflection recorded to memory.",
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
