package ai.neopsyke.agent.cortex.motor.actions.plugin.builtin

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import ai.neopsyke.agent.cortex.motor.actions.ActionDescriptor
import ai.neopsyke.agent.cortex.motor.actions.ActionDeterministicReview
import ai.neopsyke.agent.cortex.motor.actions.ActionExecutionContext
import ai.neopsyke.agent.cortex.motor.actions.ActionPluginFactoryContext
import ai.neopsyke.agent.cortex.motor.actions.ActionPromptDescriptors
import ai.neopsyke.agent.cortex.motor.actions.AgentActionPlugin
import ai.neopsyke.agent.cortex.motor.actions.AgentActionPluginFactory
import ai.neopsyke.agent.cortex.motor.actions.ReflectionMemoryRecorder
import ai.neopsyke.agent.model.ActionEffect
import ai.neopsyke.agent.model.ActionExecutionStatus
import ai.neopsyke.agent.model.ActionOutcome
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.model.DataTrust
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.model.SuperegoContext
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

private val mapper: ObjectMapper = ObjectMapper()
    .registerKotlinModule()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

class ReflectInternalActionPlugin(
    private val reflectionMemoryRecorder: ReflectionMemoryRecorder,
) : AgentActionPlugin {
    private val promptDescriptor = ActionPromptDescriptors.load(ActionType.REFLECT_INTERNAL.id)
    override val descriptor: ActionDescriptor = ActionDescriptor(
        actionType = ActionType.REFLECT_INTERNAL,
        dispatchable = true,
        plannerDescription = promptDescriptor.plannerDescription,
        payloadGuidance = promptDescriptor.payloadGuidance,
        payloadSchemaExample = promptDescriptor.payloadSchemaExample,
        requiresFollowUpThought = false,
        followUpPrefix = promptDescriptor.followUpPrefix ?: "Trusted reflection recorded.",
        superegoDirectives = promptDescriptor.superegoDirectives,
        capabilities = emptySet(),
        allowedArgumentDataTrust = setOf(DataTrust.TRUSTED_DATA),
    )

    override fun deterministicReview(
        action: PendingAction,
        context: SuperegoContext,
        config: AgentConfig,
    ): ActionDeterministicReview {
        if (context.threadSecurityContext.aggregatedDataTrust != DataTrust.TRUSTED_DATA) {
            return ActionDeterministicReview(
                allow = false,
                ruleId = "reflect_internal_tainted_context",
                reason = "REFLECT_INTERNAL requires trusted thread data.",
            )
        }
        val payload = parseInternalPayload(action.payload)
            ?: return ActionDeterministicReview(
                allow = false,
                ruleId = "reflect_internal_payload_invalid",
                reason = "REFLECT_INTERNAL payload must be valid JSON with a non-blank summary.",
            )
        if (payload.summary.isBlank()) {
            return ActionDeterministicReview(
                allow = false,
                ruleId = "reflect_internal_summary_blank",
                reason = "REFLECT_INTERNAL summary must not be blank.",
            )
        }
        return ActionDeterministicReview(allow = true)
    }

    override fun repairPlannerPayload(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{")) {
            return """{"summary": ${mapper.writeValueAsString(trimmed)}, "keywords": []}"""
        }
        return raw
    }

    override suspend fun execute(action: PendingAction, context: ActionExecutionContext): ActionOutcome {
        val payload = parseInternalPayload(action.payload)
            ?: return invalidOutcome("reflect_internal_payload_invalid")
        val summary = payload.summary.trim()
        val keywords = payload.keywords.map { it.trim() }.filter { it.isNotEmpty() }
        val saved = reflectionMemoryRecorder.recordInternalReflection(
            action = action,
            summary = summary,
            keywords = keywords,
        )
        if (!saved) {
            logger.info { "Reflect internal action did not persist to durable memory." }
            return failedSaveOutcome("Trusted insight not saved to memory.")
        }
        return successOutcome("Trusted insight saved: $summary")
    }
}

class ReflectEvidenceActionPlugin(
    private val context: ActionPluginFactoryContext,
    private val reflectionMemoryRecorder: ReflectionMemoryRecorder,
) : AgentActionPlugin {
    private val promptDescriptor = ActionPromptDescriptors.load(ActionType.REFLECT_EVIDENCE.id)
    override val descriptor: ActionDescriptor = ActionDescriptor(
        actionType = ActionType.REFLECT_EVIDENCE,
        dispatchable = true,
        plannerDescription = promptDescriptor.plannerDescription,
        payloadGuidance = promptDescriptor.payloadGuidance,
        payloadSchemaExample = promptDescriptor.payloadSchemaExample,
        requiresFollowUpThought = false,
        followUpPrefix = promptDescriptor.followUpPrefix ?: "Evidence-backed reflection recorded.",
        superegoDirectives = promptDescriptor.superegoDirectives,
        capabilities = emptySet(),
        allowedArgumentDataTrust = setOf(DataTrust.SANITIZED_EXTERNAL_DATA),
    )

    override fun deterministicReview(
        action: PendingAction,
        context: SuperegoContext,
        config: AgentConfig,
    ): ActionDeterministicReview {
        parseEvidencePayload(action.payload)
            ?: return ActionDeterministicReview(
                allow = false,
                ruleId = "reflect_evidence_payload_invalid",
                reason = "REFLECT_EVIDENCE payload must be valid JSON with summary_hint or keywords.",
            )
        return ActionDeterministicReview(allow = true)
    }

    override fun repairPlannerPayload(raw: String): String {
        val payload = parseEvidencePayload(raw) ?: return raw
        return mapper.writeValueAsString(
            mapOf(
                "summary_hint" to payload.summaryHint.trim(),
                "keywords" to payload.keywords.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            )
        )
    }

    override suspend fun execute(action: PendingAction, context: ActionExecutionContext): ActionOutcome {
        val payload = parseEvidencePayload(action.payload)
            ?: return invalidOutcome("reflect_evidence_payload_invalid")
        val artifacts = this.context.evidenceArtifactStore.resolveAll(
            rootInputId = action.rootInputId,
            conversationContext = action.conversationContext,
        )
        if (artifacts.isEmpty()) {
            return ActionOutcome(
                statusSummary = "Evidence reflection failed: no artifacts available in scope for this request.",
                executionStatus = ActionExecutionStatus.FAILED,
                actionErrorCategory = "reflect_evidence_no_artifacts_in_scope",
            )
        }
        val saved = reflectionMemoryRecorder.recordEvidenceReflection(
            action = action,
            summaryHint = payload.summaryHint.trim(),
            keywords = payload.keywords.map { it.trim() }.filter { it.isNotEmpty() },
            artifacts = artifacts,
        )
        if (!saved) {
            return failedSaveOutcome("Evidence-backed insight not saved to memory.")
        }
        return successOutcome("Evidence-backed insight saved.")
    }
}

private data class ReflectInternalPayload(
    val summary: String = "",
    val keywords: List<String> = emptyList(),
)

private data class ReflectEvidencePayload(
    val summaryHint: String = "",
    val keywords: List<String> = emptyList(),
)

private fun parseInternalPayload(raw: String): ReflectInternalPayload? =
    runCatching { mapper.readValue<ReflectInternalPayload>(raw) }.getOrNull()

private fun parseEvidencePayload(raw: String): ReflectEvidencePayload? =
    runCatching {
        val node = mapper.readTree(raw)
        ReflectEvidencePayload(
            summaryHint = node.path("summary_hint").asText().ifEmpty { node.path("summaryHint").asText() }.trim(),
            keywords = when {
                node.path("keywords").isArray -> node.path("keywords").mapNotNull { if (it.isTextual) it.asText().trim() else null }.filter { it.isNotBlank() }
                else -> emptyList()
            }
        )
    }.getOrNull()

private fun invalidOutcome(reason: String): ActionOutcome =
    ActionOutcome(
        statusSummary = "Reflection failed: invalid payload.",
        executionStatus = ActionExecutionStatus.FAILED,
        actionErrorCategory = reason,
    )

private fun failedSaveOutcome(plannerSignal: String): ActionOutcome =
    ActionOutcome(
        statusSummary = "Reflection failed: memory save unsuccessful.",
        executionStatus = ActionExecutionStatus.FAILED,
        actionErrorCategory = "reflect_memory_save_failed",
        plannerSignal = plannerSignal,
    )

private fun successOutcome(plannerSignal: String): ActionOutcome =
    ActionOutcome(
        statusSummary = "Reflection recorded to memory.",
        executionStatus = ActionExecutionStatus.SUCCESS,
        effects = setOf(ActionEffect.TASK_PROGRESS, ActionEffect.DURABLE_MEMORY_SAVED),
        plannerSignal = plannerSignal,
    )

class ReflectInternalActionPluginFactory : AgentActionPluginFactory {
    override fun create(context: ActionPluginFactoryContext): AgentActionPlugin =
        ReflectInternalActionPlugin(
            reflectionMemoryRecorder = context.reflectionMemoryRecorder,
        )
}

class ReflectEvidenceActionPluginFactory : AgentActionPluginFactory {
    override fun create(context: ActionPluginFactoryContext): AgentActionPlugin =
        ReflectEvidenceActionPlugin(
            context = context,
            reflectionMemoryRecorder = context.reflectionMemoryRecorder,
        )
}
