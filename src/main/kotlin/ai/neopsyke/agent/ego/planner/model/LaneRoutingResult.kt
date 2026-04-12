package ai.neopsyke.agent.ego.planner.model

import ai.neopsyke.agent.ego.planner.LaneId
import ai.neopsyke.agent.model.EgoDecision

/**
 * Typed re-routing result for lane-to-lane handoff.
 *
 * Satisfies the spec requirement: "A lane may itself return another typed
 * routing result when the next step is semantically ambiguous."
 *
 * In practice, InputPlanner uses this via InputIntentRouter -> sub-planner
 * dispatch. Other L1 lanes may use Reroute in the future if their LLM call
 * determines the trigger was mis-classified. For now, only InputPlanner
 * actively uses the re-routing path; other L1 lanes return Resolved only.
 */
sealed interface LaneRoutingResult {

    /** Lane produced a final decision. */
    data class Resolved(val decision: EgoDecision) : LaneRoutingResult

    /** Lane determined the next step is ambiguous and another lane should handle it. */
    data class Reroute(
        val targetLane: LaneId,
        val context: Map<String, Any> = emptyMap(),
    ) : LaneRoutingResult
}
