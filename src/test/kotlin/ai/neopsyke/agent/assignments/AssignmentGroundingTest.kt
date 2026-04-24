package ai.neopsyke.agent.assignments

import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.EgoTrigger
import ai.neopsyke.agent.model.GroundingMetadata
import ai.neopsyke.agent.model.GroundingRequirement
import ai.neopsyke.agent.model.GroundingSource
import ai.neopsyke.agent.model.OpportunityTrigger
import ai.neopsyke.agent.model.PlannerContext
import ai.neopsyke.agent.model.QueueSnapshot
import ai.neopsyke.support.StubChatModelClient
import ai.neopsyke.support.buildTestHierarchicalPlanner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * AC 24: Assignment-work typed grounding.
 * Verifies that AssignmentActivation carries typed grounding metadata from the
 * assignment-step policy and that AssignmentLanePlanner consumes it without inspecting
 * free-text step prose for grounding semantics.
 */
class AssignmentGroundingTest {

    @Test
    fun `AssignmentActivation carries REQUIRED from assignment step policy`() {
        val activation = activation(
            stepDescription = "Check weather in Hamburg",
            requirement = GroundingRequirement.REQUIRED,
        )

        assertNotNull(activation.groundingMetadata)
        assertEquals(GroundingRequirement.REQUIRED, activation.groundingMetadata.requirement)
        assertEquals(GroundingSource.ASSIGNMENT_STEP_POLICY, activation.groundingMetadata.source)
    }

    @Test
    fun `OpportunityTrigger Assignment exposes typed grounding from activation`() {
        val trigger = OpportunityTrigger.Assignment(
            activation(
                stepDescription = "Get live weather",
                requirement = GroundingRequirement.REQUIRED,
            )
        )

        assertEquals(GroundingRequirement.REQUIRED, trigger.groundingMetadata.requirement)
        assertEquals(GroundingSource.ASSIGNMENT_STEP_POLICY, trigger.groundingMetadata.source)
    }

    @Test
    fun `AssignmentLanePlanner includes grounding prompt when typed policy requires it`() {
        val promptText = assignmentPromptFor(
            activation(
                stepDescription = "Summarize the latest build diagnostics",
                requirement = GroundingRequirement.REQUIRED,
            )
        )

        assertTrue(
            promptText.contains("GROUNDING REQUIREMENT"),
            "AssignmentLanePlanner must use typed assignment-step grounding policy when it is REQUIRED."
        )
    }

    @Test
    fun `changing assignment step prose alone does not add grounding prompt when typed policy stays NOT_REQUIRED`() {
        val weatherLikePrompt = assignmentPromptFor(
            activation(
                stepDescription = "Check today's weather forecast in Hamburg",
                requirement = GroundingRequirement.NOT_REQUIRED,
            )
        )
        val genericPrompt = assignmentPromptFor(
            activation(
                stepDescription = "Generate a summary report",
                requirement = GroundingRequirement.NOT_REQUIRED,
            )
        )

        assertTrue(
            !weatherLikePrompt.contains("GROUNDING REQUIREMENT"),
            "Weather-like prose must not override a NOT_REQUIRED assignment-step policy."
        )
        assertTrue(
            !genericPrompt.contains("GROUNDING REQUIREMENT"),
            "AssignmentLanePlanner must omit grounding guidance when the typed policy is NOT_REQUIRED."
        )
    }

    private fun assignmentPromptFor(activation: AssignmentActivation): String {
        val llm = StubChatModelClient().apply {
            enqueueRawResponseForCallSite(
                "assignment",
                """{"decision":"intend","urgency":"medium","intention_kind":"observe","commit_mode_preference":"not_applicable","action_type":"contact_user","action_payload":"done","action_summary":"done"}"""
            )
        }
        val planner = buildTestHierarchicalPlanner(modelClient = llm)

        planner.decide(
            EgoTrigger.Assignment(activation),
            PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0),
                groundingMetadata = activation.groundingMetadata,
            )
        )

        val assignmentCall = llm.calls.lastOrNull { it.options.metadata.callSite == "assignment" }
        assertNotNull(assignmentCall, "Expected AssignmentLanePlanner LLM call")
        return assignmentCall.messages.joinToString("\n") { it.content }
    }

    private fun activation(
        stepDescription: String,
        requirement: GroundingRequirement,
    ): AssignmentActivation =
        AssignmentActivation(
            workItemId = "assignment-1",
            stepId = "step-1",
            rootInputId = "assignment-root-1",
            stepDescription = stepDescription,
            acceptanceCriteria = "Produce a result",
            workingContext = "Assignment context",
            conversationContext = ConversationContext.default(),
            groundingMetadata = GroundingMetadata(
                requirement = requirement,
                source = GroundingSource.ASSIGNMENT_STEP_POLICY,
            ),
        )
}
