package ai.neopsyke.agent.durablework

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
 * AC 24: Goal-work typed grounding.
 * Verifies that DurableWorkActivation carries typed grounding metadata from the
 * goal-step policy and that DurableWorkLanePlanner consumes it without inspecting
 * free-text step prose for grounding semantics.
 */
class GoalWorkGroundingTest {

    @Test
    fun `DurableWorkActivation carries REQUIRED from goal step policy`() {
        val activation = activation(
            stepDescription = "Check weather in Hamburg",
            requirement = GroundingRequirement.REQUIRED,
        )

        assertNotNull(activation.groundingMetadata)
        assertEquals(GroundingRequirement.REQUIRED, activation.groundingMetadata.requirement)
        assertEquals(GroundingSource.DURABLE_WORK_STEP_POLICY, activation.groundingMetadata.source)
    }

    @Test
    fun `OpportunityTrigger GoalWork exposes typed grounding from activation`() {
        val trigger = OpportunityTrigger.DurableWork(
            activation(
                stepDescription = "Get live weather",
                requirement = GroundingRequirement.REQUIRED,
            )
        )

        assertEquals(GroundingRequirement.REQUIRED, trigger.groundingMetadata.requirement)
        assertEquals(GroundingSource.DURABLE_WORK_STEP_POLICY, trigger.groundingMetadata.source)
    }

    @Test
    fun `DurableWorkLanePlanner includes grounding prompt when typed policy requires it`() {
        val promptText = goalWorkPromptFor(
            activation(
                stepDescription = "Summarize the latest build diagnostics",
                requirement = GroundingRequirement.REQUIRED,
            )
        )

        assertTrue(
            promptText.contains("GROUNDING REQUIREMENT"),
            "DurableWorkLanePlanner must use typed goal-step grounding policy when it is REQUIRED."
        )
    }

    @Test
    fun `changing goal step prose alone does not add grounding prompt when typed policy stays NOT_REQUIRED`() {
        val weatherLikePrompt = goalWorkPromptFor(
            activation(
                stepDescription = "Check today's weather forecast in Hamburg",
                requirement = GroundingRequirement.NOT_REQUIRED,
            )
        )
        val genericPrompt = goalWorkPromptFor(
            activation(
                stepDescription = "Generate a summary report",
                requirement = GroundingRequirement.NOT_REQUIRED,
            )
        )

        assertTrue(
            !weatherLikePrompt.contains("GROUNDING REQUIREMENT"),
            "Weather-like prose must not override a NOT_REQUIRED goal-step policy."
        )
        assertTrue(
            !genericPrompt.contains("GROUNDING REQUIREMENT"),
            "DurableWorkLanePlanner must omit grounding guidance when the typed policy is NOT_REQUIRED."
        )
    }

    private fun goalWorkPromptFor(activation: DurableWorkActivation): String {
        val llm = StubChatModelClient().apply {
            enqueueRawResponseForCallSite(
                "durable_work",
                """{"decision":"intend","urgency":"medium","intention_kind":"observe","commit_mode_preference":"not_applicable","action_type":"contact_user","action_payload":"done","action_summary":"done"}"""
            )
        }
        val planner = buildTestHierarchicalPlanner(modelClient = llm)

        planner.decide(
            EgoTrigger.DurableWork(activation),
            PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0),
                groundingMetadata = activation.groundingMetadata,
            )
        )

        val goalWorkCall = llm.calls.lastOrNull { it.options.metadata.callSite == "durable_work" }
        assertNotNull(goalWorkCall, "Expected DurableWorkLanePlanner LLM call")
        return goalWorkCall.messages.joinToString("\n") { it.content }
    }

    private fun activation(
        stepDescription: String,
        requirement: GroundingRequirement,
    ): DurableWorkActivation =
        DurableWorkActivation(
            workItemId = "goal-1",
            stepId = "step-1",
            rootInputId = "goal-root-1",
            stepDescription = stepDescription,
            acceptanceCriteria = "Produce a result",
            workingContext = "Goal context",
            conversationContext = ConversationContext.default(),
            groundingMetadata = GroundingMetadata(
                requirement = requirement,
                source = GroundingSource.DURABLE_WORK_STEP_POLICY,
            ),
        )
}
