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
        assertIs<psyke.agent.model.LoopTask.ProcessInput>(task)
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

        assertIs<psyke.agent.model.LoopTask.PerformAction>(first)
        assertIs<psyke.agent.model.LoopTask.ProcessThought>(second)
        assertEquals("high", second.item.content)
        assertIs<psyke.agent.model.LoopTask.ProcessThought>(third)
        assertEquals("low", third.item.content)
    }

    @Test
    fun `inputs are selected by priority then insertion order`() {
        val scheduler = AttentionScheduler(config)
        scheduler.enqueueInput("medium-first")
        scheduler.enqueueInput("high-second", InputPriority.HIGH)

        val first = scheduler.nextTask()
        val second = scheduler.nextTask()

        assertIs<psyke.agent.model.LoopTask.ProcessInput>(first)
        assertIs<psyke.agent.model.LoopTask.ProcessInput>(second)
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
        val rootA = "root-a"
        val rootB = "root-b"
        scheduler.enqueueThought("a-thought", Urgency.HIGH, rootInputId = rootA)
        scheduler.enqueueThought("b-thought", Urgency.HIGH, rootInputId = rootB)
        scheduler.enqueueAction(
            ActionType.WEB_SEARCH,
            "a-action",
            "a-summary",
            Urgency.HIGH,
            rootInputId = rootA
        )
        scheduler.enqueueAction(
            ActionType.WEB_SEARCH,
            "b-action",
            "b-summary",
            Urgency.HIGH,
            rootInputId = rootB
        )

        val cleared = scheduler.clearPendingWorkForInput(rootA, "default")

        assertEquals(1, cleared.thoughtsRemoved)
        assertEquals(1, cleared.actionsRemoved)
        val state = scheduler.queueState()
        assertEquals(listOf("b-thought"), state.thoughts.map { it.content })
        assertEquals(listOf("b-action"), state.actions.map { it.payload })
    }

    @Test
    fun `scheduler clear pending work is scoped by session for same root input`() {
        val scheduler = AttentionScheduler(config.copy(maxPendingThoughts = 8, maxPendingActions = 8))
        val root = "root-shared"
        val sessionA = "session-a"
        val sessionB = "session-b"
        val ctxA = psyke.agent.model.ConversationContext(sessionA, psyke.agent.model.Interlocutor.named("a"))
        val ctxB = psyke.agent.model.ConversationContext(sessionB, psyke.agent.model.Interlocutor.named("b"))
        scheduler.enqueueThought(
            content = "a-thought",
            urgency = Urgency.HIGH,
            rootInputId = root,
            conversationContext = ctxA
        )
        scheduler.enqueueThought(
            content = "b-thought",
            urgency = Urgency.HIGH,
            rootInputId = root,
            conversationContext = ctxB
        )
        scheduler.enqueueAction(
            type = ActionType.WEB_SEARCH,
            payload = "a-action",
            summary = "a-summary",
            urgency = Urgency.HIGH,
            rootInputId = root,
            conversationContext = ctxA
        )
        scheduler.enqueueAction(
            type = ActionType.WEB_SEARCH,
            payload = "b-action",
            summary = "b-summary",
            urgency = Urgency.HIGH,
            rootInputId = root,
            conversationContext = ctxB
        )

        val cleared = scheduler.clearPendingWorkForInput(rootInputId = root, sessionId = sessionA)

        assertEquals(1, cleared.thoughtsRemoved)
        assertEquals(1, cleared.actionsRemoved)
        val state = scheduler.queueState()
        assertEquals(listOf("b-thought"), state.thoughts.map { it.content })
        assertEquals(listOf("b-action"), state.actions.map { it.payload })
    }

    @Test
    fun `scheduler can detect pending fallback and plan thoughts for an input`() {
        val scheduler = AttentionScheduler(config.copy(maxPendingThoughts = 8, maxPendingActions = 8))
        val root = "root-plan"
        scheduler.enqueueThought(
            content = "step 1",
            urgency = Urgency.MEDIUM,
            rootInputId = root,
            planContext = psyke.agent.model.PlanContext(
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
            rootInputId = root
        )

        assertTrue(scheduler.hasPendingPlanThoughtsForInput(root, "default"))
        assertTrue(scheduler.hasPendingFallbackExplanationAction(root, "default"))
        assertFalse(scheduler.hasPendingPlanThoughtsForInput("root-missing", "default"))
        assertFalse(scheduler.hasPendingFallbackExplanationAction("root-missing", "default"))
    }

    @Test
    fun `scheduler pending checks are scoped by session for same root input`() {
        val scheduler = AttentionScheduler(config.copy(maxPendingThoughts = 8, maxPendingActions = 8))
        val root = "root-shared-2"
        val ctxA = psyke.agent.model.ConversationContext("session-a", psyke.agent.model.Interlocutor.named("a"))
        val ctxB = psyke.agent.model.ConversationContext("session-b", psyke.agent.model.Interlocutor.named("b"))
        scheduler.enqueueThought(
            content = "step one",
            urgency = Urgency.MEDIUM,
            rootInputId = root,
            planContext = psyke.agent.model.PlanContext(
                planId = "p1",
                planGoal = "goal-a",
                stepIndex = 0,
                totalSteps = 1,
                stepDescription = "step-a"
            ),
            conversationContext = ctxA
        )
        scheduler.enqueueAction(
            type = ActionType.ANSWER,
            payload = "fallback-a",
            summary = "fallback-a",
            urgency = Urgency.HIGH,
            isFallbackExplanation = true,
            rootInputId = root,
            conversationContext = ctxA
        )
        scheduler.enqueueThought(
            content = "${AttentionScheduler.CONVERGENCE_THOUGHT_PREFIX}other session",
            urgency = Urgency.MEDIUM,
            rootInputId = root,
            conversationContext = ctxB
        )

        assertTrue(scheduler.hasPendingPlanThoughtsForInput(root, "session-a"))
        assertTrue(scheduler.hasPendingFallbackExplanationAction(root, "session-a"))
        assertFalse(scheduler.hasPendingPlanThoughtsForInput(root, "session-b"))
        assertFalse(scheduler.hasPendingFallbackExplanationAction(root, "session-b"))
        assertFalse(scheduler.hasPendingConvergenceThoughtForInput(root, "session-a"))
        assertTrue(scheduler.hasPendingConvergenceThoughtForInput(root, "session-b"))
    }
}
