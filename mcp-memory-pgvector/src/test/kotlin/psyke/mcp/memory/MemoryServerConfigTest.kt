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
        assertEquals("", config.embeddingApiKey)
        assertEquals(MemoryServerConfig.DEFAULT_EMBEDDING_BASE_URL, config.embeddingBaseUrl)
        assertEquals(MemoryServerConfig.DEFAULT_EMBEDDING_MODEL, config.embeddingModel)
        assertEquals(MemoryServerConfig.DEFAULT_EMBEDDING_DIMENSIONS, config.embeddingDimensions)
        assertEquals(MemoryServerConfig.DEFAULT_SEARCH_LIMIT, config.searchDefaultLimit)
    }

    @Test
    fun `env overrides take precedence`() {
        val env = mapOf(
            "PGVECTOR_DB_URL" to "jdbc:postgresql://custom:5432/mydb",
            "PGVECTOR_DB_USER" to "admin",
            "EMBEDDING_API_KEY" to "sk-test-key",
            "EMBEDDING_MODEL" to "custom-embed",
            "EMBEDDING_DIMENSIONS" to "768",
        )
        val config = MemoryServerConfig.fromEnv(env)
        assertEquals("jdbc:postgresql://custom:5432/mydb", config.dbUrl)
        assertEquals("admin", config.dbUser)
        assertEquals("sk-test-key", config.embeddingApiKey)
        assertEquals("custom-embed", config.embeddingModel)
        assertEquals(768, config.embeddingDimensions)
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
