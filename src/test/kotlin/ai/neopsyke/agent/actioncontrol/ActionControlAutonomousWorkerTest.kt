package ai.neopsyke.agent.actioncontrol

import ai.neopsyke.agent.config.ActionControlConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue

class ActionControlAutonomousWorkerTest {

    @Test
    fun `worker polls autonomous staged batch processor`() = runBlocking {
        val processed = CompletableDeferred<Int>()
        val job = SupervisorJob()
        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Default + job)
        val worker = ActionControlAutonomousWorker(
            scope = scope,
            config = ActionControlConfig(
                autonomousWorkerEnabled = true,
                autonomousWorkerPollMs = 50L,
                autonomousWorkerBatchSize = 3,
            ),
            processBatch = { limit ->
                if (!processed.isCompleted) {
                    processed.complete(limit)
                }
                1
            }
        )

        try {
            val batchSize = withTimeout(2_000L) { processed.await() }
            assertTrue(batchSize == 3)
        } finally {
            worker.close()
            job.cancelAndJoin()
        }
    }
}
