package ai.neopsyke.agent.cortex.motor.actions.fetch

interface FetchTool {
    suspend fun fetch(payload: String): String

    suspend fun fetchWithOutcome(payload: String): FetchOutcome =
        FetchOutcome(message = fetch(payload))

    suspend fun healthCheck(): ToolHealthStatus = ToolHealthStatus(
        available = true,
        detail = "health check not implemented"
    )
}

data class ToolHealthStatus(
    val available: Boolean,
    val detail: String,
)

enum class FetchErrorCategory {
    NONE,
    MALFORMED_REQUEST,
    NON_RETRYABLE,
    RETRYABLE,
}

data class FetchOutcome(
    val message: String,
    val errorCategory: FetchErrorCategory = FetchErrorCategory.NONE,
)
