package psyke.agent.project

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.time.Instant
import java.util.concurrent.ConcurrentSkipListMap

private val logger = KotlinLogging.logger {}

/**
 * Coroutine-based timer that fires [ProjectSignal.ScheduledWakeUp] signals
 * at registered wake-up times.
 */
class TimerScheduler(
    private val resolutionMs: Long,
    private val onWakeUp: (String, Long) -> Unit,
) {
    private val timers = ConcurrentSkipListMap<Long, MutableSet<String>>()
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job = scope.launch {
            while (isActive) {
                checkTimers()
                delay(resolutionMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun register(projectId: String, wakeAt: Instant) {
        val ms = wakeAt.toEpochMilli()
        timers.getOrPut(ms) { mutableSetOf() }.add(projectId)
        logger.debug { "Timer registered: project=$projectId, wakeAt=$wakeAt" }
    }

    fun cancel(projectId: String) {
        timers.values.forEach { it.remove(projectId) }
        timers.entries.removeIf { it.value.isEmpty() }
    }

    private fun checkTimers() {
        val now = System.currentTimeMillis()
        val firedEntries = timers.headMap(now, true)
        val fired = firedEntries.flatMap { (ms, ids) -> ids.map { it to ms } }
        firedEntries.clear()
        for ((projectId, scheduledAtMs) in fired) {
            logger.debug { "Timer fired: project=$projectId" }
            onWakeUp(projectId, scheduledAtMs)
        }
    }
}
