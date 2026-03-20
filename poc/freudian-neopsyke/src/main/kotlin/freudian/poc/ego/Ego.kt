package freudian.poc.ego

import freudian.poc.cortex.MotorCortex
import freudian.poc.instrumentation.EventLogger
import freudian.poc.instrumentation.RuntimeEvent
import freudian.poc.model.IdImpulse
import freudian.poc.model.ImpulseFeedback
import freudian.poc.model.OriginSource
import freudian.poc.model.ThoughtTask
import freudian.poc.model.UserRequest
import freudian.poc.superego.Superego

class Ego(
    private val planner: EgoPlanner,
    private val superego: Superego,
    private val motorCortex: MotorCortex,
    private val eventLogger: EventLogger,
) {
    private val thoughtQueue = ArrayDeque<ThoughtTask>()
    private val actionQueue = ArrayDeque<freudian.poc.model.ActionProposal>()
    private val lifecycleTracker = ImpulseLifecycleTracker()

    fun submitUserRequest(tick: Int, userRequest: UserRequest) {
        val thoughtTasks = planner.planForUserRequest(userRequest)
        thoughtTasks.forEach { thoughtQueue.addLast(it) }
        eventLogger.log(
            RuntimeEvent(
                tick = tick,
                type = "ego_user_request_queued",
                attributes = mapOf(
                    "content" to userRequest.content,
                    "thought_count" to thoughtTasks.size,
                )
            )
        )
    }

    fun submitImpulse(tick: Int, impulse: IdImpulse) {
        val thoughtTasks = planner.planForImpulse(impulse)
        lifecycleTracker.start(
            rootImpulseId = impulse.rootImpulseId,
            needName = impulse.needName,
            initialThoughtCount = thoughtTasks.size,
        )
        thoughtTasks.forEach { thoughtQueue.addLast(it) }

        eventLogger.log(
            RuntimeEvent(
                tick = tick,
                type = "ego_impulse_queued",
                attributes = mapOf(
                    "root_impulse_id" to impulse.rootImpulseId,
                    "need_name" to impulse.needName,
                    "thought_count" to thoughtTasks.size,
                )
            )
        )
    }

    fun processAllPending(tick: Int): EgoProcessingResult {
        val feedback = mutableListOf<ImpulseFeedback>()
        var actionsProposed = 0
        var actionsDeniedBySuperego = 0
        var actionsExecuted = 0

        while (thoughtQueue.isNotEmpty() || actionQueue.isNotEmpty()) {
            while (thoughtQueue.isNotEmpty()) {
                val thoughtTask = thoughtQueue.removeFirst()
                eventLogger.log(
                    RuntimeEvent(
                        tick = tick,
                        type = "ego_thought_processing",
                        attributes = mapOf(
                            "thought_id" to thoughtTask.thoughtId,
                            "origin" to thoughtTask.origin.name.lowercase(),
                            "root_impulse_id" to thoughtTask.rootImpulseId,
                            "strategy" to thoughtTask.strategy.name.lowercase(),
                            "content" to thoughtTask.content,
                        )
                    )
                )

                val actionProposal = planner.proposeAction(thoughtTask)
                if (actionProposal != null) {
                    actionQueue.addLast(actionProposal)
                    actionsProposed += 1
                    if (actionProposal.rootImpulseId != null) {
                        lifecycleTracker.registerAction(actionProposal.rootImpulseId)?.let { feedback += it }
                    }

                    eventLogger.log(
                        RuntimeEvent(
                            tick = tick,
                            type = "ego_action_proposed",
                            attributes = mapOf(
                                "action_id" to actionProposal.actionId,
                                "type" to actionProposal.type.name,
                                "origin" to actionProposal.origin.name.lowercase(),
                                "root_impulse_id" to actionProposal.rootImpulseId,
                                "need_name" to actionProposal.needName,
                            )
                        )
                    )
                }

                if (thoughtTask.rootImpulseId != null) {
                    lifecycleTracker.completeThought(thoughtTask.rootImpulseId)?.let { feedback += it }
                }
            }

            while (actionQueue.isNotEmpty()) {
                val actionProposal = actionQueue.removeFirst()
                val superegoDecision = superego.review(actionProposal)
                eventLogger.log(
                    RuntimeEvent(
                        tick = tick,
                        type = "superego_review",
                        attributes = mapOf(
                            "action_id" to actionProposal.actionId,
                            "type" to actionProposal.type.name,
                            "origin" to actionProposal.origin.name.lowercase(),
                            "root_impulse_id" to actionProposal.rootImpulseId,
                            "allow" to superegoDecision.allow,
                            "reason_code" to superegoDecision.reasonCode,
                            "reason" to superegoDecision.reason,
                        )
                    )
                )

                val wasExecuted = if (superegoDecision.allow) {
                    val motorOutcome = motorCortex.execute(actionProposal)
                    actionsExecuted += if (motorOutcome.success) 1 else 0
                    eventLogger.log(
                        RuntimeEvent(
                            tick = tick,
                            type = "motor_execution",
                            attributes = mapOf(
                                "action_id" to actionProposal.actionId,
                                "success" to motorOutcome.success,
                                "status" to motorOutcome.status,
                                "origin" to actionProposal.origin.name.lowercase(),
                                "root_impulse_id" to actionProposal.rootImpulseId,
                            )
                        )
                    )
                    motorOutcome.success
                } else {
                    actionsDeniedBySuperego += 1
                    false
                }

                if (actionProposal.rootImpulseId != null) {
                    lifecycleTracker.completeAction(
                        rootImpulseId = actionProposal.rootImpulseId,
                        executed = wasExecuted,
                    )?.let { feedback += it }
                }
            }
        }

        feedback.forEach {
            eventLogger.log(
                RuntimeEvent(
                    tick = tick,
                    type = "impulse_lifecycle_finalized",
                    attributes = mapOf(
                        "root_impulse_id" to it.rootImpulseId,
                        "need_name" to it.needName,
                        "result" to it.result.name.lowercase(),
                    )
                )
            )
        }

        return EgoProcessingResult(
            impulseFeedback = feedback,
            actionsProposed = actionsProposed,
            actionsDeniedBySuperego = actionsDeniedBySuperego,
            actionsExecuted = actionsExecuted,
        )
    }

    fun forceDenyAllImpulses(tick: Int): List<ImpulseFeedback> {
        val deniedFeedback = lifecycleTracker.forceDenyAll()
        deniedFeedback.forEach {
            eventLogger.log(
                RuntimeEvent(
                    tick = tick,
                    type = "impulse_lifecycle_forced_denied",
                    attributes = mapOf(
                        "root_impulse_id" to it.rootImpulseId,
                        "need_name" to it.needName,
                    )
                )
            )
        }
        return deniedFeedback
    }
}

data class EgoProcessingResult(
    val impulseFeedback: List<ImpulseFeedback>,
    val actionsProposed: Int,
    val actionsDeniedBySuperego: Int,
    val actionsExecuted: Int,
)
