package psyke.agent.project

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Periodically checks registered wait conditions and fires callbacks when
 * conditions are satisfied or timed out.
 */
class WaitConditionMonitor(
    private val checkIntervalMs: Long,
    private val onConditionSatisfied: (String, String) -> Unit,
    private val onConditionTimedOut: (String, String) -> Unit,
) {
    data class Registration(
        val projectId: String,
        val stepId: String,
        val condition: WaitCondition,
    )

    private val registrations = mutableListOf<Registration>()
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
        synchronized(registrations) {
            registrations.add(Registration(projectId, stepId, condition))
        }
        logger.debug { "Wait condition registered: project=$projectId, step=$stepId, type=${condition.type}" }
    }

    fun unregister(projectId: String, stepId: String) {
        synchronized(registrations) {
            registrations.removeAll { it.projectId == projectId && it.stepId == stepId }
        }
    }

    private fun checkConditions() {
        val now = Instant.now()
        val snapshot = synchronized(registrations) { registrations.toList() }
        for (reg in snapshot) {
            val timedOut = reg.condition.timeoutAt?.let { now.isAfter(it) } ?: false
            if (timedOut) {
                logger.debug { "Wait condition timed out: project=${reg.projectId}, step=${reg.stepId}" }
                synchronized(registrations) { registrations.remove(reg) }
                onConditionTimedOut(reg.projectId, reg.stepId)
            }
        }
    }
}
