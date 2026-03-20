package ai.neopsyke.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdaptiveStructuredOutputChatClientTest {
    @Test
    fun `classifier normalizes groq tool and json validation failures`() {
        val toolUse = IllegalStateException(
            """{"error":{"message":"Tool choice is none, but model called a tool","code":"tool_use_failed"}}"""
        )
        val jsonValidation = IllegalStateException(
            """{"error":{"message":"Failed to validate JSON","code":"json_validate_failed"}}"""
        )

        assertEquals(StructuredOutputFailureKind.COMPATIBILITY, StructuredOutputFailureClassifier.classify(toolUse))
        assertEquals(StructuredOutputFailureKind.COMPATIBILITY, StructuredOutputFailureClassifier.classify(jsonValidation))
    }

    @Test
    fun `adaptive client downgrades to relaxed mode and keeps sticky mode for later thought calls`() {
        val observedOptions = mutableListOf<ChatRequestOptions>()
        val delegate = object : ChatModelClient {
            override val modelName: String = "openai/gpt-oss-120b"
            private var calls = 0

            override fun chat(messages: List<ChatMessage>, options: ChatRequestOptions): ChatCompletion {
                observedOptions += options
                calls += 1
                if (calls == 1) {
                    throw IllegalStateException(
                        """{"error":{"message":"Tool choice is none, but model called a tool","code":"tool_use_failed"}}"""
                    )
                }
                return ChatCompletion(
                    content = """{"decision":"noop","reason":"ok"}""",
                    model = modelName
                )
            }
        }
        val client = AdaptiveStructuredOutputChatClient(delegate = delegate, provider = "groq")
        val format = ChatResponseFormat.JsonSchema(
            name = "ego_planner_decision",
            schemaJson = """{"type":"object","properties":{"decision":{"type":"string","maxLength":20}}}""",
            strict = true,
            relaxedSchemaJson = """{"type":"object","properties":{"decision":{"type":"string"}}}"""
        )
        val options = ChatRequestOptions(
            responseFormat = format,
            metadata = ChatCallMetadata(actor = "ego", callSite = "thought")
        )

        client.chat(messages = listOf(ChatMessage(ChatRole.USER, "hello")), options = options)
        client.chat(messages = listOf(ChatMessage(ChatRole.USER, "hello again")), options = options)

        assertEquals(3, observedOptions.size)
        val first = observedOptions[0].responseFormat as ChatResponseFormat.JsonSchema
        val second = observedOptions[1].responseFormat as ChatResponseFormat.JsonSchema
        val third = observedOptions[2].responseFormat as ChatResponseFormat.JsonSchema
        assertTrue(first.strict)
        assertTrue(first.schemaJson.contains("maxLength"))
        assertFalse(second.strict)
        assertFalse(second.schemaJson.contains("maxLength"))
        assertFalse(third.strict)
        assertFalse(third.schemaJson.contains("maxLength"))
    }

    @Test
    fun `adaptive client falls back to prompt only json after strict and relaxed compatibility failures`() {
        val observedOptions = mutableListOf<ChatRequestOptions>()
        val observedMessages = mutableListOf<List<ChatMessage>>()
        val delegate = object : ChatModelClient {
            override val modelName: String = "openai/gpt-oss-120b"
            private var calls = 0

            override fun chat(messages: List<ChatMessage>, options: ChatRequestOptions): ChatCompletion {
                observedMessages += messages
                observedOptions += options
                calls += 1
                if (calls <= 2) {
                    throw IllegalStateException(
                        """{"error":{"message":"Failed to validate JSON","code":"json_validate_failed"}}"""
                    )
                }
                return ChatCompletion(
                    content = """{"decision":"noop","reason":"prompt only recovered"}""",
                    model = modelName
                )
            }
        }
        val client = AdaptiveStructuredOutputChatClient(delegate = delegate, provider = "groq")
        val format = ChatResponseFormat.JsonSchema(
            name = "ego_planner_decision",
            schemaJson = """{"type":"object","properties":{"decision":{"type":"string","maxLength":20}}}""",
            strict = true,
            relaxedSchemaJson = """{"type":"object","properties":{"decision":{"type":"string"}}}"""
        )

        val completion = client.chat(
            messages = listOf(ChatMessage(ChatRole.USER, "hello")),
            options = ChatRequestOptions(
                responseFormat = format,
                metadata = ChatCallMetadata(actor = "ego", callSite = "thought")
            )
        )

        assertTrue(completion.content.contains("prompt only recovered"))
        assertEquals(3, observedOptions.size)
        assertTrue(observedOptions[0].responseFormat is ChatResponseFormat.JsonSchema)
        assertTrue(observedOptions[1].responseFormat is ChatResponseFormat.JsonSchema)
        assertNull(observedOptions[2].responseFormat)
        assertTrue(observedMessages[2].last().content.contains("raw JSON object", ignoreCase = true))
    }

    @Test
    fun `adaptive client surfaces normalized compatibility failure after exhausting modes`() {
        val delegate = object : ChatModelClient {
            override val modelName: String = "openai/gpt-oss-120b"

            override fun chat(messages: List<ChatMessage>, options: ChatRequestOptions): ChatCompletion {
                throw IllegalStateException(
                    """{"error":{"message":"Tool choice is none, but model called a tool","code":"tool_use_failed"}}"""
                )
            }
        }
        val client = AdaptiveStructuredOutputChatClient(delegate = delegate, provider = "groq")
        val format = ChatResponseFormat.JsonSchema(
            name = "ego_planner_decision",
            schemaJson = """{"type":"object"}""",
            strict = true,
            relaxedSchemaJson = """{"type":"object"}"""
        )

        val ex = assertFailsWith<StructuredOutputCompatibilityFailureException> {
            client.chat(
                messages = listOf(ChatMessage(ChatRole.USER, "hello")),
                options = ChatRequestOptions(
                    responseFormat = format,
                    metadata = ChatCallMetadata(actor = "ego", callSite = "thought")
                )
            )
        }

        assertTrue(ex.message.orEmpty().contains("compatibility failure", ignoreCase = true))
    }
}
