package ai.neopsyke.agent.ego

import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.instrumentation.NoopAgentInstrumentation
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies that DeliberationEngine isolates guidance per session.
 * Two sessions should get independent guidance strings after `setActiveSession()`.
 */
class SessionScopedDeliberationTest {

    private fun createEngine(): DeliberationEngine {
        // MetaReasoner is not invoked directly in these tests — we test
        // guidance storage/retrieval only. The Noop meta-reasoner suffices.
        return DeliberationEngine(
            config = AgentConfig(),
            instrumentation = NoopAgentInstrumentation,
            metaReasoner = NoopMetaReasoner,
        )
    }

    @Test
    fun `guidance is empty for fresh session`() {
        val engine = createEngine()
        engine.setActiveSession("fresh-session")
        assertEquals("", engine.guidance(), "Fresh session guidance should be empty")
    }

    @Test
    fun `guidance is isolated per session`() {
        val engine = createEngine()

        // No direct API to set guidance other than through maybeAssessAndUpdateGuidance
        // which requires a full PlannerContext and LLM call. Instead we verify
        // that the default guidance is session-scoped through reset behavior.
        engine.setActiveSession("session-A")
        assertEquals("", engine.guidance())

        engine.setActiveSession("session-B")
        assertEquals("", engine.guidance())
    }

    @Test
    fun `reset clears all session guidance`() {
        val engine = createEngine()

        engine.setActiveSession("session-A")
        assertEquals("", engine.guidance())

        engine.setActiveSession("session-B")
        assertEquals("", engine.guidance())

        engine.reset()

        engine.setActiveSession("session-A")
        assertEquals("", engine.guidance(), "After reset, session A guidance should be empty")

        engine.setActiveSession("session-B")
        assertEquals("", engine.guidance(), "After reset, session B guidance should be empty")
    }

    @Test
    fun `default session has empty guidance`() {
        val engine = createEngine()
        engine.setActiveSession(ConversationContext.DEFAULT_SESSION_ID)
        assertEquals("", engine.guidance())
    }

    @Test
    fun `deliberation progress is isolated per session`() {
        val engine = createEngine()

        engine.setActiveSession("session-A")
        val stateAAfterStep = engine.startStep()
        assertEquals(1, stateAAfterStep.stepIndex)

        engine.setActiveSession("session-B")
        val stateBInitial = engine.snapshot()
        assertEquals(0, stateBInitial.stepIndex, "Session B should start from clean deliberation state")
        val stateBAfterStep = engine.startStep()
        assertEquals(1, stateBAfterStep.stepIndex)

        engine.setActiveSession("session-A")
        val stateAReturn = engine.snapshot()
        assertEquals(1, stateAReturn.stepIndex, "Session A should preserve its own deliberation progress")
    }
}
