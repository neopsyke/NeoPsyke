package ai.neopsyke.agent.ego

import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.config.MetaReasonerConfig
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.EgoDecision
import ai.neopsyke.agent.model.Urgency
import ai.neopsyke.instrumentation.NoopAgentInstrumentation
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies that once a forced terminal answer is queued for an input,
 * [DeliberationEngine.hasForcedTerminalForInput] returns true and
 * [DeliberationEngine.clearForInput] with retainThreadContinuity=true
 * preserves the flag (preventing re-queuing and deferred-step spirals).
 */
class ForcedTerminalShortCircuitTest {

    private fun buildEngine(config: AgentConfig = AgentConfig(
        metaReasoner = MetaReasonerConfig(
            deliberationPressureAssessmentMinStep = 2,
            forcedTerminalPressureThreshold = 0.5,
            forcedTerminalStaleStreakThreshold = 2,
        )
    )): DeliberationEngine =
        DeliberationEngine(
            config = config,
            instrumentation = NoopAgentInstrumentation,
            metaReasoner = NoopMetaReasoner,
        )

    private fun driveToHighPressure(engine: DeliberationEngine) {
        engine.setActiveSession("s1")
        // Each startStep + Noop decision increases pressure and stale streak.
        repeat(10) {
            engine.startStep()
            engine.onPlannerDecision(EgoDecision.Noop("test"))
        }
    }

    @Test
    fun `hasForcedTerminalForInput is false before force`() {
        val engine = buildEngine()
        assertFalse(engine.hasForcedTerminalForInput("root-1", "s1"))
    }

    @Test
    fun `hasForcedTerminalForInput is true after forced terminal is queued`() {
        val engine = buildEngine()
        driveToHighPressure(engine)
        val scheduler = AttentionScheduler(AgentConfig())
        engine.maybeForceTerminalAnswer(
            scheduler = scheduler,
            rootInputId = "root-1",
            rootInputReceivedAtMs = System.currentTimeMillis(),
            conversationContext = ConversationContext.default().copy(sessionId = "s1"),
        )
        assertTrue(engine.hasForcedTerminalForInput("root-1", "s1"))
    }

    @Test
    fun `clearForInput with retainThreadContinuity preserves forced terminal flag`() {
        val engine = buildEngine()
        driveToHighPressure(engine)
        val scheduler = AttentionScheduler(AgentConfig())
        engine.maybeForceTerminalAnswer(
            scheduler = scheduler,
            rootInputId = "root-1",
            rootInputReceivedAtMs = System.currentTimeMillis(),
            conversationContext = ConversationContext.default().copy(sessionId = "s1"),
        )
        assertTrue(engine.hasForcedTerminalForInput("root-1", "s1"))

        engine.clearForInput("root-1", "s1", retainThreadContinuity = true)
        assertTrue(engine.hasForcedTerminalForInput("root-1", "s1"),
            "Forced terminal flag must survive clearForInput with retainThreadContinuity=true")
    }

    @Test
    fun `clearForInput without retainThreadContinuity clears forced terminal flag`() {
        val engine = buildEngine()
        driveToHighPressure(engine)
        val scheduler = AttentionScheduler(AgentConfig())
        engine.maybeForceTerminalAnswer(
            scheduler = scheduler,
            rootInputId = "root-1",
            rootInputReceivedAtMs = System.currentTimeMillis(),
            conversationContext = ConversationContext.default().copy(sessionId = "s1"),
        )
        assertTrue(engine.hasForcedTerminalForInput("root-1", "s1"))

        engine.clearForInput("root-1", "s1", retainThreadContinuity = false)
        assertFalse(engine.hasForcedTerminalForInput("root-1", "s1"),
            "Forced terminal flag must be cleared when retainThreadContinuity=false")
    }

    @Test
    fun `forced terminal is not re-queued for same input`() {
        val engine = buildEngine()
        driveToHighPressure(engine)
        val scheduler = AttentionScheduler(AgentConfig())
        engine.maybeForceTerminalAnswer(
            scheduler = scheduler,
            rootInputId = "root-1",
            rootInputReceivedAtMs = System.currentTimeMillis(),
            conversationContext = ConversationContext.default().copy(sessionId = "s1"),
        )

        // Drive pressure up again and try to force another terminal.
        driveToHighPressure(engine)
        engine.maybeForceTerminalAnswer(
            scheduler = scheduler,
            rootInputId = "root-1",
            rootInputReceivedAtMs = System.currentTimeMillis(),
            conversationContext = ConversationContext.default().copy(sessionId = "s1"),
        )

        // Only one contact_user action should be enqueued.
        val state = scheduler.queueState()
        val contactActions = state.actions.filter { it.type == ai.neopsyke.agent.model.ActionType.CONTACT_USER }
        assertTrue(contactActions.size <= 1, "Forced terminal must not be re-queued for the same input")
    }
}
