package psyke.mcp.memory

data class MemoryServerConfig(
    val dbUrl: String,
    val dbUser: String,
    val dbPassword: String,
    val embeddingApiKey: String,
    val embeddingBaseUrl: String,
    val embeddingModel: String,
    val embeddingDimensions: Int,
    val searchDefaultLimit: Int,
    val serverName: String,
    val serverVersion: String,
) {
    companion object {
        const val DEFAULT_DB_URL = "jdbc:postgresql://localhost:5432/psyke_memory"
        const val DEFAULT_DB_USER = "psyke"
        const val DEFAULT_DB_PASSWORD = "psyke_dev"
        const val DEFAULT_EMBEDDING_BASE_URL = "https://api.mistral.ai/v1"
        const val DEFAULT_EMBEDDING_MODEL = "mistral-embed"
        const val DEFAULT_EMBEDDING_DIMENSIONS = 1024
        const val DEFAULT_SEARCH_LIMIT = 10
        const val SERVER_NAME = "psyke-memory-pgvector"
        const val SERVER_VERSION = "0.1.0"

        fun fromEnv(env: Map<String, String> = System.getenv()): MemoryServerConfig {
            val embeddingApiKey = env(env, "EMBEDDING_API_KEY")
                ?: env(env, "MISTRAL_API_KEY")
                ?: ""

            return MemoryServerConfig(
                dbUrl = env(env, "PGVECTOR_DB_URL") ?: DEFAULT_DB_URL,
                dbUser = env(env, "PGVECTOR_DB_USER") ?: DEFAULT_DB_USER,
                dbPassword = env(env, "PGVECTOR_DB_PASSWORD") ?: DEFAULT_DB_PASSWORD,
                embeddingApiKey = embeddingApiKey,
                embeddingBaseUrl = env(env, "EMBEDDING_BASE_URL") ?: DEFAULT_EMBEDDING_BASE_URL,
                embeddingModel = env(env, "EMBEDDING_MODEL") ?: DEFAULT_EMBEDDING_MODEL,
                embeddingDimensions = envInt(env, "EMBEDDING_DIMENSIONS") ?: DEFAULT_EMBEDDING_DIMENSIONS,
                searchDefaultLimit = envInt(env, "MEMORY_SEARCH_DEFAULT_LIMIT") ?: DEFAULT_SEARCH_LIMIT,
                serverName = SERVER_NAME,
                serverVersion = SERVER_VERSION,
            )
        }

        private fun env(env: Map<String, String>, key: String): String? =
            env[key]?.trim()?.ifBlank { null }

        private fun envInt(env: Map<String, String>, key: String): Int? =
            env[key]?.trim()?.toIntOrNull()
    }
}
