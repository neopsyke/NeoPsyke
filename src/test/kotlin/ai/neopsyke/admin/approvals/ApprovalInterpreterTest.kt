package ai.neopsyke.admin.approvals

import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.support.StubChatModelClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApprovalInterpreterTest {
    @Test
    fun `deterministic approve handles unicode and punctuation normalization`() {
        val interpreter = DefaultApprovalInterpreter(AgentConfig())

        val result = interpreter.classify(
            ApprovalInterpreterInput(
                reply = "  YES\u00A0!!!  ",
                canonicalSummary = "action: contact_user",
                sessionId = "chat-1",
                rootInputId = "root-1",
            )
        )

        assertEquals(ApprovalClassificationKind.APPROVE, result.kind)
        assertFalse(result.usedModelAssistance)
    }

    @Test
    fun `deterministic modification reply becomes deny and reissue`() {
        val interpreter = DefaultApprovalInterpreter(AgentConfig())

        val result = interpreter.classify(
            ApprovalInterpreterInput(
                reply = "Okay, send it tomorrow instead",
                canonicalSummary = "action: contact_user",
                sessionId = "chat-1",
                rootInputId = "root-1",
            )
        )

        assertEquals(ApprovalClassificationKind.DENY_AND_REISSUE, result.kind)
        assertFalse(result.usedModelAssistance)
    }

    @Test
    fun `polite deterministic approvals and denials remain bounded`() {
        val interpreter = DefaultApprovalInterpreter(AgentConfig())

        val approve = interpreter.classify(
            ApprovalInterpreterInput(
                reply = "yes please",
                canonicalSummary = "action: contact_user",
                sessionId = "chat-1",
                rootInputId = "root-1",
            )
        )
        val deny = interpreter.classify(
            ApprovalInterpreterInput(
                reply = "no thanks",
                canonicalSummary = "action: contact_user",
                sessionId = "chat-1",
                rootInputId = "root-1",
            )
        )

        assertEquals(ApprovalClassificationKind.APPROVE, approve.kind)
        assertEquals(ApprovalClassificationKind.DENY, deny.kind)
        assertFalse(approve.usedModelAssistance)
        assertFalse(deny.usedModelAssistance)
    }

    @Test
    fun `question-like approval phrase fails closed`() {
        val interpreter = DefaultApprovalInterpreter(AgentConfig())

        val result = interpreter.classify(
            ApprovalInterpreterInput(
                reply = "sure?",
                canonicalSummary = "action: contact_user",
                sessionId = "chat-1",
                rootInputId = "root-1",
            )
        )

        assertEquals(ApprovalClassificationKind.UNCLEAR, result.kind)
        assertFalse(result.usedModelAssistance)
    }

    @Test
    fun `approve with explicit approval ref remains deterministic approve`() {
        val interpreter = DefaultApprovalInterpreter(AgentConfig())

        val result = interpreter.classify(
            ApprovalInterpreterInput(
                reply = "yes ref abc12345",
                canonicalSummary = "action: contact_user",
                sessionId = "chat-1",
                rootInputId = "root-1",
            )
        )

        assertEquals(ApprovalClassificationKind.APPROVE, result.kind)
        assertFalse(result.usedModelAssistance)
    }

    @Test
    fun `deterministic explanation question remains explain even outside prefix allowlist`() {
        val client = StubChatModelClient().apply {
            enqueueRawResponse("""{"decision":"approve"}""")
        }
        val interpreter = DefaultApprovalInterpreter(AgentConfig(), client)

        val result = interpreter.classify(
            ApprovalInterpreterInput(
                reply = "Can you explain the target?",
                canonicalSummary = "action: contact_user",
                sessionId = "chat-1",
                rootInputId = "root-1",
            )
        )

        assertEquals(ApprovalClassificationKind.EXPLAIN, result.kind)
        assertTrue(client.calls.isEmpty())
    }

    @Test
    fun `fallback model output is bounded and cannot emit explain`() {
        val client = StubChatModelClient().apply {
            enqueueRawResponse("""{"decision":"deny_and_reissue"}""")
        }
        val interpreter = DefaultApprovalInterpreter(AgentConfig(), client)

        val result = interpreter.classify(
            ApprovalInterpreterInput(
                reply = "acknowledged " + "x".repeat(800),
                canonicalSummary = "action: contact_user\nsummary: " + "p".repeat(600),
                sessionId = "chat-1",
                rootInputId = "root-1",
            )
        )

        val userPrompt = client.lastMessages.last().content
        val schema = client.lastOptions.responseFormat
        assertEquals(ApprovalClassificationKind.DENY_AND_REISSUE, result.kind)
        assertTrue(userPrompt.length < 1000)
        assertTrue(userPrompt.contains("acknowledged"))
        assertTrue(schema.toString().contains("deny_and_reissue"))
        assertFalse(schema.toString().contains("explain"))
    }

    @Test
    fun `missing required model field fails closed to unclear`() {
        val client = StubChatModelClient().apply {
            enqueueRawResponse("""{}""")
        }
        val interpreter = DefaultApprovalInterpreter(AgentConfig(), client)

        val result = interpreter.classify(
            ApprovalInterpreterInput(
                reply = "uncertain",
                canonicalSummary = "action: contact_user",
                sessionId = "chat-1",
                rootInputId = "root-1",
            )
        )

        assertEquals(ApprovalClassificationKind.UNCLEAR, result.kind)
        assertTrue(result.usedModelAssistance)
    }

    // ── Plan-edit classification tests ──

    @Test
    fun `plan edit reply classifies as deny_and_reissue via model`() {
        val client = StubChatModelClient().apply {
            enqueueRawResponse("""{"decision":"deny_and_reissue"}""")
        }
        val interpreter = DefaultApprovalInterpreter(AgentConfig(), client)

        val result = interpreter.classify(
            ApprovalInterpreterInput(
                reply = "combine the first two steps",
                canonicalSummary = "action: durable_work_operation",
                approvalContextText = "Plan:\n1. Search weather data\n2. Format summary\n3. Send to user",
                sessionId = "chat-1",
                rootInputId = "root-1",
            )
        )

        assertEquals(ApprovalClassificationKind.DENY_AND_REISSUE, result.kind)
        assertTrue(result.usedModelAssistance)
    }

    @Test
    fun `mixed approve-but-modify classifies as deny_and_reissue deterministically`() {
        val interpreter = DefaultApprovalInterpreter(AgentConfig())

        val result = interpreter.classify(
            ApprovalInterpreterInput(
                reply = "approve, but remove step 3",
                canonicalSummary = "action: durable_work_operation",
                approvalContextText = "Plan:\n1. Search data\n2. Summarize\n3. Archive",
                sessionId = "chat-1",
                rootInputId = "root-1",
            )
        )

        assertEquals(ApprovalClassificationKind.DENY_AND_REISSUE, result.kind)
        assertFalse(result.usedModelAssistance, "approve-but pattern is caught by deterministic conditional modifier check")
    }

    @Test
    fun `approval context text is included in LLM prompt`() {
        val client = StubChatModelClient().apply {
            enqueueRawResponse("""{"decision":"approve"}""")
        }
        val interpreter = DefaultApprovalInterpreter(AgentConfig(), client)

        val planContext = "Plan:\n1. Fetch weather\n2. Send summary"
        interpreter.classify(
            ApprovalInterpreterInput(
                reply = "looks good, go ahead",
                canonicalSummary = "action: durable_work_operation",
                approvalContextText = planContext,
                sessionId = "chat-1",
                rootInputId = "root-1",
            )
        )

        assertTrue(client.calls.isNotEmpty())
        val userMessage = client.lastMessages.last().content
        assertTrue(
            userMessage.contains("Fetch weather"),
            "Approval context should be included in the LLM user prompt",
        )
    }
}
