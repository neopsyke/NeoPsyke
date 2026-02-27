package psyke.instrumentation

import psyke.llm.ChatCallMetadata
import psyke.llm.ChatCallRecord
import psyke.llm.ChatCallStatus
import psyke.support.RecordingInstrumentation
import kotlin.test.Test
import kotlin.test.assertEquals

class LlmCallEventObserverTest {
    @Test
    fun `observer emits llm_call event with mapped fields`() {
        val instrumentation = RecordingInstrumentation()
        val observer = LlmCallEventObserver(
            provider = "mistral",
            instrumentation = instrumentation
        )
        observer.onChatCall(
            ChatCallRecord(
                model = "mistral-small-latest",
                metadata = ChatCallMetadata(actor = "ego", callSite = "planner", actionType = "answer"),
                latencyMs = 42,
                promptTokens = 10,
                completionTokens = 2,
                totalTokens = 12,
                status = ChatCallStatus.OK
            )
        )

        val event = instrumentation.events.single()
        assertEquals("llm_call", event.type)
        assertEquals("mistral", event.data["provider"])
        assertEquals("ego", event.data["actor"])
        assertEquals("planner", event.data["call_site"])
        assertEquals("answer", event.data["action_type"])
        assertEquals(12, event.data["total_tokens"])
        assertEquals("ok", event.data["status"])
    }
}
