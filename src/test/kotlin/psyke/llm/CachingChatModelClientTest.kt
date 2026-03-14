package psyke.llm

import psyke.instrumentation.AgentEvent
import psyke.instrumentation.AgentInstrumentation
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CachingChatModelClientTest {

    private fun stubClient(response: String = "ok"): ChatModelClient = object : ChatModelClient {
        override val modelName: String = "stub-model"
        var callCount = 0
            private set

        override fun chat(messages: List<ChatMessage>, options: ChatRequestOptions): ChatCompletion {
            callCount += 1
            return ChatCompletion(
                content = response,
                model = modelName,
                finishReason = "stop",
                usage = ChatUsage(promptTokens = 10, completionTokens = 5, totalTokens = 15)
            )
        }
    }

    private class RecordingInstrumentation : AgentInstrumentation {
        val events = mutableListOf<AgentEvent>()
        override fun emit(event: AgentEvent) {
            events.add(event)
        }
    }

    @Test
    fun `OFF mode is pure passthrough`() {
        val cacheFile = Files.createTempFile("llm-cache-", ".jsonl")
        try {
            val delegate = stubClient()
            val manager = LlmCacheManager(mode = LlmCacheMode.OFF, cacheFile = cacheFile)
            val wrapped = manager.wrapClient(delegate)

            // OFF mode returns the delegate directly
            assertTrue(wrapped === delegate)
            manager.close()
        } finally {
            Files.deleteIfExists(cacheFile)
        }
    }

    @Test
    fun `RECORD mode writes JSONL entries`() {
        val cacheFile = Files.createTempFile("llm-cache-", ".jsonl")
        try {
            val delegate = stubClient("hello world")
            val instrumentation = RecordingInstrumentation()
            val manager = LlmCacheManager(
                mode = LlmCacheMode.RECORD,
                cacheFile = cacheFile,
                instrumentation = instrumentation
            )
            val wrapped = manager.wrapClient(delegate)

            val messages = listOf(ChatMessage(ChatRole.USER, "test input"))
            val options = ChatRequestOptions(metadata = ChatCallMetadata(actor = "planner", callSite = "test"))
            val result = wrapped.chat(messages, options)

            assertEquals("hello world", result.content)
            manager.close()

            val lines = Files.readAllLines(cacheFile)
            assertEquals(1, lines.size)
            assertTrue(lines[0].contains("\"content\":\"hello world\""))
            assertTrue(lines[0].contains("\"actor\":\"planner\""))
        } finally {
            Files.deleteIfExists(cacheFile)
        }
    }

    @Test
    fun `RECORD mode writes multiple entries with sequential indices`() {
        val cacheFile = Files.createTempFile("llm-cache-", ".jsonl")
        try {
            val delegate = stubClient("response")
            val manager = LlmCacheManager(mode = LlmCacheMode.RECORD, cacheFile = cacheFile)
            val wrapped = manager.wrapClient(delegate)

            repeat(3) { i ->
                wrapped.chat(
                    listOf(ChatMessage(ChatRole.USER, "input $i")),
                    ChatRequestOptions(metadata = ChatCallMetadata(actor = "planner", callSite = "call_$i"))
                )
            }
            manager.close()

            val lines = Files.readAllLines(cacheFile)
            assertEquals(3, lines.size)
            assertTrue(lines[0].contains("\"seq\":0"))
            assertTrue(lines[1].contains("\"seq\":1"))
            assertTrue(lines[2].contains("\"seq\":2"))
        } finally {
            Files.deleteIfExists(cacheFile)
        }
    }

    @Test
    fun `REPLAY mode returns cached response on hash match`() {
        val cacheFile = Files.createTempFile("llm-cache-", ".jsonl")
        try {
            // First: record
            val delegate = stubClient("cached answer")
            val recordManager = LlmCacheManager(mode = LlmCacheMode.RECORD, cacheFile = cacheFile)
            val recordWrapped = recordManager.wrapClient(delegate)
            val messages = listOf(ChatMessage(ChatRole.USER, "what is 2+2?"))
            val options = ChatRequestOptions(metadata = ChatCallMetadata(actor = "planner", callSite = "test"))
            recordWrapped.chat(messages, options)
            recordManager.close()

            // Then: replay (delegate should NOT be called)
            val replayDelegate = stubClient("should not see this")
            val instrumentation = RecordingInstrumentation()
            val replayManager = LlmCacheManager(
                mode = LlmCacheMode.REPLAY,
                cacheFile = cacheFile,
                instrumentation = instrumentation
            )
            val replayWrapped = replayManager.wrapClient(replayDelegate)
            val result = replayWrapped.chat(messages, options)

            assertEquals("cached answer", result.content)
            assertEquals(0, (replayDelegate as Any).let {
                // Verify delegate was NOT called
                val field = it.javaClass.getDeclaredField("callCount")
                field.isAccessible = true
                field.getInt(it)
            })
            assertFalse(replayManager.passthroughMode)

            val hitEvents = instrumentation.events.filter { it.type == "llm_cache_hit" }
            assertEquals(1, hitEvents.size)
            assertEquals(0, hitEvents[0].data["sequence_index"])
            replayManager.close()
        } finally {
            Files.deleteIfExists(cacheFile)
        }
    }

    @Test
    fun `REPLAY mode switches to passthrough on hash mismatch`() {
        val cacheFile = Files.createTempFile("llm-cache-", ".jsonl")
        try {
            // Record with one message
            val delegate = stubClient("cached")
            val recordManager = LlmCacheManager(mode = LlmCacheMode.RECORD, cacheFile = cacheFile)
            val recordWrapped = recordManager.wrapClient(delegate)
            recordWrapped.chat(
                listOf(ChatMessage(ChatRole.USER, "original input")),
                ChatRequestOptions(metadata = ChatCallMetadata(actor = "planner", callSite = "test"))
            )
            recordManager.close()

            // Replay with DIFFERENT message
            val replayDelegate = stubClient("real response")
            val instrumentation = RecordingInstrumentation()
            val replayManager = LlmCacheManager(
                mode = LlmCacheMode.REPLAY,
                cacheFile = cacheFile,
                instrumentation = instrumentation
            )
            val replayWrapped = replayManager.wrapClient(replayDelegate)
            val result = replayWrapped.chat(
                listOf(ChatMessage(ChatRole.USER, "different input")),
                ChatRequestOptions(metadata = ChatCallMetadata(actor = "planner", callSite = "test"))
            )

            assertEquals("real response", result.content)
            assertTrue(replayManager.passthroughMode)

            val divergenceEvents = instrumentation.events.filter { it.type == "llm_cache_divergence" }
            assertEquals(1, divergenceEvents.size)
            assertEquals(0, divergenceEvents[0].data["sequence_index"])
            replayManager.close()
        } finally {
            Files.deleteIfExists(cacheFile)
        }
    }

    @Test
    fun `REPLAY mode switches to passthrough on index exhaustion`() {
        val cacheFile = Files.createTempFile("llm-cache-", ".jsonl")
        try {
            // Record 1 entry
            val delegate = stubClient("cached")
            val recordManager = LlmCacheManager(mode = LlmCacheMode.RECORD, cacheFile = cacheFile)
            val recordWrapped = recordManager.wrapClient(delegate)
            recordWrapped.chat(
                listOf(ChatMessage(ChatRole.USER, "input")),
                ChatRequestOptions(metadata = ChatCallMetadata(actor = "planner", callSite = "test"))
            )
            recordManager.close()

            // Replay: first call hits cache, second call exhausts index
            val replayDelegate = stubClient("real")
            val instrumentation = RecordingInstrumentation()
            val replayManager = LlmCacheManager(
                mode = LlmCacheMode.REPLAY,
                cacheFile = cacheFile,
                instrumentation = instrumentation
            )
            val replayWrapped = replayManager.wrapClient(replayDelegate)

            val first = replayWrapped.chat(
                listOf(ChatMessage(ChatRole.USER, "input")),
                ChatRequestOptions(metadata = ChatCallMetadata(actor = "planner", callSite = "test"))
            )
            assertEquals("cached", first.content)
            assertFalse(replayManager.passthroughMode)

            val second = replayWrapped.chat(
                listOf(ChatMessage(ChatRole.USER, "another input")),
                ChatRequestOptions(metadata = ChatCallMetadata(actor = "planner", callSite = "test2"))
            )
            assertEquals("real", second.content)
            assertTrue(replayManager.passthroughMode)
            replayManager.close()
        } finally {
            Files.deleteIfExists(cacheFile)
        }
    }

    @Test
    fun `global sequence counter shared across multiple wrapped clients`() {
        val cacheFile = Files.createTempFile("llm-cache-", ".jsonl")
        try {
            val delegate1 = stubClient("response-1")
            val delegate2 = stubClient("response-2")
            val manager = LlmCacheManager(mode = LlmCacheMode.RECORD, cacheFile = cacheFile)
            val wrapped1 = manager.wrapClient(delegate1)
            val wrapped2 = manager.wrapClient(delegate2)

            wrapped1.chat(
                listOf(ChatMessage(ChatRole.USER, "from client 1")),
                ChatRequestOptions(metadata = ChatCallMetadata(actor = "planner", callSite = "c1"))
            )
            wrapped2.chat(
                listOf(ChatMessage(ChatRole.USER, "from client 2")),
                ChatRequestOptions(metadata = ChatCallMetadata(actor = "superego", callSite = "c2"))
            )
            wrapped1.chat(
                listOf(ChatMessage(ChatRole.USER, "from client 1 again")),
                ChatRequestOptions(metadata = ChatCallMetadata(actor = "planner", callSite = "c1b"))
            )
            manager.close()

            val lines = Files.readAllLines(cacheFile)
            assertEquals(3, lines.size)
            assertTrue(lines[0].contains("\"seq\":0"))
            assertTrue(lines[0].contains("\"actor\":\"planner\""))
            assertTrue(lines[1].contains("\"seq\":1"))
            assertTrue(lines[1].contains("\"actor\":\"superego\""))
            assertTrue(lines[2].contains("\"seq\":2"))
            assertTrue(lines[2].contains("\"actor\":\"planner\""))
        } finally {
            Files.deleteIfExists(cacheFile)
        }
    }

    @Test
    fun `messages hash is deterministic`() {
        val messages = listOf(
            ChatMessage(ChatRole.SYSTEM, "You are helpful."),
            ChatMessage(ChatRole.USER, "Hello"),
        )
        val hash1 = LlmCacheManager.hashMessages(messages)
        val hash2 = LlmCacheManager.hashMessages(messages)

        assertEquals(hash1, hash2)
        assertEquals(64, hash1.length) // SHA-256 hex = 64 chars
    }

    @Test
    fun `REPLAY passthrough stays in passthrough for subsequent calls`() {
        val cacheFile = Files.createTempFile("llm-cache-", ".jsonl")
        try {
            // Record 2 entries
            val delegate = stubClient("cached")
            val recordManager = LlmCacheManager(mode = LlmCacheMode.RECORD, cacheFile = cacheFile)
            val recordWrapped = recordManager.wrapClient(delegate)
            recordWrapped.chat(
                listOf(ChatMessage(ChatRole.USER, "input-0")),
                ChatRequestOptions(metadata = ChatCallMetadata(actor = "planner", callSite = "t"))
            )
            recordWrapped.chat(
                listOf(ChatMessage(ChatRole.USER, "input-1")),
                ChatRequestOptions(metadata = ChatCallMetadata(actor = "planner", callSite = "t"))
            )
            recordManager.close()

            // Replay: first call diverges (different message), second should also use real
            val replayDelegate = stubClient("real")
            val instrumentation = RecordingInstrumentation()
            val replayManager = LlmCacheManager(
                mode = LlmCacheMode.REPLAY,
                cacheFile = cacheFile,
                instrumentation = instrumentation
            )
            val replayWrapped = replayManager.wrapClient(replayDelegate)

            val first = replayWrapped.chat(
                listOf(ChatMessage(ChatRole.USER, "different")),
                ChatRequestOptions(metadata = ChatCallMetadata(actor = "planner", callSite = "t"))
            )
            assertEquals("real", first.content)
            assertTrue(replayManager.passthroughMode)

            val second = replayWrapped.chat(
                listOf(ChatMessage(ChatRole.USER, "input-1")),
                ChatRequestOptions(metadata = ChatCallMetadata(actor = "planner", callSite = "t"))
            )
            assertEquals("real", second.content)
            assertTrue(replayManager.passthroughMode)

            // Only one divergence event should be emitted (not per subsequent call)
            val divergenceEvents = instrumentation.events.filter { it.type == "llm_cache_divergence" }
            assertEquals(1, divergenceEvents.size)
            replayManager.close()
        } finally {
            Files.deleteIfExists(cacheFile)
        }
    }
}
