package ai.neopsyke.agent.ego

import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.EgoTrigger
import ai.neopsyke.agent.model.GroundingRequirement
import ai.neopsyke.agent.model.Opportunity
import ai.neopsyke.agent.model.OpportunityKind
import ai.neopsyke.agent.model.OpportunityTrigger
import ai.neopsyke.agent.model.PendingInput
import ai.neopsyke.agent.model.PlannerContext
import ai.neopsyke.agent.model.QueueSnapshot
import ai.neopsyke.agent.model.ScheduledOpportunity
import ai.neopsyke.support.StubChatModelClient
import ai.neopsyke.support.buildTestHierarchicalPlanner
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class GroundingCarrierPropagationTest {

    @Test
    fun `resolved input grounding carries into input opportunity trigger and scheduled opportunity`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponseForCallSite("input_intent_router", """{"route":"direct_response","reasoning":"freshness needed"}""")
            enqueueRawResponseForCallSite("grounding_classifier", """{"grounding_required":true}""")
            enqueueRawResponseForCallSite("direct_response", """{"answer":"Need evidence","summary":"fresh answer","needs_more_context":false}""")
        }
        val planner = buildTestHierarchicalPlanner(modelClient = llm)

        planner.decide(
            EgoTrigger.IncomingInput(PendingInput(id = 1L, content = "what is the weather right now")),
            PlannerContext(
                recentDialogue = emptyList(),
                queue = QueueSnapshot(0, 0, 0),
            )
        )

        val resolvedInput = planner.lastResolvedInput
        assertNotNull(resolvedInput, "Expected InputPlanner to expose the grounded root input")
        assertEquals(GroundingRequirement.REQUIRED, resolvedInput.groundingMetadata.requirement)

        val trigger = OpportunityTrigger.Input(resolvedInput)
        val scheduledOpportunity = ScheduledOpportunity(
            queueId = 1L,
            opportunity = Opportunity(
                id = "opp-1",
                cognitiveThreadId = "thread-1",
                kind = OpportunityKind.RESPOND,
                summary = "Respond",
                salience = 1.0,
                createdAt = Instant.now(),
                conversationContext = ConversationContext.default(),
            ),
            trigger = trigger,
        )

        assertEquals(GroundingRequirement.REQUIRED, trigger.groundingMetadata.requirement)
        assertEquals(GroundingRequirement.REQUIRED, scheduledOpportunity.trigger.groundingMetadata.requirement)
        assertEquals(
            resolvedInput.groundingMetadata,
            (scheduledOpportunity.trigger as OpportunityTrigger.Input).input.groundingMetadata,
        )
    }
}
