package ai.neopsyke.memory.pgvector.metrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryProviderMetricsTest {

    @Test
    fun `snapshot returns all metric categories`() {
        val metrics = MemoryProviderMetrics()
        val snapshot = metrics.snapshot()

        assertTrue(snapshot.containsKey("embedding"))
        assertTrue(snapshot.containsKey("cache"))
        assertTrue(snapshot.containsKey("database"))
        assertTrue(snapshot.containsKey("tools"))
    }

    @Test
    fun `embedding counters increment correctly`() {
        val metrics = MemoryProviderMetrics()

        metrics.embeddingRequests.incrementAndGet()
        metrics.embeddingRequests.incrementAndGet()
        metrics.embeddingRequestErrors.incrementAndGet()
        metrics.embeddingPromptTokens.addAndGet(150)
        metrics.embeddingTotalTokens.addAndGet(200)
        metrics.embeddingInputChars.addAndGet(500)

        @Suppress("UNCHECKED_CAST")
        val embed = metrics.snapshot()["embedding"] as Map<String, Any>
        assertEquals(2L, embed["requests"])
        assertEquals(1L, embed["errors"])
        assertEquals(150L, embed["prompt_tokens"])
        assertEquals(200L, embed["total_tokens"])
        assertEquals(500L, embed["input_chars"])
    }

    @Test
    fun `cache hit rate is calculated correctly`() {
        val metrics = MemoryProviderMetrics()

        metrics.cacheHits.set(3)
        metrics.cacheMisses.set(1)

        @Suppress("UNCHECKED_CAST")
        val cache = metrics.snapshot()["cache"] as Map<String, Any>
        assertEquals(3L, cache["hits"])
        assertEquals(1L, cache["misses"])
        assertEquals("0.75", cache["hit_rate"])
    }

    @Test
    fun `cache hit rate is zero when no calls`() {
        val metrics = MemoryProviderMetrics()

        @Suppress("UNCHECKED_CAST")
        val cache = metrics.snapshot()["cache"] as Map<String, Any>
        assertEquals("0.00", cache["hit_rate"])
    }

    @Test
    fun `database counters increment correctly`() {
        val metrics = MemoryProviderMetrics()

        metrics.dbSearches.set(5)
        metrics.dbInserts.set(10)
        metrics.dbUpsertConflicts.set(2)
        metrics.dbDeletes.set(1)
        metrics.dbErrors.set(3)
        metrics.dbSemanticDedupeHits.set(4)
        metrics.dbFactUpserts.set(6)
        metrics.dbFactSupersedes.set(2)

        @Suppress("UNCHECKED_CAST")
        val db = metrics.snapshot()["database"] as Map<String, Any>
        assertEquals(5L, db["searches"])
        assertEquals(10L, db["inserts"])
        assertEquals(2L, db["upsert_conflicts"])
        assertEquals(1L, db["deletes"])
        assertEquals(3L, db["errors"])
        assertEquals(4L, db["semantic_dedupe_hits"])
        assertEquals(6L, db["fact_upserts"])
        assertEquals(2L, db["fact_supersedes"])
    }

    @Test
    fun `tool invocation tracking works`() {
        val metrics = MemoryProviderMetrics()

        val start1 = metrics.startTimer()
        Thread.sleep(5)
        metrics.recordToolInvocation("search_memory", start1)

        val start2 = metrics.startTimer()
        Thread.sleep(5)
        metrics.recordToolInvocation("search_memory", start2)

        metrics.recordToolError("search_memory")

        @Suppress("UNCHECKED_CAST")
        val tools = metrics.snapshot()["tools"] as Map<String, Map<String, Any>>
        val searchTool = tools["search_memory"]!!
        assertEquals(2L, searchTool["invocations"])
        assertEquals(1L, searchTool["errors"])
        assertTrue((searchTool["total_latency_ms"] as Double) > 0.0)
    }

    @Test
    fun `embedding latency min max tracking works`() {
        val metrics = MemoryProviderMetrics()

        // Simulate two embedding calls with different durations
        val start1 = metrics.startTimer()
        Thread.sleep(10)
        metrics.recordEmbeddingLatency(start1)

        val start2 = metrics.startTimer()
        Thread.sleep(20)
        metrics.recordEmbeddingLatency(start2)

        @Suppress("UNCHECKED_CAST")
        val embed = metrics.snapshot()["embedding"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val latency = embed["latency_ms"] as Map<String, Any>

        assertTrue((latency["total"] as Double) > 0.0)
        assertTrue((latency["min"] as Double) > 0.0)
        assertTrue((latency["max"] as Double) >= (latency["min"] as Double))
    }

    @Test
    fun `cache eviction counter increments`() {
        val metrics = MemoryProviderMetrics()
        metrics.cacheEvictions.set(5)

        @Suppress("UNCHECKED_CAST")
        val cache = metrics.snapshot()["cache"] as Map<String, Any>
        assertEquals(5L, cache["evictions"])
    }

    @Test
    fun `logSummary does not throw`() {
        val metrics = MemoryProviderMetrics()
        metrics.embeddingRequests.set(10)
        metrics.embeddingPromptTokens.set(5000)
        metrics.cacheHits.set(7)
        metrics.cacheMisses.set(3)
        metrics.dbInserts.set(5)
        metrics.dbSearches.set(2)

        // Should not throw
        metrics.logSummary()
    }
}
