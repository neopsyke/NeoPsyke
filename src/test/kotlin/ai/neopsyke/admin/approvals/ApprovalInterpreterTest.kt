package ai.neopsyke.admin.approvals

import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.llm.ChatCompletion
import ai.neopsyke.llm.ChatMessage
import ai.neopsyke.llm.ChatModelClient
import ai.neopsyke.llm.ChatRequestOptions
import ai.neopsyke.support.StubChatModelClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApprovalInterpreterTest {

    // ── Guard tests ──

    @Test
    fun `blank reply returns unclear without model call`() {
        val client = StubChatModelClient()
        val interpreter = DefaultApprovalInterpreter(AgentConfig(), client)

        val result = interpreter.classify(input(reply = "   "))

        assertEquals(ApprovalClassificationKind.UNCLEAR, result.kind)
        assertFalse(result.usedModelAssistance)
        assertTrue(client.calls.isEmpty(), "Blank reply should not trigger an LLM call")
    }

    @Test
    fun `no llm client returns unclear without model assistance`() {
        val interpreter = DefaultApprovalInterpreter(AgentConfig())

        val result = interpreter.classify(input(reply = "yes"))

        assertEquals(ApprovalClassificationKind.UNCLEAR, result.kind)
        assertFalse(result.usedModelAssistance)
    }

    // ── Decision mapping tests ──

    @Test
    fun `all LLM decision values map to correct classification kinds`() {
        val decisions = mapOf(
            "approve" to ApprovalClassificationKind.APPROVE,
            "deny" to ApprovalClassificationKind.DENY,
            "deny_and_reissue" to ApprovalClassificationKind.DENY_AND_REISSUE,
            "explain" to ApprovalClassificationKind.EXPLAIN,
            "unclear" to ApprovalClassificationKind.UNCLEAR,
        )
        for ((decision, expectedKind) in decisions) {
            val client = StubChatModelClient().apply {
                enqueueRawResponse("""{"decision":"$decision"}""")
            }
            val interpreter = DefaultApprovalInterpreter(AgentConfig(), client)

            val result = interpreter.classify(input(reply = "test input"))

            assertEquals(expectedKind, result.kind, "Decision '$decision' should map to $expectedKind")
            assertTrue(result.usedModelAssistance)
        }
    }

    @Test
    fun `unknown decision value fails closed to unclear`() {
        val client = StubChatModelClient().apply {
            enqueueRawResponse("""{"decision":"something_unexpected"}""")
        }
        val interpreter = DefaultApprovalInterpreter(AgentConfig(), client)

        val result = interpreter.classify(input(reply = "test"))

        assertEquals(ApprovalClassificationKind.UNCLEAR, result.kind)
        assertTrue(result.usedModelAssistance)
    }

    @Test
    fun `missing required model field fails closed to unclear`() {
        val client = StubChatModelClient().apply {
            enqueueRawResponse("""{}""")
        }
        val interpreter = DefaultApprovalInterpreter(AgentConfig(), client)

        val result = interpreter.classify(input(reply = "uncertain"))

        assertEquals(ApprovalClassificationKind.UNCLEAR, result.kind)
        assertTrue(result.usedModelAssistance)
    }

    // ── Failure handling tests ──

    @Test
    fun `llm exception falls back to unclear after retries`() {
        val failingClient = object : ChatModelClient {
            override val modelName = "failing-model"
            var callCount = 0
            override fun chat(messages: List<ChatMessage>, options: ChatRequestOptions): ChatCompletion {
                callCount++
                throw java.io.IOException("simulated LLM failure")
            }
        }
        val config = AgentConfig(llmRetryAttempts = 2)
        val interpreter = DefaultApprovalInterpreter(config, failingClient)

        val result = interpreter.classify(input(reply = "yes"))

        assertEquals(ApprovalClassificationKind.UNCLEAR, result.kind)
        assertTrue(result.usedModelAssistance)
        assertEquals(2, failingClient.callCount, "Should retry the configured number of times")
    }

    // ── Prompt construction tests ──

    @Test
    fun `prompt includes canonical summary and reply but not approval context`() {
        val client = StubChatModelClient().apply {
            enqueueRawResponse("""{"decision":"approve"}""")
        }
        val interpreter = DefaultApprovalInterpreter(AgentConfig(), client)

        interpreter.classify(
            ApprovalInterpreterInput(
                reply = "looks good",
                canonicalSummary = "action: assignment_operation",
                approvalContextText = "Plan:\n1. Fetch weather\n2. Send summary",
                sessionId = "chat-1",
                rootInputId = "root-1",
            )
        )

        val userMessage = client.lastMessages.last().content
        assertTrue(userMessage.contains("assignment_operation"), "Canonical summary should be in the prompt")
        assertTrue(userMessage.contains("looks good"), "Reply should be in the prompt")
        assertFalse(userMessage.contains("Fetch weather"), "Approval context should not be in the prompt")
    }

    @Test
    fun `schema includes all five decision values`() {
        val client = StubChatModelClient().apply {
            enqueueRawResponse("""{"decision":"approve"}""")
        }
        val interpreter = DefaultApprovalInterpreter(AgentConfig(), client)

        interpreter.classify(input(reply = "go ahead"))

        val schema = client.lastOptions.responseFormat.toString()
        for (value in listOf("approve", "deny", "deny_and_reissue", "explain", "unclear")) {
            assertTrue(schema.contains(value), "Schema should include '$value'")
        }
    }

    @Test
    fun `long inputs are truncated before sending to LLM`() {
        val client = StubChatModelClient().apply {
            enqueueRawResponse("""{"decision":"deny_and_reissue"}""")
        }
        val interpreter = DefaultApprovalInterpreter(AgentConfig(), client)

        interpreter.classify(
            input(
                reply = "acknowledged " + "x".repeat(800),
                canonicalSummary = "action: contact_user\nsummary: " + "p".repeat(600),
            )
        )

        val userPrompt = client.lastMessages.last().content
        assertTrue(userPrompt.length < 1000, "Long inputs should be truncated")
        assertTrue(userPrompt.contains("acknowledged"), "Content prefix should survive truncation")
    }

    @Test
    fun `unicode normalization preserves text after canonicalization`() {
        val client = StubChatModelClient().apply {
            enqueueRawResponse("""{"decision":"approve"}""")
        }
        val interpreter = DefaultApprovalInterpreter(AgentConfig(), client)

        interpreter.classify(input(reply = "  YES\u00A0!!!  "))

        val userPrompt = client.lastMessages.last().content
        assertTrue(userPrompt.contains("YES!!!"), "NBSP should be normalized and whitespace before punctuation collapsed")
    }

    // ── Helper ──

    private fun input(
        reply: String,
        canonicalSummary: String = "action: contact_user",
        approvalContextText: String = "",
    ) = ApprovalInterpreterInput(
        reply = reply,
        canonicalSummary = canonicalSummary,
        approvalContextText = approvalContextText,
        sessionId = "chat-1",
        rootInputId = "root-1",
    )
}
