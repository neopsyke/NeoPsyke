package psyke.instrumentation

import psyke.agent.core.PendingAction
import psyke.agent.core.PendingInput
import psyke.agent.core.PendingThought
import psyke.agent.core.QueueState
import psyke.agent.cortex.motor.ActionImplementationStatus
import java.time.Instant

data class AgentEvent(
    val id: Long = 0,
    val type: String,
    val ts: Long = System.currentTimeMillis(),
    val tsIso: String = Instant.now().toString(),
    val data: Map<String, Any?> = emptyMap(),
)

object AgentEvents {
    fun loopStatus(status: String, message: String? = null): AgentEvent =
        AgentEvent(
            type = "loop_status",
            data = mapOf(
                "status" to status,
                "message" to message
            )
        )

    fun loopStep(step: Int, taskType: String): AgentEvent =
        AgentEvent(
            type = "loop_step",
            data = mapOf(
                "step" to step,
                "task_type" to taskType
            )
        )

    fun queueSnapshot(source: String, queues: QueueState): AgentEvent =
        AgentEvent(
            type = "queue_snapshot",
            data = mapOf(
                "source" to source,
                "queues" to queues
            )
        )

    fun queueSaturation(queueType: String, pending: Int, capacity: Int, reason: String): AgentEvent =
        AgentEvent(
            type = "queue_saturation",
            data = mapOf(
                "queue_type" to queueType,
                "pending" to pending,
                "capacity" to capacity,
                "reason" to reason
            )
        )

    fun inputQueued(input: PendingInput): AgentEvent =
        AgentEvent(
            type = "input_queued",
            data = mapOf(
                "input" to input
            )
        )

    fun inputProcessing(input: PendingInput): AgentEvent =
        AgentEvent(
            type = "input_processing",
            data = mapOf(
                "input" to input
            )
        )

    fun thoughtProcessing(thought: PendingThought): AgentEvent =
        AgentEvent(
            type = "thought_processing",
            data = mapOf(
                "thought" to thought
            )
        )

    fun thoughtDropped(thought: PendingThought, reason: String): AgentEvent =
        AgentEvent(
            type = "thought_dropped",
            data = mapOf(
                "thought" to thought,
                "reason" to reason
            )
        )

    fun plannerDecision(
        trigger: String,
        decisionType: String,
        urgency: String? = null,
        thought: String? = null,
        actionType: String? = null,
        payload: String? = null,
        summary: String? = null,
        reason: String? = null,
    ): AgentEvent =
        AgentEvent(
            type = "planner_decision",
            data = mapOf(
                "trigger" to trigger,
                "decision_type" to decisionType,
                "urgency" to urgency,
                "thought" to thought,
                "action_type" to actionType,
                "payload" to payload,
                "summary" to summary,
                "reason" to reason
            )
        )

    fun actionProposed(
        actionType: String,
        urgency: String,
        payload: String,
        summary: String,
        queued: Boolean,
    ): AgentEvent =
        AgentEvent(
            type = "action_proposed",
            data = mapOf(
                "action_type" to actionType,
                "urgency" to urgency,
                "payload" to payload,
                "summary" to summary,
                "queued" to queued
            )
        )

    fun actionReviewRequested(action: PendingAction): AgentEvent =
        AgentEvent(
            type = "action_review_requested",
            data = mapOf(
                "action" to action
            )
        )

    fun actionReviewResult(
        actionId: Long,
        allow: Boolean,
        reason: String? = null,
        reasonCode: String? = null,
    ): AgentEvent =
        AgentEvent(
            type = "action_review_result",
            data = mapOf(
                "action_id" to actionId,
                "allow" to allow,
                "reason" to reason,
                "reason_code" to reasonCode
            )
        )

    fun actionExecuted(action: PendingAction, outcomeSummary: String): AgentEvent =
        AgentEvent(
            type = "action_executed",
            data = mapOf(
                "action" to action,
                "outcome_summary" to outcomeSummary
            )
        )

    fun actionDenied(action: PendingAction, reason: String, reasonCode: String? = null): AgentEvent =
        AgentEvent(
            type = "action_denied",
            data = mapOf(
                "action" to action,
                "reason" to reason,
                "reason_code" to reasonCode
            )
        )

    fun superegoReviewInput(
        action: PendingAction,
        directives: List<String>,
        lastUserMessage: String,
    ): AgentEvent =
        AgentEvent(
            type = "superego_input",
            data = mapOf(
                "action" to action,
                "directives" to directives,
                "last_user_message" to lastUserMessage
            )
        )

    fun superegoReviewOutput(
        actionId: Long,
        allow: Boolean,
        reason: String?,
        reasonCode: String? = null,
    ): AgentEvent =
        AgentEvent(
            type = "superego_output",
            data = mapOf(
                "action_id" to actionId,
                "allow" to allow,
                "reason" to reason,
                "reason_code" to reasonCode
            )
        )

    fun warning(message: String): AgentEvent =
        AgentEvent(
            type = "warning",
            data = mapOf("message" to message)
        )

    fun plannerOutputRepaired(actionType: String, repair: String = "action_summary_autofill"): AgentEvent =
        AgentEvent(
            type = "planner_output_repaired",
            data = mapOf(
                "action_type" to actionType,
                "repair" to repair
            )
        )

    fun memoryRecallStart(
        trigger: String,
        provider: String,
        cuePreview: String,
    ): AgentEvent =
        AgentEvent(
            type = "memory_recall_start",
            data = mapOf(
                "trigger" to trigger,
                "provider" to provider,
                "cue_preview" to cuePreview
            )
        )

    fun memoryRecallResult(
        trigger: String,
        provider: String,
        hitCount: Int,
        latencyMs: Long,
        recallChars: Int,
        truncated: Boolean,
    ): AgentEvent =
        AgentEvent(
            type = "memory_recall_result",
            data = mapOf(
                "trigger" to trigger,
                "provider" to provider,
                "hit_count" to hitCount,
                "latency_ms" to latencyMs,
                "recall_chars" to recallChars,
                "truncated" to truncated
            )
        )

    fun memoryRecallFailure(
        trigger: String,
        provider: String,
        latencyMs: Long,
        reason: String,
    ): AgentEvent =
        AgentEvent(
            type = "memory_recall_failure",
            data = mapOf(
                "trigger" to trigger,
                "provider" to provider,
                "latency_ms" to latencyMs,
                "reason" to reason
            )
        )

    fun longTermMemoryRecallRequested(
        trigger: String,
        source: String,
        queryPreview: String,
    ): AgentEvent =
        AgentEvent(
            type = "long_term_memory_recall_requested",
            data = mapOf(
                "trigger" to trigger,
                "source" to source,
                "query_preview" to queryPreview
            )
        )

    fun longTermMemoryRecallSkipped(trigger: String, reason: String): AgentEvent =
        AgentEvent(
            type = "long_term_memory_recall_skipped",
            data = mapOf(
                "trigger" to trigger,
                "reason" to reason
            )
        )

    fun longTermMemoryAssessmentParseFallback(
        trigger: String,
        stepIndex: Int,
        streak: Int,
    ): AgentEvent =
        AgentEvent(
            type = "long_term_memory_assessment_parse_fallback",
            data = mapOf(
                "trigger" to trigger,
                "step_index" to stepIndex,
                "streak" to streak
            )
        )

    fun longTermMemoryAssessmentTemporarilyDisabled(
        trigger: String,
        stepIndex: Int,
        streak: Int,
        threshold: Int,
    ): AgentEvent =
        AgentEvent(
            type = "long_term_memory_assessment_temporarily_disabled",
            data = mapOf(
                "trigger" to trigger,
                "step_index" to stepIndex,
                "streak" to streak,
                "threshold" to threshold
            )
        )

    fun responseLatencyRecorded(latencyMs: Long, actionId: Long): AgentEvent =
        AgentEvent(
            type = "response_latency_recorded",
            data = mapOf(
                "latency_ms" to latencyMs,
                "action_id" to actionId
            )
        )

    fun actionCapabilities(statuses: List<ActionImplementationStatus>): AgentEvent =
        AgentEvent(
            type = "action_capabilities",
            data = mapOf(
                "statuses" to statuses.map { status ->
                    mapOf(
                        "action_type" to status.actionType.name.lowercase(),
                        "available" to status.available,
                        "detail" to status.detail
                    )
                }
            )
        )

    fun duplicatePlanSuppressed(reason: String, rootInputEnqueuedAtMs: Long? = null): AgentEvent =
        AgentEvent(
            type = "duplicate_plan_suppressed",
            data = mapOf(
                "reason" to reason,
                "root_input_enqueued_at_ms" to rootInputEnqueuedAtMs
            )
        )

    fun convergenceThoughtEnqueued(rootInputEnqueuedAtMs: Long? = null): AgentEvent =
        AgentEvent(
            type = "convergence_thought_enqueued",
            data = mapOf(
                "root_input_enqueued_at_ms" to rootInputEnqueuedAtMs
            )
        )

    fun actionTypeTemporarilyDisabled(actionType: String, reason: String, rootInputEnqueuedAtMs: Long? = null): AgentEvent =
        AgentEvent(
            type = "action_type_temporarily_disabled",
            data = mapOf(
                "action_type" to actionType,
                "reason" to reason,
                "root_input_enqueued_at_ms" to rootInputEnqueuedAtMs
            )
        )

    fun planCreated(planId: String, goal: String, stepCount: Int, urgency: String): AgentEvent =
        AgentEvent(
            type = "plan_created",
            data = mapOf(
                "plan_id" to planId,
                "goal" to goal,
                "step_count" to stepCount,
                "urgency" to urgency
            )
        )

    fun planStepsEnqueued(planId: String, totalSteps: Int, allQueued: Boolean): AgentEvent =
        AgentEvent(
            type = "plan_steps_enqueued",
            data = mapOf(
                "plan_id" to planId,
                "total_steps" to totalSteps,
                "all_queued" to allQueued
            )
        )

    fun llmRawResponse(
        actor: String,
        callSite: String,
        rawResponse: String,
        actionType: String? = null,
    ): AgentEvent =
        AgentEvent(
            type = "llm_raw_response",
            data = mapOf(
                "actor" to actor,
                "call_site" to callSite,
                "action_type" to actionType,
                "raw_response" to rawResponse
            )
        )
}
