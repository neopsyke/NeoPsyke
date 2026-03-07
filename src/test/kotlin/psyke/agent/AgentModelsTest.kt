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
