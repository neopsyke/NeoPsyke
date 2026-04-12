package ai.neopsyke.agent

import ai.neopsyke.agent.model.GroundingMetadata
import ai.neopsyke.agent.model.IntentionKind
import kotlin.test.Test
import kotlin.test.assertTrue

class DeliberationProgressMonitorTest {
    @Test
    fun `decision pressure rises on repeated noop loops`() {
        val monitor = DeliberationProgressMonitor()
        repeat(12) {
            monitor.startStep()
            monitor.onPlannerDecision(ai.neopsyke.agent.model.EgoDecision.Noop("no progress"))
        }

        val state = monitor.snapshot()
        assertTrue(state.decisionPressure > 0.6)
        assertTrue(state.staleStreak >= 10)
        assertTrue(state.noopStreak >= 10)
    }

    @Test
    fun `decision pressure cools when evidence actions execute`() {
        val monitor = DeliberationProgressMonitor()
        repeat(8) {
            monitor.startStep()
            monitor.onPlannerDecision(ai.neopsyke.agent.model.EgoDecision.Noop("loop"))
        }
        val before = monitor.snapshot()

        monitor.startStep()
        monitor.onPlannerDecision(
           ai.neopsyke.agent.model.EgoDecision.FormIntention(
                urgency = Urgency.HIGH,
                intentionKind = IntentionKind.OBSERVE,
                actionType = ActionType.WEB_SEARCH,
                payload = "query",
                summary = "search"
            )
        )
        monitor.onActionExecuted(
            PendingAction(
                id = 1,
                urgency = Urgency.HIGH,
                type = ActionType.WEB_SEARCH,
                payload = "query",
                summary = "search",
                groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
            )
        )

        val after = monitor.snapshot()
        assertTrue(after.staleStreak <= before.staleStreak)
        assertTrue(after.progressScore > before.progressScore)
        assertTrue(after.stepsSinceNewEvidence == 0)
    }

    @Test
    fun `step pressure reaches planner guidance threshold within 50 steps`() {
        val monitor = DeliberationProgressMonitor()
        // Simulate 50 steps of moderate activity (no noops, no failures, just steps)
        repeat(50) {
            monitor.startStep()
            monitor.onPlannerDecision(
                ai.neopsyke.agent.model.EgoDecision.EnqueueContinuation(
                    urgency = Urgency.MEDIUM,
                    continuation = ai.neopsyke.agent.model.Continuation.ConvergeNow(
                        content = "thinking step $it",
                        convergenceReason = "test",
                    ),
                )
            )
        }

        val state = monitor.snapshot()
        // By step 50, pressure should be high enough that the planner's built-in
        // guidance ("if pressure >= 0.75, prefer answer") kicks in.
        assertTrue(
            state.decisionPressure >= 0.60,
            "Expected pressure >= 0.60 at step 50, got ${state.decisionPressure}"
        )
    }

    @Test
    fun `failed evidence action increases pressure instead of resetting evidence gap`() {
        val monitor = DeliberationProgressMonitor()
        repeat(4) {
            monitor.startStep()
            monitor.onPlannerDecision(ai.neopsyke.agent.model.EgoDecision.Noop("Planner unavailable due to model error."))
        }
        val before = monitor.snapshot()

        monitor.startStep()
        monitor.onPlannerDecision(
           ai.neopsyke.agent.model.EgoDecision.FormIntention(
                urgency = Urgency.MEDIUM,
                intentionKind = IntentionKind.OBSERVE,
                actionType = ActionType.WEB_SEARCH,
                payload = "query",
                summary = "search"
            )
        )
        monitor.onActionExecuted(
            action = PendingAction(
                id = 2,
                urgency = Urgency.MEDIUM,
                type = ActionType.WEB_SEARCH,
                payload = "query",
                summary = "search",
                groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
            ),
            observedEvidence = false
        )

        val after = monitor.snapshot()
        assertTrue(after.stepsSinceNewEvidence > 0)
        assertTrue(after.staleStreak >= before.staleStreak)
        assertTrue(after.decisionPressure >= before.decisionPressure)
        assertTrue(after.modelErrorStreak >= 1)
        assertTrue(after.progressScore <= before.progressScore + 0.05)
    }
}
