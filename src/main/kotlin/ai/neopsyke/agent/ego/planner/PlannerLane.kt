package ai.neopsyke.agent.ego.planner

import ai.neopsyke.agent.model.EgoDecision
import ai.neopsyke.agent.model.EgoTrigger
import ai.neopsyke.agent.model.PlannerContext

/**
 * Contract for a typed planner lane. Each L1 lane returns EgoDecision
 * directly (decision D7). L2 sub-planners are internal to their L1 lane
 * and do not implement this interface.
 */
interface PlannerLane {
    val laneId: LaneId
    fun plan(trigger: EgoTrigger, context: PlannerContext): EgoDecision
}
