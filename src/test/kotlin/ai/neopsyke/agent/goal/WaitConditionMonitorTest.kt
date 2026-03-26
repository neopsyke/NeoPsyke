package ai.neopsyke.agent.goal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import ai.neopsyke.agent.cortex.motor.actions.async.AsyncActionHandle
import ai.neopsyke.agent.cortex.motor.actions.async.AsyncActionWait
import ai.neopsyke.agent.cortex.motor.actions.async.AsyncOperationEvent
import ai.neopsyke.agent.cortex.motor.actions.async.AsyncOperationEventStatus
import ai.neopsyke.agent.cortex.motor.actions.async.AsyncOperationProvider
import ai.neopsyke.agent.cortex.motor.actions.async.AsyncOperationRegistry
import ai.neopsyke.agent.cortex.motor.actions.async.AsyncOperationStatus
import ai.neopsyke.agent.cortex.motor.actions.async.AsyncResumeMode
import ai.neopsyke.agent.cortex.motor.actions.async.AsyncWaitAggregation
import java.time.Instant
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class WaitConditionMonitorTest {

    @Test
    fun `poll wait with ALL aggregation resolves only after every handle succeeds`() = runBlocking {
        val provider = StubAsyncOperationProvider().apply {
            enqueue("op-1", listOf(AsyncOperationStatus.Succeeded("download 1 complete")))
            enqueue(
                "op-2",
                listOf(
                    AsyncOperationStatus.Pending("download 2 running", nextPollAfterMs = 15),
                    AsyncOperationStatus.Succeeded("download 2 complete"),
                )
            )
        }
        val satisfied = CopyOnWriteArrayList<Triple<String, String, WaitConditionResolution>>()
        val monitor = WaitConditionMonitor(
            checkIntervalMs = 10,
            asyncOperationRegistry = AsyncOperationRegistry.fromProviders(listOf(provider)),
            onConditionSatisfied = { goalId, stepId, resolution ->
                satisfied += Triple(goalId, stepId, resolution)
            },
            onConditionTimedOut = { _, _ -> fail("did not expect timeout") },
        )
        monitor.start(testScope())
        try {
            monitor.register(
                goalId = "proj-1",
                stepId = "step-1",
                condition = asyncCondition(
                    AsyncActionWait(
                        handles = listOf(
                            pollHandle("op-1"),
                            pollHandle("op-2"),
                        ),
                        aggregation = AsyncWaitAggregation.ALL,
                        summary = "wait for both downloads",
                    )
                )
            )

            waitUntil { satisfied.isNotEmpty() }

            val (_, _, resolution) = satisfied.single()
            assertEquals("async_operation", resolution.conditionType)
            assertEquals("succeeded", resolution.status)
            assertTrue(resolution.summary.contains("download 1 complete"))
            assertTrue(resolution.summary.contains("download 2 complete"))
        } finally {
            monitor.stop()
        }
    }

    @Test
    fun `event wait with ANY aggregation resolves after first successful matching event`() = runBlocking {
        val satisfied = CopyOnWriteArrayList<Triple<String, String, WaitConditionResolution>>()
        val monitor = WaitConditionMonitor(
            checkIntervalMs = 10,
            asyncOperationRegistry = AsyncOperationRegistry.empty(),
            onConditionSatisfied = { goalId, stepId, resolution ->
                satisfied += Triple(goalId, stepId, resolution)
            },
            onConditionTimedOut = { _, _ -> fail("did not expect timeout") },
        )
        monitor.start(testScope())
        try {
            monitor.register(
                goalId = "proj-any",
                stepId = "step-any",
                condition = asyncCondition(
                    AsyncActionWait(
                        handles = listOf(
                            eventHandle("op-a", correlationKey = "corr-a"),
                            eventHandle("op-b", correlationKey = "corr-b"),
                        ),
                        aggregation = AsyncWaitAggregation.ANY,
                        summary = "wait for any completion",
                    )
                )
            )

            val matched = monitor.notifyAsyncEvent(
                AsyncOperationEvent(
                    providerType = "test_async",
                    correlationKey = "corr-b",
                    status = AsyncOperationEventStatus.SUCCEEDED,
                    summary = "mirror download finished",
                )
            )

            waitUntil { satisfied.isNotEmpty() }

            assertEquals(1, matched)
            val (_, _, resolution) = satisfied.single()
            assertEquals("succeeded", resolution.status)
            assertEquals("mirror download finished", resolution.summary)
        } finally {
            monitor.stop()
        }
    }

    @Test
    fun `poll wait with AT_LEAST_N fails when target becomes unreachable`() = runBlocking {
        val provider = StubAsyncOperationProvider().apply {
            enqueue("op-1", listOf(AsyncOperationStatus.Succeeded("download 1 complete")))
            enqueue("op-2", listOf(AsyncOperationStatus.Failed("download 2 failed")))
            enqueue("op-3", listOf(AsyncOperationStatus.Failed("download 3 failed")))
        }
        val satisfied = CopyOnWriteArrayList<Triple<String, String, WaitConditionResolution>>()
        val monitor = WaitConditionMonitor(
            checkIntervalMs = 10,
            asyncOperationRegistry = AsyncOperationRegistry.fromProviders(listOf(provider)),
            onConditionSatisfied = { goalId, stepId, resolution ->
                satisfied += Triple(goalId, stepId, resolution)
            },
            onConditionTimedOut = { _, _ -> fail("did not expect timeout") },
        )
        monitor.start(testScope())
        try {
            monitor.register(
                goalId = "proj-threshold",
                stepId = "step-threshold",
                condition = asyncCondition(
                    AsyncActionWait(
                        handles = listOf(
                            pollHandle("op-1"),
                            pollHandle("op-2"),
                            pollHandle("op-3"),
                        ),
                        aggregation = AsyncWaitAggregation.AT_LEAST_N,
                        minimumSuccessCount = 2,
                        summary = "need two downloads",
                    )
                )
            )

            waitUntil { satisfied.isNotEmpty() }

            val (_, _, resolution) = satisfied.single()
            assertEquals("failed", resolution.status)
            assertTrue(resolution.summary.contains("download 2 failed"))
            assertTrue(resolution.summary.contains("download 3 failed"))
        } finally {
            monitor.stop()
        }
    }

    @Test
    fun `external event matches by correlation key when operation id is not provided`() = runBlocking {
        val satisfied = CopyOnWriteArrayList<Triple<String, String, WaitConditionResolution>>()
        val monitor = WaitConditionMonitor(
            checkIntervalMs = 10,
            asyncOperationRegistry = AsyncOperationRegistry.empty(),
            onConditionSatisfied = { goalId, stepId, resolution ->
                satisfied += Triple(goalId, stepId, resolution)
            },
            onConditionTimedOut = { _, _ -> fail("did not expect timeout") },
        )
        monitor.start(testScope())
        try {
            monitor.register(
                goalId = "proj-corr",
                stepId = "step-corr",
                condition = asyncCondition(
                    AsyncActionWait(
                        handles = listOf(eventHandle("op-corr", correlationKey = "corr-42")),
                        summary = "wait on callback",
                    )
                )
            )

            val matched = monitor.notifyAsyncEvent(
                AsyncOperationEvent(
                    providerType = "test_async",
                    correlationKey = "corr-42",
                    status = AsyncOperationEventStatus.SUCCEEDED,
                    summary = "provider callback delivered",
                )
            )

            waitUntil { satisfied.isNotEmpty() }

            assertEquals(1, matched)
            val (_, _, resolution) = satisfied.single()
            assertEquals("succeeded", resolution.status)
            assertEquals("provider callback delivered", resolution.summary)
        } finally {
            monitor.stop()
        }
    }

    private fun asyncCondition(asyncWait: AsyncActionWait): WaitCondition =
        WaitCondition(
            type = WaitConditionType.ASYNC_OPERATION,
            params = emptyMap(),
            registeredAt = Instant.now(),
            timeoutAt = Instant.now().plusSeconds(5),
            asyncWait = asyncWait,
        )

    private fun pollHandle(operationId: String): AsyncActionHandle =
        AsyncActionHandle(
            providerType = "test_async",
            providerId = "provider-1",
            operationId = operationId,
            resumeMode = AsyncResumeMode.POLL,
            pollAfterMs = 10,
        )

    private fun eventHandle(operationId: String, correlationKey: String): AsyncActionHandle =
        AsyncActionHandle(
            providerType = "test_async",
            providerId = "provider-1",
            operationId = operationId,
            correlationKey = correlationKey,
            resumeMode = AsyncResumeMode.EVENT,
        )

    private fun testScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private suspend fun waitUntil(predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            delay(10)
        }
        fail("condition not satisfied before timeout")
    }

    private class StubAsyncOperationProvider : AsyncOperationProvider {
        override val providerType: String = "test_async"
        private val statusesByOperation = mutableMapOf<String, ArrayDeque<AsyncOperationStatus>>()

        fun enqueue(operationId: String, statuses: List<AsyncOperationStatus>) {
            statusesByOperation[operationId] = ArrayDeque(statuses)
        }

        override suspend fun poll(handle: AsyncActionHandle): AsyncOperationStatus {
            val queue = statusesByOperation[handle.operationId]
                ?: return AsyncOperationStatus.Pending("still pending", nextPollAfterMs = 10)
            return if (queue.size > 1) {
                queue.removeFirst()
            } else {
                queue.first()
            }
        }
    }
}
