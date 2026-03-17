package psyke.mcp.memory

import kotlin.test.Test
import kotlin.test.assertEquals

class MemoryServerConfigTest {

    @Test
    fun `defaults are applied when env is empty`() {
        val config = MemoryServerConfig.fromEnv(emptyMap())
        assertEquals(MemoryServerConfig.DEFAULT_DB_URL, config.dbUrl)
        assertEquals(MemoryServerConfig.DEFAULT_DB_USER, config.dbUser)
        assertEquals(MemoryServerConfig.DEFAULT_DB_PASSWORD, config.dbPassword)
        assertEquals(MemoryServerConfig.DEFAULT_NAMESPACE, config.defaultNamespace)
        assertEquals("", config.embeddingApiKey)
        assertEquals(MemoryServerConfig.DEFAULT_EMBEDDING_BASE_URL, config.embeddingBaseUrl)
        assertEquals(MemoryServerConfig.DEFAULT_EMBEDDING_MODEL, config.embeddingModel)
        assertEquals(MemoryServerConfig.DEFAULT_EMBEDDING_DIMENSIONS, config.embeddingDimensions)
        assertEquals(MemoryServerConfig.DEFAULT_SEARCH_LIMIT, config.searchDefaultLimit)
        assertEquals(
            MemoryServerConfig.DEFAULT_SEMANTIC_DEDUPE_SIMILARITY_THRESHOLD,
            config.semanticDedupeSimilarityThreshold
        )
        assertEquals(
            MemoryServerConfig.DEFAULT_SEMANTIC_DEDUPE_MIN_CONFIDENCE,
            config.semanticDedupeMinConfidence
        )
        assertEquals(MemoryServerConfig.DEFAULT_FACT_SUBJECT, config.factDefaultSubject)
        assertEquals("me", config.factDefaultSubject)
    }

    @Test
    fun `env overrides take precedence`() {
        val env = mapOf(
            "PGVECTOR_DB_URL" to "jdbc:postgresql://custom:5432/mydb",
            "PGVECTOR_DB_USER" to "admin",
            "MEMORY_DEFAULT_NAMESPACE" to "codex_project_x",
            "EMBEDDING_API_KEY" to "sk-test-key",
            "EMBEDDING_MODEL" to "custom-embed",
            "EMBEDDING_DIMENSIONS" to "768",
            "MEMORY_SEMANTIC_DEDUPE_SIMILARITY_THRESHOLD" to "0.91",
            "MEMORY_SEMANTIC_DEDUPE_MIN_CONFIDENCE" to "0.72",
            "MEMORY_FACT_DEFAULT_SUBJECT" to "profile",
        )
        val config = MemoryServerConfig.fromEnv(env)
        assertEquals("jdbc:postgresql://custom:5432/mydb", config.dbUrl)
        assertEquals("admin", config.dbUser)
        assertEquals("codex_project_x", config.defaultNamespace)
        assertEquals("sk-test-key", config.embeddingApiKey)
        assertEquals("custom-embed", config.embeddingModel)
        assertEquals(768, config.embeddingDimensions)
        assertEquals(0.91, config.semanticDedupeSimilarityThreshold)
        assertEquals(0.72, config.semanticDedupeMinConfidence)
        assertEquals("profile", config.factDefaultSubject)
    }

    @Test
    fun `MISTRAL_API_KEY is fallback for EMBEDDING_API_KEY`() {
        val env = mapOf("MISTRAL_API_KEY" to "sk-mistral")
        val config = MemoryServerConfig.fromEnv(env)
        assertEquals("sk-mistral", config.embeddingApiKey)
    }

    @Test
    fun `EMBEDDING_API_KEY takes precedence over MISTRAL_API_KEY`() {
        val env = mapOf(
            "EMBEDDING_API_KEY" to "sk-embed",
            "MISTRAL_API_KEY" to "sk-mistral",
        )
        val config = MemoryServerConfig.fromEnv(env)
        assertEquals("sk-embed", config.embeddingApiKey)
    }

    @Test
    fun `blank env values are treated as absent`() {
        val env = mapOf(
            "PGVECTOR_DB_URL" to "  ",
            "EMBEDDING_API_KEY" to "",
        )
        val config = MemoryServerConfig.fromEnv(env)
        assertEquals(MemoryServerConfig.DEFAULT_DB_URL, config.dbUrl)
        assertEquals("", config.embeddingApiKey)
    }
}
