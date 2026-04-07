package ai.neopsyke.agent.goal

import ai.neopsyke.agent.model.GroundingMetadata
import ai.neopsyke.agent.model.GroundingRequirement
import ai.neopsyke.agent.model.GroundingSource
import ai.neopsyke.agent.model.OpportunityTrigger
import ai.neopsyke.agent.model.ConversationContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * AC 24: Goal-work typed grounding.
 * Verifies that GoalRunActivation carries typed grounding metadata from the
 * goal-step policy and that GoalWorkPlanner consumes it without inspecting
 * free-text step prose.
 */
class GoalWorkGroundingTest {

    @Test
    fun `GoalRunActivation carries REQUIRED from goal step policy`() {
        val activation = GoalRunActivation(
            goalId = "g1",
            stepId = "s1",
            rootInputId = "r1",
            stepDescription = "Check weather in Hamburg",
            acceptanceCriteria = "Fresh weather data",
            workingContext = "",
            conversationContext = ConversationContext.default(),
            groundingMetadata = GroundingMetadata(
                requirement = GroundingRequirement.REQUIRED,
                source = GroundingSource.GOAL_STEP_POLICY,
            ),
        )
        assertNotNull(activation.groundingMetadata)
        assertEquals(GroundingRequirement.REQUIRED, activation.groundingMetadata!!.requirement)
        assertEquals(GroundingSource.GOAL_STEP_POLICY, activation.groundingMetadata!!.source)
    }

    @Test
    fun `GoalRunActivation carries NOT_REQUIRED from goal step policy`() {
        val activation = GoalRunActivation(
            goalId = "g2",
            stepId = "s1",
            rootInputId = "r2",
            stepDescription = "Summarize daily progress",
            acceptanceCriteria = "Summary produced",
            workingContext = "",
            conversationContext = ConversationContext.default(),
            groundingMetadata = GroundingMetadata(
                requirement = GroundingRequirement.NOT_REQUIRED,
                source = GroundingSource.GOAL_STEP_POLICY,
            ),
        )
        assertEquals(GroundingRequirement.NOT_REQUIRED, activation.groundingMetadata!!.requirement)
    }

    @Test
    fun `OpportunityTrigger GoalWork exposes typed grounding from activation`() {
        val activation = GoalRunActivation(
            goalId = "g1",
            stepId = "s1",
            rootInputId = "r1",
            stepDescription = "Get live weather",
            acceptanceCriteria = "Fresh data",
            workingContext = "",
            conversationContext = ConversationContext.default(),
            groundingMetadata = GroundingMetadata(
                requirement = GroundingRequirement.REQUIRED,
                source = GroundingSource.GOAL_STEP_POLICY,
            ),
        )
        val trigger = OpportunityTrigger.GoalWork(activation)
        assertEquals(GroundingRequirement.REQUIRED, trigger.groundingMetadata?.requirement)
    }

    // Negative assertion: changing step prose alone does not alter grounding
    // when the typed policy input remains constant.
    @Test
    fun `changing step prose does not alter grounding when typed policy stays NOT_REQUIRED`() {
        val metadata = GroundingMetadata(
            requirement = GroundingRequirement.NOT_REQUIRED,
            source = GroundingSource.GOAL_STEP_POLICY,
        )

        val activationWithWeatherProse = GoalRunActivation(
            goalId = "g1",
            stepId = "s1",
            rootInputId = "r1",
            stepDescription = "Check today's weather forecast in Hamburg",
            acceptanceCriteria = "Weather data retrieved",
            workingContext = "",
            conversationContext = ConversationContext.default(),
            groundingMetadata = metadata,
        )

        val activationWithGenericProse = GoalRunActivation(
            goalId = "g1",
            stepId = "s1",
            rootInputId = "r1",
            stepDescription = "Generate a summary report",
            acceptanceCriteria = "Report generated",
            workingContext = "",
            conversationContext = ConversationContext.default(),
            groundingMetadata = metadata,
        )

        // Both activations should have the same grounding requirement despite different prose.
        assertEquals(
            activationWithWeatherProse.groundingMetadata,
            activationWithGenericProse.groundingMetadata,
            "Grounding metadata should be determined by typed policy, not prose content"
        )
        assertEquals(GroundingRequirement.NOT_REQUIRED, activationWithWeatherProse.groundingMetadata!!.requirement)
    }
}
