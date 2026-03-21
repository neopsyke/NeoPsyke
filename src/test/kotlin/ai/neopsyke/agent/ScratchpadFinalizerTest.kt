package ai.neopsyke.agent

import ai.neopsyke.support.RecordingInstrumentation
import ai.neopsyke.support.StubChatModelClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ScratchpadFinalizerTest {
    @Test
    fun `llm finalizer rewrites payload from grounded json response`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse("""{"rewrite":"Refined final answer","confidence":0.88,"grounded":true,"reason":"direct_answer"}""")
        }
        val config = AgentConfig(
            memory = MemoryConfig(
                taskWorkspace = TaskWorkspaceConfig(
                    enabled = true,
                    finalPassMaxTokens = 321
                )
            )
        )
        val finalizer = LlmScratchpadFinalizer(
            modelClient = llm,
            config = config,
            instrumentation = RecordingInstrumentation()
        )

        val result = finalizer.finalize(
            ScratchpadFinalizerRequest(
                action = PendingAction(
                    id = 1,
                    urgency = Urgency.MEDIUM,
                    type = ActionType.CONTACT_USER,
                    payload = "Draft answer",
                    summary = "respond"
                ),
                workspaceCompilation = "Scratchpad final compilation:\nsections:\n1. Request: summarize pricing",
                workspaceConfidence = 0.73,
                recentDialogue = listOf(
                    DialogueTurn(DialogueRole.USER, "What is the current pricing?")
                )
            )
        )

        assertNotNull(result)
        assertEquals("Refined final answer", result.rewrittenPayload)
        assertEquals(0.88, result.confidence)
        assertEquals("scratchpad_finalizer", llm.calls.single().options.metadata.callSite)
        assertEquals("contact_user", llm.calls.single().options.metadata.actionType)
        assertEquals(321, llm.calls.single().options.maxTokens)
    }

    @Test
    fun `llm finalizer switches guidance mode for fallback explanation actions`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse("""{"rewrite":"Fallback explanation","confidence":0.77,"grounded":true,"reason":"fallback_explanation"}""")
        }
        val finalizer = LlmScratchpadFinalizer(
            modelClient = llm,
            config = AgentConfig(),
            instrumentation = RecordingInstrumentation()
        )

        finalizer.finalize(
            ScratchpadFinalizerRequest(
                action = PendingAction(
                    id = 2,
                    urgency = Urgency.MEDIUM,
                    type = ActionType.CONTACT_USER,
                    payload = "Initial fallback",
                    summary = "fallback",
                    isFallbackExplanation = true
                ),
                workspaceCompilation = "Scratchpad final compilation:\nsections:\n1. Request: explain constraint",
                workspaceConfidence = 0.64,
                recentDialogue = emptyList()
            )
        )

        val userPrompt = llm.calls.single().messages.last().content
        assertTrue(userPrompt.contains("Mode=fallback_explanation."))
    }
}
