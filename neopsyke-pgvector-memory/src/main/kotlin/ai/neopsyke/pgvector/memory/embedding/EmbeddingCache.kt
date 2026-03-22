package ai.neopsyke.pgvector.memory.embedding

import ai.neopsyke.pgvector.memory.metrics.MemoryServerMetrics

/**
 * Thread-safe LRU cache wrapping an [Embedder].
 * Avoids re-embedding identical text within the same session.
 */
class EmbeddingCache(
    private val delegate: Embedder,
    private val maxSize: Int = DEFAULT_MAX_SIZE,
    private val metrics: MemoryServerMetrics? = null,
) : Embedder by delegate {

    companion object {
        const val DEFAULT_MAX_SIZE = 256
    }

    private val cache = object : LinkedHashMap<String, FloatArray>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, FloatArray>): Boolean {
            val evict = size > maxSize
            if (evict) metrics?.cacheEvictions?.incrementAndGet()
            return evict
        }
    }

    override fun embed(text: String): FloatArray {
        synchronized(cache) {
            cache[text]?.let {
                metrics?.cacheHits?.incrementAndGet()
                return it
            }
        }
        metrics?.cacheMisses?.incrementAndGet()
        val result = delegate.embed(text)
        synchronized(cache) {
            cache[text] = result
        }
        return result
    }
}
