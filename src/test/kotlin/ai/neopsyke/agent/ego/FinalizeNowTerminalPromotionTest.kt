package ai.neopsyke.agent.ego

import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.config.MetaReasonerConfig
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.EgoDecision
import ai.neopsyke.agent.model.GroundingMetadata
import ai.neopsyke.agent.model.GroundingRequirement
import ai.neopsyke.agent.model.GroundingSource
import ai.neopsyke.agent.model.IntentionKind
import ai.neopsyke.agent.model.Urgency
import ai.neopsyke.instrumentation.NoopAgentInstrumentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Verifies that [MetaReasonerVerdict.FINALIZE_NOW] directly enqueues a forced
 * terminal answer rather than producing a soft [EgoDecision.EnqueueContinuation] hint.
 *
 * [MetaReasonerVerdict.REQUEST_TOOL_THEN_FINALIZE] still uses the soft hint to
 * give the planner one more chance for a tool action.
 */
class FinalizeNowTerminalPromotionTest {

    private val sessionId = "s1"
    private val rootInputId = "root-1"
    private val convCtx = ConversationContext.default().copy(sessionId = sessionId)

    private fun buildEngine(config: AgentConfig = AgentConfig()): DeliberationEngine =
        DeliberationEngine(
            config = config,
            instrumentation = NoopAgentInstrumentation,
            metaReasoner = NoopMetaReasoner,
        )

    private fun finalizeNowAssessment() = MetaReasonerAssessment(
        verdict = MetaReasonerVerdict.FINALIZE_NOW,
        confidence = 0.9,
        reason = "test finalize",
    )

    private fun requestToolAssessment() = MetaReasonerAssessment(
        verdict = MetaReasonerVerdict.REQUEST_TOOL_THEN_FINALIZE,
        confidence = 0.8,
        reason = "test request tool",
    )

    private fun continueAssessment() = MetaReasonerAssessment(
        verdict = MetaReasonerVerdict.CONTINUE,
        confidence = 0.7,
        reason = "test continue",
    )

    @Test
    fun `FINALIZE_NOW with non-action decision enqueues forced terminal action`() {
        val engine = buildEngine()
        engine.setActiveSession(sessionId)
        val scheduler = AttentionScheduler(AgentConfig())
        val noopDecision = EgoDecision.Noop("test noop")

        val result = engine.maybeApplyPressureOverride(
            decision = noopDecision,
            assessment = finalizeNowAssessment(),
            scheduler = scheduler,
            rootInputId = rootInputId,
            rootInputReceivedAtMs = 1000L,
            conversationContext = convCtx,
            groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
        )

        // Original decision should be returned unchanged.
        assertIs<EgoDecision.Noop>(result)

        // Forced terminal action should be enqueued in the scheduler.
        val state = scheduler.queueState()
        val contactActions = state.actions.filter { it.type == ActionType.CONTACT_USER }
        assertEquals(1, contactActions.size, "Exactly one forced terminal CONTACT_USER should be enqueued")
        assertTrue(contactActions.first().isForcedTerminal, "Action must be marked as forced terminal")

        // hasForcedTerminalForInput should be set.
        assertTrue(engine.hasForcedTerminalForInput(rootInputId, sessionId))
    }

    @Test
    fun `FINALIZE_NOW with FormIntention passes through unchanged`() {
        val engine = buildEngine()
        engine.setActiveSession(sessionId)
        val scheduler = AttentionScheduler(AgentConfig())
        val formDecision = EgoDecision.FormIntention(
            urgency = Urgency.MEDIUM,
            intentionKind = IntentionKind.COMMIT,
            actionType = ActionType.CONTACT_USER,
            payload = "Here is the answer",
            summary = "Answer",
        )

        val result = engine.maybeApplyPressureOverride(
            decision = formDecision,
            assessment = finalizeNowAssessment(),
            scheduler = scheduler,
            rootInputId = rootInputId,
            rootInputReceivedAtMs = 1000L,
            conversationContext = convCtx,
            groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
        )

        // FormIntention passes through without override.
        assertIs<EgoDecision.FormIntention>(result)
        assertEquals("Here is the answer", result.payload)

        // No forced terminal should be enqueued -- the planner produced an action.
        val state = scheduler.queueState()
        assertTrue(state.actions.isEmpty(), "No additional action should be enqueued when planner produced FormIntention")
        assertFalse(engine.hasForcedTerminalForInput(rootInputId, sessionId))
    }

    @Test
    fun `REQUEST_TOOL_THEN_FINALIZE with non-action decision produces soft hint`() {
        val engine = buildEngine()
        engine.setActiveSession(sessionId)
        val scheduler = AttentionScheduler(AgentConfig())
        val noopDecision = EgoDecision.Noop("test noop")

        val result = engine.maybeApplyPressureOverride(
            decision = noopDecision,
            assessment = requestToolAssessment(),
            scheduler = scheduler,
            rootInputId = rootInputId,
            rootInputReceivedAtMs = 1000L,
            conversationContext = convCtx,
            groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
        )

        // Should produce a soft EnqueueContinuation hint (not forced terminal).
        val continuation = assertIs<EgoDecision.EnqueueContinuation>(result)
        assertTrue(continuation.continuation.content.contains("Decision pressure is high"))
        assertEquals(Urgency.HIGH, continuation.urgency)

        // No forced terminal should be enqueued.
        val state = scheduler.queueState()
        assertTrue(state.actions.isEmpty(), "Soft hint should not enqueue a forced terminal action")
        assertFalse(engine.hasForcedTerminalForInput(rootInputId, sessionId))
    }

    @Test
    fun `CONTINUE assessment does not override decision`() {
        val engine = buildEngine()
        engine.setActiveSession(sessionId)
        val scheduler = AttentionScheduler(AgentConfig())
        val noopDecision = EgoDecision.Noop("test noop")

        val result = engine.maybeApplyPressureOverride(
            decision = noopDecision,
            assessment = continueAssessment(),
            scheduler = scheduler,
            rootInputId = rootInputId,
            rootInputReceivedAtMs = 1000L,
            conversationContext = convCtx,
            groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
        )

        // Decision should pass through unchanged.
        assertIs<EgoDecision.Noop>(result)
        val state = scheduler.queueState()
        assertTrue(state.actions.isEmpty())
    }

    @Test
    fun `null assessment does not override decision`() {
        val engine = buildEngine()
        engine.setActiveSession(sessionId)
        val scheduler = AttentionScheduler(AgentConfig())
        val noopDecision = EgoDecision.Noop("test noop")

        val result = engine.maybeApplyPressureOverride(
            decision = noopDecision,
            assessment = null,
            scheduler = scheduler,
            rootInputId = rootInputId,
            rootInputReceivedAtMs = 1000L,
            conversationContext = convCtx,
            groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
        )

        assertIs<EgoDecision.Noop>(result)
    }

    @Test
    fun `FINALIZE_NOW is not re-queued for same input`() {
        val engine = buildEngine()
        engine.setActiveSession(sessionId)
        val scheduler = AttentionScheduler(AgentConfig())
        val noopDecision = EgoDecision.Noop("test noop")

        // First call: enqueues forced terminal.
        engine.maybeApplyPressureOverride(
            decision = noopDecision,
            assessment = finalizeNowAssessment(),
            scheduler = scheduler,
            rootInputId = rootInputId,
            rootInputReceivedAtMs = 1000L,
            conversationContext = convCtx,
            groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
        )

        // Second call for same input: should fall through to soft hint
        // since forced terminal is already queued.
        val result2 = engine.maybeApplyPressureOverride(
            decision = noopDecision,
            assessment = finalizeNowAssessment(),
            scheduler = scheduler,
            rootInputId = rootInputId,
            rootInputReceivedAtMs = 1000L,
            conversationContext = convCtx,
            groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
        )

        // Falls through to soft hint because forced terminal already queued.
        assertIs<EgoDecision.EnqueueContinuation>(result2)

        // Only one forced terminal action in the scheduler.
        val state = scheduler.queueState()
        val contactActions = state.actions.filter { it.type == ActionType.CONTACT_USER && it.isForcedTerminal }
        assertEquals(1, contactActions.size, "Must not re-queue forced terminal for same input")
    }

    @Test
    fun `FINALIZE_NOW synthesizes payload from evidence when available`() {
        val engine = buildEngine()
        engine.setActiveSession(sessionId)
        val scheduler = AttentionScheduler(AgentConfig())

        // Record some successful evidence.
        val dummyAction = ai.neopsyke.agent.model.PendingAction(
            id = 1,
            urgency = Urgency.MEDIUM,
            type = ActionType.WEB_SEARCH,
            payload = "weather",
            summary = "Search weather",
            requiresFollowUpThought = true,
            rootInputId = rootInputId,
            conversationContext = convCtx,
            groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
        )
        val outcome = ai.neopsyke.agent.model.ActionOutcome(
            statusSummary = "Hamburg: +10C, partly cloudy",
            plannerSignal = "Hamburg: +10C, partly cloudy",
            observedEvidence = true,
        )
        engine.recordEvidenceProgress(dummyAction, outcome, observed = true)

        val result = engine.maybeApplyPressureOverride(
            decision = EgoDecision.Noop("test"),
            assessment = finalizeNowAssessment(),
            scheduler = scheduler,
            rootInputId = rootInputId,
            rootInputReceivedAtMs = 1000L,
            conversationContext = convCtx,
            groundingMetadata = GroundingMetadata(
                requirement = GroundingRequirement.REQUIRED,
                source = GroundingSource.DURABLE_WORK_STEP_POLICY,
            ),
        )

        assertIs<EgoDecision.Noop>(result)
        val state = scheduler.queueState()
        val actions = state.actions.filter { it.type == ActionType.CONTACT_USER }
        assertEquals(1, actions.size)
        // The payload should contain the evidence signal, not the generic "diminishing returns" text.
        assertTrue(
            actions.first().payload.contains("Hamburg"),
            "Forced terminal payload should incorporate gathered evidence"
        )
    }

    @Test
    fun `FINALIZE_NOW uses generic payload when no evidence`() {
        val engine = buildEngine()
        engine.setActiveSession(sessionId)
        val scheduler = AttentionScheduler(AgentConfig())

        engine.maybeApplyPressureOverride(
            decision = EgoDecision.Noop("test"),
            assessment = finalizeNowAssessment(),
            scheduler = scheduler,
            rootInputId = rootInputId,
            rootInputReceivedAtMs = 1000L,
            conversationContext = convCtx,
            groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
        )

        val state = scheduler.queueState()
        val actions = state.actions.filter { it.type == ActionType.CONTACT_USER }
        assertEquals(1, actions.size)
        assertTrue(
            actions.first().payload.contains("diminishing returns"),
            "Without evidence, generic payload should be used"
        )
    }

    @Test
    fun `FINALIZE_NOW with EnqueueContinuation decision also triggers forced terminal`() {
        val engine = buildEngine()
        engine.setActiveSession(sessionId)
        val scheduler = AttentionScheduler(AgentConfig())
        val thoughtDecision = EgoDecision.EnqueueContinuation(
            urgency = Urgency.LOW,
            continuation = ai.neopsyke.agent.model.Continuation.ConvergeNow(
                content = "Still thinking...",
                convergenceReason = "test",
            ),
        )

        val result = engine.maybeApplyPressureOverride(
            decision = thoughtDecision,
            assessment = finalizeNowAssessment(),
            scheduler = scheduler,
            rootInputId = rootInputId,
            rootInputReceivedAtMs = 1000L,
            conversationContext = convCtx,
            groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
        )

        // Original decision returned (continuation will be dropped by hasForcedTerminalForInput).
        val continuation = assertIs<EgoDecision.EnqueueContinuation>(result)
        assertEquals("Still thinking...", continuation.continuation.content)

        // Forced terminal enqueued.
        val state = scheduler.queueState()
        assertEquals(1, state.actions.filter { it.isForcedTerminal }.size)
        assertTrue(engine.hasForcedTerminalForInput(rootInputId, sessionId))
    }
}
