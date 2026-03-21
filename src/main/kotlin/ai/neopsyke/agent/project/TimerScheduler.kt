package ai.neopsyke.agent.project

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap

private val logger = KotlinLogging.logger {}

/**
 * Coroutine-based timer that fires [GoalRuntimeCue.ScheduledWakeUp] signals
 * at registered wake-up times.
 *
 * Supports both one-shot timers ([register]) and recurring cron-based schedules
 * ([registerCron]).
 */
class TimerScheduler(
    private val resolutionMs: Long,
    private val onWakeUp: (String, Long) -> Unit,
) {
    private val timers = ConcurrentSkipListMap<Long, MutableSet<String>>()

    /**
     * M1: Cron registrations — projectId → cronExpression.
     * Stored separately so we can re-register the next occurrence after each fire.
     */
    private val cronSchedules = ConcurrentHashMap<String, String>()

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

    /** Register a one-shot timer. */
    fun register(projectId: String, wakeAt: Instant) {
        val ms = wakeAt.toEpochMilli()
        timers.getOrPut(ms) { mutableSetOf() }.add(projectId)
        logger.debug { "Timer registered: project=$projectId, wakeAt=$wakeAt" }
    }

    /**
     * M1: Register a recurring cron schedule.
     *
     * The cron expression is evaluated in the system's default timezone.
     * On each fire the next occurrence is automatically scheduled.
     *
     * **Limitations of this minimal implementation:**
     * - Supports 5-field cron only: `minute hour dayOfMonth month dayOfWeek`
     * - Field syntax: numeric literals, `*` (any), `*‌/N` (every N), `N-M` (range, dayOfWeek only)
     * - Does NOT support: named months/days (JAN, MON), comma-separated lists (1,3,5),
     *   `L`/`W`/`#` special characters, `@yearly`/`@monthly` etc. shortcuts,
     *   second-precision (6-field) cron, or timezone-per-expression.
     * - Minimum resolution is [resolutionMs] — cron expressions finer than that are imprecise.
     * - No DST awareness: times in the "lost hour" may skip or double-fire.
     *
     * @param projectId the project to wake up
     * @param expression a standard 5-field cron string, e.g. `"0 9 * * *"` for daily at 09:00
     */
    fun registerCron(projectId: String, expression: String) {
        val next = CronParser.nextAfter(expression, ZonedDateTime.now())
        if (next == null) {
            logger.warn { "Could not compute next cron time for project=$projectId expr='$expression'" }
            return
        }
        cronSchedules[projectId] = expression
        val ms = next.toInstant().toEpochMilli()
        timers.getOrPut(ms) { mutableSetOf() }.add(projectId)
        logger.debug { "Cron registered: project=$projectId, expr='$expression', next=$next" }
    }

    fun cancel(projectId: String) {
        cronSchedules.remove(projectId)
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
            // M1: Re-register next occurrence for cron schedules
            cronSchedules[projectId]?.let { expr ->
                val next = CronParser.nextAfter(expr, ZonedDateTime.now())
                if (next != null) {
                    val nextMs = next.toInstant().toEpochMilli()
                    timers.getOrPut(nextMs) { mutableSetOf() }.add(projectId)
                    logger.debug { "Cron rescheduled: project=$projectId, next=$next" }
                } else {
                    logger.warn { "Could not reschedule cron for project=$projectId — removing" }
                    cronSchedules.remove(projectId)
                }
            }
        }
    }
}

/**
 * Minimal 5-field cron expression parser.
 *
 * Computes the next [ZonedDateTime] at or after [from] that matches the expression.
 * Evaluated in [from]'s timezone.
 *
 * Limitations are documented on [TimerScheduler.registerCron].
 */
internal object CronParser {

    /**
     * Returns the next [ZonedDateTime] matching [expression] that is strictly after [from],
     * or null if the expression is invalid or no match is found within 4 years.
     */
    fun nextAfter(expression: String, from: ZonedDateTime): ZonedDateTime? {
        val parts = expression.trim().split(Regex("\\s+"))
        if (parts.size != 5) {
            logger.warn { "Invalid cron expression (expected 5 fields): '$expression'" }
            return null
        }
        val (minuteExpr, hourExpr, domExpr, monthExpr, dowExpr) = parts

        // Start searching from the next minute
        var candidate = from.withSecond(0).withNano(0).plusMinutes(1)

        // Safety: give up after 4 years of iteration (avoids infinite loop on invalid exprs)
        val limit = from.plusYears(4)

        while (candidate.isBefore(limit)) {
            if (!matchField(monthExpr, candidate.monthValue, 1, 12)) {
                // Advance to the first day of the next matching month
                candidate = candidate.withDayOfMonth(1).withHour(0).withMinute(0).plusMonths(1)
                continue
            }
            if (!matchField(domExpr, candidate.dayOfMonth, 1, 31)) {
                candidate = candidate.withHour(0).withMinute(0).plusDays(1)
                continue
            }
            // dayOfWeek: cron uses 0=Sun..6=Sat; Java uses 1=Mon..7=Sun
            val cronDow = (candidate.dayOfWeek.value % 7) // Mon=1..Sat=6, Sun=0
            if (!matchField(dowExpr, cronDow, 0, 6)) {
                candidate = candidate.withHour(0).withMinute(0).plusDays(1)
                continue
            }
            if (!matchField(hourExpr, candidate.hour, 0, 23)) {
                candidate = candidate.withMinute(0).plusHours(1)
                continue
            }
            if (!matchField(minuteExpr, candidate.minute, 0, 59)) {
                candidate = candidate.plusMinutes(1)
                continue
            }
            return candidate
        }
        return null
    }

    /**
     * Returns true if [value] matches the cron [field] expression.
     * Supported syntax: `*`, numeric literal, `*‌/N` (step), `N-M` (range).
     */
    internal fun matchField(field: String, value: Int, min: Int, max: Int): Boolean {
        if (field == "*") return true
        // Step: */N
        if (field.startsWith("*/")) {
            val step = field.removePrefix("*/").toIntOrNull() ?: return false
            return (value - min) % step == 0
        }
        // Range: N-M
        if (field.contains('-')) {
            val (lo, hi) = field.split('-', limit = 2)
            val loVal = lo.toIntOrNull() ?: return false
            val hiVal = hi.toIntOrNull() ?: return false
            return value in loVal..hiVal
        }
        // Literal
        return field.toIntOrNull() == value
    }
}
