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
    ANSWER,
    MCP_TIME,
    MCP_FETCH;

    companion object {
        fun fromRaw(value: String?): ActionType? =
            when (value?.trim()?.lowercase()) {
                "web_search" -> WEB_SEARCH
                "answer" -> ANSWER
                "mcp_time" -> MCP_TIME
                "mcp_fetch" -> MCP_FETCH
                else -> null
            }
    }
}

enum class InputPriority(val level: Int) {
    LOW(1),
    MEDIUM(2),
    HIGH(3);

    companion object {
        fun fromRaw(value: String?): InputPriority =
            when (value?.trim()?.lowercase()) {
                "high", "3" -> HIGH
                "low", "1" -> LOW
                else -> MEDIUM
            }
    }
}

data class AgentConfig(
    val maxLoopStepsPerInput: Int = 180,
    val loopDelayMs: Int = 0,
    val maxThoughtPasses: Int = 5,
    val maxPendingThoughts: Int = 64,
    val maxPendingActions: Int = 32,
    val maxPendingInputs: Int = 32,
    val maxInputChars: Int = 2_000,
    val maxMemoryChars: Int = 12_000,
    val maxMemoryPromptTokens: Int = 256,
    val maxThoughtChars: Int = 600,
    val maxActionPayloadChars: Int = 4_000,
    val maxActionSummaryChars: Int = 180,
    val maxPromptTokens: Int = 2_400,
    val maxCompletionTokens: Int = 900, 
    val searchResultCount: Int = 5,
    val mcpCallTimeoutMs: Long = 8_000,
    val mcpFetchMaxChars: Int = 4_000,
    val mcpMemoryCallTimeoutMs: Long = 8_000,
    val memoryRecallMaxItems: Int = 4,
    val memoryRecallMaxChars: Int = 1_200,
    val deliberationPressureAssessmentMinStep: Int = 16,
    val deliberationPressureAssessmentEverySteps: Int = 8,
    val deliberationPressureAssessmentThreshold: Double = 0.68,
    val metaReasonerCooldownSteps: Int = 6,
    val metaReasonerMaxTokens: Int = 120,
    val memoryConsolidationEverySteps: Int = 8,
    val memoryConsolidationCooldownSteps: Int = 4,
    val memoryConsolidationMinConfidence: Double = 0.65,
    val memoryConsolidationMaxTokens: Int = 180,
    val memoryConsolidationMaxSummaryChars: Int = 320
) {
    companion object {
        fun fromEnv(): AgentConfig =
            AgentConfig.fromResolvedEnv()

        private fun fromResolvedEnv(): AgentConfig {
            val mcpCallTimeoutMs = readLong("MCP_CALL_TIMEOUT_MS", 8000)
            return AgentConfig(
                maxLoopStepsPerInput = readInt("EGO_MAX_LOOP_STEPS", 180),
                loopDelayMs = readNonNegativeInt("EGO_LOOP_DELAY_MS", 0),
                maxThoughtPasses = readInt("EGO_MAX_THOUGHT_PASSES", 5),
                maxMemoryChars = readInt("EGO_MAX_MEMORY_CHARS", 12000),
                maxMemoryPromptTokens = readInt("EGO_MAX_MEMORY_PROMPT_TOKENS", 256),
                maxActionPayloadChars = readInt("EGO_MAX_ACTION_PAYLOAD_CHARS", 4000),
                maxPromptTokens = readInt("EGO_MAX_PROMPT_TOKENS", 2400),
                maxCompletionTokens = readInt("EGO_MAX_COMPLETION_TOKENS", 900),
                searchResultCount = readInt("EGO_SEARCH_RESULT_COUNT", 5),
                mcpCallTimeoutMs = mcpCallTimeoutMs,
                mcpFetchMaxChars = readInt("MCP_FETCH_MAX_CHARS", 4000),
                mcpMemoryCallTimeoutMs = readLong("MCP_MEMORY_CALL_TIMEOUT_MS", mcpCallTimeoutMs),
                memoryRecallMaxItems = readInt("EGO_MEMORY_RECALL_MAX_ITEMS", 4),
                memoryRecallMaxChars = readInt("EGO_MEMORY_RECALL_MAX_CHARS", 1200),
                deliberationPressureAssessmentMinStep = readInt("EGO_PRESSURE_MIN_STEP", 16),
                deliberationPressureAssessmentEverySteps = readInt("EGO_PRESSURE_ASSESS_EVERY_STEPS", 8),
                deliberationPressureAssessmentThreshold = readDouble("EGO_PRESSURE_ASSESS_THRESHOLD", 0.68),
                metaReasonerCooldownSteps = readInt("EGO_META_REASONER_COOLDOWN_STEPS", 6),
                metaReasonerMaxTokens = readInt("EGO_META_REASONER_MAX_TOKENS", 120),
                memoryConsolidationEverySteps = readInt("EGO_MEMORY_CONSOLIDATION_EVERY_STEPS", 8),
                memoryConsolidationCooldownSteps = readInt("EGO_MEMORY_CONSOLIDATION_COOLDOWN_STEPS", 4),
                memoryConsolidationMinConfidence = readDouble("EGO_MEMORY_CONSOLIDATION_MIN_CONFIDENCE", 0.65),
                memoryConsolidationMaxTokens = readInt("EGO_MEMORY_CONSOLIDATION_MAX_TOKENS", 180),
                memoryConsolidationMaxSummaryChars = readInt("EGO_MEMORY_CONSOLIDATION_MAX_SUMMARY_CHARS", 320)
            )
        }

        private fun readInt(name: String, fallback: Int): Int =
            System.getenv(name)?.toIntOrNull()?.takeIf { it > 0 } ?: fallback

        private fun readNonNegativeInt(name: String, fallback: Int): Int =
            System.getenv(name)?.toIntOrNull()?.takeIf { it >= 0 } ?: fallback

        private fun readLong(name: String, fallback: Long): Long =
            System.getenv(name)?.toLongOrNull()?.takeIf { it > 0 } ?: fallback

        private fun readDouble(name: String, fallback: Double): Double =
            System.getenv(name)?.toDoubleOrNull()?.takeIf { it in 0.0..1.0 } ?: fallback
    }
}

data class PendingInput(
    val id: Long,
    val content: String,
    val priority: InputPriority = InputPriority.MEDIUM,
)

data class PendingThought(
    val id: Long,
    val urgency: Urgency,
    val content: String,
    val passes: Int = 0,
    val deniedActionType: ActionType? = null,
    val deniedActionPayload: String? = null,
    val denialReason: String? = null,
    val allowFallbackExplanation: Boolean = false,
)

data class PendingAction(
    val id: Long,
    val urgency: Urgency,
    val type: ActionType,
    val payload: String,
    val summary: String,
    val attempts: Int = 0,
    val isFallbackExplanation: Boolean = false,
)

data class QueueState(
    val inputs: List<PendingInput>,
    val thoughts: List<PendingThought>,
    val actions: List<PendingAction>,
)

enum class DialogueRole {
    USER,
    ASSISTANT
}

data class DialogueTurn(
    val role: DialogueRole,
    val content: String,
)

data class QueueSnapshot(
    val pendingInputCount: Int,
    val pendingThoughtCount: Int,
    val pendingActionCount: Int,
)

data class PlannerContext(
    val recentDialogue: List<DialogueTurn>,
    val queue: QueueSnapshot,
    val memorySummary: String = "",
    val memoryRecall: String = "",
    val deliberation: DeliberationState = DeliberationState(),
    val metaGuidance: String = "",
    val availableActions: Set<ActionType> = ActionType.entries.toSet(),
)

data class SuperegoContext(
    val recentDialogue: List<DialogueTurn>,
    val memorySummary: String = "",
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

data class DeliberationState(
    val stepIndex: Int = 0,
    val decisionPressure: Double = 0.0,
    val staleStreak: Int = 0,
    val progressScore: Double = 0.0,
    val denialCount: Int = 0,
    val stepsSinceNewEvidence: Int = 0,
    val repeatSignatureHits: Int = 0,
    val noopStreak: Int = 0,
)
