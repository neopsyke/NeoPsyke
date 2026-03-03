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
        assertIs<psyke.agent.core.LoopTask.ProcessInput>(task)
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

        assertIs<psyke.agent.core.LoopTask.PerformAction>(first)
        assertIs<psyke.agent.core.LoopTask.ProcessThought>(second)
        assertEquals("high", second.item.content)
        assertIs<psyke.agent.core.LoopTask.ProcessThought>(third)
        assertEquals("low", third.item.content)
    }

    @Test
    fun `inputs are selected by priority then insertion order`() {
        val scheduler = AttentionScheduler(config)
        scheduler.enqueueInput("medium-first")
        scheduler.enqueueInput("high-second", InputPriority.HIGH)

        val first = scheduler.nextTask()
        val second = scheduler.nextTask()

        assertIs<psyke.agent.core.LoopTask.ProcessInput>(first)
        assertIs<psyke.agent.core.LoopTask.ProcessInput>(second)
        assertEquals("high-second", first.item.content)
        assertEquals(InputPriority.HIGH, first.item.priority)
        assertEquals("medium-first", second.item.content)
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
    fun `queue snapshot reports current queue counts`() {
        val scheduler = AttentionScheduler(config)
        scheduler.enqueueInput("line-1")
        scheduler.enqueueThought("line-2", Urgency.HIGH)
        scheduler.enqueueAction(ActionType.ANSWER, "line-3", "summary", Urgency.MEDIUM)

        val snapshot = scheduler.queueSnapshot()
        assertEquals(1, snapshot.pendingInputCount)
        assertEquals(1, snapshot.pendingThoughtCount)
        assertEquals(1, snapshot.pendingActionCount)
    }

    @Test
    fun `queue state returns sorted thoughts and actions`() {
        val scheduler = AttentionScheduler(config)
        scheduler.enqueueInput("medium-input", InputPriority.MEDIUM)
        scheduler.enqueueInput("high-input", InputPriority.HIGH)
        scheduler.enqueueThought("low", Urgency.LOW)
        scheduler.enqueueThought("high", Urgency.HIGH)
        scheduler.enqueueAction(ActionType.ANSWER, "low-action", "s1", Urgency.LOW)
        scheduler.enqueueAction(ActionType.ANSWER, "high-action", "s2", Urgency.HIGH)

        val state = scheduler.queueState()
        assertEquals(listOf("high-input", "medium-input"), state.inputs.map { it.content })
        assertEquals(listOf("high", "low"), state.thoughts.map { it.content })
        assertEquals(listOf("high-action", "low-action"), state.actions.map { it.payload })
        assertNotNull(scheduler.nextTask())
    }

    @Test
    fun `scheduler can clear pending thoughts and actions for a resolved input`() {
        val scheduler = AttentionScheduler(config.copy(maxPendingThoughts = 8, maxPendingActions = 8))
        val rootA = 100L
        val rootB = 200L
        scheduler.enqueueThought("a-thought", Urgency.HIGH, rootInputEnqueuedAtMs = rootA)
        scheduler.enqueueThought("b-thought", Urgency.HIGH, rootInputEnqueuedAtMs = rootB)
        scheduler.enqueueAction(
            ActionType.WEB_SEARCH,
            "a-action",
            "a-summary",
            Urgency.HIGH,
            rootInputEnqueuedAtMs = rootA
        )
        scheduler.enqueueAction(
            ActionType.WEB_SEARCH,
            "b-action",
            "b-summary",
            Urgency.HIGH,
            rootInputEnqueuedAtMs = rootB
        )

        val cleared = scheduler.clearPendingWorkForInput(rootA)

        assertEquals(1, cleared.thoughtsRemoved)
        assertEquals(1, cleared.actionsRemoved)
        val state = scheduler.queueState()
        assertEquals(listOf("b-thought"), state.thoughts.map { it.content })
        assertEquals(listOf("b-action"), state.actions.map { it.payload })
    }

    @Test
    fun `scheduler can detect pending fallback and plan thoughts for an input`() {
        val scheduler = AttentionScheduler(config.copy(maxPendingThoughts = 8, maxPendingActions = 8))
        val root = 300L
        scheduler.enqueueThought(
            content = "step 1",
            urgency = Urgency.MEDIUM,
            rootInputEnqueuedAtMs = root,
            planContext = psyke.agent.core.PlanContext(
                planId = "p1",
                planGoal = "goal",
                stepIndex = 0,
                totalSteps = 2,
                stepDescription = "step"
            )
        )
        scheduler.enqueueAction(
            type = ActionType.ANSWER,
            payload = "fallback",
            summary = "fallback",
            urgency = Urgency.HIGH,
            isFallbackExplanation = true,
            rootInputEnqueuedAtMs = root
        )

        assertTrue(scheduler.hasPendingPlanThoughtsForInput(root))
        assertTrue(scheduler.hasPendingFallbackExplanationAction(root))
        assertFalse(scheduler.hasPendingPlanThoughtsForInput(999L))
        assertFalse(scheduler.hasPendingFallbackExplanationAction(999L))
    }
}
