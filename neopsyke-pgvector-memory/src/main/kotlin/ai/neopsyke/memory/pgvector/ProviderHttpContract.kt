package ai.neopsyke.memory.pgvector

/**
 * Stable HTTP contract for NeoPsyke memory providers.
 *
 * Breaking wire changes must move to a new versioned namespace (`/v2/...`)
 * instead of mutating the v1 paths in place.
 */
object ProviderHttpContract {
    const val API_VERSION = "v1"

    const val HEALTH_PATH = "/$API_VERSION/health"
    const val METRICS_PATH = "/$API_VERSION/metrics"
    const val RECALL_PATH = "/$API_VERSION/recall"
    const val IMPRINT_PATH = "/$API_VERSION/imprint"
    const val FORGET_PATH = "/$API_VERSION/admin/forget"
    const val RESET_PATH = "/$API_VERSION/admin/reset"
}

data class ProviderHttpErrorResponse(
    val provider: String,
    val error: String,
    val detail: String,
)

