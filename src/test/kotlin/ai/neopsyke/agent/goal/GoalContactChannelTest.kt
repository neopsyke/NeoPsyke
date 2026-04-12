package ai.neopsyke.agent.goal

import ai.neopsyke.agent.ego.planner.model.GoalCommand
import ai.neopsyke.agent.ego.planner.model.SerializedGoalCommand
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Verifies that the contactChannel field flows correctly through the goal
 * data chain: serialized command -> typed command -> goal state -> work unit.
 */
class GoalContactChannelTest {

    private val workspacePath = Paths.get("/tmp/test-goals/contact-channel-test")
    private val now = Instant.parse("2026-04-08T06:00:00Z")

    // ── SerializedGoalCommand round-trip ─────────────────────────────────

    @Test
    fun `SerializedGoalCommand create round-trips contactChannel`() {
        val command = GoalCommand.Create(
            title = "Weather reminder",
            instruction = "Send weather update",
            contactChannel = "telegram",
        )
        val serialized = SerializedGoalCommand.fromGoalCommand(command)
        assertEquals("telegram", serialized.contactChannel)

        val restored = serialized.toGoalCommand()
        val create = restored as GoalCommand.Create
        assertEquals("telegram", create.contactChannel)
    }

    @Test
    fun `SerializedGoalCommand create without contactChannel round-trips null`() {
        val command = GoalCommand.Create(
            title = "Weather",
            instruction = "Check weather",
        )
        val serialized = SerializedGoalCommand.fromGoalCommand(command)
        assertNull(serialized.contactChannel)

        val restored = serialized.toGoalCommand() as GoalCommand.Create
        assertNull(restored.contactChannel)
    }

    @Test
    fun `SerializedGoalCommand update round-trips contactChannel`() {
        val command = GoalCommand.Update(
            reference = ai.neopsyke.agent.ego.planner.model.GoalReference.ByInternalId("goal-1"),
            contactChannel = "webapp",
        )
        val serialized = SerializedGoalCommand.fromGoalCommand(command)
        assertEquals("webapp", serialized.contactChannel)

        val restored = serialized.toGoalCommand() as GoalCommand.Update
        assertEquals("webapp", restored.contactChannel)
    }

    // ── GoalContextLoader.buildWorkUnit ──────────────────────────────────

    @Test
    fun `buildWorkUnit uses contactChannel as provider when set`() {
        val state = goalState(contactChannel = "telegram")
        val work = GoalContextLoader.buildWorkUnit(
            state = state,
            step = state.goal.plan.steps.first(),
            rootInputId = "root-1",
            wakeReason = "cron_wake_active",
        )
        assertEquals("telegram", work.conversationContext.security.channel.provider)
    }

    @Test
    fun `buildWorkUnit falls back to goal-runtime when contactChannel is null`() {
        val state = goalState(contactChannel = null)
        val work = GoalContextLoader.buildWorkUnit(
            state = state,
            step = state.goal.plan.steps.first(),
            rootInputId = "root-1",
            wakeReason = "cron_wake_active",
        )
        assertEquals("goal-runtime", work.conversationContext.security.channel.provider)
    }

    // ── GoalSnapshot round-trip ──────────────────────────────────────────

    @Test
    fun `GoalSnapshot preserves contactChannel through round-trip`() {
        val state = goalState(contactChannel = "telegram")
        val snapshot = GoalSnapshot.from(state)
        assertEquals("telegram", snapshot.contactChannel)

        val restored = snapshot.toState(workspacePath)
        assertEquals("telegram", restored.goal.contactChannel)
    }

    @Test
    fun `GoalSnapshot preserves null contactChannel`() {
        val state = goalState(contactChannel = null)
        val snapshot = GoalSnapshot.from(state)
        assertNull(snapshot.contactChannel)

        val restored = snapshot.toState(workspacePath)
        assertNull(restored.goal.contactChannel)
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun goalState(contactChannel: String? = null): GoalState {
        val event = GoalEvent.Created(
            goalId = "goal-1",
            title = "Weather reminder",
            instruction = "Send daily weather",
            priority = GoalPriority.MEDIUM,
            completionCriteria = "Message delivered",
            contactChannel = contactChannel,
            timestamp = now,
        )
        val state = GoalStateMachine.initialState(event, workspacePath)
        val plan = GoalPlan(
            steps = listOf(
                PlanStep(
                    id = "step1",
                    description = "Fetch weather and notify user",
                    status = StepStatus.READY,
                    acceptanceCriteria = "User received forecast",
                ),
            ),
            generatedAt = now,
        )
        return GoalStateMachine.transition(
            state,
            GoalEvent.PlanGenerated(goalId = "goal-1", plan = plan, timestamp = now),
        ).first
    }
}
