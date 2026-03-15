package psyke.agent.id

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import psyke.agent.model.ActionOrigin
import psyke.agent.model.ActionType
import psyke.agent.model.LoopTask
import psyke.agent.model.OriginSource
import psyke.agent.model.PendingImpulse
import psyke.agent.ego.AttentionScheduler
import psyke.agent.config.AgentConfig
import psyke.instrumentation.AgentEvent
import psyke.instrumentation.AgentInstrumentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for the Id + AttentionScheduler pipeline.
 *
 * Verifies that the Id fires impulses into the scheduler, the scheduler
 * dispatches them as [LoopTask.ProcessImpulse] tasks at the correct priority
 * (after inputs but before actions/thoughts), and callbacks from the Ego
 * correctly close the impulse lifecycle.
 */
class IdEgoIntegrationTest {

    private val events = mutableListOf<AgentEvent>()
    private val recordingInstrumentation = object : AgentInstrumentation {
        override fun emit(event: AgentEvent) {
            events.add(event)
        }
    }

    private fun defaultAgentConfig(): AgentConfig = AgentConfig()

    private fun buildSchedulerAndId(
        triggerThreshold: Double = 0.0,
        growthRate: Double = 0.2,
        cooldownPulses: Int = 0,
        needName: String = "test-need",
        prompt: String = "Test impulse prompt",
    ): Pair<AttentionScheduler, Id> {
        val agentConfig = defaultAgentConfig()
        val scheduler = AttentionScheduler(agentConfig)
        val idConfig = IdConfig(
            enabled = true,
            pulseIntervalMs = 1000,
            triggerThreshold = triggerThreshold,
            thresholdOnUrgency = true,
            maxConsecutiveDenials = 5,
            backoffPulses = 10,
            maxInFlightPulses = 20,
            maxPendingImpulses = 1,
            needs = mapOf(
                needName to NeedConfig(
                    description = "test",
                    growthRate = growthRate,
                    satisfactionDecay = 0.8,
                    cooldownPulses = cooldownPulses,
                    prompt = prompt,
                    responseCurve = ResponseCurveConfig(type = "linear"),
                    activityDecay = mapOf(
                        "action_executed" to 0.1,
                        "input_received" to 0.15,
                    ),
                ),
            ),
        )
        val id = Id(
            config = idConfig,
            instrumentation = recordingInstrumentation,
            scope = CoroutineScope(Dispatchers.Unconfined),
            enqueueImpulse = { impulse -> scheduler.enqueueImpulse(impulse, idConfig.maxPendingImpulses) },
            hasPendingWork = { scheduler.hasPendingWork() },
        )
        return scheduler to id
    }

    // ── Impulse flows from Id through scheduler ─────────────────────────

    @Test
    fun `impulse fired by Id is dequeued as ProcessImpulse task`() {
        val (scheduler, id) = buildSchedulerAndId()

        id.pulse()

        val task = scheduler.nextTask()
        assertNotNull(task, "Scheduler should have a task")
        assertIs<LoopTask.ProcessImpulse>(task)
        assertEquals("test-need", task.item.needId)
        assertEquals("Test impulse prompt", task.item.prompt)
    }

    @Test
    fun `impulse respects scheduler capacity limit`() {
        val (scheduler, id) = buildSchedulerAndId(cooldownPulses = 0)

        // First pulse fires impulse (enqueue succeeds, maxPendingImpulses = 1)
        id.pulse()
        val snapshot1 = scheduler.queueSnapshot()
        assertEquals(1, snapshot1.pendingImpulseCount)

        // Complete impulse so need is no longer in-flight, and simulate a second pulse
        // But don't dequeue the first impulse — queue is full
        id.onImpulseCompleted("test-need", success = true)

        // Now grow the need again to fire
        repeat(3) { id.pulse() } // grow + try to fire

        // Queue should still only have 1 impulse (original was never dequeued)
        assertEquals(1, scheduler.queueSnapshot().pendingImpulseCount)
    }

    @Test
    fun `inputs take priority over impulses in scheduler`() {
        val (scheduler, id) = buildSchedulerAndId()

        id.pulse() // fires impulse
        assertEquals(1, scheduler.queueSnapshot().pendingImpulseCount)

        // Enqueue a user input after the impulse
        scheduler.enqueueInput(content = "User says hello", source = "test")
        assertEquals(1, scheduler.queueSnapshot().pendingInputCount)

        // Input should be dequeued first
        val task1 = scheduler.nextTask()
        assertIs<LoopTask.ProcessInput>(task1, "Input should take priority over impulse")

        // Then impulse
        val task2 = scheduler.nextTask()
        assertIs<LoopTask.ProcessImpulse>(task2, "Impulse should come second")
    }

    @Test
    fun `impulses take priority over actions and thoughts`() {
        val (scheduler, _) = buildSchedulerAndId()

        // Enqueue a thought and action first
        scheduler.enqueueThought(
            content = "thinking...",
            urgency = psyke.agent.model.Urgency.MEDIUM,
        )
        scheduler.enqueueAction(
            type = ActionType.ANSWER,
            payload = "hello",
            summary = "answer",
            urgency = psyke.agent.model.Urgency.MEDIUM,
        )

        // Manually enqueue an impulse (Id.pulse() won't fire because hasPendingWork=true)
        val impulse = PendingImpulse(
            id = 1,
            needId = "test-need",
            prompt = "test",
            urgency = 0.8,
            rawValue = 0.8,
            conversationContext = psyke.agent.model.ConversationContext(
                sessionId = Id.SESSION_ID,
                interlocutor = Id.INTERLOCUTOR,
            ),
        )
        scheduler.enqueueImpulse(impulse, maxPendingImpulses = 1)

        // Impulse should come before thought and action
        val task1 = scheduler.nextTask()
        assertIs<LoopTask.ProcessImpulse>(task1, "Impulse should beat thoughts and actions")
    }

    @Test
    fun `idle gate blocks impulse when scheduler has pending work`() {
        val (scheduler, id) = buildSchedulerAndId()

        // Put work in the scheduler to make it "busy"
        scheduler.enqueueThought(
            content = "pending thought",
            urgency = psyke.agent.model.Urgency.LOW,
        )

        id.pulse()

        // Impulse should not have been fired (hasPendingWork() returned true)
        assertEquals(0, scheduler.queueSnapshot().pendingImpulseCount, "Impulse should not fire when Ego has pending work")
    }

    // ── Full impulse lifecycle ──────────────────────────────────────────

    @Test
    fun `full lifecycle - fire, accept, complete with success`() {
        val (scheduler, id) = buildSchedulerAndId(cooldownPulses = 2)
        val need = id.needs["test-need"]!!

        // 1. Pulse fires impulse
        id.pulse()
        assertTrue(need.inFlight, "Need should be in-flight after fire")
        assertEquals(0.2, need.value, 1e-9)

        // 2. Dequeue impulse from scheduler
        val task = scheduler.nextTask()
        assertIs<LoopTask.ProcessImpulse>(task)

        // 3. Ego accepts (planner produced a plan)
        id.onImpulseAccepted("test-need")
        val acceptedEvents = events.filter { it.type == "id_impulse_accepted" }
        assertEquals(1, acceptedEvents.size)

        // 4. Ego completes with success
        id.onImpulseCompleted("test-need", success = true)
        // value was 0.2, satisfaction decay 0.8 -> 0.2 * (1-0.8) = 0.04
        assertEquals(0.04, need.value, 1e-9)
        assertEquals(false, need.inFlight)
        assertEquals(0, need.consecutiveDenials)

        val completedEvents = events.filter { it.type == "id_impulse_completed" }
        assertEquals(1, completedEvents.size)
        assertEquals(true, completedEvents[0].data["success"])
    }

    @Test
    fun `full lifecycle - fire, deny by planner noop`() {
        val (scheduler, id) = buildSchedulerAndId()
        val need = id.needs["test-need"]!!

        id.pulse()
        assertTrue(need.inFlight)

        val task = scheduler.nextTask()
        assertIs<LoopTask.ProcessImpulse>(task)

        // Planner returns Noop -> Ego calls onImpulseDenied
        id.onImpulseDenied("test-need")
        assertEquals(false, need.inFlight)
        assertEquals(1, need.consecutiveDenials)
        // Value is NOT decayed on denial (only on success)
        assertEquals(0.2, need.value, 1e-9)
    }

    @Test
    fun `full lifecycle - fire, complete with failure`() {
        val (scheduler, id) = buildSchedulerAndId()
        val need = id.needs["test-need"]!!

        id.pulse()
        scheduler.nextTask() // dequeue

        id.onImpulseCompleted("test-need", success = false)
        assertEquals(false, need.inFlight)
        assertEquals(1, need.consecutiveDenials)
        // Value NOT decayed on failure
        assertEquals(0.2, need.value, 1e-9)
    }

    // ── ActionOrigin propagation ────────────────────────────────────────

    @Test
    fun `impulse metadata carries correct conversation context`() {
        val (scheduler, id) = buildSchedulerAndId()
        id.pulse()

        val task = scheduler.nextTask()
        assertIs<LoopTask.ProcessImpulse>(task)
        assertEquals(Id.SESSION_ID, task.item.conversationContext.sessionId)
        assertEquals(Id.INTERLOCUTOR, task.item.conversationContext.interlocutor)
    }

    @Test
    fun `ActionOrigin id() factory creates correct origin`() {
        val origin = ActionOrigin.id(needId = "be-useful", rootImpulseId = "abc-123")
        assertEquals(OriginSource.ID, origin.source)
        assertEquals("be-useful", origin.needId)
        assertEquals("abc-123", origin.rootImpulseId)
    }

    // ── Activity decay integration ──────────────────────────────────────

    @Test
    fun `activity callbacks from simulated Ego actions decay needs correctly`() {
        val (_, id) = buildSchedulerAndId(growthRate = 0.5)
        val need = id.needs["test-need"]!!

        id.pulse() // grows to 0.5

        // Simulate Ego processing action -> calls onActivity
        id.onActivity("action_executed")
        assertEquals(0.4, need.value, 1e-9) // 0.5 - 0.1

        // Simulate user input -> calls onActivity
        id.onActivity("input_received")
        assertEquals(0.25, need.value, 1e-9) // 0.4 - 0.15
    }

    @Test
    fun `activity decay emits instrumentation events for each decay`() {
        val (_, id) = buildSchedulerAndId(growthRate = 0.5)
        id.pulse()
        events.clear()

        id.onActivity("action_executed")
        id.onActivity("input_received")

        val decayEvents = events.filter { it.type == "id_activity_decay" }
        assertEquals(2, decayEvents.size)
        assertEquals("action_executed", decayEvents[0].data["event_type"])
        assertEquals("input_received", decayEvents[1].data["event_type"])
    }

    // ── Queue snapshot integration ──────────────────────────────────────

    @Test
    fun `queue snapshot reflects impulse count accurately`() {
        val (scheduler, id) = buildSchedulerAndId()

        assertEquals(0, scheduler.queueSnapshot().pendingImpulseCount)

        id.pulse()
        assertEquals(1, scheduler.queueSnapshot().pendingImpulseCount)

        scheduler.nextTask()
        assertEquals(0, scheduler.queueSnapshot().pendingImpulseCount)
    }

    @Test
    fun `clearPendingImpulses removes all queued impulses`() {
        val (scheduler, id) = buildSchedulerAndId()
        id.pulse()
        assertEquals(1, scheduler.queueSnapshot().pendingImpulseCount)

        scheduler.clearPendingImpulses()
        assertEquals(0, scheduler.queueSnapshot().pendingImpulseCount)
        assertNull(scheduler.nextTask())
    }
}
