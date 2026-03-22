package ai.neopsyke.memory.pgvector

import ai.neopsyke.memory.pgvector.db.MemoryRepository
import ai.neopsyke.memory.pgvector.embedding.EmbeddingCache
import ai.neopsyke.memory.pgvector.embedding.Embedder
import ai.neopsyke.memory.pgvector.embedding.MistralEmbedder
import ai.neopsyke.memory.pgvector.metrics.MemoryProviderMetrics

data class ProviderRuntime(
    val config: PgvectorMemoryProviderConfig,
    val repository: MemoryRepository,
    val embedder: Embedder,
    val metrics: MemoryProviderMetrics,
) : AutoCloseable {
    override fun close() {
        repository.close()
    }
}

object ProviderRuntimeFactory {
    fun create(config: PgvectorMemoryProviderConfig): ProviderRuntime {
        val metrics = MemoryProviderMetrics()
        val repository = MemoryRepository(config, metrics)
        repository.initSchema()
        val embedder = EmbeddingCache(MistralEmbedder(config, metrics), metrics = metrics)
        return ProviderRuntime(
            config = config,
            repository = repository,
            embedder = embedder,
            metrics = metrics,
        )
    }
}
