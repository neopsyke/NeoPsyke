package ai.neopsyke.agent.cortex.motor.actions

import ai.neopsyke.prompt.PromptCatalog

data class ActionPromptDescriptor(
    val plannerDescription: String,
    val payloadGuidance: String,
    val payloadSchemaExample: String? = null,
    val followUpPrefix: String? = null,
    val superegoDirectives: List<String> = emptyList(),
)

object ActionPromptDescriptors {
    fun load(actionId: String, catalog: PromptCatalog = PromptCatalog.shared): ActionPromptDescriptor {
        val base = "actions/$actionId"
        val description = catalog.renderText("$base/planner-description").text
        val guidance = catalog.renderText("$base/payload-guidance").text
        val example = runCatching { catalog.renderText("$base/payload-schema-example").text }.getOrNull()
        val followUpPrefix = runCatching { catalog.renderText("$base/follow-up-prefix").text }.getOrNull()
        val directives = runCatching { catalog.renderText("$base/superego-directives").text }
            .getOrNull()
            ?.lineSequence()
            ?.map { it.trim().removePrefix("-").trim() }
            ?.filter { it.isNotBlank() }
            ?.toList()
            .orEmpty()
        return ActionPromptDescriptor(
            plannerDescription = description,
            payloadGuidance = guidance,
            payloadSchemaExample = example,
            followUpPrefix = followUpPrefix,
            superegoDirectives = directives,
        )
    }
}
