package psyke.dashboard

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import psyke.agent.ActionType
import psyke.agent.PendingAction
import psyke.agent.PendingInput
import psyke.agent.PendingThought
import psyke.agent.QueueState
import psyke.agent.Urgency
import psyke.instrumentation.AgentEvent
import psyke.metrics.MetricsSnapshot
import psyke.metrics.MetricsTotals
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DashboardStateStoreTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `snapshot aggregates state and keeps events ordered by id`() {
        val store = DashboardStateStore(maxEvents = 100)
        val queues = QueueState(
            inputs = listOf(PendingInput(1, "hello")),
            thoughts = listOf(PendingThought(2, Urgency.HIGH, "think", 1)),
            actions = listOf(PendingAction(3, Urgency.MEDIUM, ActionType.ANSWER, "payload", "sum", 0))
        )
        val metrics = MetricsSnapshot(
            runId = "run-1",
            keyFingerprint = "fp",
            updatedAtIso = "2026-01-01T00:00:00Z",
            runTotals = MetricsTotals(1, 2, 3, 5, 0, 0),
            persistentTotals = MetricsTotals(2, 4, 6, 10, 1, 0),
            runCountForKey = 2
        )

        store.onEvent(AgentEvent(id = 2, type = "loop_step", data = mapOf("step" to 2, "task_type" to "thought")))
        store.onEvent(AgentEvent(id = 1, type = "loop_status", data = mapOf("status" to "running", "message" to "ok")))
        store.onEvent(AgentEvent(id = 3, type = "queue_snapshot", data = mapOf("queues" to queues)))
        store.onEvent(AgentEvent(id = 4, type = "superego_input", data = mapOf("allow" to false)))
        store.onEvent(AgentEvent(id = 5, type = "superego_output", data = mapOf("allow" to true)))
        store.onEvent(AgentEvent(id = 6, type = "limits_config", data = mapOf("limits" to mapOf("max_prompt_tokens" to 2400))))
        store.onEvent(AgentEvent(id = 7, type = "metrics_snapshot", data = mapOf("metrics" to metrics)))

        val snapshot: DashboardSnapshot = mapper.readValue(store.snapshotJson())
        assertEquals("running", snapshot.loopStatus)
        assertEquals("ok", snapshot.loopMessage)
        assertEquals(2, snapshot.loopStep)
        assertEquals("thought", snapshot.currentTaskType)
        assertEquals(1, snapshot.queues.inputs.size)
        assertEquals(1, snapshot.queues.thoughts.size)
        assertEquals(1, snapshot.queues.actions.size)
        assertEquals(true, snapshot.lastSuperegoOutput?.get("allow"))
        assertEquals(2400, snapshot.limits["max_prompt_tokens"])
        assertEquals(metrics, snapshot.metrics)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L), snapshot.recentEvents.map { it.id })
    }

    @Test
    fun `subscription receives json payload for incoming events`() {
        val store = DashboardStateStore(maxEvents = 10)
        val subscription = store.subscribe()
        store.onEvent(AgentEvent(id = 9, type = "warning", data = mapOf("message" to "test")))

        val payload = subscription.poll(timeoutMs = 200)
        assertNotNull(payload)
        assertTrue(payload.contains("\"type\":\"warning\""))
        assertTrue(payload.contains("\"id\":9"))
        subscription.close()
        store.close()
    }
}
