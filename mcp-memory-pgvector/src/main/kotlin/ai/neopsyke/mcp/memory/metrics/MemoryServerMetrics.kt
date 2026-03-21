package ai.neopsyke.mcp.memory.metrics

import mu.KotlinLogging
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.DoubleAdder

private val logger = KotlinLogging.logger {}

/**
 * Central metrics registry for the pgvector memory MCP server.
 *
 * All counters use atomic types for lock-free thread safety.
 * Latency is tracked as cumulative nanoseconds + count, so average
 * can be derived as total / count.
 */
class MemoryServerMetrics {

    // ---- Embedding API metrics ----
    val embeddingRequests = AtomicLong(0)
    val embeddingRequestErrors = AtomicLong(0)
    val embeddingRetries = AtomicLong(0)
    val embeddingPromptTokens = AtomicLong(0)
    val embeddingTotalTokens = AtomicLong(0)
    val embeddingLatencyNs = AtomicLong(0)
    val embeddingMinLatencyNs = AtomicLong(Long.MAX_VALUE)
    val embeddingMaxLatencyNs = AtomicLong(0)
    val embeddingInputChars = AtomicLong(0)

    // ---- Embedding cache metrics ----
    val cacheHits = AtomicLong(0)
    val cacheMisses = AtomicLong(0)
    val cacheEvictions = AtomicLong(0)

    // ---- Database operation metrics ----
    val dbSearches = AtomicLong(0)
    val dbSearchLatencyNs = AtomicLong(0)
    val dbInserts = AtomicLong(0)
    val dbInsertLatencyNs = AtomicLong(0)
    val dbUpsertConflicts = AtomicLong(0)
    val dbDeletes = AtomicLong(0)
    val dbDeleteLatencyNs = AtomicLong(0)
    val dbReads = AtomicLong(0)
    val dbReadLatencyNs = AtomicLong(0)
    val dbErrors = AtomicLong(0)
    val dbSemanticDedupeHits = AtomicLong(0)
    val dbFactUpserts = AtomicLong(0)
    val dbFactSupersedes = AtomicLong(0)

    // ---- Tool invocation metrics ----
    private val toolInvocations = ConcurrentHashMap<String, AtomicLong>()
    private val toolErrors = ConcurrentHashMap<String, AtomicLong>()
    private val toolLatencyNs = ConcurrentHashMap<String, AtomicLong>()

    /** Record the start time; returns nanoTime for use with [recordLatency]. */
    fun startTimer(): Long = System.nanoTime()

    fun recordEmbeddingLatency(startNs: Long) {
        val elapsed = System.nanoTime() - startNs
        embeddingLatencyNs.addAndGet(elapsed)
        updateMin(embeddingMinLatencyNs, elapsed)
        updateMax(embeddingMaxLatencyNs, elapsed)
    }

    fun recordToolInvocation(toolName: String, startNs: Long) {
        toolInvocations.computeIfAbsent(toolName) { AtomicLong(0) }.incrementAndGet()
        val elapsed = System.nanoTime() - startNs
        toolLatencyNs.computeIfAbsent(toolName) { AtomicLong(0) }.addAndGet(elapsed)
    }

    fun recordToolError(toolName: String) {
        toolErrors.computeIfAbsent(toolName) { AtomicLong(0) }.incrementAndGet()
    }

    /** Returns a snapshot of all metrics as a structured map. */
    fun snapshot(): Map<String, Any> {
        val embeddingCount = embeddingRequests.get()
        val cacheTotal = cacheHits.get() + cacheMisses.get()
        return mapOf(
            "embedding" to mapOf(
                "requests" to embeddingCount,
                "errors" to embeddingRequestErrors.get(),
                "retries" to embeddingRetries.get(),
                "prompt_tokens" to embeddingPromptTokens.get(),
                "total_tokens" to embeddingTotalTokens.get(),
                "input_chars" to embeddingInputChars.get(),
                "latency_ms" to mapOf(
                    "total" to nsToMs(embeddingLatencyNs.get()),
                    "avg" to if (embeddingCount > 0) nsToMs(embeddingLatencyNs.get() / embeddingCount) else 0.0,
                    "min" to if (embeddingMinLatencyNs.get() == Long.MAX_VALUE) 0.0 else nsToMs(embeddingMinLatencyNs.get()),
                    "max" to nsToMs(embeddingMaxLatencyNs.get()),
                ),
            ),
            "cache" to mapOf(
                "hits" to cacheHits.get(),
                "misses" to cacheMisses.get(),
                "evictions" to cacheEvictions.get(),
                "hit_rate" to if (cacheTotal > 0) String.format(Locale.US, "%.2f", cacheHits.get().toDouble() / cacheTotal) else "0.00",
            ),
            "database" to mapOf(
                "searches" to dbSearches.get(),
                "search_latency_ms" to nsToMs(dbSearchLatencyNs.get()),
                "inserts" to dbInserts.get(),
                "insert_latency_ms" to nsToMs(dbInsertLatencyNs.get()),
                "upsert_conflicts" to dbUpsertConflicts.get(),
                "deletes" to dbDeletes.get(),
                "delete_latency_ms" to nsToMs(dbDeleteLatencyNs.get()),
                "reads" to dbReads.get(),
                "read_latency_ms" to nsToMs(dbReadLatencyNs.get()),
                "errors" to dbErrors.get(),
                "semantic_dedupe_hits" to dbSemanticDedupeHits.get(),
                "fact_upserts" to dbFactUpserts.get(),
                "fact_supersedes" to dbFactSupersedes.get(),
            ),
            "tools" to buildToolSnapshot(),
        )
    }

    /** Log a summary line to stderr for periodic monitoring. */
    fun logSummary() {
        val cacheTotal = cacheHits.get() + cacheMisses.get()
        val hitRate = if (cacheTotal > 0) String.format(Locale.US, "%.1f%%", cacheHits.get().toDouble() * 100 / cacheTotal) else "n/a"
        logger.info {
            "Metrics: embed_calls=${embeddingRequests.get()} " +
                "prompt_tokens=${embeddingPromptTokens.get()} " +
                "total_tokens=${embeddingTotalTokens.get()} " +
                "cache_hit_rate=$hitRate " +
                "db_inserts=${dbInserts.get()} " +
                "db_searches=${dbSearches.get()} " +
                "db_errors=${dbErrors.get()} " +
                "semantic_dedupe_hits=${dbSemanticDedupeHits.get()} " +
                "fact_upserts=${dbFactUpserts.get()}"
        }
    }

    private fun buildToolSnapshot(): Map<String, Map<String, Any>> {
        val allTools = (toolInvocations.keys + toolErrors.keys).toSortedSet()
        return allTools.associateWith { tool ->
            val count = toolInvocations[tool]?.get() ?: 0
            val errors = toolErrors[tool]?.get() ?: 0
            val latency = toolLatencyNs[tool]?.get() ?: 0
            mapOf<String, Any>(
                "invocations" to count,
                "errors" to errors,
                "avg_latency_ms" to if (count > 0) nsToMs(latency / count) else 0.0,
                "total_latency_ms" to nsToMs(latency),
            )
        }
    }

    companion object {
        private fun nsToMs(ns: Long): Double = ns / 1_000_000.0

        private fun updateMin(ref: AtomicLong, value: Long) {
            var current = ref.get()
            while (value < current) {
                if (ref.compareAndSet(current, value)) return
                current = ref.get()
            }
        }

        private fun updateMax(ref: AtomicLong, value: Long) {
            var current = ref.get()
            while (value > current) {
                if (ref.compareAndSet(current, value)) return
                current = ref.get()
            }
        }
    }
}
