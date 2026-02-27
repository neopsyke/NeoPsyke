package psyke.agent

import java.util.PriorityQueue

class AttentionScheduler(
    private val config: AgentConfig,
) {
    private var idCounter = 0L
    private val inputs = ArrayDeque<PendingInput>()
    private val thoughts = PriorityQueue<PendingThought>(thoughtComparator)
    private val actions = PriorityQueue<PendingAction>(actionComparator)

    fun enqueueInput(content: String): Boolean {
        if (inputs.size >= config.maxPendingInputs) {
            return false
        }
        inputs.addLast(PendingInput(id = nextId(), content = content))
        return true
    }

    fun enqueueThought(content: String, urgency: Urgency, passes: Int = 0): Boolean {
        if (thoughts.size >= config.maxPendingThoughts) {
            return false
        }
        thoughts.add(
            PendingThought(
                id = nextId(),
                urgency = urgency,
                content = content,
                passes = passes
            )
        )
        return true
    }

    fun enqueueAction(
        type: ActionType,
        payload: String,
        summary: String,
        urgency: Urgency,
        attempts: Int = 0,
    ): Boolean {
        if (actions.size >= config.maxPendingActions) {
            return false
        }
        actions.add(
            PendingAction(
                id = nextId(),
                urgency = urgency,
                type = type,
                payload = payload,
                summary = summary,
                attempts = attempts
            )
        )
        return true
    }

    fun nextTask(): LoopTask? {
        val input = inputs.removeFirstOrNull()
        if (input != null) {
            return LoopTask.ProcessInput(input)
        }

        val topAction = actions.peek()
        val topThought = thoughts.peek()
        if (topAction == null && topThought == null) {
            return null
        }
        if (topAction == null) {
            return LoopTask.ProcessThought(thoughts.remove())
        }
        if (topThought == null) {
            return LoopTask.PerformAction(actions.remove())
        }

        return if (topAction.urgency.priority >= topThought.urgency.priority) {
            LoopTask.PerformAction(actions.remove())
        } else {
            LoopTask.ProcessThought(thoughts.remove())
        }
    }

    fun hasPendingWork(): Boolean = inputs.isNotEmpty() || thoughts.isNotEmpty() || actions.isNotEmpty()

    fun snapshot(dialogue: List<DialogueTurn>): AgentSnapshot =
        AgentSnapshot(
            recentDialogue = dialogue.takeLast(12),
            pendingInputCount = inputs.size,
            pendingThoughtCount = thoughts.size,
            pendingActionCount = actions.size
        )

    private fun nextId(): Long {
        idCounter += 1
        return idCounter
    }

    private companion object {
        val thoughtComparator = compareByDescending<PendingThought> { it.urgency.priority }
            .thenBy { it.id }

        val actionComparator = compareByDescending<PendingAction> { it.urgency.priority }
            .thenBy { it.id }
    }
}
