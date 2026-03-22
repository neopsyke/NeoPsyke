package ai.neopsyke.memory.pgvector.embedding

/**
 * Converts text into a fixed-size float vector for semantic similarity search.
 * Implementations may call an external API (e.g. Mistral) or run a local model.
 */
interface Embedder {
    val dimensions: Int
    fun embed(text: String): FloatArray
}
