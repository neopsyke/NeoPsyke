package ai.neopsyke.agent.ego

import ai.neopsyke.agent.model.GroundingMetadata
import ai.neopsyke.agent.model.GroundingRequirement
import ai.neopsyke.agent.model.GroundingSource
import ai.neopsyke.support.RecordingInstrumentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GroundingMetadataResolverTest {

    @Test
    fun `inherited metadata is returned unchanged when present`() {
        val instrumentation = RecordingInstrumentation()
        val metadata = GroundingMetadata(GroundingRequirement.REQUIRED, GroundingSource.INPUT_CLASSIFIER)

        val resolved = GroundingMetadataResolver.inheritedOrDefault(
            metadata = metadata,
            rootInputId = "root-1",
            triggerType = "deferred_intention",
            instrumentation = instrumentation,
        )

        assertEquals(metadata, resolved)
        assertTrue(
            instrumentation.events.none {
                it.type == GroundingMetadataResolver.EVENT_TYPE_GROUNDING_METADATA_MISSING_INHERITED
            }
        )
    }

    @Test
    fun `missing inherited metadata emits warning and falls back to not required`() {
        val instrumentation = RecordingInstrumentation()

        val resolved = GroundingMetadataResolver.inheritedOrDefault(
            metadata = null,
            rootInputId = "root-2",
            triggerType = "action_feedback",
            instrumentation = instrumentation,
        )

        assertEquals(GroundingRequirement.NOT_REQUIRED, resolved.requirement)
        assertEquals(GroundingSource.INHERITED, resolved.source)
        assertTrue(
            instrumentation.events.any {
                it.type == GroundingMetadataResolver.EVENT_TYPE_GROUNDING_METADATA_MISSING_INHERITED &&
                    it.data["root_input_id"] == "root-2" &&
                    it.data["trigger_type"] == "action_feedback"
            },
            "Expected explicit grounding inheritance warning event."
        )
        assertTrue(
            instrumentation.events.any {
                it.type == "warning" &&
                    it.data["message"]?.toString()?.contains("Expected inherited grounding metadata") == true
            },
            "Expected visible warning when inherited grounding metadata is missing."
        )
    }
}
