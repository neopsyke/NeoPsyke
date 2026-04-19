package ai.neopsyke.agent.assignments

import java.time.Instant
import java.time.ZonedDateTime

/**
 * Runtime projection for a work item — optimized for operator surfaces and API
 * responses. Read from runtime state, not reconstructed from event logs.
 */
data class WorkItemProjection(
    val workItemId: String,
    val kind: WorkItemKind,
    val title: String,
    val status: WorkItemStatus,
    val health: WorkItemHealth,
    val priority: WorkItemPriority,
    val deliveryPolicy: DeliveryPolicy,
    val planRevision: Int,
    val nextWakeAt: Instant? = null,
    val lastSuccessfulActivation: Instant? = null,
    val lastMeaningfulChange: Instant? = null,
    val lastReviewAt: Instant? = null,
    val nextReviewAt: Instant? = null,
    val lastReviewWakeReasonType: WakeReasonType? = null,
    val currentBlocker: String? = null,
    val failureCountInWindow: Int = 0,
    val latestArtifactSummary: String? = null,
    val latestDeliveryDecision: DeliveryDecision? = null,
    val latestDeliverySuppressionReason: DeliverySuppressionReason? = null,
    val explanation: WorkItemExplanation,
)

/**
 * "Why" explanations for the operator — one per condition.
 *
 * Each explains the current state in operator-readable terms. Null means the
 * condition does not apply.
 */
data class WorkItemExplanation(
    val whyActive: String? = null,
    val whyBlocked: String? = null,
    val whyStalled: String? = null,
    val whyQuiet: String? = null,
    val whyReviewPending: String? = null,
    val whyNotified: String? = null,
    val whySkippedOrDeferred: String? = null,
)

/**
 * Builds a [WorkItemProjection] from runtime state.
 */
object WorkItemProjectionBuilder {

    fun build(state: WorkItemState): WorkItemProjection {
        val workItem = state.workItem
        val explanation = buildExplanation(state)
        return WorkItemProjection(
            workItemId = state.id,
            kind = workItem.kind,
            title = workItem.title,
            status = workItem.status,
            health = workItem.health,
            priority = workItem.priority,
            deliveryPolicy = workItem.deliveryPolicy,
            planRevision = workItem.planRevision,
            nextWakeAt = computeNextWakeAt(state),
            lastSuccessfulActivation = workItem.lastWorkedAt,
            lastMeaningfulChange = computeLastMeaningfulChange(state),
            lastReviewAt = workItem.lastReviewAt ?: state.durableState.monitor.review.lastReviewAt,
            nextReviewAt = workItem.nextReviewAt ?: state.durableState.monitor.review.nextReviewDueAt,
            lastReviewWakeReasonType = state.durableState.monitor.review.history.lastOrNull()?.wakeReasonType,
            currentBlocker = findBlocker(state),
            failureCountInWindow = workItem.failureWindow.failureCount,
            latestArtifactSummary = state.durableState.artifacts.lastSummary,
            latestDeliveryDecision = state.durableState.delivery.lastDecision,
            latestDeliverySuppressionReason = state.durableState.delivery.lastSuppressionReason,
            explanation = explanation,
        )
    }

    private fun buildExplanation(state: WorkItemState): WorkItemExplanation {
        val workItem = state.workItem
        val now = Instant.now()
        val nextReviewAt = workItem.nextReviewAt ?: state.durableState.monitor.review.nextReviewDueAt
        return WorkItemExplanation(
            whyActive = when (workItem.status) {
                WorkItemStatus.ACTIVE -> {
                    val step = state.nextRunnableStep()
                    if (step != null) "Executing step: ${step.description}"
                    else if (workItem.kind == WorkItemKind.RESPONSIBILITY) {
                        "Responsibility remains active between review cycles."
                    } else {
                        "Active with no runnable steps"
                    }
                }
                else -> null
            },
            whyBlocked = when (workItem.status) {
                WorkItemStatus.BLOCKED -> {
                    val blockedSteps = workItem.plan.steps.filter { it.status == StepStatus.BLOCKED }
                    if (blockedSteps.isNotEmpty()) {
                        "Blocked on: ${blockedSteps.joinToString(", ") { "${it.id} (${it.waitCondition?.type?.name?.lowercase() ?: "unknown"})" }}"
                    } else {
                        "Blocked (no specific step identified)"
                    }
                }
                else -> null
            },
            whyStalled = when (workItem.status) {
                WorkItemStatus.STALLED -> "Stalled: failure count ${workItem.failureWindow.failureCount} exceeds window threshold"
                else -> if (workItem.health == WorkItemHealth.STALLED) "Health degraded to stalled" else null
            },
            whyQuiet = when (workItem.deliveryPolicy) {
                DeliveryPolicy.ONLY_ON_CHANGE -> "Delivery policy is only-on-change; no meaningful change detected"
                DeliveryPolicy.DIGEST -> {
                    state.durableState.delivery.activeDigestWindow?.let { window ->
                        "Delivery policy is digest; accumulating in window ${window.windowKey}"
                    } ?: "Delivery policy is digest; accumulating for next digest window"
                }
                else -> null
            },
            whyReviewPending = when {
                workItem.kind != WorkItemKind.RESPONSIBILITY -> null
                workItem.status == WorkItemStatus.SUSPENDED ->
                    "Responsibility review is paused${workItem.suspendedUntil?.let { " until $it" } ?: ""}"
                nextReviewAt == null ->
                    "Responsibility remains ongoing until explicitly completed or retired."
                nextReviewAt.isAfter(now) ->
                    "Responsibility remains ongoing; next review is scheduled for $nextReviewAt"
                else ->
                    "Responsibility review is overdue since $nextReviewAt"
            },
            whyNotified = when (workItem.status) {
                WorkItemStatus.COMPLETED -> "Completed and no longer scheduled for review"
                WorkItemStatus.NEEDS_ATTENTION -> "Marked as needing operator attention"
                WorkItemStatus.RETIRED -> "Retired from active responsibility handling"
                else -> null
            },
            whySkippedOrDeferred = when {
                workItem.status == WorkItemStatus.SUSPENDED -> "Suspended${workItem.suspendedUntil?.let { " until $it" } ?: ""}"
                workItem.deliveryPolicy == DeliveryPolicy.MANUAL_REVIEW -> "Deferred for manual review"
                state.durableState.delivery.lastSuppressionReason != null ->
                    "Last delivery was suppressed: ${state.durableState.delivery.lastSuppressionReason.name.lowercase()}"
                else -> null
            },
        )
    }

    private fun computeNextWakeAt(state: WorkItemState): Instant? {
        val workItem = state.workItem
        if (workItem.status == WorkItemStatus.SUSPENDED && workItem.suspendedUntil != null) {
            return workItem.suspendedUntil
        }
        val timerWakes = workItem.plan.steps
            .filter { it.status == StepStatus.BLOCKED && it.waitCondition?.type == WaitConditionType.TIMER }
            .mapNotNull { it.waitCondition?.timeoutAt }
        val cronNext = workItem.cronExpression?.let { expr ->
            CronParser.nextAfter(expr, ZonedDateTime.now())?.toInstant()
        }
        val reviewNext = if (
            workItem.reviewPolicy.enabled &&
            workItem.status != WorkItemStatus.SUSPENDED &&
            !state.isTerminal()
        ) {
            workItem.nextReviewAt ?: state.durableState.monitor.review.nextReviewDueAt
        } else {
            null
        }
        return (timerWakes + listOfNotNull(cronNext, reviewNext)).minOrNull()
    }

    private fun computeLastMeaningfulChange(state: WorkItemState): Instant? {
        val stepTimes = state.workItem.plan.steps.mapNotNull { it.completedAt }
        val lastStepCompletion = stepTimes.maxOrNull()
        return state.workItem.lastMeaningfulChangeAt
            ?: state.durableState.monitor.lastMeaningfulChangeAt
            ?: state.durableState.delivery.lastMeaningfulChangeAt
            ?: lastStepCompletion
            ?: state.workItem.lastWorkedAt
    }

    private fun findBlocker(state: WorkItemState): String? {
        val blockedStep = state.workItem.plan.steps.firstOrNull { it.status == StepStatus.BLOCKED }
        return blockedStep?.let { step ->
            step.waitCondition?.let { wc ->
                "${step.id}: waiting on ${wc.type.name.lowercase()}"
            } ?: "${step.id}: blocked"
        }
    }
}
