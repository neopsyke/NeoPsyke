package psyke.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentModelsTest {
    @Test
    fun `agent config defaults to zero loop delay`() {
        val config = AgentConfig()
        assertEquals(0, config.loopDelayMs)
        assertEquals(180, config.planner.maxLoopStepsPerInput)
        assertEquals(2, config.planner.llmRetryAttempts)
        assertEquals(0, config.planner.maxRunTotalTokens)
        assertEquals(0, config.planner.maxRunTokensPerProvider)
        assertEquals(0, config.planner.maxRunTokensPerRole)
        assertEquals(192, config.superego.maxCompletionTokens)
        assertEquals(true, config.superego.dynamicCompletionEnabled)
        assertEquals(640, config.superego.dynamicCompletionHardMaxTokens)
        assertEquals(0.10, config.superego.dynamicPromptToCompletionRatio)
        assertEquals(160, config.superego.dynamicCompletionMinPromptTokens)
        assertEquals(false, config.superego.twoStageReviewEnabled)
        assertEquals(0.60, config.superego.twoStageLowConfidenceThreshold)
        assertEquals(true, config.superego.twoStageEscalateOnMediumPolicyRisk)
        assertEquals(320, config.memory.longTermMemoryMaxTokens)
        assertEquals(true, config.memory.longTermMemoryPromptCompressionEnabled)
        assertEquals(1100, config.memory.longTermMemoryPromptDialogueMaxChars)
        assertEquals(900, config.memory.longTermMemoryPromptRecallMaxChars)
        assertEquals(true, config.memory.longTermMemoryDynamicCompletionEnabled)
        assertEquals(512, config.memory.longTermMemoryDynamicCompletionHardMaxTokens)
        assertEquals(0.08, config.memory.longTermMemoryDynamicPromptToCompletionRatio)
        assertEquals(160, config.memory.longTermMemoryDynamicCompletionMinPromptTokens)
        assertEquals(384, config.metaReasoner.maxTokens)
        assertEquals(true, config.metaReasoner.dynamicCompletionEnabled)
        assertEquals(640, config.metaReasoner.dynamicCompletionHardMaxTokens)
        assertEquals(0.10, config.metaReasoner.dynamicPromptToCompletionRatio)
        assertEquals(160, config.metaReasoner.dynamicCompletionMinPromptTokens)
        assertEquals(0.98, config.metaReasoner.forcedTerminalPressureThreshold)
        assertEquals(8, config.metaReasoner.forcedTerminalStaleStreakThreshold)
    }

    @Test
    fun `urgency parser maps known values and defaults to medium`() {
        assertEquals(Urgency.HIGH, Urgency.fromRaw("high"))
        assertEquals(Urgency.LOW, Urgency.fromRaw("LOW"))
        assertEquals(Urgency.MEDIUM, Urgency.fromRaw("unknown"))
        assertEquals(Urgency.MEDIUM, Urgency.fromRaw(null))
    }

    @Test
    fun `action type parser maps valid values only`() {
        assertEquals(ActionType.WEB_SEARCH, ActionType.fromRaw("web_search"))
        assertEquals(ActionType.ANSWER, ActionType.fromRaw("answer"))
        assertEquals(ActionType.MCP_TIME, ActionType.fromRaw("mcp_time"))
        assertEquals(ActionType.MCP_FETCH, ActionType.fromRaw("mcp_fetch"))
        assertNull(ActionType.fromRaw("search"))
        assertNull(ActionType.fromRaw(null))
    }
}
