package ai.neopsyke.memory.pgvector

enum class PgvectorBootstrapMode {
    OFF,
    AUTO;

    companion object {
        fun parse(raw: String?): PgvectorBootstrapMode? =
            when (raw?.trim()?.lowercase()) {
                "off" -> OFF
                "auto" -> AUTO
                else -> null
            }
    }
}

data class PgvectorMemoryProviderConfig(
    val dbUrl: String,
    val dbUser: String,
    val dbPassword: String,
    val defaultNamespace: String,
    val embeddingApiKey: String,
    val embeddingBaseUrl: String,
    val embeddingModel: String,
    val embeddingDimensions: Int,
    val searchDefaultLimit: Int,
    val semanticDedupeSimilarityThreshold: Double,
    val semanticDedupeMinConfidence: Double,
    val factDefaultSubject: String,
    val bootstrapMode: PgvectorBootstrapMode,
    val bootstrapDockerImage: String,
    val bootstrapContainerName: String,
    val bootstrapVolumeName: String,
    val bootstrapStartupTimeoutMs: Long,
    val providerName: String,
    val providerVersion: String,
) {
    companion object {
        const val DEFAULT_DB_URL = "jdbc:postgresql://localhost:5432/neopsyke_memory"
        const val DEFAULT_DB_USER = "neopsyke"
        const val DEFAULT_DB_PASSWORD = "neopsyke_dev"
        const val DEFAULT_NAMESPACE = "default"
        const val DEFAULT_EMBEDDING_BASE_URL = "https://api.mistral.ai/v1"
        const val DEFAULT_EMBEDDING_MODEL = "mistral-embed"
        const val DEFAULT_EMBEDDING_DIMENSIONS = 1024
        const val DEFAULT_SEARCH_LIMIT = 10
        const val DEFAULT_SEMANTIC_DEDUPE_SIMILARITY_THRESHOLD = 0.93
        const val DEFAULT_SEMANTIC_DEDUPE_MIN_CONFIDENCE = 0.65
        const val DEFAULT_FACT_SUBJECT = "me"
        val DEFAULT_BOOTSTRAP_MODE = PgvectorBootstrapMode.AUTO
        const val DEFAULT_BOOTSTRAP_DOCKER_IMAGE = "pgvector/pgvector:pg17"
        const val DEFAULT_BOOTSTRAP_CONTAINER_NAME = "neopsyke-pgvector-memory-db"
        const val DEFAULT_BOOTSTRAP_VOLUME_NAME = "neopsyke-pgvector-memory-data"
        const val DEFAULT_BOOTSTRAP_STARTUP_TIMEOUT_MS = 45_000L
        const val PROVIDER_NAME = "neopsyke-pgvector-memory"
        const val PROVIDER_VERSION = "0.1.0"

        fun fromEnv(env: Map<String, String> = System.getenv()): PgvectorMemoryProviderConfig {
            val embeddingApiKey = env(env, "EMBEDDING_API_KEY")
                ?: env(env, "MISTRAL_API_KEY")
                ?: ""

            return PgvectorMemoryProviderConfig(
                dbUrl = env(env, "PGVECTOR_DB_URL") ?: DEFAULT_DB_URL,
                dbUser = env(env, "PGVECTOR_DB_USER") ?: DEFAULT_DB_USER,
                dbPassword = env(env, "PGVECTOR_DB_PASSWORD") ?: DEFAULT_DB_PASSWORD,
                defaultNamespace = env(env, "MEMORY_DEFAULT_NAMESPACE") ?: DEFAULT_NAMESPACE,
                embeddingApiKey = embeddingApiKey,
                embeddingBaseUrl = env(env, "EMBEDDING_BASE_URL") ?: DEFAULT_EMBEDDING_BASE_URL,
                embeddingModel = env(env, "EMBEDDING_MODEL") ?: DEFAULT_EMBEDDING_MODEL,
                embeddingDimensions = envInt(env, "EMBEDDING_DIMENSIONS") ?: DEFAULT_EMBEDDING_DIMENSIONS,
                searchDefaultLimit = envInt(env, "MEMORY_SEARCH_DEFAULT_LIMIT") ?: DEFAULT_SEARCH_LIMIT,
                semanticDedupeSimilarityThreshold = envDouble(env, "MEMORY_SEMANTIC_DEDUPE_SIMILARITY_THRESHOLD")
                    ?.coerceIn(0.0, 1.0)
                    ?: DEFAULT_SEMANTIC_DEDUPE_SIMILARITY_THRESHOLD,
                semanticDedupeMinConfidence = envDouble(env, "MEMORY_SEMANTIC_DEDUPE_MIN_CONFIDENCE")
                    ?.coerceIn(0.0, 1.0)
                    ?: DEFAULT_SEMANTIC_DEDUPE_MIN_CONFIDENCE,
                factDefaultSubject = env(env, "MEMORY_FACT_DEFAULT_SUBJECT") ?: DEFAULT_FACT_SUBJECT,
                bootstrapMode = PgvectorBootstrapMode.parse(env(env, "PGVECTOR_BOOTSTRAP_MODE"))
                    ?: DEFAULT_BOOTSTRAP_MODE,
                bootstrapDockerImage = env(env, "PGVECTOR_BOOTSTRAP_DOCKER_IMAGE")
                    ?: DEFAULT_BOOTSTRAP_DOCKER_IMAGE,
                bootstrapContainerName = env(env, "PGVECTOR_BOOTSTRAP_CONTAINER_NAME")
                    ?: DEFAULT_BOOTSTRAP_CONTAINER_NAME,
                bootstrapVolumeName = env(env, "PGVECTOR_BOOTSTRAP_VOLUME_NAME")
                    ?: DEFAULT_BOOTSTRAP_VOLUME_NAME,
                bootstrapStartupTimeoutMs = env(env, "PGVECTOR_BOOTSTRAP_STARTUP_TIMEOUT_MS")
                    ?.toLongOrNull()
                    ?.coerceAtLeast(1_000L)
                    ?: DEFAULT_BOOTSTRAP_STARTUP_TIMEOUT_MS,
                providerName = PROVIDER_NAME,
                providerVersion = PROVIDER_VERSION,
            )
        }

        private fun env(env: Map<String, String>, key: String): String? =
            env[key]?.trim()?.ifBlank { null }

        private fun envInt(env: Map<String, String>, key: String): Int? =
            env[key]?.trim()?.toIntOrNull()

        private fun envDouble(env: Map<String, String>, key: String): Double? =
            env[key]?.trim()?.toDoubleOrNull()
    }
}
