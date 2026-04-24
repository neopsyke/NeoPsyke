package ai.neopsyke.agent.ego.planner

import ai.neopsyke.agent.ego.Ego
import ai.neopsyke.agent.ego.planner.lane.InputPlanner
import ai.neopsyke.agent.ego.planner.runtime.PlannerRuntime
import ai.neopsyke.agent.model.EgoDecision
import ai.neopsyke.agent.model.EgoTrigger
import ai.neopsyke.agent.model.GroundingMetadata
import ai.neopsyke.agent.model.OriginSource
import ai.neopsyke.agent.model.PendingInput
import ai.neopsyke.agent.model.PlannerContext
import ai.neopsyke.instrumentation.AgentEvent
import ai.neopsyke.instrumentation.AgentInstrumentation
import ai.neopsyke.llm.ChatCallMetadata
import java.util.Locale

/**
 * L0 entry point: typed hierarchical planner that replaces LlmEgoPlanner.
 *
 * Routing is purely typed: `when (trigger)` dispatches on the sealed interface
 * variant. No natural-language text inspection. This is deterministic routing on
 * typed runtime metadata (the trigger sealed-class variant), which is explicitly
 * allowed by the mandatory routing rule.
 *
 * Each L1 lane returns EgoDecision directly (decision D7). The L0 orchestrator
 * does not reinterpret lane decisions.
 */
class HierarchicalEgoPlanner(
    private val runtime: PlannerRuntime,
    private val instrumentation: AgentInstrumentation,
    private val inputPlanner: PlannerLane,
    private val progressionPlanner: PlannerLane,
    private val assignmentLanePlanner: PlannerLane,
    private val impulsePlanner: PlannerLane,
) : Ego.Planner {

    override fun decide(trigger: EgoTrigger, context: PlannerContext): EgoDecision {
        val triggerLabel = triggerLabel(trigger)
        val rootInputId = rootInputId(trigger)
        val sessionId = context.conversationContext.sessionId

        instrumentation.emit(
            AgentEvent(
                type = "planner_start",
                data = mapOf(
                    "trigger" to triggerLabel,
                    "pending_inputs" to context.queue.pendingInputCount,
                    "pending_continuations" to context.queue.continuationCount,
                    "pending_actions" to context.queue.pendingActionCount,
                    "pending_intentions" to context.queue.pendingIntentionCount,
                )
            )
        )

        val lane = when (trigger) {
            is EgoTrigger.IncomingInput -> inputPlanner
            is EgoTrigger.Continuation -> progressionPlanner
            is EgoTrigger.ActionFeedback -> progressionPlanner
            is EgoTrigger.Assignment -> assignmentLanePlanner
            is EgoTrigger.IncomingImpulse -> impulsePlanner
        }

        instrumentation.emit(
            AgentEvent(
                type = "planner_lane_selected",
                data = mapOf(
                    "trigger" to triggerLabel,
                    "lane" to lane.laneId.configKey,
                    "root_input_id" to rootInputId,
                )
            )
        )

        val decision = lane.plan(trigger, context)

        // Expose resolved grounding from InputPlanner for the Ego dispatcher.
        _lastResolvedInput = (lane as? InputPlanner)?.lastResolvedInput
        _lastResolvedGrounding = _lastResolvedInput?.groundingMetadata ?: (lane as? InputPlanner)?.lastResolvedGrounding

        runtime.emitDecision(triggerLabel, decision, sessionId, rootInputId)
        return decision
    }

    @Volatile private var _lastResolvedInput: PendingInput? = null
    override val lastResolvedInput: PendingInput? get() = _lastResolvedInput
    @Volatile private var _lastResolvedGrounding: GroundingMetadata? = null
    override val lastResolvedGrounding: GroundingMetadata? get() = _lastResolvedGrounding

    override fun resetForInput(rootInputId: String) {
        runtime.resetAllCircuits(rootInputId)
    }

    companion object {
        fun triggerLabel(trigger: EgoTrigger): String = when (trigger) {
            is EgoTrigger.IncomingInput -> "input"
            is EgoTrigger.Continuation -> "continuation"
            is EgoTrigger.ActionFeedback -> "feedback"
            is EgoTrigger.IncomingImpulse -> "impulse"
            is EgoTrigger.Assignment -> "assignment"
        }

        fun rootInputId(trigger: EgoTrigger): String? = when (trigger) {
            is EgoTrigger.IncomingInput -> trigger.input.rootInputId
            is EgoTrigger.Continuation -> trigger.continuation.rootInputId
            is EgoTrigger.ActionFeedback -> trigger.feedback.cue.rootInputId
            is EgoTrigger.IncomingImpulse -> trigger.impulse.rootImpulseId
            is EgoTrigger.Assignment -> trigger.workUnit.workItemId
        }

        fun plannerChatMetadata(
            trigger: EgoTrigger,
            callSite: String,
            sessionId: String,
            rootInputId: String?,
        ): ChatCallMetadata {
            val base = ChatCallMetadata(
                actor = "ego",
                cognitiveRole = "planner",
                callSite = callSite,
                trigger = callSite,
                sessionId = sessionId,
                rootInputId = rootInputId,
            )
            return when (trigger) {
                is EgoTrigger.IncomingInput -> base.copy(originSource = OriginSource.USER.name.lowercase(Locale.ROOT))
                is EgoTrigger.IncomingImpulse -> base.copy(
                    originSource = OriginSource.ID.name.lowercase(Locale.ROOT),
                    needId = trigger.impulse.needId,
                    rootImpulseId = trigger.impulse.rootImpulseId,
                )
                is EgoTrigger.ActionFeedback -> {
                    val cue = trigger.feedback.cue
                    base.copy(
                        originSource = cue.origin.source.name.lowercase(Locale.ROOT),
                        needId = cue.origin.needId,
                        rootImpulseId = cue.origin.rootImpulseId,
                        actionType = cue.actionType.id,
                    )
                }
                is EgoTrigger.Continuation -> {
                    val continuation = trigger.continuation
                    val plan = continuation.planContext
                    base.copy(
                        originSource = continuation.origin.source.name.lowercase(Locale.ROOT),
                        needId = continuation.origin.needId,
                        rootImpulseId = continuation.origin.rootImpulseId,
                        thoughtId = continuation.queueId,
                        planId = plan?.planId,
                        planStepIndex = plan?.stepIndex,
                        planTotalSteps = plan?.totalSteps,
                        planStepDescription = plan?.stepDescription,
                    )
                }
                is EgoTrigger.Assignment -> base.copy(originSource = OriginSource.ASSIGNMENT.name.lowercase(Locale.ROOT))
            }
        }
    }
}
