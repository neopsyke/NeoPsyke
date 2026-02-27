package psyke.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class ChatCallObserversTest {
    @Test
    fun `combine returns null when no observers are provided`() {
        assertNull(combineChatCallObservers())
    }

    @Test
    fun `combine returns single observer instance unchanged`() {
        val observer = ChatCallObserver {}
        val combined = combineChatCallObservers(observer)
        assertSame(observer, combined)
    }

    @Test
    fun `composite observer fans out and isolates observer failures`() {
        var aCount = 0
        var bCount = 0
        val combined = combineChatCallObservers(
            ChatCallObserver { aCount += 1 },
            ChatCallObserver { throw IllegalStateException("boom") },
            ChatCallObserver { bCount += 1 }
        )!!
        val record = ChatCallRecord(
            model = "m",
            metadata = ChatCallMetadata(actor = "ego"),
            latencyMs = 10,
            status = ChatCallStatus.OK
        )

        combined.onChatCall(record)
        assertEquals(1, aCount)
        assertEquals(1, bCount)
    }
}
