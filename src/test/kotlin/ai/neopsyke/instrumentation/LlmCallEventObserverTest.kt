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
                    cognitiveRole = "planner",
                    trigger = "thought",
                    originSource = "id",
                    needId = "learn-something",
                    rootImpulseId = "impulse-4",
                    thoughtId = 19L,
                    planId = "plan-7",
                    planStepIndex = 1,
                    planTotalSteps = 4,
                    planStepDescription = "Fetch summary of top result",
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
        assertEquals("planner", event.data["cognitive_role"])
        assertEquals("thought", event.data["trigger"])
        assertEquals("id", event.data["origin_source"])
        assertEquals("learn-something", event.data["need_id"])
        assertEquals("impulse-4", event.data["root_impulse_id"])
        assertEquals(19L, event.data["thought_id"])
        assertEquals("plan-7", event.data["plan_id"])
        assertEquals(1, event.data["plan_step_index"])
        assertEquals(4, event.data["plan_total_steps"])
        assertEquals("Fetch summary of top result", event.data["plan_step_description"])
        assertEquals("relaxed", event.data["structured_output_mode"])
        assertEquals("session-7", event.data["session_id"])
        assertEquals("root-7", event.data["root_input_id"])
        assertEquals(12, event.data["total_tokens"])
        assertEquals("ok", event.data["status"])
    }
}
