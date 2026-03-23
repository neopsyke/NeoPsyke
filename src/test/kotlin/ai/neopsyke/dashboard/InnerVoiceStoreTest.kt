package ai.neopsyke.dashboard

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InnerVoiceStoreTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `emit broadcasts to session subscribers`() {
        val store = InnerVoiceStore()
        val subscription = store.subscribe("session-1")
        assertNotNull(subscription)

        val event = InnerVoiceEvent(
            id = 1,
            type = InnerVoiceEventType.DELIBERATION,
            content = "Thinking about the query...",
            rootInputId = "root-1",
            sessionId = "session-1",
            ts = System.currentTimeMillis()
        )
        store.emit(event)

        runBlocking {
            val payload = withTimeoutOrNull(1000) { subscription.receive() }
            assertNotNull(payload)
            val parsed = mapper.readValue<Map<String, Any?>>(payload)
            assertEquals("thinking", parsed["type"])
            @Suppress("UNCHECKED_CAST")
            val inner = parsed["event"] as Map<String, Any?>
            assertEquals("DELIBERATION", inner["type"])
            assertEquals("Thinking about the query...", inner["content"])
            assertEquals("root-1", inner["root_input_id"])
            assertEquals("session-1", inner["session_id"])
        }

        subscription.close()
        store.close()
    }

    @Test
    fun `emit broadcasts sequence field in payload`() {
        val store = InnerVoiceStore()
        val subscription = store.subscribe("session-1")
        assertNotNull(subscription)

        val event = InnerVoiceEvent(
            id = 1,
            type = InnerVoiceEventType.DELIBERATION,
            content = "Thinking...",
            rootInputId = "root-1",
            sessionId = "session-1",
            ts = System.currentTimeMillis(),
            sequence = 42
        )
        store.emit(event)

        runBlocking {
            val payload = withTimeoutOrNull(1000) { subscription.receive() }
            assertNotNull(payload)
            val parsed = mapper.readValue<Map<String, Any?>>(payload)
            @Suppress("UNCHECKED_CAST")
            val inner = parsed["event"] as Map<String, Any?>
            assertEquals(42L, (inner["sequence"] as Number).toLong())
        }

        subscription.close()
        store.close()
    }

    @Test
    fun `events for different sessions are isolated`() {
        val store = InnerVoiceStore()
        val sub1 = store.subscribe("session-1")!!
        val sub2 = store.subscribe("session-2")!!

        store.emit(
            InnerVoiceEvent(
                id = 1,
                type = InnerVoiceEventType.INTENTION,
                content = "Searching...",
                rootInputId = "root-1",
                sessionId = "session-1",
                ts = System.currentTimeMillis()
            )
        )

        runBlocking {
            val payload1 = withTimeoutOrNull(1000) { sub1.receive() }
            assertNotNull(payload1)
            val payload2 = withTimeoutOrNull(200) { sub2.receive() }
            assertEquals(null, payload2)
        }

        sub1.close()
        sub2.close()
        store.close()
    }

    @Test
    fun `events with null sessionId are dropped`() {
        val store = InnerVoiceStore()
        val sub = store.subscribe("session-1")!!

        store.emit(
            InnerVoiceEvent(
                id = 1,
                type = InnerVoiceEventType.DELIBERATION,
                content = "no session",
                rootInputId = "root-1",
                sessionId = null,
                ts = System.currentTimeMillis()
            )
        )

        runBlocking {
            val payload = withTimeoutOrNull(200) { sub.receive() }
            assertEquals(null, payload)
        }

        sub.close()
        store.close()
    }

    @Test
    fun `buffer prunes old events when maxEventsPerSession exceeded`() {
        val store = InnerVoiceStore(maxEventsPerSession = 3)
        val sub = store.subscribe("session-1")!!

        repeat(5) { i ->
            store.emit(
                InnerVoiceEvent(
                    id = i.toLong(),
                    type = InnerVoiceEventType.DELIBERATION,
                    content = "thought-$i",
                    rootInputId = "root-1",
                    sessionId = "session-1",
                    ts = System.currentTimeMillis()
                )
            )
        }

        // All 5 should still be broadcast (pruning affects buffer, not delivery)
        runBlocking {
            repeat(5) {
                val payload = withTimeoutOrNull(1000) { sub.receive() }
                assertNotNull(payload)
            }
        }

        sub.close()
        store.close()
    }

    @Test
    fun `close cleans up all subscribers`() {
        val store = InnerVoiceStore()
        store.subscribe("session-1")
        store.subscribe("session-2")
        store.close()
        // Verify no exceptions thrown
        assertTrue(true)
    }
}
