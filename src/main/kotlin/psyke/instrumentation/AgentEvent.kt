package psyke.instrumentation

import psyke.agent.PendingAction
import psyke.agent.PendingInput
import psyke.agent.PendingThought
import psyke.agent.QueueState
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
    ): AgentEvent =
        AgentEvent(
            type = "action_review_result",
            data = mapOf(
                "action_id" to actionId,
                "allow" to allow,
                "reason" to reason
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

    fun actionDenied(action: PendingAction, reason: String): AgentEvent =
        AgentEvent(
            type = "action_denied",
            data = mapOf(
                "action" to action,
                "reason" to reason
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
    ): AgentEvent =
        AgentEvent(
            type = "superego_output",
            data = mapOf(
                "action_id" to actionId,
                "allow" to allow,
                "reason" to reason
            )
        )

    fun warning(message: String): AgentEvent =
        AgentEvent(
            type = "warning",
            data = mapOf("message" to message)
        )
}
