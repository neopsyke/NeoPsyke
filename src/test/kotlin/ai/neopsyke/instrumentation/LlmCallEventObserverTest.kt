package ai.neopsyke.instrumentation

import ai.neopsyke.llm.ChatCallMetadata
import ai.neopsyke.llm.ChatCallRecord
import ai.neopsyke.llm.ChatCallStatus
import ai.neopsyke.support.RecordingInstrumentation
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
                metadata = ChatCallMetadata(
                    actor = "ego",
                    callSite = "planner",
                    actionType = "answer",
                    structuredOutputMode = "relaxed",
                    sessionId = "session-7",
                    rootInputId = "root-7",
                ),
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
        assertEquals("relaxed", event.data["structured_output_mode"])
        assertEquals("session-7", event.data["session_id"])
        assertEquals("root-7", event.data["root_input_id"])
        assertEquals(12, event.data["total_tokens"])
        assertEquals("ok", event.data["status"])
    }
}
