package psyke.mcp.memory.embedding

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
}
