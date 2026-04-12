package ai.neopsyke.agent.durablework

import ai.neopsyke.agent.ego.planner.model.DurableWorkCommand
import ai.neopsyke.agent.ego.planner.model.SerializedDurableWorkCommand
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

    // ── SerializedDurableWorkCommand round-trip ─────────────────────────────────

    @Test
    fun `SerializedDurableWorkCommand create round-trips contactChannel`() {
        val command = DurableWorkCommand.Create(
            title = "Weather reminder",
            instruction = "Send weather update",
            contactChannel = "telegram",
        )
        val serialized = SerializedDurableWorkCommand.fromDurableWorkCommand(command)
        assertEquals("telegram", serialized.contactChannel)

        val restored = serialized.toDurableWorkCommand()
        val create = restored as DurableWorkCommand.Create
        assertEquals("telegram", create.contactChannel)
    }

    @Test
    fun `SerializedDurableWorkCommand create without contactChannel round-trips null`() {
        val command = DurableWorkCommand.Create(
            title = "Weather",
            instruction = "Check weather",
        )
        val serialized = SerializedDurableWorkCommand.fromDurableWorkCommand(command)
        assertNull(serialized.contactChannel)

        val restored = serialized.toDurableWorkCommand() as DurableWorkCommand.Create
        assertNull(restored.contactChannel)
    }

    @Test
    fun `SerializedDurableWorkCommand update round-trips contactChannel`() {
        val command = DurableWorkCommand.Update(
            reference = ai.neopsyke.agent.ego.planner.model.WorkItemReference.ByInternalId("goal-1"),
            contactChannel = "webapp",
        )
        val serialized = SerializedDurableWorkCommand.fromDurableWorkCommand(command)
        assertEquals("webapp", serialized.contactChannel)

        val restored = serialized.toDurableWorkCommand() as DurableWorkCommand.Update
        assertEquals("webapp", restored.contactChannel)
    }

    // ── WorkContextLoader.buildWorkUnit ──────────────────────────────────

    @Test
    fun `buildWorkUnit uses contactChannel as provider when set`() {
        val state = goalState(contactChannel = "telegram")
        val work = WorkContextLoader.buildWorkUnit(
            state = state,
            step = state.workItem.plan.steps.first(),
            rootInputId = "root-1",
            wakeReason = "cron_wake_active",
        )
        assertEquals("telegram", work.conversationContext.security.channel.provider)
    }

    @Test
    fun `buildWorkUnit falls back to goal-runtime when contactChannel is null`() {
        val state = goalState(contactChannel = null)
        val work = WorkContextLoader.buildWorkUnit(
            state = state,
            step = state.workItem.plan.steps.first(),
            rootInputId = "root-1",
            wakeReason = "cron_wake_active",
        )
        assertEquals("durable-work-runtime", work.conversationContext.security.channel.provider)
    }

    // ── WorkItemSnapshot round-trip ──────────────────────────────────────────

    @Test
    fun `WorkItemSnapshot preserves contactChannel through round-trip`() {
        val state = goalState(contactChannel = "telegram")
        val snapshot = WorkItemSnapshot.from(state)
        assertEquals("telegram", snapshot.contactChannel)

        val restored = snapshot.toState(workspacePath)
        assertEquals("telegram", restored.workItem.contactChannel)
    }

    @Test
    fun `WorkItemSnapshot preserves null contactChannel`() {
        val state = goalState(contactChannel = null)
        val snapshot = WorkItemSnapshot.from(state)
        assertNull(snapshot.contactChannel)

        val restored = snapshot.toState(workspacePath)
        assertNull(restored.workItem.contactChannel)
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun goalState(contactChannel: String? = null): WorkItemState {
        val event = WorkItemEvent.Created(
            workItemId = "goal-1",
            title = "Weather reminder",
            instruction = "Send daily weather",
            priority = WorkItemPriority.MEDIUM,
            completionCriteria = "Message delivered",
            contactChannel = contactChannel,
            timestamp = now,
        )
        val state = WorkItemStateMachine.initialState(event, workspacePath)
        val plan = WorkItemPlan(
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
        return WorkItemStateMachine.transition(
            state,
            WorkItemEvent.PlanGenerated(workItemId = "goal-1", plan = plan, timestamp = now),
        ).first
    }
}
