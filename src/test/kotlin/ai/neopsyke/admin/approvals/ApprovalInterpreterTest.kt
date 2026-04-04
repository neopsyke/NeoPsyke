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
                promptSummary = "prompt",
                actionSummary = "action",
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
                promptSummary = "prompt",
                actionSummary = "action",
                sessionId = "chat-1",
                rootInputId = "root-1",
            )
        )

        assertEquals(ApprovalClassificationKind.DENY_AND_REISSUE, result.kind)
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
                promptSummary = "prompt",
                actionSummary = "action",
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
                reply = "“Maybe” " + "x".repeat(800),
                promptSummary = "prompt " + "p".repeat(400),
                actionSummary = "action " + "a".repeat(400),
                sessionId = "chat-1",
                rootInputId = "root-1",
            )
        )

        val userPrompt = client.lastMessages.last().content
        val schema = client.lastOptions.responseFormat
        assertEquals(ApprovalClassificationKind.DENY_AND_REISSUE, result.kind)
        assertTrue(userPrompt.length < 1000)
        assertTrue(userPrompt.contains("maybe"))
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
                promptSummary = "prompt",
                actionSummary = "action",
                sessionId = "chat-1",
                rootInputId = "root-1",
            )
        )

        assertEquals(ApprovalClassificationKind.UNCLEAR, result.kind)
        assertTrue(result.usedModelAssistance)
    }
}
