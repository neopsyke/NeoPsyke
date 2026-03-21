package ai.neopsyke.instrumentation

import ai.neopsyke.llm.ChatCallObserver
import ai.neopsyke.llm.ChatCallRecord
import ai.neopsyke.metrics.MetricsRuntime

class MetricsSnapshotObserver(
    private val metricsRuntime: MetricsRuntime,
    private val instrumentation: AgentInstrumentation,
) : ChatCallObserver {
    override fun onChatCall(record: ChatCallRecord) {
        val snapshot = metricsRuntime.snapshot() ?: return
        instrumentation.emit(
            AgentEvent(
                type = "metrics_snapshot",
                data = mapOf("metrics" to snapshot)
            )
        )
    }
}
