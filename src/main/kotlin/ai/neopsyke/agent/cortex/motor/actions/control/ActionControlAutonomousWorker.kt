package ai.neopsyke.agent.cortex.motor.actions.control

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mu.KotlinLogging
import ai.neopsyke.agent.config.ActionControlConfig
import ai.neopsyke.instrumentation.AgentEvent
import ai.neopsyke.instrumentation.AgentInstrumentation
import ai.neopsyke.instrumentation.NoopAgentInstrumentation

private val logger = KotlinLogging.logger {}

class ActionControlAutonomousWorker(
    scope: CoroutineScope,
    private val config: ActionControlConfig,
    private val processBatch: suspend (Int) -> Int,
    private val instrumentation: AgentInstrumentation = NoopAgentInstrumentation,
) : AutoCloseable {
    private val job: Job? = if (config.autonomousWorkerEnabled) {
        scope.launch {
            logger.info { "Action-control autonomous worker started." }
            instrumentation.emit(
                AgentEvent(
                    type = "action_control_worker_started",
                    data = mapOf(
                        "poll_ms" to config.autonomousWorkerPollMs,
                        "batch_size" to config.autonomousWorkerBatchSize,
                    )
                )
            )
            while (isActive) {
                try {
                    val processed = processBatch(config.autonomousWorkerBatchSize)
                    if (processed > 0) {
                        instrumentation.emit(
                            AgentEvent(
                                type = "action_control_worker_tick",
                                data = mapOf("processed" to processed)
                            )
                        )
                    }
                } catch (ex: Exception) {
                    logger.warn(ex) { "Action-control autonomous worker tick failed." }
                    instrumentation.emit(
                        AgentEvent(
                            type = "action_control_worker_error",
                            data = mapOf("message" to (ex.message ?: "unknown error"))
                        )
                    )
                }
                delay(config.autonomousWorkerPollMs.coerceAtLeast(MIN_POLL_MS))
            }
        }
    } else {
        null
    }

    override fun close() {
        job?.cancel()
    }

    private companion object {
        const val MIN_POLL_MS: Long = 50L
    }
}
