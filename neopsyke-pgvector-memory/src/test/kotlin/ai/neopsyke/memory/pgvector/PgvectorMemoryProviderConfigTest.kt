package ai.neopsyke.memory.pgvector

import kotlin.test.Test
import kotlin.test.assertEquals

class PgvectorMemoryProviderConfigTest {

    @Test
    fun `defaults are applied when env is empty`() {
        val config = PgvectorMemoryProviderConfig.fromEnv(emptyMap())
        assertEquals(PgvectorMemoryProviderConfig.DEFAULT_DB_URL, config.dbUrl)
        assertEquals(PgvectorMemoryProviderConfig.DEFAULT_DB_USER, config.dbUser)
        assertEquals(PgvectorMemoryProviderConfig.DEFAULT_DB_PASSWORD, config.dbPassword)
        assertEquals(PgvectorMemoryProviderConfig.DEFAULT_NAMESPACE, config.defaultNamespace)
        assertEquals("", config.embeddingApiKey)
        assertEquals(PgvectorMemoryProviderConfig.DEFAULT_EMBEDDING_BASE_URL, config.embeddingBaseUrl)
        assertEquals(PgvectorMemoryProviderConfig.DEFAULT_EMBEDDING_MODEL, config.embeddingModel)
        assertEquals(PgvectorMemoryProviderConfig.DEFAULT_EMBEDDING_DIMENSIONS, config.embeddingDimensions)
        assertEquals(PgvectorMemoryProviderConfig.DEFAULT_SEARCH_LIMIT, config.searchDefaultLimit)
        assertEquals(
            PgvectorMemoryProviderConfig.DEFAULT_SEMANTIC_DEDUPE_SIMILARITY_THRESHOLD,
            config.semanticDedupeSimilarityThreshold
        )
        assertEquals(
            PgvectorMemoryProviderConfig.DEFAULT_SEMANTIC_DEDUPE_MIN_CONFIDENCE,
            config.semanticDedupeMinConfidence
        )
        assertEquals(PgvectorMemoryProviderConfig.DEFAULT_FACT_SUBJECT, config.factDefaultSubject)
        assertEquals(PgvectorMemoryProviderConfig.DEFAULT_BOOTSTRAP_MODE, config.bootstrapMode)
        assertEquals(
            PgvectorMemoryProviderConfig.DEFAULT_BOOTSTRAP_DOCKER_IMAGE,
            config.bootstrapDockerImage
        )
        assertEquals(
            PgvectorMemoryProviderConfig.DEFAULT_BOOTSTRAP_CONTAINER_NAME,
            config.bootstrapContainerName
        )
        assertEquals(
            PgvectorMemoryProviderConfig.DEFAULT_BOOTSTRAP_VOLUME_NAME,
            config.bootstrapVolumeName
        )
        assertEquals(
            PgvectorMemoryProviderConfig.DEFAULT_BOOTSTRAP_STARTUP_TIMEOUT_MS,
            config.bootstrapStartupTimeoutMs
        )
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
            "PGVECTOR_BOOTSTRAP_MODE" to "off",
            "PGVECTOR_BOOTSTRAP_DOCKER_IMAGE" to "pgvector/pgvector:pg16",
            "PGVECTOR_BOOTSTRAP_CONTAINER_NAME" to "custom-container",
            "PGVECTOR_BOOTSTRAP_VOLUME_NAME" to "custom-volume",
            "PGVECTOR_BOOTSTRAP_STARTUP_TIMEOUT_MS" to "30000",
        )
        val config = PgvectorMemoryProviderConfig.fromEnv(env)
        assertEquals("jdbc:postgresql://custom:5432/mydb", config.dbUrl)
        assertEquals("admin", config.dbUser)
        assertEquals("codex_project_x", config.defaultNamespace)
        assertEquals("sk-test-key", config.embeddingApiKey)
        assertEquals("custom-embed", config.embeddingModel)
        assertEquals(768, config.embeddingDimensions)
        assertEquals(0.91, config.semanticDedupeSimilarityThreshold)
        assertEquals(0.72, config.semanticDedupeMinConfidence)
        assertEquals("profile", config.factDefaultSubject)
        assertEquals(PgvectorBootstrapMode.OFF, config.bootstrapMode)
        assertEquals("pgvector/pgvector:pg16", config.bootstrapDockerImage)
        assertEquals("custom-container", config.bootstrapContainerName)
        assertEquals("custom-volume", config.bootstrapVolumeName)
        assertEquals(30_000L, config.bootstrapStartupTimeoutMs)
    }

    @Test
    fun `MISTRAL_API_KEY is fallback for EMBEDDING_API_KEY`() {
        val env = mapOf("MISTRAL_API_KEY" to "sk-mistral")
        val config = PgvectorMemoryProviderConfig.fromEnv(env)
        assertEquals("sk-mistral", config.embeddingApiKey)
    }

    @Test
    fun `EMBEDDING_API_KEY takes precedence over MISTRAL_API_KEY`() {
        val env = mapOf(
            "EMBEDDING_API_KEY" to "sk-embed",
            "MISTRAL_API_KEY" to "sk-mistral",
        )
        val config = PgvectorMemoryProviderConfig.fromEnv(env)
        assertEquals("sk-embed", config.embeddingApiKey)
    }

    @Test
    fun `blank env values are treated as absent`() {
        val env = mapOf(
            "PGVECTOR_DB_URL" to "  ",
            "EMBEDDING_API_KEY" to "",
        )
        val config = PgvectorMemoryProviderConfig.fromEnv(env)
        assertEquals(PgvectorMemoryProviderConfig.DEFAULT_DB_URL, config.dbUrl)
        assertEquals("", config.embeddingApiKey)
    }
}
