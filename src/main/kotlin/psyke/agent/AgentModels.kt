package psyke.agent

enum class Urgency(val priority: Int) {
    LOW(1),
    MEDIUM(2),
    HIGH(3);

    companion object {
        fun fromRaw(value: String?): Urgency =
            when (value?.trim()?.lowercase()) {
                "high" -> HIGH
                "low" -> LOW
                else -> MEDIUM
            }
    }
}

enum class ActionType {
    WEB_SEARCH,
    ANSWER;

    companion object {
        fun fromRaw(value: String?): ActionType? =
            when (value?.trim()?.lowercase()) {
                "web_search" -> WEB_SEARCH
                "answer" -> ANSWER
                else -> null
            }
    }
}

data class AgentConfig(
    val maxLoopStepsPerInput: Int = 18,
    val maxThoughtPasses: Int = 5,
    val maxPendingThoughts: Int = 64,
    val maxPendingActions: Int = 32,
    val maxPendingInputs: Int = 32,
    val maxInputChars: Int = 2_000,
    val maxThoughtChars: Int = 600,
    val maxActionPayloadChars: Int = 1_000,
    val maxActionSummaryChars: Int = 180,
    val maxPromptTokens: Int = 2_400,
    val maxCompletionTokens: Int = 300,
    val searchResultCount: Int = 5
) {
    companion object {
        fun fromEnv(): AgentConfig =
            AgentConfig(
                maxLoopStepsPerInput = readInt("EGO_MAX_LOOP_STEPS", 18),
                maxThoughtPasses = readInt("EGO_MAX_THOUGHT_PASSES", 5),
                maxPromptTokens = readInt("EGO_MAX_PROMPT_TOKENS", 2400),
                maxCompletionTokens = readInt("EGO_MAX_COMPLETION_TOKENS", 300),
                searchResultCount = readInt("EGO_SEARCH_RESULT_COUNT", 5)
            )

        private fun readInt(name: String, fallback: Int): Int =
            System.getenv(name)?.toIntOrNull()?.takeIf { it > 0 } ?: fallback
    }
}

data class PendingInput(
    val id: Long,
    val content: String,
)

data class PendingThought(
    val id: Long,
    val urgency: Urgency,
    val content: String,
    val passes: Int = 0,
)

data class PendingAction(
    val id: Long,
    val urgency: Urgency,
    val type: ActionType,
    val payload: String,
    val summary: String,
    val attempts: Int = 0,
)

enum class DialogueRole {
    USER,
    ASSISTANT
}

data class DialogueTurn(
    val role: DialogueRole,
    val content: String,
)

data class AgentSnapshot(
    val recentDialogue: List<DialogueTurn>,
    val pendingInputCount: Int,
    val pendingThoughtCount: Int,
    val pendingActionCount: Int,
)

sealed interface LoopTask {
    data class ProcessInput(val item: PendingInput) : LoopTask
    data class ProcessThought(val item: PendingThought) : LoopTask
    data class PerformAction(val item: PendingAction) : LoopTask
}

sealed interface EgoTrigger {
    data class IncomingInput(val input: PendingInput) : EgoTrigger
    data class PendingThoughtInput(val thought: PendingThought) : EgoTrigger
}

sealed interface EgoDecision {
    data class EnqueueThought(val urgency: Urgency, val content: String) : EgoDecision
    data class ProposeAction(
        val urgency: Urgency,
        val actionType: ActionType,
        val payload: String,
        val summary: String,
    ) : EgoDecision
    data class Noop(val reason: String) : EgoDecision
}

data class GateDecision(
    val allow: Boolean,
    val reason: String,
)

data class ActionOutcome(
    val statusSummary: String,
    val assistantOutput: String? = null,
)
