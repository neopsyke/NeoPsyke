package ai.neopsyke.agent.assignments

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
 * Thread-safety: single-writer assumed (AssignmentRuntime serializes writes).
 */
class WorkItemEventLog(
    private val path: Path,
    private val maxSegmentBytes: Long = WorkItemStore.DEFAULT_MAX_EVENT_LOG_SEGMENT_BYTES,
    private val maxArchivedSegments: Int = WorkItemStore.DEFAULT_MAX_ARCHIVED_EVENT_SEGMENTS,
) {

    private val mapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    fun append(event: WorkItemEvent) {
        val wrapper = EventWrapper.from(event)
        val json = mapper.writeValueAsString(wrapper)
        Files.createDirectories(path.parent)
        rollSegmentIfNeeded()
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
        val files = segmentPaths()
        if (files.isEmpty()) return emptyList()
        return files.flatMap { file ->
            Files.readAllLines(file)
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    try {
                        val wrapper = mapper.readValue<EventWrapper>(line)
                        wrapper.toEvent()
                    } catch (e: Exception) {
                        logger.warn { "Skipping malformed event line from ${file.fileName}: ${e.message}" }
                        null
                    }
                }
        }
    }

    fun readFrom(sequenceNumber: Int): List<WorkItemEvent> =
        readAll().drop(sequenceNumber)

    private fun segmentPaths(): List<Path> {
        val dir = path.parent ?: return emptyList()
        if (!Files.isDirectory(dir)) return emptyList()
        val currentName = path.fileName.toString()
        val archiveName = archivePath().fileName.toString()
        val archivedPrefix = currentName.removeSuffix(".jsonl") + "."
        return Files.list(dir).use { stream ->
            stream.filter { candidate ->
                val name = candidate.fileName.toString()
                name == currentName ||
                    name == archiveName ||
                    (name.startsWith(archivedPrefix) && name.endsWith(".jsonl"))
            }
                .sorted(
                    compareBy<Path> { candidate ->
                        when (candidate.fileName.toString()) {
                            archiveName -> 0
                            currentName -> 2
                            else -> 1
                        }
                    }.thenComparator { left, right ->
                        compareSegmentOrder(left, right)
                    }
                )
                .toList()
        }
    }

    private fun rollSegmentIfNeeded() {
        if (!Files.exists(path)) return
        if (Files.size(path) < maxSegmentBytes) return
        val archived = archivedPath()
        Files.move(path, archived)
        trimArchivedSegments()
    }

    private fun archivedPath(): Path {
        val baseName = path.fileName.toString().removeSuffix(".jsonl")
        val stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")
            .format(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC))
        var attempt = 0
        while (true) {
            val suffix = if (attempt == 0) "" else "-$attempt"
            val candidate = path.resolveSibling("$baseName.$stamp$suffix.jsonl")
            if (!Files.exists(candidate)) return candidate
            attempt += 1
        }
    }

    private fun trimArchivedSegments() {
        val dir = path.parent ?: return
        val archivedPrefix = path.fileName.toString().removeSuffix(".jsonl") + "."
        val archiveName = archivePath().fileName.toString()
        val archived = Files.list(dir).use { stream ->
            stream.filter { candidate ->
                val name = candidate.fileName.toString()
                name != archiveName &&
                    name.startsWith(archivedPrefix) &&
                    name.endsWith(".jsonl")
            }
                .sorted(::compareSegmentOrder)
                .toList()
        }
        val overflow = archived.size - maxArchivedSegments
        if (overflow > 0) {
            val archive = archivePath()
            Files.createDirectories(archive.parent)
            archived.take(overflow).forEach { segment ->
                if (Files.exists(segment)) {
                    Files.newOutputStream(
                        archive,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND,
                        StandardOpenOption.SYNC,
                    ).bufferedWriter().use { writer ->
                        Files.readAllLines(segment).forEach { line ->
                            if (line.isNotBlank()) {
                                writer.append(line)
                                writer.append('\n')
                            }
                        }
                        writer.flush()
                    }
                    Files.deleteIfExists(segment)
                }
            }
        }
    }

    private fun archivePath(): Path =
        path.resolveSibling(path.fileName.toString().removeSuffix(".jsonl") + ".archive.jsonl")

    private fun compareSegmentOrder(left: Path, right: Path): Int {
        return compareValuesBy(
            left,
            right,
            { if (it == archivePath()) Long.MIN_VALUE else Files.getLastModifiedTime(it).toMillis() },
            { it.fileName.toString() }
        )
    }
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
    val kind: String? = null,
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
    val operatorSummary: String? = null,
    // Lease/Activation lifecycle
    val leaseToken: String? = null,
    val planRevision: Int? = null,
    val wakeReason: WakeReason? = null,
    val wakeReasons: List<WakeReason>? = null,
    // Effect intent
    val effectIntentId: String? = null,
    val actionType: String? = null,
    // Monitoring / delivery / review
    val sourceKey: String? = null,
    val cursor: String? = null,
    val itemKey: String? = null,
    val fingerprint: String? = null,
    val changeClass: String? = null,
    val windowKey: String? = null,
    val deliveryDecision: String? = null,
    val deliverySuppressionReason: String? = null,
    val scanSummary: String? = null,
    val wakeReasonType: String? = null,
    val segmentName: String? = null,
) {
    fun toEvent(): WorkItemEvent {
        val ts = java.time.Instant.parse(timestamp)
        return when (type) {
            "Created" -> WorkItemEvent.Created(
                workItemId = workItemId,
                kind = WorkItemKind.fromSerialized(kind),
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
            "ResponsibilityCycleRearmed" -> WorkItemEvent.ResponsibilityCycleRearmed(workItemId, reason ?: "", ts)
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
                operatorSummary = operatorSummary,
                reason = reason,
                timestamp = ts,
            )
            // Lease lifecycle
            "LeaseAcquired" -> WorkItemEvent.LeaseAcquired(workItemId, leaseToken ?: "", ts)
            "LeaseHeartbeat" -> WorkItemEvent.LeaseHeartbeat(workItemId, leaseToken ?: "", ts)
            "LeaseExpired" -> WorkItemEvent.LeaseExpired(workItemId, leaseToken ?: "", reason ?: "", ts)
            "WakeCoalesced" -> WorkItemEvent.WakeCoalesced(
                workItemId = workItemId,
                wakeReason = wakeReason ?: WakeReason(WakeReasonType.COALESCED_WAKE),
                timestamp = ts,
            )
            // Activation lifecycle
            "ActivationStarted" -> WorkItemEvent.ActivationStarted(
                workItemId = workItemId,
                stepId = stepId ?: "",
                leaseToken = leaseToken ?: "",
                planRevision = planRevision ?: 1,
                wakeReasons = wakeReasons.orEmpty(),
                timestamp = ts,
            )
            "ActivationFinished" -> WorkItemEvent.ActivationFinished(workItemId, stepId ?: "", leaseToken ?: "", actionsExecuted ?: 0, ts)
            "ActivationRecovered" -> WorkItemEvent.ActivationRecovered(workItemId, leaseToken ?: "", reason ?: "", ts)
            // Health and delivery
            "MarkedStalled" -> WorkItemEvent.MarkedStalled(workItemId, reason ?: "", ts)
            "MarkedNeedsAttention" -> WorkItemEvent.MarkedNeedsAttention(workItemId, reason ?: "", ts)
            "DeliveryDeferred" -> WorkItemEvent.DeliveryDeferred(workItemId, reason ?: "", ts)
            "DeliverySent" -> WorkItemEvent.DeliverySent(workItemId, summary ?: "", ts)
            "DeliveryDecisionRecorded" -> WorkItemEvent.DeliveryDecisionRecorded(
                workItemId = workItemId,
                decision = DeliveryDecision.valueOf(deliveryDecision ?: DeliveryDecision.DEFER.name),
                suppressionReason = deliverySuppressionReason?.let { DeliverySuppressionReason.valueOf(it) },
                fingerprint = fingerprint,
                summary = summary,
                timestamp = ts,
            )
            "MonitorScanStarted" -> WorkItemEvent.MonitorScanStarted(workItemId, sourceKey ?: "", ts)
            "MonitorScanCompleted" -> WorkItemEvent.MonitorScanCompleted(workItemId, sourceKey ?: "", scanSummary, ts)
            "MonitorCursorAdvanced" -> WorkItemEvent.MonitorCursorAdvanced(workItemId, sourceKey ?: "", cursor, ts)
            "SeenItemRecorded" -> WorkItemEvent.SeenItemRecorded(workItemId, itemKey ?: "", fingerprint, ts)
            "SeenItemUpdated" -> WorkItemEvent.SeenItemUpdated(workItemId, itemKey ?: "", fingerprint, ts)
            "MeaningfulChangeDetected" -> WorkItemEvent.MeaningfulChangeDetected(
                workItemId = workItemId,
                itemKey = itemKey ?: "",
                changeClass = changeClass?.let { ChangeClass.valueOf(it) } ?: ChangeClass.NOTEWORTHY,
                summary = summary,
                timestamp = ts,
            )
            "ReportWindowOpened" -> WorkItemEvent.ReportWindowOpened(workItemId, windowKey ?: "", ts)
            "ReportWindowClosed" -> WorkItemEvent.ReportWindowClosed(workItemId, windowKey ?: "", ts)
            "DeliverySuppressed" -> WorkItemEvent.DeliverySuppressed(
                workItemId = workItemId,
                reason = deliverySuppressionReason?.let { DeliverySuppressionReason.valueOf(it) }
                    ?: DeliverySuppressionReason.NO_MEANINGFUL_CHANGE,
                summary = summary,
                timestamp = ts,
            )
            "ReviewRecorded" -> WorkItemEvent.ReviewRecorded(
                workItemId = workItemId,
                wakeReasonType = wakeReasonType?.let { WakeReasonType.valueOf(it) } ?: WakeReasonType.MANUAL_REVIEW,
                outcome = reason ?: "",
                summary = summary,
                timestamp = ts,
            )
            "IdReviewRequested" -> WorkItemEvent.IdReviewRequested(workItemId, reason ?: "", ts)
            "IdReviewAccepted" -> WorkItemEvent.IdReviewAccepted(workItemId, reason ?: "", ts)
            "IdReviewDeferred" -> WorkItemEvent.IdReviewDeferred(workItemId, reason ?: "", ts)
            "Retired" -> WorkItemEvent.Retired(workItemId, reason ?: "", ts)
            "EventSegmentRolled" -> WorkItemEvent.EventSegmentRolled(workItemId, segmentName ?: "", ts)
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
                    kind = event.kind.name,
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
                is WorkItemEvent.ResponsibilityCycleRearmed -> base.copy(reason = event.reason)
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
                    operatorSummary = event.operatorSummary,
                    reason = event.reason,
                )
                // Lease lifecycle
                is WorkItemEvent.LeaseAcquired -> base.copy(leaseToken = event.leaseToken)
                is WorkItemEvent.LeaseHeartbeat -> base.copy(leaseToken = event.leaseToken)
                is WorkItemEvent.LeaseExpired -> base.copy(leaseToken = event.leaseToken, reason = event.reason)
                is WorkItemEvent.WakeCoalesced -> base.copy(wakeReason = event.wakeReason)
                // Activation lifecycle
                is WorkItemEvent.ActivationStarted -> base.copy(
                    stepId = event.stepId,
                    leaseToken = event.leaseToken,
                    planRevision = event.planRevision,
                    wakeReasons = event.wakeReasons,
                )
                is WorkItemEvent.ActivationFinished -> base.copy(stepId = event.stepId, leaseToken = event.leaseToken, actionsExecuted = event.actionsExecuted)
                is WorkItemEvent.ActivationRecovered -> base.copy(leaseToken = event.leaseToken, reason = event.reason)
                // Health and delivery
                is WorkItemEvent.MarkedStalled -> base.copy(reason = event.reason)
                is WorkItemEvent.MarkedNeedsAttention -> base.copy(reason = event.reason)
                is WorkItemEvent.DeliveryDeferred -> base.copy(reason = event.reason)
                is WorkItemEvent.DeliverySent -> base.copy(summary = event.summary)
                is WorkItemEvent.DeliveryDecisionRecorded -> base.copy(
                    deliveryDecision = event.decision.name,
                    deliverySuppressionReason = event.suppressionReason?.name,
                    fingerprint = event.fingerprint,
                    summary = event.summary,
                )
                is WorkItemEvent.MonitorScanStarted -> base.copy(sourceKey = event.sourceKey)
                is WorkItemEvent.MonitorScanCompleted -> base.copy(sourceKey = event.sourceKey, scanSummary = event.scanSummary)
                is WorkItemEvent.MonitorCursorAdvanced -> base.copy(sourceKey = event.sourceKey, cursor = event.cursor)
                is WorkItemEvent.SeenItemRecorded -> base.copy(itemKey = event.itemKey, fingerprint = event.fingerprint)
                is WorkItemEvent.SeenItemUpdated -> base.copy(itemKey = event.itemKey, fingerprint = event.fingerprint)
                is WorkItemEvent.MeaningfulChangeDetected -> base.copy(
                    itemKey = event.itemKey,
                    changeClass = event.changeClass.name,
                    summary = event.summary,
                )
                is WorkItemEvent.ReportWindowOpened -> base.copy(windowKey = event.windowKey)
                is WorkItemEvent.ReportWindowClosed -> base.copy(windowKey = event.windowKey)
                is WorkItemEvent.DeliverySuppressed -> base.copy(
                    deliverySuppressionReason = event.reason.name,
                    summary = event.summary,
                )
                is WorkItemEvent.ReviewRecorded -> base.copy(
                    wakeReasonType = event.wakeReasonType.name,
                    reason = event.outcome,
                    summary = event.summary,
                )
                is WorkItemEvent.IdReviewRequested -> base.copy(reason = event.reason)
                is WorkItemEvent.IdReviewAccepted -> base.copy(reason = event.reason)
                is WorkItemEvent.IdReviewDeferred -> base.copy(reason = event.reason)
                is WorkItemEvent.Retired -> base.copy(reason = event.reason)
                is WorkItemEvent.EventSegmentRolled -> base.copy(segmentName = event.segmentName)
                // Effect intent
                is WorkItemEvent.EffectIntentRecorded -> base.copy(effectIntentId = event.effectIntentId, actionType = event.actionType)
                is WorkItemEvent.EffectConfirmed -> base.copy(effectIntentId = event.effectIntentId)
                is WorkItemEvent.EffectAbandoned -> base.copy(effectIntentId = event.effectIntentId, reason = event.reason)
                is WorkItemEvent.EffectUncertain -> base.copy(effectIntentId = event.effectIntentId, reason = event.reason)
            }
        }
    }
}
