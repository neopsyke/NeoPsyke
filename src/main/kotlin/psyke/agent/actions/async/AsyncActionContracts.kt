package psyke.agent.actions.async

import com.fasterxml.jackson.annotation.JsonIgnore
import java.time.Instant

data class AsyncActionWait(
    val handles: List<AsyncActionHandle>,
    val aggregation: AsyncWaitAggregation = AsyncWaitAggregation.ALL,
    val minimumSuccessCount: Int? = null,
    val summary: String = "",
) {
    init {
        require(handles.isNotEmpty()) { "AsyncActionWait requires at least one handle." }
    }
}

data class AsyncActionHandle(
    val providerType: String,
    val providerId: String,
    val operationId: String,
    val resumeMode: AsyncResumeMode = AsyncResumeMode.POLL,
    val pollAfterMs: Long? = null,
    val timeoutAt: Instant? = null,
    val correlationKey: String? = null,
    val metadata: Map<String, String> = emptyMap(),
) {
    @get:JsonIgnore
    val key: String
        get() = listOf(providerType, providerId, operationId, correlationKey.orEmpty()).joinToString("::")
}

enum class AsyncResumeMode {
    POLL,
    EVENT,
    HYBRID,
}

enum class AsyncWaitAggregation {
    ALL,
    ANY,
    AT_LEAST_N,
}

sealed interface AsyncOperationStatus {
    data class Pending(
        val summary: String = "",
        val nextPollAfterMs: Long? = null,
    ) : AsyncOperationStatus

    data class Succeeded(
        val summary: String = "",
    ) : AsyncOperationStatus

    data class Failed(
        val reason: String,
        val retryable: Boolean = false,
    ) : AsyncOperationStatus

    data class Cancelled(
        val reason: String = "",
    ) : AsyncOperationStatus
}

interface AsyncOperationProvider {
    val providerType: String

    suspend fun poll(handle: AsyncActionHandle): AsyncOperationStatus
}

class AsyncOperationRegistry private constructor(
    private val providersByType: Map<String, AsyncOperationProvider>,
) {
    fun provider(providerType: String): AsyncOperationProvider? = providersByType[providerType]

    companion object {
        fun empty(): AsyncOperationRegistry = AsyncOperationRegistry(emptyMap())

        fun fromProviders(providers: List<AsyncOperationProvider>): AsyncOperationRegistry =
            AsyncOperationRegistry(providers.associateBy { it.providerType })
    }
}

data class AsyncOperationEvent(
    val providerType: String,
    val providerId: String? = null,
    val operationId: String? = null,
    val correlationKey: String? = null,
    val status: AsyncOperationEventStatus,
    val summary: String = "",
    val metadata: Map<String, String> = emptyMap(),
) {
    fun matches(handle: AsyncActionHandle): Boolean {
        if (providerType != handle.providerType) return false
        if (providerId != null && providerId != handle.providerId) return false
        if (operationId != null && operationId != handle.operationId) return false
        if (correlationKey != null && correlationKey != handle.correlationKey) return false
        return operationId != null || correlationKey != null
    }
}

enum class AsyncOperationEventStatus {
    SUCCEEDED,
    FAILED,
    CANCELLED,
}
