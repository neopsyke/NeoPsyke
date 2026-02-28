package psyke.agent

import kotlin.test.Test
import kotlin.test.assertTrue

class DeliberationProgressMonitorTest {
    @Test
    fun `decision pressure rises on repeated noop loops`() {
        val monitor = DeliberationProgressMonitor()
        repeat(12) {
            monitor.startStep()
            monitor.onPlannerDecision(EgoDecision.Noop("no progress"))
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
            monitor.onPlannerDecision(EgoDecision.Noop("loop"))
        }
        val before = monitor.snapshot()

        monitor.startStep()
        monitor.onPlannerDecision(
            EgoDecision.ProposeAction(
                urgency = Urgency.HIGH,
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
                summary = "search"
            )
        )

        val after = monitor.snapshot()
        assertTrue(after.staleStreak <= before.staleStreak)
        assertTrue(after.progressScore > before.progressScore)
        assertTrue(after.stepsSinceNewEvidence == 0)
    }

    @Test
    fun `failed evidence action increases pressure instead of resetting evidence gap`() {
        val monitor = DeliberationProgressMonitor()
        repeat(4) {
            monitor.startStep()
            monitor.onPlannerDecision(EgoDecision.Noop("Planner unavailable due to model error."))
        }
        val before = monitor.snapshot()

        monitor.startStep()
        monitor.onPlannerDecision(
            EgoDecision.ProposeAction(
                urgency = Urgency.MEDIUM,
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
                summary = "search"
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
