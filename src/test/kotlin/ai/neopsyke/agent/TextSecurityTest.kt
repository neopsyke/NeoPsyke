package ai.neopsyke.agent

import ai.neopsyke.llm.ChatMessage
import ai.neopsyke.llm.ChatRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TextSecurityTest {
    @Test
    fun `clamp and preview trim content safely`() {
        assertEquals("hello", TextSecurity.clamp("hello", 10))
        assertEquals("hel", TextSecurity.clamp("hello", 3))
        assertEquals("a b c", TextSecurity.preview("  a \n\t b   c  ", 40))
    }

    @Test
    fun `estimate tokens has minimum of one`() {
        assertEquals(1, TextSecurity.estimateTokens(""))
        assertEquals(2, TextSecurity.estimateTokens("12345678"))
    }

    @Test
    fun `clamp to token budget trims oversized text`() {
        val text = "a".repeat(100)
        val clamped = TextSecurity.clampToTokenBudget(text, maxTokens = 10)
        assertEquals(10, TextSecurity.estimateTokens(clamped))
    }

    @Test
    fun `trim messages keeps system prefix and most recent tail`() {
        val messages = listOf(
            ChatMessage(ChatRole.SYSTEM, "12345678"),
            ChatMessage(ChatRole.USER, "u1-12345"),
            ChatMessage(ChatRole.ASSISTANT, "a1-12345"),
            ChatMessage(ChatRole.USER, "u2-12345")
        )

        val trimmed = TextSecurity.trimMessagesToBudget(messages, maxTokens = 12)
        assertEquals(2, trimmed.size)
        assertEquals(ChatRole.SYSTEM, trimmed[0].role)
        assertEquals("u2-12345", trimmed[1].content)
    }

    @Test
    fun `extract json object handles fenced or wrapped responses`() {
        val fenced = "```json\n{\"a\":1,\"b\":2}\n```"
        val wrapped = "response:\n{\n  \"ok\": true\n}\nthanks"
        assertEquals("{\"a\":1,\"b\":2}", TextSecurity.extractJsonObject(fenced))
        assertEquals("{\n  \"ok\": true\n}", TextSecurity.extractJsonObject(wrapped))
    }

    @Test
    fun `extract json object skips malformed fenced block and finds next valid object`() {
        val raw = """
            ```json
            {"broken":
            ```
            explanation text
            ```json
            {"allow": false, "reason": "insufficient context"}
            ```
        """.trimIndent()

        assertEquals(
            "{\"allow\": false, \"reason\": \"insufficient context\"}",
            TextSecurity.extractJsonObject(raw)
        )
    }

    @Test
    fun `extract json object handles braces inside string values`() {
        val raw = """
            Here is your payload:
            {"message":"keep literal braces like {this} intact","ok":true}
            trailing note
        """.trimIndent()

        val extracted = TextSecurity.extractJsonObject(raw)
        assertTrue(extracted.contains("\"keep literal braces like {this} intact\""))
    }

    @Test
    fun `extract json object returns first valid object from mixed text`() {
        val raw = "prefix {oops} middle {\"first\":1} then {\"second\":2}"
        assertEquals("{\"first\":1}", TextSecurity.extractJsonObject(raw))
    }

    @Test
    fun `extract json object fails when no json object exists`() {
        assertFailsWith<IllegalArgumentException> {
            TextSecurity.extractJsonObject("no json here")
        }
    }
}
