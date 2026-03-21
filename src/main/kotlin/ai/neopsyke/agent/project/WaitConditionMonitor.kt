package ai.neopsyke.agent.project

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mu.KotlinLogging
import ai.neopsyke.agent.actions.async.AsyncActionHandle
import ai.neopsyke.agent.actions.async.AsyncOperationEvent
import ai.neopsyke.agent.actions.async.AsyncOperationEventStatus
import ai.neopsyke.agent.actions.async.AsyncOperationRegistry
import ai.neopsyke.agent.actions.async.AsyncOperationStatus
import ai.neopsyke.agent.actions.async.AsyncResumeMode
import ai.neopsyke.agent.actions.async.AsyncWaitAggregation
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Periodically checks registered wait conditions and fires callbacks when
 * conditions are satisfied or timed out.
 *
 * Generic async-operation waits are restored from persisted project state and
 * resumed through provider adapters or externally-delivered completion events.
 */
class WaitConditionMonitor(
    private val checkIntervalMs: Long,
    private val asyncOperationRegistry: AsyncOperationRegistry,
    private val onConditionSatisfied: (String, String, WaitConditionResolution) -> Unit,
    private val onConditionTimedOut: (String, String) -> Unit,
) {
    data class Registration(
        val projectId: String,
        val stepId: String,
        val condition: WaitCondition,
    )

    private data class RegistrationKey(
        val projectId: String,
        val stepId: String,
    )

    private data class TerminalHandleState(
        val status: AsyncOperationEventStatus,
        val summary: String,
    )

    private data class RegistrationState(
        val registration: Registration,
        val nextPollAtMs: ConcurrentHashMap<String, Long> = ConcurrentHashMap(),
        val terminalHandleStates: ConcurrentHashMap<String, TerminalHandleState> = ConcurrentHashMap(),
    )

    private val registrations = ConcurrentHashMap<RegistrationKey, RegistrationState>()
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job = scope.launch {
            while (isActive) {
                checkConditions()
                delay(checkIntervalMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun register(projectId: String, stepId: String, condition: WaitCondition) {
        registrations[RegistrationKey(projectId, stepId)] = RegistrationState(
            registration = Registration(projectId, stepId, condition)
        )
        logger.debug { "Wait condition registered: project=$projectId, step=$stepId, type=${condition.type}" }
    }

    fun unregister(projectId: String, stepId: String) {
        registrations.remove(RegistrationKey(projectId, stepId))
    }

    fun notifyAsyncEvent(event: AsyncOperationEvent): Int {
        var matched = 0
        registrations.forEach { (key, state) ->
            val wait = state.registration.condition.asyncWait ?: return@forEach
            val matchingHandles = wait.handles.filter { event.matches(it) }
            if (matchingHandles.isEmpty()) return@forEach
            matchingHandles.forEach { handle ->
                state.terminalHandleStates[handle.key] = TerminalHandleState(event.status, event.summary)
            }
            matched += matchingHandles.size
            resolveAsyncWaitIfReady(key, state, wait)
        }
        return matched
    }

    private suspend fun checkConditions() {
        val now = Instant.now()
        val snapshot = registrations.entries.toList()
        for ((key, state) in snapshot) {
            val condition = state.registration.condition
            val timedOut = condition.timeoutAt?.let { now.isAfter(it) } ?: false
            if (timedOut) {
                logger.debug { "Wait condition timed out: project=${key.projectId}, step=${key.stepId}" }
                registrations.remove(key)
                onConditionTimedOut(key.projectId, key.stepId)
                continue
            }
            if (condition.type == WaitConditionType.ASYNC_OPERATION) {
                pollAsyncWaitIfDue(key, state, now.toEpochMilli())
            }
        }
    }

    private suspend fun pollAsyncWaitIfDue(
        key: RegistrationKey,
        state: RegistrationState,
        nowMs: Long,
    ) {
        val wait = state.registration.condition.asyncWait ?: return
        wait.handles
            .filter { it.resumeMode == AsyncResumeMode.POLL || it.resumeMode == AsyncResumeMode.HYBRID }
            .forEach { handle ->
                if (!shouldPoll(handle, state, nowMs)) return@forEach
                val provider = asyncOperationRegistry.provider(handle.providerType) ?: return@forEach
                when (val status = provider.poll(handle)) {
                    is AsyncOperationStatus.Pending -> {
                        val nextPollMs = nowMs + (status.nextPollAfterMs ?: handle.pollAfterMs ?: checkIntervalMs)
                        state.nextPollAtMs[handle.key] = nextPollMs
                    }

                    is AsyncOperationStatus.Succeeded -> {
                        state.terminalHandleStates[handle.key] =
                            TerminalHandleState(AsyncOperationEventStatus.SUCCEEDED, status.summary)
                    }

                    is AsyncOperationStatus.Failed -> {
                        state.terminalHandleStates[handle.key] =
                            TerminalHandleState(AsyncOperationEventStatus.FAILED, status.reason)
                    }

                    is AsyncOperationStatus.Cancelled -> {
                        state.terminalHandleStates[handle.key] =
                            TerminalHandleState(AsyncOperationEventStatus.CANCELLED, status.reason)
                    }
                }
            }
        resolveAsyncWaitIfReady(key, state, wait)
    }

    private fun shouldPoll(
        handle: AsyncActionHandle,
        state: RegistrationState,
        nowMs: Long,
    ): Boolean {
        if (state.terminalHandleStates.containsKey(handle.key)) return false
        val nextPollAtMs = state.nextPollAtMs[handle.key] ?: 0L
        return nowMs >= nextPollAtMs
    }

    private fun resolveAsyncWaitIfReady(
        key: RegistrationKey,
        state: RegistrationState,
        wait: ai.neopsyke.agent.actions.async.AsyncActionWait,
    ) {
        val resolution = resolveAsyncWait(wait, state.terminalHandleStates)
        if (resolution == null) return
        if (!registrations.remove(key, state)) return
        onConditionSatisfied(key.projectId, key.stepId, resolution)
    }

    private fun resolveAsyncWait(
        wait: ai.neopsyke.agent.actions.async.AsyncActionWait,
        terminalHandleStates: Map<String, TerminalHandleState>,
    ): WaitConditionResolution? {
        val total = wait.handles.size
        val successCount = terminalHandleStates.values.count { it.status == AsyncOperationEventStatus.SUCCEEDED }
        val failedCount = terminalHandleStates.values.count { it.status == AsyncOperationEventStatus.FAILED }
        val cancelledCount = terminalHandleStates.values.count { it.status == AsyncOperationEventStatus.CANCELLED }
        val terminalCount = terminalHandleStates.size
        val pendingCount = total - terminalCount
        val summary = terminalHandleStates.values
            .map { it.summary.trim() }
            .filter { it.isNotBlank() }
            .joinToString(" | ")

        val status = when (wait.aggregation) {
            AsyncWaitAggregation.ALL -> when {
                failedCount > 0 -> "failed"
                cancelledCount > 0 -> "cancelled"
                successCount == total -> "succeeded"
                else -> null
            }

            AsyncWaitAggregation.ANY -> when {
                successCount > 0 -> "succeeded"
                pendingCount == 0 && failedCount > 0 -> "failed"
                pendingCount == 0 && cancelledCount > 0 -> "cancelled"
                else -> null
            }

            AsyncWaitAggregation.AT_LEAST_N -> {
                val target = wait.minimumSuccessCount ?: total
                when {
                    successCount >= target -> "succeeded"
                    successCount + pendingCount < target -> "failed"
                    else -> null
                }
            }
        }

        return status?.let {
            WaitConditionResolution(
                conditionType = "async_operation",
                summary = if (summary.isNotBlank()) summary else wait.summary,
                status = it,
            )
        }
    }
}
