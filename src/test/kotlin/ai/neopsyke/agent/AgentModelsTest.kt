package ai.neopsyke.agent

import ai.neopsyke.agent.config.MetaReasonerConfig
import ai.neopsyke.agent.config.SuperegoConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentModelsTest {
    @Test
    fun `agent config defaults to zero loop delay`() {
        val config = AgentConfig()
        assertEquals(0, config.loopDelayMs)
        assertEquals(180, config.planner.maxLoopStepsPerInput)
        assertEquals(2, config.llmRetryAttempts)
        assertEquals(0, config.planner.maxRunTotalTokens)
        assertEquals(0, config.planner.maxRunTokensPerProvider)
        assertEquals(0, config.planner.maxRunTokensPerRole)
        assertEquals(SuperegoConfig.DEFAULT_MAX_COMPLETION_TOKENS, config.superego.maxCompletionTokens)
        assertEquals(false, config.superego.twoStageReviewEnabled)
        assertEquals(0.60, config.superego.twoStageLowConfidenceThreshold)
        assertEquals(true, config.superego.twoStageEscalateOnMediumPolicyRisk)
        assertEquals(2048, config.memory.longTermMemoryMaxTokens)
        assertEquals(true, config.memory.longTermMemoryPromptCompressionEnabled)
        assertEquals(1100, config.memory.longTermMemoryPromptDialogueMaxChars)
        assertEquals(900, config.memory.longTermMemoryPromptRecallMaxChars)
        assertEquals(MetaReasonerConfig.DEFAULT_MAX_TOKENS, config.metaReasoner.maxTokens)
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
        assertEquals(ActionType.CONTACT_USER, ActionType.fromRaw("contact_user"))
        assertEquals(ActionType.WEBSITE_FETCH, ActionType.fromRaw("website_fetch"))
        assertEquals(ActionType("search"), ActionType.fromRaw("search"))
        assertNull(ActionType.fromRaw(null))
    }

    @Test
    fun `action effect parser maps snake case values`() {
        assertEquals(ActionEffect.TASK_PROGRESS, ActionEffect.fromRaw("task_progress"))
        assertEquals(ActionEffect.DURABLE_MEMORY_SAVED, ActionEffect.fromRaw("durable_memory_saved"))
        assertNull(ActionEffect.fromRaw("not_real"))
    }
}
