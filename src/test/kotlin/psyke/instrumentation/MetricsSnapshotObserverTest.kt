package psyke.instrumentation

import psyke.llm.ChatCallMetadata
import psyke.llm.ChatCallRecord
import psyke.llm.ChatCallStatus
import psyke.metrics.MetricsSnapshot
import psyke.metrics.MetricsTotals
import psyke.support.RecordingInstrumentation
import psyke.support.StubMetricsRuntime
import kotlin.test.Test
import kotlin.test.assertEquals

class MetricsSnapshotObserverTest {
    @Test
    fun `observer emits snapshot event when metrics are available`() {
        val snapshot = MetricsSnapshot(
            runId = "r1",
            keyFingerprint = "fp",
            updatedAtIso = "2026-01-01T00:00:00Z",
            runTotals = MetricsTotals(1, 2, 3, 5, 0, 0),
            persistentTotals = MetricsTotals(10, 20, 30, 50, 2, 1),
            runCountForKey = 4,
            runSuperegoTokens = 7,
            persistentSuperegoTokens = 70
        )
        val runtime = StubMetricsRuntime(snapshotValue = snapshot)
        val instrumentation = RecordingInstrumentation()
        val observer = MetricsSnapshotObserver(
            metricsRuntime = runtime,
            instrumentation = instrumentation
        )

        observer.onChatCall(
            ChatCallRecord(
                model = "m",
                metadata = ChatCallMetadata(),
                latencyMs = 10,
                status = ChatCallStatus.OK
            )
        )

        val event = instrumentation.events.single()
        assertEquals("metrics_snapshot", event.type)
        assertEquals(snapshot, event.data["metrics"])
    }

    @Test
    fun `observer skips emission when snapshot is unavailable`() {
        val runtime = StubMetricsRuntime(snapshotValue = null)
        val instrumentation = RecordingInstrumentation()
        val observer = MetricsSnapshotObserver(runtime, instrumentation)

        observer.onChatCall(
            ChatCallRecord(
                model = "m",
                metadata = ChatCallMetadata(),
                latencyMs = 10,
                status = ChatCallStatus.ERROR
            )
        )

        assertEquals(0, instrumentation.events.size)
    }
}
