package psyke.mcp.memory.embedding

/**
 * Thread-safe LRU cache wrapping an [Embedder].
 * Avoids re-embedding identical text within the same session.
 */
class EmbeddingCache(
    private val delegate: Embedder,
    private val maxSize: Int = DEFAULT_MAX_SIZE,
) : Embedder by delegate {

    companion object {
        const val DEFAULT_MAX_SIZE = 256
    }

    private val cache = object : LinkedHashMap<String, FloatArray>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, FloatArray>): Boolean =
            size > maxSize
    }

    override fun embed(text: String): FloatArray {
        synchronized(cache) {
            cache[text]?.let { return it }
        }
        val result = delegate.embed(text)
        synchronized(cache) {
            cache[text] = result
        }
        return result
    }
}
