package ai.neopsyke

import ai.neopsyke.agent.ego.planner.LaneId
import ai.neopsyke.agent.ego.planner.ResolvedLaneConfig
import ai.neopsyke.agent.ego.planner.StructuredOutputMode
import ai.neopsyke.config.LlmCognitiveRolesConfig
import ai.neopsyke.config.LlmEndpointConfig
import ai.neopsyke.config.LlmProvider
import ai.neopsyke.config.LlmRuntimeConfig
import ai.neopsyke.llm.ChatMessage
import ai.neopsyke.llm.ChatRequestOptions
import ai.neopsyke.llm.ChatRole
import ai.neopsyke.support.StubChatModelClient
import kotlin.test.Test
import kotlin.test.assertEquals

class AppModeRunnersReasoningEffortTest {
    @Test
    fun `same-model planner lane applies lane-specific reasoning effort`() {
        val plannerEndpoint = endpoint(model = "openai/gpt-oss-120b", reasoningEffort = "medium")
        val laneEndpoint = endpoint(model = "openai/gpt-oss-120b", reasoningEffort = "low")
        val llm = runtimeConfig(
            plannerEndpoint = plannerEndpoint,
            lanes = mapOf(LaneId.INPUT_INTENT_ROUTER.configKey to laneEndpoint),
        )
        val plannerClient = StubChatModelClient().apply { enqueueRawResponse("""{"ok":true}""") }
        var createdLaneClientCount = 0
        val resolver = buildPlannerLaneModelClientResolver(
            llm = llm,
            plannerClient = plannerClient,
            createPlannerClient = {
                createdLaneClientCount++
                StubChatModelClient().apply { enqueueRawResponse("""{"ok":true}""") }
            },
        )

        val client = resolver(LaneId.INPUT_INTENT_ROUTER, defaultResolvedLane())!!
        client.chat(
            messages = listOf(ChatMessage(ChatRole.USER, "classify this")),
            options = ChatRequestOptions(),
        )

        assertEquals("low", plannerClient.lastOptions.reasoningEffort)
        assertEquals(0, createdLaneClientCount)
    }

    private fun endpoint(
        provider: LlmProvider = LlmProvider.GROQ,
        model: String = "openai/gpt-oss-120b",
        reasoningEffort: String? = null,
    ): LlmEndpointConfig =
        LlmEndpointConfig(
            provider = provider,
            apiKey = "test-key",
            apiKeyEnvVar = "${provider.id.uppercase()}_API_KEY",
            baseUrl = "https://example.invalid/${provider.id}",
            model = model,
            reasoningEffort = reasoningEffort,
        )

    private fun runtimeConfig(
        plannerEndpoint: LlmEndpointConfig,
        lanes: Map<String, LlmEndpointConfig>,
    ): LlmRuntimeConfig =
        LlmRuntimeConfig(
            cognitiveRoles = LlmCognitiveRolesConfig(
                planner = plannerEndpoint,
                superego = endpoint(reasoningEffort = "low"),
                metaReasoner = endpoint(reasoningEffort = "medium"),
                memoryAdvisor = endpoint(reasoningEffort = "low"),
                approvalInterpreter = endpoint(provider = LlmProvider.OPENAI, model = "gpt-5-nano", reasoningEffort = "low"),
                plannerLanes = lanes,
            ),
            webSearch = endpoint(model = "groq/compound-mini"),
        )

    private fun defaultResolvedLane(): ResolvedLaneConfig =
        ResolvedLaneConfig(
            provider = null,
            model = null,
            temperature = ResolvedLaneConfig.DEFAULT_TEMPERATURE,
            maxCompletionTokens = ResolvedLaneConfig.DEFAULT_MAX_COMPLETION_TOKENS,
            retryAttempts = ResolvedLaneConfig.DEFAULT_RETRY_ATTEMPTS,
            structuredOutput = StructuredOutputMode.STRICT,
        )
}
