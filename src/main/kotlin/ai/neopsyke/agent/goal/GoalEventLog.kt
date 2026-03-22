package ai.neopsyke.agent.goal

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

private val logger = KotlinLogging.logger {}

/**
 * Append-only JSONL event log for a single goal.
 *
 * Each line is a JSON object with `"type"` discriminator + event fields.
 * Thread-safety: single-writer assumed (GoalManager serializes writes).
 */
class GoalEventLog(private val path: Path) {

    private val mapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    fun append(event: GoalEvent) {
        val wrapper = EventWrapper.from(event)
        val json = mapper.writeValueAsString(wrapper)
        Files.createDirectories(path.parent)
        Files.newOutputStream(
            path,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
            StandardOpenOption.SYNC,
        ).bufferedWriter().use { writer ->
            writer.append(json)
            writer.append('\n')
            writer.flush()
        }
    }

    fun readAll(): List<GoalEvent> {
        if (!Files.exists(path)) return emptyList()
        return Files.readAllLines(path)
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                try {
                    val wrapper = mapper.readValue<EventWrapper>(line)
                    wrapper.toEvent()
                } catch (e: Exception) {
                    logger.warn { "Skipping malformed event line: ${e.message}" }
                    null
                }
            }
    }

    fun readFrom(sequenceNumber: Int): List<GoalEvent> =
        readAll().drop(sequenceNumber)
}

/**
 * JSON-serializable envelope for [GoalEvent].
 * Uses a flat `type` discriminator + nullable fields for each event variant.
 * This avoids Jackson polymorphic type info while keeping the JSONL human-readable.
 */
internal data class EventWrapper(
    val type: String = "",
    val goalId: String = "",
    val timestamp: String = "",
    // Created
    val title: String? = null,
    val instruction: String? = null,
    val priority: String? = null,
    val completionCriteria: String? = null,
    // PlanGenerated / PlanRevised
    val plan: GoalPlan? = null,
    val reason: String? = null,
    // Step events
    val stepId: String? = null,
    val actionResult: String? = null,
    // Wait conditions
    val waitCondition: WaitCondition? = null,
    val conditionType: String? = null,
    val resolutionSummary: String? = null,
    val resolutionStatus: String? = null,
    // Suspended
    val resumeAt: String? = null,
    // ContextUpdated
    val tier: Int? = null,
    val summary: String? = null,
    // WorkCycleCompleted
    val actionsExecuted: Int? = null,
) {
    fun toEvent(): GoalEvent {
        val ts = java.time.Instant.parse(timestamp)
        return when (type) {
            "Created" -> GoalEvent.Created(
                goalId, title ?: "", instruction ?: "",
                GoalPriority.valueOf(priority ?: "MEDIUM"),
                completionCriteria ?: "", ts
            )
            "PlanGenerated" -> GoalEvent.PlanGenerated(goalId, plan!!, ts)
            "PlanRevised" -> GoalEvent.PlanRevised(goalId, plan!!, reason ?: "", ts)
            "StepStarted" -> GoalEvent.StepStarted(goalId, stepId!!, ts)
            "StepActionExecuted" -> GoalEvent.StepActionExecuted(goalId, stepId!!, actionResult ?: "", ts)
            "StepAcceptancePassed" -> GoalEvent.StepAcceptancePassed(goalId, stepId!!, ts)
            "StepAcceptanceFailed" -> GoalEvent.StepAcceptanceFailed(goalId, stepId!!, reason ?: "", ts)
            "StepBlocked" -> GoalEvent.StepBlocked(goalId, stepId!!, waitCondition!!, ts)
            "StepUnblocked" -> GoalEvent.StepUnblocked(goalId, stepId!!, ts)
            "StepSkipped" -> GoalEvent.StepSkipped(goalId, stepId!!, reason ?: "", ts)
            "WaitConditionRegistered" -> GoalEvent.WaitConditionRegistered(goalId, stepId!!, waitCondition!!, ts)
            "WaitConditionSatisfied" -> GoalEvent.WaitConditionSatisfied(
                goalId = goalId,
                stepId = stepId!!,
                conditionType = conditionType ?: "",
                resolutionSummary = resolutionSummary,
                resolutionStatus = resolutionStatus,
                timestamp = ts,
            )
            "WaitConditionTimedOut" -> GoalEvent.WaitConditionTimedOut(goalId, stepId!!, ts)
            "Suspended" -> GoalEvent.Suspended(goalId, reason ?: "", resumeAt?.let { java.time.Instant.parse(it) }, ts)
            "Resumed" -> GoalEvent.Resumed(goalId, ts)
            "CronCycleStarted" -> GoalEvent.CronCycleStarted(goalId, ts)
            "Completed" -> GoalEvent.Completed(goalId, ts)
            "PriorityChanged" -> GoalEvent.PriorityChanged(
                goalId = goalId,
                priority = GoalPriority.valueOf(priority ?: "MEDIUM"),
                timestamp = ts,
            )
            "Failed" -> GoalEvent.Failed(goalId, reason ?: "", ts)
            "ContextUpdated" -> GoalEvent.ContextUpdated(goalId, tier ?: 1, summary ?: "", ts)
            "WorkCycleCompleted" -> GoalEvent.WorkCycleCompleted(goalId, stepId ?: "", actionsExecuted ?: 0, ts)
            else -> error("Unknown event type: $type")
        }
    }

    companion object {
        fun from(event: GoalEvent): EventWrapper {
            val base = EventWrapper(
                type = event::class.simpleName ?: "Unknown",
                goalId = event.goalId,
                timestamp = event.timestamp.toString(),
            )
            return when (event) {
                is GoalEvent.Created -> base.copy(
                    title = event.title,
                    instruction = event.instruction,
                    priority = event.priority.name,
                    completionCriteria = event.completionCriteria,
                )
                is GoalEvent.PlanGenerated -> base.copy(plan = event.plan)
                is GoalEvent.PlanRevised -> base.copy(plan = event.plan, reason = event.reason)
                is GoalEvent.StepStarted -> base.copy(stepId = event.stepId)
                is GoalEvent.StepActionExecuted -> base.copy(stepId = event.stepId, actionResult = event.actionResult)
                is GoalEvent.StepAcceptancePassed -> base.copy(stepId = event.stepId)
                is GoalEvent.StepAcceptanceFailed -> base.copy(stepId = event.stepId, reason = event.reason)
                is GoalEvent.StepBlocked -> base.copy(stepId = event.stepId, waitCondition = event.waitCondition)
                is GoalEvent.StepUnblocked -> base.copy(stepId = event.stepId)
                is GoalEvent.StepSkipped -> base.copy(stepId = event.stepId, reason = event.reason)
                is GoalEvent.WaitConditionRegistered -> base.copy(stepId = event.stepId, waitCondition = event.condition)
                is GoalEvent.WaitConditionSatisfied -> base.copy(
                    stepId = event.stepId,
                    conditionType = event.conditionType,
                    resolutionSummary = event.resolutionSummary,
                    resolutionStatus = event.resolutionStatus,
                )
                is GoalEvent.WaitConditionTimedOut -> base.copy(stepId = event.stepId)
                is GoalEvent.Suspended -> base.copy(reason = event.reason, resumeAt = event.resumeAt?.toString())
                is GoalEvent.Resumed -> base
                is GoalEvent.CronCycleStarted -> base
                is GoalEvent.Completed -> base
                is GoalEvent.PriorityChanged -> base.copy(priority = event.priority.name)
                is GoalEvent.Failed -> base.copy(reason = event.reason)
                is GoalEvent.ContextUpdated -> base.copy(tier = event.tier, summary = event.summary)
                is GoalEvent.WorkCycleCompleted -> base.copy(stepId = event.stepId, actionsExecuted = event.actionsExecuted)
            }
        }
    }
}
