package ai.neopsyke.agent.project

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
 * Append-only JSONL event log for a single project.
 *
 * Each line is a JSON object with `"type"` discriminator + event fields.
 * Thread-safety: single-writer assumed (ProjectManager serializes writes).
 */
class ProjectEventLog(private val path: Path) {

    private val mapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    fun append(event: ProjectEvent) {
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

    fun readAll(): List<ProjectEvent> {
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

    fun readFrom(sequenceNumber: Int): List<ProjectEvent> =
        readAll().drop(sequenceNumber)
}

/**
 * JSON-serializable envelope for [ProjectEvent].
 * Uses a flat `type` discriminator + nullable fields for each event variant.
 * This avoids Jackson polymorphic type info while keeping the JSONL human-readable.
 */
internal data class EventWrapper(
    val type: String = "",
    val projectId: String = "",
    val timestamp: String = "",
    // Created
    val title: String? = null,
    val instruction: String? = null,
    val priority: String? = null,
    val completionCriteria: String? = null,
    // PlanGenerated / PlanRevised
    val plan: ProjectPlan? = null,
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
    fun toEvent(): ProjectEvent {
        val ts = java.time.Instant.parse(timestamp)
        return when (type) {
            "Created" -> ProjectEvent.Created(
                projectId, title ?: "", instruction ?: "",
                ProjectPriority.valueOf(priority ?: "MEDIUM"),
                completionCriteria ?: "", ts
            )
            "PlanGenerated" -> ProjectEvent.PlanGenerated(projectId, plan!!, ts)
            "PlanRevised" -> ProjectEvent.PlanRevised(projectId, plan!!, reason ?: "", ts)
            "StepStarted" -> ProjectEvent.StepStarted(projectId, stepId!!, ts)
            "StepActionExecuted" -> ProjectEvent.StepActionExecuted(projectId, stepId!!, actionResult ?: "", ts)
            "StepAcceptancePassed" -> ProjectEvent.StepAcceptancePassed(projectId, stepId!!, ts)
            "StepAcceptanceFailed" -> ProjectEvent.StepAcceptanceFailed(projectId, stepId!!, reason ?: "", ts)
            "StepBlocked" -> ProjectEvent.StepBlocked(projectId, stepId!!, waitCondition!!, ts)
            "StepUnblocked" -> ProjectEvent.StepUnblocked(projectId, stepId!!, ts)
            "StepSkipped" -> ProjectEvent.StepSkipped(projectId, stepId!!, reason ?: "", ts)
            "WaitConditionRegistered" -> ProjectEvent.WaitConditionRegistered(projectId, stepId!!, waitCondition!!, ts)
            "WaitConditionSatisfied" -> ProjectEvent.WaitConditionSatisfied(
                projectId = projectId,
                stepId = stepId!!,
                conditionType = conditionType ?: "",
                resolutionSummary = resolutionSummary,
                resolutionStatus = resolutionStatus,
                timestamp = ts,
            )
            "WaitConditionTimedOut" -> ProjectEvent.WaitConditionTimedOut(projectId, stepId!!, ts)
            "Suspended" -> ProjectEvent.Suspended(projectId, reason ?: "", resumeAt?.let { java.time.Instant.parse(it) }, ts)
            "Resumed" -> ProjectEvent.Resumed(projectId, ts)
            "Completed" -> ProjectEvent.Completed(projectId, ts)
            "PriorityChanged" -> ProjectEvent.PriorityChanged(
                projectId = projectId,
                priority = ProjectPriority.valueOf(priority ?: "MEDIUM"),
                timestamp = ts,
            )
            "Failed" -> ProjectEvent.Failed(projectId, reason ?: "", ts)
            "ContextUpdated" -> ProjectEvent.ContextUpdated(projectId, tier ?: 1, summary ?: "", ts)
            "WorkCycleCompleted" -> ProjectEvent.WorkCycleCompleted(projectId, stepId ?: "", actionsExecuted ?: 0, ts)
            else -> error("Unknown event type: $type")
        }
    }

    companion object {
        fun from(event: ProjectEvent): EventWrapper {
            val base = EventWrapper(
                type = event::class.simpleName ?: "Unknown",
                projectId = event.projectId,
                timestamp = event.timestamp.toString(),
            )
            return when (event) {
                is ProjectEvent.Created -> base.copy(
                    title = event.title,
                    instruction = event.instruction,
                    priority = event.priority.name,
                    completionCriteria = event.completionCriteria,
                )
                is ProjectEvent.PlanGenerated -> base.copy(plan = event.plan)
                is ProjectEvent.PlanRevised -> base.copy(plan = event.plan, reason = event.reason)
                is ProjectEvent.StepStarted -> base.copy(stepId = event.stepId)
                is ProjectEvent.StepActionExecuted -> base.copy(stepId = event.stepId, actionResult = event.actionResult)
                is ProjectEvent.StepAcceptancePassed -> base.copy(stepId = event.stepId)
                is ProjectEvent.StepAcceptanceFailed -> base.copy(stepId = event.stepId, reason = event.reason)
                is ProjectEvent.StepBlocked -> base.copy(stepId = event.stepId, waitCondition = event.waitCondition)
                is ProjectEvent.StepUnblocked -> base.copy(stepId = event.stepId)
                is ProjectEvent.StepSkipped -> base.copy(stepId = event.stepId, reason = event.reason)
                is ProjectEvent.WaitConditionRegistered -> base.copy(stepId = event.stepId, waitCondition = event.condition)
                is ProjectEvent.WaitConditionSatisfied -> base.copy(
                    stepId = event.stepId,
                    conditionType = event.conditionType,
                    resolutionSummary = event.resolutionSummary,
                    resolutionStatus = event.resolutionStatus,
                )
                is ProjectEvent.WaitConditionTimedOut -> base.copy(stepId = event.stepId)
                is ProjectEvent.Suspended -> base.copy(reason = event.reason, resumeAt = event.resumeAt?.toString())
                is ProjectEvent.Resumed -> base
                is ProjectEvent.Completed -> base
                is ProjectEvent.PriorityChanged -> base.copy(priority = event.priority.name)
                is ProjectEvent.Failed -> base.copy(reason = event.reason)
                is ProjectEvent.ContextUpdated -> base.copy(tier = event.tier, summary = event.summary)
                is ProjectEvent.WorkCycleCompleted -> base.copy(stepId = event.stepId, actionsExecuted = event.actionsExecuted)
            }
        }
    }
}
