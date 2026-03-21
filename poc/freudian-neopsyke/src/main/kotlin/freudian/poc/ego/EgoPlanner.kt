package freudian.poc.ego

import freudian.poc.config.EgoConfig
import freudian.poc.model.ActionProposal
import freudian.poc.model.ActionType
import freudian.poc.model.IdImpulse
import freudian.poc.model.OriginSource
import freudian.poc.model.ThoughtStrategy
import freudian.poc.model.ThoughtTask
import freudian.poc.model.UserRequest
import java.util.UUID

interface EgoPlanner {
    fun planForUserRequest(userRequest: UserRequest): List<ThoughtTask>
    fun planForImpulse(impulse: IdImpulse): List<ThoughtTask>
    fun proposeAction(thoughtTask: ThoughtTask): ActionProposal?
}

class DeterministicEgoPlanner(
    private val config: EgoConfig,
) : EgoPlanner {

    override fun planForUserRequest(userRequest: UserRequest): List<ThoughtTask> = listOf(
        ThoughtTask(
            thoughtId = UUID.randomUUID().toString(),
            rootImpulseId = null,
            needName = null,
            origin = OriginSource.USER,
            content = "Respond to user request: ${userRequest.content}",
            strategy = ThoughtStrategy.USER_REQUEST_BRANCH,
        )
    )

    override fun planForImpulse(impulse: IdImpulse): List<ThoughtTask> {
        val thoughtTasks = mutableListOf<ThoughtTask>()
        if (config.includeNoopThoughtBranch) {
            thoughtTasks += ThoughtTask(
                thoughtId = UUID.randomUUID().toString(),
                rootImpulseId = impulse.rootImpulseId,
                needName = impulse.needName,
                origin = OriginSource.ID,
                content = "Evaluate conservative option for need ${impulse.needName}",
                strategy = ThoughtStrategy.NOOP_BRANCH,
            )
        }

        val executionBranchCount = if (config.parallelThoughtsPerImpulse <= 0) {
            1
        } else {
            config.parallelThoughtsPerImpulse - thoughtTasks.size
        }

        repeat(executionBranchCount.coerceAtLeast(1)) {
            thoughtTasks += ThoughtTask(
                thoughtId = UUID.randomUUID().toString(),
                rootImpulseId = impulse.rootImpulseId,
                needName = impulse.needName,
                origin = OriginSource.ID,
                content = "Evaluate executable option for need ${impulse.needName}",
                strategy = ThoughtStrategy.EXECUTION_BRANCH,
            )
        }

        return thoughtTasks
    }

    override fun proposeAction(thoughtTask: ThoughtTask): ActionProposal? {
        return when (thoughtTask.strategy) {
            ThoughtStrategy.NOOP_BRANCH -> null

            ThoughtStrategy.USER_REQUEST_BRANCH -> ActionProposal(
                actionId = UUID.randomUUID().toString(),
                rootImpulseId = null,
                needName = null,
                origin = thoughtTask.origin,
                type = ActionType.USER_MESSAGE,
                summary = "Deliver direct response to user request.",
                payload = thoughtTask.content.removePrefix("Respond to user request: "),
            )

            ThoughtStrategy.EXECUTION_BRANCH -> {
                val actionType = when (thoughtTask.needName) {
                    "interact_with_user" -> ActionType.USER_MESSAGE
                    "learn_something" -> ActionType.WEB_LOOKUP
                    else -> ActionType.INTERNAL_REFLECTION
                }
                val payload = when (actionType) {
                    ActionType.INTERNAL_REFLECTION -> "Generate an internal plan for need ${thoughtTask.needName}."
                    ActionType.WEB_LOOKUP -> "Research one useful fact related to need ${thoughtTask.needName}."
                    ActionType.USER_MESSAGE -> "Proactively message the user about need ${thoughtTask.needName}."
                }

                ActionProposal(
                    actionId = UUID.randomUUID().toString(),
                    rootImpulseId = thoughtTask.rootImpulseId,
                    needName = thoughtTask.needName,
                    origin = thoughtTask.origin,
                    type = actionType,
                    summary = "Action candidate generated from impulse branch.",
                    payload = payload,
                )
            }
        }
    }
}
