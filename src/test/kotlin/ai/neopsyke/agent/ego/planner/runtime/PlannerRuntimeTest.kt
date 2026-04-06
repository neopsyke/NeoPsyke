package ai.neopsyke.agent.ego.planner.runtime

import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.config.PlannerConfig
import ai.neopsyke.agent.ego.planner.LaneConfig
import ai.neopsyke.agent.ego.planner.LaneId
import ai.neopsyke.agent.ego.planner.StructuredOutputMode
import ai.neopsyke.instrumentation.NoopAgentInstrumentation
import ai.neopsyke.llm.ChatCallMetadata
import ai.neopsyke.llm.ChatCompletion
import ai.neopsyke.llm.ChatMessage
import ai.neopsyke.llm.ChatModelClient
import ai.neopsyke.llm.ChatRequestOptions
import ai.neopsyke.llm.ChatResponseFormat
import ai.neopsyke.llm.ChatRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PlannerRuntimeTest {

    @Test
    fun `resolvedConfig applies lane defaults and per-lane overrides`() {
        val runtime = PlannerRuntime(
            defaultModelClient = CountingClient("default"),
            config = AgentConfig(
                llmRetryAttempts = 4,
                planner = PlannerConfig(
                    laneDefaults = LaneConfig(
                        provider = "openai",
                        model = "gpt-4.1-mini",
                        temperature = 0.25,
                        maxCompletionTokens = 480,
                        retryAttempts = 5,
                        structuredOutput = StructuredOutputMode.RELAXED,
                    ),
                    lanes = mapOf(
                        LaneId.GOAL_CREATION.configKey to LaneConfig(
                            provider = "mistral",
                            model = "mistral-small-latest",
                            temperature = 0.0,
                            maxCompletionTokens = 220,
                        )
                    ),
                ),
            ),
            instrumentation = NoopAgentInstrumentation,
        )

        val resolved = runtime.resolvedConfig(LaneId.GOAL_CREATION)
        assertEquals("mistral", resolved.provider)
        assertEquals("mistral-small-latest", resolved.model)
        assertEquals(0.0, resolved.temperature)
        assertEquals(220, resolved.maxCompletionTokens)
        assertEquals(5, resolved.retryAttempts)
        assertEquals(StructuredOutputMode.RELAXED, resolved.structuredOutput)
    }

    @Test
    fun `call uses lane model client resolver when lane selects different provider or model`() {
        val defaultClient = CountingClient("default-model")
        val laneClient = CountingClient("lane-model")
        val runtime = PlannerRuntime(
            defaultModelClient = defaultClient,
            config = AgentConfig(
                planner = PlannerConfig(
                    laneDefaults = LaneConfig(provider = "openai", model = "gpt-default"),
                    lanes = mapOf(
                        LaneId.INPUT_INTENT_ROUTER.configKey to LaneConfig(
                            provider = "mistral",
                            model = "mistral-small-latest",
                        )
                    ),
                ),
            ),
            instrumentation = NoopAgentInstrumentation,
            laneModelClientResolver = { laneId, _ ->
                if (laneId == LaneId.INPUT_INTENT_ROUTER) laneClient else null
            },
        )

        val completion = runtime.call(
            laneId = LaneId.INPUT_INTENT_ROUTER,
            messages = listOf(ChatMessage(ChatRole.USER, "route this input")),
            metadata = ChatCallMetadata(callSite = "runtime_test"),
            responseFormat = TEST_FORMAT,
        )

        assertNotNull(completion)
        assertEquals("lane-model", completion.model)
        assertEquals(0, defaultClient.calls)
        assertEquals(1, laneClient.calls)
    }

    private class CountingClient(
        override val modelName: String,
    ) : ChatModelClient {
        var calls: Int = 0

        override fun chat(messages: List<ChatMessage>, options: ChatRequestOptions): ChatCompletion {
            calls += 1
            return ChatCompletion(
                content = """{"decision":"noop","reason":"ok"}""",
                model = modelName,
            )
        }
    }

    private companion object {
        val TEST_FORMAT = ChatResponseFormat.JsonSchema(
            name = "planner_runtime_test",
            schemaJson = """
                {
                  "type": "object",
                  "additionalProperties": false,
                  "required": ["decision", "reason"],
                  "properties": {
                    "decision": { "type": "string" },
                    "reason": { "type": "string" }
                  }
                }
            """.trimIndent(),
            strict = true,
        )
    }
}
