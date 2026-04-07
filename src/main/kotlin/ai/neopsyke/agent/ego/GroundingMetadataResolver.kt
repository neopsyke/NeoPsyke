package ai.neopsyke.agent.ego

import ai.neopsyke.agent.model.GroundingMetadata
import ai.neopsyke.agent.model.GroundingRequirement
import ai.neopsyke.agent.model.GroundingSource
import ai.neopsyke.instrumentation.AgentEvent
import ai.neopsyke.instrumentation.AgentEvents
import ai.neopsyke.instrumentation.AgentInstrumentation

internal object GroundingMetadataResolver {
    fun inheritedOrDefault(
        metadata: GroundingMetadata?,
        rootInputId: String?,
        triggerType: String,
        instrumentation: AgentInstrumentation,
    ): GroundingMetadata {
        if (metadata != null) {
            return metadata
        }

        instrumentation.emit(
            AgentEvent(
                type = EVENT_TYPE_GROUNDING_METADATA_MISSING_INHERITED,
                data = mapOf(
                    "root_input_id" to rootInputId,
                    "trigger_type" to triggerType,
                    "fallback_requirement" to GroundingRequirement.NOT_REQUIRED.name.lowercase(),
                    "fallback_source" to GroundingSource.INHERITED.name.lowercase(),
                )
            )
        )
        instrumentation.emit(
            AgentEvents.warning(
                "Expected inherited grounding metadata for $triggerType trigger but none was present; " +
                    "defaulting to not_required/inherited."
            )
        )
        return GroundingMetadata(
            requirement = GroundingRequirement.NOT_REQUIRED,
            source = GroundingSource.INHERITED,
        )
    }

    const val EVENT_TYPE_GROUNDING_METADATA_MISSING_INHERITED: String = "grounding_metadata_missing_inherited"
}
