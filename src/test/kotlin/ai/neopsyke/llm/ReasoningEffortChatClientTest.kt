package ai.neopsyke.llm

import ai.neopsyke.support.StubChatModelClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReasoningEffortChatClientTest {
    @Test
    fun `injects default reasoning effort when caller does not specify one`() {
        val stub = StubChatModelClient().apply { enqueueRawResponse("""{"ok":true}""") }
        val wrapped = ReasoningEffortChatClient(stub, defaultEffort = "low")

        wrapped.chat(
            messages = listOf(ChatMessage(ChatRole.USER, "test")),
            options = ChatRequestOptions()
        )

        assertEquals("low", stub.lastOptions.reasoningEffort)
    }

    @Test
    fun `preserves caller-specified reasoning effort over default`() {
        val stub = StubChatModelClient().apply { enqueueRawResponse("""{"ok":true}""") }
        val wrapped = ReasoningEffortChatClient(stub, defaultEffort = "low")

        wrapped.chat(
            messages = listOf(ChatMessage(ChatRole.USER, "test")),
            options = ChatRequestOptions(reasoningEffort = "high")
        )

        assertEquals("high", stub.lastOptions.reasoningEffort)
    }

    @Test
    fun `unwrapped client receives null reasoning effort by default`() {
        val stub = StubChatModelClient().apply { enqueueRawResponse("""{"ok":true}""") }

        stub.chat(
            messages = listOf(ChatMessage(ChatRole.USER, "test")),
            options = ChatRequestOptions()
        )

        assertNull(stub.lastOptions.reasoningEffort)
    }
}
