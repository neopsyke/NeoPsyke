package psyke.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AttentionSchedulerTest {
    private val config = AgentConfig(
        maxPendingInputs = 2,
        maxPendingThoughts = 2,
        maxPendingActions = 2
    )

    @Test
    fun `inputs always take priority over thoughts and actions`() {
        val scheduler = AttentionScheduler(config)
        scheduler.enqueueThought("process this", Urgency.HIGH)
        scheduler.enqueueAction(ActionType.ANSWER, "hello", "reply", Urgency.HIGH)
        scheduler.enqueueInput("new input")

        val task = scheduler.nextTask()
        assertIs<LoopTask.ProcessInput>(task)
        assertEquals("new input", task.item.content)
    }

    @Test
    fun `actions and thoughts are selected by urgency then insertion order`() {
        val scheduler = AttentionScheduler(config)
        scheduler.enqueueThought("low", Urgency.LOW)
        scheduler.enqueueThought("high", Urgency.HIGH)
        scheduler.enqueueAction(ActionType.WEB_SEARCH, "q", "s", Urgency.HIGH)

        val first = scheduler.nextTask()
        val second = scheduler.nextTask()
        val third = scheduler.nextTask()

        assertIs<LoopTask.PerformAction>(first)
        assertIs<LoopTask.ProcessThought>(second)
        assertEquals("high", second.item.content)
        assertIs<LoopTask.ProcessThought>(third)
        assertEquals("low", third.item.content)
    }

    @Test
    fun `queue limits are enforced`() {
        val scheduler = AttentionScheduler(config)

        assertTrue(scheduler.enqueueInput("1"))
        assertTrue(scheduler.enqueueInput("2"))
        assertFalse(scheduler.enqueueInput("3"))

        assertTrue(scheduler.enqueueThought("t1", Urgency.MEDIUM))
        assertTrue(scheduler.enqueueThought("t2", Urgency.MEDIUM))
        assertFalse(scheduler.enqueueThought("t3", Urgency.MEDIUM))

        assertTrue(scheduler.enqueueAction(ActionType.ANSWER, "a1", "s1", Urgency.MEDIUM))
        assertTrue(scheduler.enqueueAction(ActionType.ANSWER, "a2", "s2", Urgency.MEDIUM))
        assertFalse(scheduler.enqueueAction(ActionType.ANSWER, "a3", "s3", Urgency.MEDIUM))
    }

    @Test
    fun `snapshot keeps last twelve dialogue turns`() {
        val scheduler = AttentionScheduler(config)
        val dialogue = (1..14).map {
            DialogueTurn(
                role = if (it % 2 == 0) DialogueRole.ASSISTANT else DialogueRole.USER,
                content = "line-$it"
            )
        }

        val snapshot = scheduler.snapshot(dialogue)
        assertEquals(12, snapshot.recentDialogue.size)
        assertEquals("line-3", snapshot.recentDialogue.first().content)
        assertEquals("line-14", snapshot.recentDialogue.last().content)
    }

    @Test
    fun `queue state returns sorted thoughts and actions`() {
        val scheduler = AttentionScheduler(config)
        scheduler.enqueueThought("low", Urgency.LOW)
        scheduler.enqueueThought("high", Urgency.HIGH)
        scheduler.enqueueAction(ActionType.ANSWER, "low-action", "s1", Urgency.LOW)
        scheduler.enqueueAction(ActionType.ANSWER, "high-action", "s2", Urgency.HIGH)

        val state = scheduler.queueState()
        assertEquals(listOf("high", "low"), state.thoughts.map { it.content })
        assertEquals(listOf("high-action", "low-action"), state.actions.map { it.payload })
        assertNotNull(scheduler.nextTask())
    }
}
