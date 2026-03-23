package ai.neopsyke.metrics

interface MetricsQueryProvider {
    fun llmCallStats(runOnly: Boolean = true, timeframeMs: Long? = null): LlmCallStatsReport
}

data class LlmCallStatsReport(
    val byModel: Map<String, ModelStats>,
    val byRole: Map<String, RoleStats>,
    val errorBreakdown: Map<String, Map<String, Long>>,
)

data class ModelStats(
    val callCount: Long,
    val avgLatencyMs: Long,
    val p50LatencyMs: Long,
    val p95LatencyMs: Long,
    val errorCount: Long,
    val totalTokens: Long,
    val promptTokens: Long,
    val completionTokens: Long,
)

data class RoleStats(
    val callCount: Long,
    val totalTokens: Long,
    val avgLatencyMs: Long,
)
