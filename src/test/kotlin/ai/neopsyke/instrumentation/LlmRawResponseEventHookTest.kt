package ai.neopsyke.instrumentation

import ai.neopsyke.llm.ChatCallMetadata
import ai.neopsyke.llm.ChatCompletion
import ai.neopsyke.llm.ChatRequestOptions
import ai.neopsyke.support.RecordingInstrumentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LlmRawResponseEventHookTest {
    @Test
    fun `hook emits llm raw response event with metadata and clamped content`() {
        val instrumentation = RecordingInstrumentation()
        val hook = LlmRawResponseEventHook(
            instrumentation = instrumentation,
            maxRawResponseChars = 5
        )

        hook.onSuccess(
            messages = emptyList(),
            options = ChatRequestOptions(
                metadata = ChatCallMetadata(
                    actor = "ego",
                    callSite = "input",
                    actionType = "answer"
                )
            ),
            completion = ChatCompletion(content = "abcdefg", model = "stub")
        )

        val event = instrumentation.events.single()
        assertEquals("llm_raw_response", event.type)
        assertEquals("ego", event.data["actor"])
        assertEquals("input", event.data["call_site"])
        assertEquals("answer", event.data["action_type"])
        assertEquals("abcde", event.data["raw_response"])
    }

    @Test
    fun `hook skips emission when required metadata is missing`() {
        val instrumentation = RecordingInstrumentation()
        val hook = LlmRawResponseEventHook(
            instrumentation = instrumentation,
            maxRawResponseChars = 20
        )

        hook.onSuccess(
            messages = emptyList(),
            options = ChatRequestOptions(metadata = ChatCallMetadata(actor = "ego", callSite = "")),
            completion = ChatCompletion(content = "ok", model = "stub")
        )

        assertTrue(instrumentation.events.isEmpty())
    }
}
