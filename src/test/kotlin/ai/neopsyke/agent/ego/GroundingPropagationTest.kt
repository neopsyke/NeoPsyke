package ai.neopsyke.agent.ego

import ai.neopsyke.agent.cortex.sensory.ActionFeedbackCue
import ai.neopsyke.agent.durablework.DurableWorkActivation
import ai.neopsyke.agent.model.ActionExecutionStatus
import ai.neopsyke.agent.model.ActionOrigin
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.GroundingMetadata
import ai.neopsyke.agent.model.GroundingRequirement
import ai.neopsyke.agent.model.GroundingSource
import ai.neopsyke.agent.model.Intention
import ai.neopsyke.agent.model.IntentionKind
import ai.neopsyke.agent.model.OpportunityTrigger
import ai.neopsyke.agent.model.Percept
import ai.neopsyke.agent.model.PerceptFamily
import ai.neopsyke.agent.model.PendingInput
import ai.neopsyke.agent.model.QueuedIntention
import ai.neopsyke.agent.model.Urgency
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * AC 19: Metadata propagation across carrier chain.
 * Verifies that GroundingMetadata is copied forward unchanged when
 * runtime derives one envelope from another.
 */
class GroundingPropagationTest {

    private val requiredMetadata = GroundingMetadata(GroundingRequirement.REQUIRED, GroundingSource.INPUT_CLASSIFIER)
    private val notRequiredMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER

    // --- Input -> OpportunityTrigger.Input ---

    @Test
    fun `OpportunityTrigger Input exposes PendingInput grounding metadata`() {
        val input = PendingInput(id = 1L, content = "weather in Hamburg", groundingMetadata = requiredMetadata)
        val trigger = OpportunityTrigger.Input(input)
        assertEquals(requiredMetadata, trigger.groundingMetadata)
    }

    @Test
    fun `OpportunityTrigger Input defaults to NOT_REQUIRED_PREFILTER when no grounding specified`() {
        val input = PendingInput(id = 1L, content = "hello")
        val trigger = OpportunityTrigger.Input(input)
        assertEquals(notRequiredMetadata, trigger.groundingMetadata)
    }

    // --- QueuedIntention / QueuedContinuation carriers ---

    @Test
    fun `QueuedIntention carries grounding metadata unchanged`() {
        val intention = QueuedIntention(
            intention = Intention(
                id = "i1",
                cognitiveThreadId = "t1",
                kind = IntentionKind.PREPARE,
                summary = "test",
                createdAt = Instant.now(),
                conversationContext = ConversationContext.default(),
            ),
            urgency = Urgency.MEDIUM,
            groundingMetadata = requiredMetadata,
        )
        assertEquals(requiredMetadata, intention.groundingMetadata)
    }

    @Test
    fun `QueuedIntention preserves NOT_REQUIRED_PREFILTER grounding when default`() {
        val intention = QueuedIntention(
            intention = Intention(
                id = "i2",
                cognitiveThreadId = "t1",
                kind = IntentionKind.PREPARE,
                summary = "test",
                createdAt = Instant.now(),
                conversationContext = ConversationContext.default(),
            ),
            urgency = Urgency.MEDIUM,
            groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
        )
        assertEquals(notRequiredMetadata, intention.groundingMetadata)
    }

    // --- DurableWorkActivation -> OpportunityTrigger.DurableWork ---

    @Test
    fun `OpportunityTrigger GoalWork exposes DurableWorkActivation grounding metadata`() {
        val activation = DurableWorkActivation(
            workItemId = "g1",
            stepId = "s1",
            rootInputId = "r1",
            stepDescription = "Check weather",
            acceptanceCriteria = "Fresh data",
            workingContext = "",
            conversationContext = ConversationContext.default(),
            groundingMetadata = requiredMetadata,
        )
        val trigger = OpportunityTrigger.DurableWork(activation)
        assertEquals(requiredMetadata, trigger.groundingMetadata)
    }

    // --- Impulse -> OpportunityTrigger.Impulse ---

    @Test
    fun `OpportunityTrigger Impulse defaults to NOT_REQUIRED`() {
        val trigger = OpportunityTrigger.Impulse(
            impulse = ai.neopsyke.agent.model.PendingImpulse(
                id = 1L,
                needId = "curiosity",
                prompt = "self-check",
                tension = 0.5,
                rawValue = 0.5,
                rootImpulseId = "imp1",
                conversationContext = ConversationContext.default(),
            )
        )
        assertEquals(GroundingMetadata.NOT_REQUIRED_PREFILTER, trigger.groundingMetadata)
    }

    // --- Feedback -> OpportunityTrigger.Feedback ---

    @Test
    fun `OpportunityTrigger Feedback exposes ActionFeedbackCue grounding metadata`() {
        val cue = ActionFeedbackCue(
            rootInputId = "r1",
            actionType = ActionType.WEB_SEARCH,
            actionSummary = "search",
            feedbackContent = "result",
            statusSummary = "ok",
            plannerSignal = "ok",
            executionStatus = ActionExecutionStatus.SUCCESS,
            conversationContext = ConversationContext.default(),
            groundingMetadata = requiredMetadata,
        )
        val feedback = ai.neopsyke.agent.model.PendingFeedback(
            cue = cue,
            percept = Percept(
                id = "p1",
                family = PerceptFamily.FEEDBACK,
                summary = "feedback",
                source = "test",
                occurredAt = Instant.now(),
                conversationContext = ConversationContext.default(),
                cognitiveThreadId = "t1",
            ),
            stimulusId = "s1",
            stimulusContent = "feedback",
            receivedAtMs = System.currentTimeMillis(),
        )
        val trigger = OpportunityTrigger.Feedback(feedback)
        assertEquals(requiredMetadata, trigger.groundingMetadata)
    }
}
