package ai.neopsyke.memory.pgvector.embedding

import ai.neopsyke.memory.pgvector.metrics.MemoryProviderMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals

class EmbeddingCacheTest {

    private class CountingEmbedder(override val dimensions: Int = 4) : Embedder {
        var callCount = 0
        override fun embed(text: String): FloatArray {
            callCount++
            return FloatArray(dimensions) { text.hashCode().toFloat() + it }
        }
    }

    @Test
    fun `cache returns same result for repeated calls`() {
        val delegate = CountingEmbedder()
        val cache = EmbeddingCache(delegate, maxSize = 10)

        val first = cache.embed("hello")
        val second = cache.embed("hello")

        assertContentEquals(first, second)
        assertEquals(1, delegate.callCount, "Delegate should only be called once for identical text")
    }

    @Test
    fun `cache calls delegate for different texts`() {
        val delegate = CountingEmbedder()
        val cache = EmbeddingCache(delegate, maxSize = 10)

        cache.embed("hello")
        cache.embed("world")

        assertEquals(2, delegate.callCount)
    }

    @Test
    fun `cache evicts oldest entries when full`() {
        val delegate = CountingEmbedder()
        val cache = EmbeddingCache(delegate, maxSize = 2)

        cache.embed("a")
        cache.embed("b")
        cache.embed("c") // evicts "a"
        cache.embed("a") // should re-compute

        assertEquals(4, delegate.callCount)
    }

    @Test
    fun `dimensions are delegated`() {
        val delegate = CountingEmbedder(dimensions = 1024)
        val cache = EmbeddingCache(delegate)
        assertEquals(1024, cache.dimensions)
    }

    @Test
    fun `cache tracks hits and misses in metrics`() {
        val metrics = MemoryProviderMetrics()
        val delegate = CountingEmbedder()
        val cache = EmbeddingCache(delegate, maxSize = 10, metrics = metrics)

        cache.embed("hello") // miss
        cache.embed("hello") // hit
        cache.embed("world") // miss
        cache.embed("hello") // hit

        assertEquals(2, metrics.cacheHits.get())
        assertEquals(2, metrics.cacheMisses.get())
    }

    @Test
    fun `cache tracks evictions in metrics`() {
        val metrics = MemoryProviderMetrics()
        val delegate = CountingEmbedder()
        val cache = EmbeddingCache(delegate, maxSize = 2, metrics = metrics)

        cache.embed("a") // miss
        cache.embed("b") // miss
        cache.embed("c") // miss, evicts "a"

        assertEquals(1, metrics.cacheEvictions.get())
        assertEquals(0, metrics.cacheHits.get())
        assertEquals(3, metrics.cacheMisses.get())
    }
}
