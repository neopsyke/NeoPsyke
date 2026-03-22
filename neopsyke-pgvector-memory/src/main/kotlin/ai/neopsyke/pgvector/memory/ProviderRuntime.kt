package ai.neopsyke.pgvector.memory

import ai.neopsyke.pgvector.memory.db.MemoryRepository
import ai.neopsyke.pgvector.memory.embedding.EmbeddingCache
import ai.neopsyke.pgvector.memory.embedding.Embedder
import ai.neopsyke.pgvector.memory.embedding.MistralEmbedder
import ai.neopsyke.pgvector.memory.metrics.MemoryServerMetrics

data class ProviderRuntime(
    val config: MemoryServerConfig,
    val repository: MemoryRepository,
    val embedder: Embedder,
    val metrics: MemoryServerMetrics,
) : AutoCloseable {
    override fun close() {
        repository.close()
    }
}

object ProviderRuntimeFactory {
    fun create(config: MemoryServerConfig): ProviderRuntime {
        val metrics = MemoryServerMetrics()
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
