package ai.neopsyke.agent.assignments

import ai.neopsyke.agent.cortex.motor.actions.ContactChannelPolicy
import ai.neopsyke.agent.ego.planner.model.AssignmentCommand
import ai.neopsyke.agent.ego.planner.model.SerializedAssignmentCommand
import ai.neopsyke.agent.model.AmbientContext
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies that the contactChannel field flows correctly through the assignment
 * data chain: serialized command -> typed command -> assignment state -> work unit.
 */
class AssignmentContactChannelTest {

    private val workspacePath = Paths.get("/tmp/test-assignments/contact-channel-test")
    private val now = Instant.parse("2026-04-08T06:00:00Z")

    // ── SerializedAssignmentCommand round-trip ─────────────────────────────────

    @Test
    fun `SerializedAssignmentCommand create round-trips contactChannel`() {
        val command = AssignmentCommand.Create(
            title = "Weather reminder",
            instruction = "Send weather update",
            contactChannel = "telegram",
        )
        val serialized = SerializedAssignmentCommand.fromAssignmentCommand(command)
        assertEquals("telegram", serialized.contactChannel)

        val restored = serialized.toAssignmentCommand()
        val create = restored as AssignmentCommand.Create
        assertEquals("telegram", create.contactChannel)
    }

    @Test
    fun `SerializedAssignmentCommand create without contactChannel round-trips null`() {
        val command = AssignmentCommand.Create(
            title = "Weather",
            instruction = "Check weather",
        )
        val serialized = SerializedAssignmentCommand.fromAssignmentCommand(command)
        assertNull(serialized.contactChannel)

        val restored = serialized.toAssignmentCommand() as AssignmentCommand.Create
        assertNull(restored.contactChannel)
    }

    @Test
    fun `SerializedAssignmentCommand update round-trips contactChannel`() {
        val command = AssignmentCommand.Update(
            reference = ai.neopsyke.agent.ego.planner.model.WorkItemReference.ByInternalId("assignment-1"),
            contactChannel = "dashboard",
        )
        val serialized = SerializedAssignmentCommand.fromAssignmentCommand(command)
        assertEquals("dashboard", serialized.contactChannel)

        val restored = serialized.toAssignmentCommand() as AssignmentCommand.Update
        assertEquals("dashboard", restored.contactChannel)
    }

    // ── WorkContextLoader.buildWorkUnit ──────────────────────────────────

    @Test
    fun `buildWorkUnit carries contactChannel as a preferred-channel hint`() {
        val state = assignmentState(contactChannel = "telegram")
        val work = WorkContextLoader.buildWorkUnit(
            state = state,
            step = state.workItem.plan.steps.first(),
            rootInputId = "root-1",
            wakeReason = "cron_wake_active",
        )
        val channel = work.conversationContext.security.channel
        assertEquals("assignment-runtime", channel.provider)
        assertEquals("", channel.channelId)
        assertEquals(
            "telegram",
            channel.attributes[ContactChannelPolicy.PREFERRED_CHANNEL_ATTRIBUTE],
        )
    }

    @Test
    fun `buildWorkUnit omits hint when contactChannel is null`() {
        val state = assignmentState(contactChannel = null)
        val work = WorkContextLoader.buildWorkUnit(
            state = state,
            step = state.workItem.plan.steps.first(),
            rootInputId = "root-1",
            wakeReason = "cron_wake_active",
        )
        val channel = work.conversationContext.security.channel
        assertEquals("assignment-runtime", channel.provider)
        assertEquals("", channel.channelId)
        assertNull(channel.attributes[ContactChannelPolicy.PREFERRED_CHANNEL_ATTRIBUTE])
    }

    // ── WorkItemSnapshot round-trip ──────────────────────────────────────────

    @Test
    fun `WorkItemSnapshot preserves contactChannel through round-trip`() {
        val state = assignmentState(contactChannel = "telegram")
        val snapshot = WorkItemSnapshot.from(state)
        assertEquals("telegram", snapshot.contactChannel)

        val restored = snapshot.toState(workspacePath)
        assertEquals("telegram", restored.workItem.contactChannel)
    }

    @Test
    fun `WorkItemSnapshot preserves null contactChannel`() {
        val state = assignmentState(contactChannel = null)
        val snapshot = WorkItemSnapshot.from(state)
        assertNull(snapshot.contactChannel)

        val restored = snapshot.toState(workspacePath)
        assertNull(restored.workItem.contactChannel)
    }

    // ── AmbientContext scratchpad theme filtering ─────────────────────────

    @Test
    fun `AmbientContext copy with empty scratchpad themes excludes themes from render`() {
        val original = AmbientContext(
            activeWorkItems = listOf("Send daily weather reminder via Telegram"),
            recentScratchpadThemes = listOf("Update channel to Telegram and time to 00:15"),
            recentUsefulActionsOrUpdates = listOf("Executed web_search: forecast data"),
        )

        val filtered = original.copy(recentScratchpadThemes = emptyList())

        assertFalse(filtered.isEmpty())
        assertTrue(filtered.render().contains("active_assignments"))
        assertTrue(filtered.render().contains("recent_useful_actions_updates"))
        assertFalse(filtered.render().contains("recent_scratchpad_themes"))
        assertFalse(filtered.render().contains("Update channel to Telegram"))
    }

    @Test
    fun `AmbientContext filtering preserves other fields`() {
        val original = AmbientContext(
            activeWorkItems = listOf("Assignment A"),
            recentScratchpadThemes = listOf("Theme X"),
            recentUsefulActionsOrUpdates = listOf("Action Y"),
            unresolvedOpenLoops = listOf("Loop Z"),
        )

        val filtered = original.copy(recentScratchpadThemes = emptyList())

        assertEquals(listOf("Assignment A"), filtered.activeWorkItems)
        assertEquals(emptyList(), filtered.recentScratchpadThemes)
        assertEquals(listOf("Action Y"), filtered.recentUsefulActionsOrUpdates)
        assertEquals(listOf("Loop Z"), filtered.unresolvedOpenLoops)
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun assignmentState(contactChannel: String? = null): WorkItemState {
        val event = WorkItemEvent.Created(
            workItemId = "assignment-1",
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
            WorkItemEvent.PlanGenerated(workItemId = "assignment-1", plan = plan, timestamp = now),
        ).first
    }
}
