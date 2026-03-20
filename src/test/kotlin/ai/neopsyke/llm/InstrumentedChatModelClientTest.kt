package ai.neopsyke.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InstrumentedChatModelClientTest {
    @Test
    fun `chat delegates and emits success hook`() {
        val delegate = object : ChatModelClient {
            override val modelName: String = "stub"
            override fun chat(messages: List<ChatMessage>, options: ChatRequestOptions): ChatCompletion =
                ChatCompletion(content = "ok", model = modelName)
        }
        var successCount = 0
        var observedActor: String? = null
        val client = InstrumentedChatModelClient(
            delegate = delegate,
            hooks = listOf(
                object : ChatModelHook {
                    override fun onSuccess(
                        messages: List<ChatMessage>,
                        options: ChatRequestOptions,
                        completion: ChatCompletion,
                    ) {
                        successCount += 1
                        observedActor = options.metadata.actor
                        assertEquals("ok", completion.content)
                    }
                }
            )
        )

        val completion = client.chat(
            messages = listOf(ChatMessage(ChatRole.USER, "hello")),
            options = ChatRequestOptions(metadata = ChatCallMetadata(actor = "ego"))
        )

        assertEquals("ok", completion.content)
        assertEquals(1, successCount)
        assertEquals("ego", observedActor)
    }

    @Test
    fun `chat emits error hook and keeps failures non-blocking`() {
        val delegate = object : ChatModelClient {
            override val modelName: String = "stub"
            override fun chat(messages: List<ChatMessage>, options: ChatRequestOptions): ChatCompletion {
                throw IllegalStateException("boom")
            }
        }
        var errorCount = 0
        val client = InstrumentedChatModelClient(
            delegate = delegate,
            hooks = listOf(
                object : ChatModelHook {
                    override fun onError(
                        messages: List<ChatMessage>,
                        options: ChatRequestOptions,
                        error: Exception,
                    ) {
                        errorCount += 1
                        throw IllegalArgumentException("hook failure")
                    }
                }
            )
        )

        val ex = assertFailsWith<IllegalStateException> {
            client.chat(messages = listOf(ChatMessage(ChatRole.USER, "hello")))
        }

        assertTrue(ex.message.orEmpty().contains("boom"))
        assertEquals(1, errorCount)
    }
}
