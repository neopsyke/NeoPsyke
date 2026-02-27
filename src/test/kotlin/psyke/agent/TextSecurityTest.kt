package psyke.agent

import psyke.llm.ChatMessage
import psyke.llm.ChatRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
    fun `extract json object fails when no json object exists`() {
        assertFailsWith<IllegalArgumentException> {
            TextSecurity.extractJsonObject("no json here")
        }
    }
}
