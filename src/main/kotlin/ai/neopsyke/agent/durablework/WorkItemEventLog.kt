package ai.neopsyke.agent.durablework

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
 * Append-only JSONL event log for a single workItem.
 *
 * Each line is a JSON object with `"type"` discriminator + event fields.
 * Thread-safety: single-writer assumed (DurableWorkRuntime serializes writes).
 */
class WorkItemEventLog(private val path: Path) {

    private val mapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    fun append(event: WorkItemEvent) {
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

    fun readAll(): List<WorkItemEvent> {
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

    fun readFrom(sequenceNumber: Int): List<WorkItemEvent> =
        readAll().drop(sequenceNumber)
}

/**
 * JSON-serializable envelope for [WorkItemEvent].
 * Uses a flat `type` discriminator + nullable fields for each event variant.
 * This avoids Jackson polymorphic type info while keeping the JSONL human-readable.
 */
internal data class EventWrapper(
    val type: String = "",
    val workItemId: String = "",
    val timestamp: String = "",
    // Created
    val title: String? = null,
    val instruction: String? = null,
    val priority: String? = null,
    val completionCriteria: String? = null,
    // PlanGenerated / PlanRevised
    val plan: WorkItemPlan? = null,
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
    // Updated
    val cronExpression: String? = null,
    val contactChannel: String? = null,
    // Lease/Activation lifecycle
    val leaseToken: String? = null,
    val planRevision: Int? = null,
    val wakeReason: String? = null,
    // Effect intent
    val effectIntentId: String? = null,
    val actionType: String? = null,
) {
    fun toEvent(): WorkItemEvent {
        val ts = java.time.Instant.parse(timestamp)
        return when (type) {
            "Created" -> WorkItemEvent.Created(
                workItemId = workItemId,
                title = title ?: "",
                instruction = instruction ?: "",
                priority = WorkItemPriority.valueOf(priority ?: "MEDIUM"),
                completionCriteria = completionCriteria ?: "",
                contactChannel = contactChannel,
                timestamp = ts,
            )
            "PlanGenerated" -> WorkItemEvent.PlanGenerated(workItemId, plan!!, ts)
            "PlanRevised" -> WorkItemEvent.PlanRevised(workItemId, plan!!, reason ?: "", ts)
            "StepStarted" -> WorkItemEvent.StepStarted(workItemId, stepId!!, ts)
            "StepActionExecuted" -> WorkItemEvent.StepActionExecuted(workItemId, stepId!!, actionResult ?: "", ts)
            "StepAcceptancePassed" -> WorkItemEvent.StepAcceptancePassed(workItemId, stepId!!, ts)
            "StepAcceptanceFailed" -> WorkItemEvent.StepAcceptanceFailed(workItemId, stepId!!, reason ?: "", ts)
            "StepBlocked" -> WorkItemEvent.StepBlocked(workItemId, stepId!!, waitCondition!!, ts)
            "StepUnblocked" -> WorkItemEvent.StepUnblocked(workItemId, stepId!!, ts)
            "StepSkipped" -> WorkItemEvent.StepSkipped(workItemId, stepId!!, reason ?: "", ts)
            "WaitConditionRegistered" -> WorkItemEvent.WaitConditionRegistered(workItemId, stepId!!, waitCondition!!, ts)
            "WaitConditionSatisfied" -> WorkItemEvent.WaitConditionSatisfied(
                workItemId = workItemId,
                stepId = stepId!!,
                conditionType = conditionType ?: "",
                resolutionSummary = resolutionSummary,
                resolutionStatus = resolutionStatus,
                timestamp = ts,
            )
            "WaitConditionTimedOut" -> WorkItemEvent.WaitConditionTimedOut(workItemId, stepId!!, ts)
            "Suspended" -> WorkItemEvent.Suspended(workItemId, reason ?: "", resumeAt?.let { java.time.Instant.parse(it) }, ts)
            "Resumed" -> WorkItemEvent.Resumed(workItemId, ts)
            "CronCycleStarted" -> WorkItemEvent.CronCycleStarted(workItemId, ts)
            "Completed" -> WorkItemEvent.Completed(workItemId, ts)
            "PriorityChanged" -> WorkItemEvent.PriorityChanged(
                workItemId = workItemId,
                priority = WorkItemPriority.valueOf(priority ?: "MEDIUM"),
                timestamp = ts,
            )
            "Failed" -> WorkItemEvent.Failed(workItemId, reason ?: "", ts)
            "ContextUpdated" -> WorkItemEvent.ContextUpdated(workItemId, tier ?: 1, summary ?: "", ts)
            "WorkCycleCompleted" -> WorkItemEvent.WorkCycleCompleted(workItemId, stepId ?: "", actionsExecuted ?: 0, ts)
            "Updated" -> WorkItemEvent.Updated(
                workItemId = workItemId,
                cronExpression = cronExpression,
                title = title,
                instruction = instruction,
                completionCriteria = completionCriteria,
                contactChannel = contactChannel,
                reason = reason,
                timestamp = ts,
            )
            // Lease lifecycle
            "LeaseAcquired" -> WorkItemEvent.LeaseAcquired(workItemId, leaseToken ?: "", ts)
            "LeaseHeartbeat" -> WorkItemEvent.LeaseHeartbeat(workItemId, leaseToken ?: "", ts)
            "LeaseExpired" -> WorkItemEvent.LeaseExpired(workItemId, leaseToken ?: "", reason ?: "", ts)
            "WakeCoalesced" -> WorkItemEvent.WakeCoalesced(workItemId, wakeReason ?: "", ts)
            // Activation lifecycle
            "ActivationStarted" -> WorkItemEvent.ActivationStarted(workItemId, stepId ?: "", leaseToken ?: "", planRevision ?: 1, ts)
            "ActivationFinished" -> WorkItemEvent.ActivationFinished(workItemId, stepId ?: "", leaseToken ?: "", actionsExecuted ?: 0, ts)
            "ActivationRecovered" -> WorkItemEvent.ActivationRecovered(workItemId, leaseToken ?: "", reason ?: "", ts)
            // Health and delivery
            "MarkedStalled" -> WorkItemEvent.MarkedStalled(workItemId, reason ?: "", ts)
            "MarkedNeedsAttention" -> WorkItemEvent.MarkedNeedsAttention(workItemId, reason ?: "", ts)
            "DeliveryDeferred" -> WorkItemEvent.DeliveryDeferred(workItemId, reason ?: "", ts)
            "DeliverySent" -> WorkItemEvent.DeliverySent(workItemId, summary ?: "", ts)
            // Effect intent
            "EffectIntentRecorded" -> WorkItemEvent.EffectIntentRecorded(workItemId, effectIntentId ?: "", actionType ?: "", ts)
            "EffectConfirmed" -> WorkItemEvent.EffectConfirmed(workItemId, effectIntentId ?: "", ts)
            "EffectAbandoned" -> WorkItemEvent.EffectAbandoned(workItemId, effectIntentId ?: "", reason ?: "", ts)
            "EffectUncertain" -> WorkItemEvent.EffectUncertain(workItemId, effectIntentId ?: "", reason ?: "", ts)
            else -> error("Unknown event type: $type")
        }
    }

    companion object {
        fun from(event: WorkItemEvent): EventWrapper {
            val base = EventWrapper(
                type = event::class.simpleName ?: "Unknown",
                workItemId = event.workItemId,
                timestamp = event.timestamp.toString(),
            )
            return when (event) {
                is WorkItemEvent.Created -> base.copy(
                    title = event.title,
                    instruction = event.instruction,
                    priority = event.priority.name,
                    completionCriteria = event.completionCriteria,
                    contactChannel = event.contactChannel,
                )
                is WorkItemEvent.PlanGenerated -> base.copy(plan = event.plan)
                is WorkItemEvent.PlanRevised -> base.copy(plan = event.plan, reason = event.reason)
                is WorkItemEvent.StepStarted -> base.copy(stepId = event.stepId)
                is WorkItemEvent.StepActionExecuted -> base.copy(stepId = event.stepId, actionResult = event.actionResult)
                is WorkItemEvent.StepAcceptancePassed -> base.copy(stepId = event.stepId)
                is WorkItemEvent.StepAcceptanceFailed -> base.copy(stepId = event.stepId, reason = event.reason)
                is WorkItemEvent.StepBlocked -> base.copy(stepId = event.stepId, waitCondition = event.waitCondition)
                is WorkItemEvent.StepUnblocked -> base.copy(stepId = event.stepId)
                is WorkItemEvent.StepSkipped -> base.copy(stepId = event.stepId, reason = event.reason)
                is WorkItemEvent.WaitConditionRegistered -> base.copy(stepId = event.stepId, waitCondition = event.condition)
                is WorkItemEvent.WaitConditionSatisfied -> base.copy(
                    stepId = event.stepId,
                    conditionType = event.conditionType,
                    resolutionSummary = event.resolutionSummary,
                    resolutionStatus = event.resolutionStatus,
                )
                is WorkItemEvent.WaitConditionTimedOut -> base.copy(stepId = event.stepId)
                is WorkItemEvent.Suspended -> base.copy(reason = event.reason, resumeAt = event.resumeAt?.toString())
                is WorkItemEvent.Resumed -> base
                is WorkItemEvent.CronCycleStarted -> base
                is WorkItemEvent.Completed -> base
                is WorkItemEvent.PriorityChanged -> base.copy(priority = event.priority.name)
                is WorkItemEvent.Failed -> base.copy(reason = event.reason)
                is WorkItemEvent.ContextUpdated -> base.copy(tier = event.tier, summary = event.summary)
                is WorkItemEvent.WorkCycleCompleted -> base.copy(stepId = event.stepId, actionsExecuted = event.actionsExecuted)
                is WorkItemEvent.Updated -> base.copy(
                    title = event.title,
                    instruction = event.instruction,
                    completionCriteria = event.completionCriteria,
                    cronExpression = event.cronExpression,
                    contactChannel = event.contactChannel,
                    reason = event.reason,
                )
                // Lease lifecycle
                is WorkItemEvent.LeaseAcquired -> base.copy(leaseToken = event.leaseToken)
                is WorkItemEvent.LeaseHeartbeat -> base.copy(leaseToken = event.leaseToken)
                is WorkItemEvent.LeaseExpired -> base.copy(leaseToken = event.leaseToken, reason = event.reason)
                is WorkItemEvent.WakeCoalesced -> base.copy(wakeReason = event.wakeReason)
                // Activation lifecycle
                is WorkItemEvent.ActivationStarted -> base.copy(stepId = event.stepId, leaseToken = event.leaseToken, planRevision = event.planRevision)
                is WorkItemEvent.ActivationFinished -> base.copy(stepId = event.stepId, leaseToken = event.leaseToken, actionsExecuted = event.actionsExecuted)
                is WorkItemEvent.ActivationRecovered -> base.copy(leaseToken = event.leaseToken, reason = event.reason)
                // Health and delivery
                is WorkItemEvent.MarkedStalled -> base.copy(reason = event.reason)
                is WorkItemEvent.MarkedNeedsAttention -> base.copy(reason = event.reason)
                is WorkItemEvent.DeliveryDeferred -> base.copy(reason = event.reason)
                is WorkItemEvent.DeliverySent -> base.copy(summary = event.summary)
                // Effect intent
                is WorkItemEvent.EffectIntentRecorded -> base.copy(effectIntentId = event.effectIntentId, actionType = event.actionType)
                is WorkItemEvent.EffectConfirmed -> base.copy(effectIntentId = event.effectIntentId)
                is WorkItemEvent.EffectAbandoned -> base.copy(effectIntentId = event.effectIntentId, reason = event.reason)
                is WorkItemEvent.EffectUncertain -> base.copy(effectIntentId = event.effectIntentId, reason = event.reason)
            }
        }
    }
}
