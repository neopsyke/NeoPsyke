package ai.neopsyke.session

import ai.neopsyke.instrumentation.AgentEvent
import ai.neopsyke.instrumentation.AgentInstrumentation
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordReplayChannelTest {

    private val mapper = jacksonObjectMapper()

    private class RecordingInstrumentation : AgentInstrumentation {
        val events = mutableListOf<AgentEvent>()
        override fun emit(event: AgentEvent) {
            events.add(event)
        }
    }

    private fun dataNode(content: String) =
        mapper.createObjectNode().put("content", content)

    @Test
    fun `OFF mode does not write or read`() {
        val file = Files.createTempFile("session-off-", ".jsonl")
        try {
            val channel = RecordReplayChannel(
                channelName = "test",
                mode = SessionRecordingMode.OFF,
                file = file,
            )
            assertEquals(0, channel.entryCount)
            assertFalse(channel.passthroughMode)
            channel.close()
            assertEquals(0, Files.readAllLines(file).size)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `RECORD mode writes JSONL entries`() {
        val file = Files.createTempFile("session-rec-", ".jsonl")
        try {
            val channel = RecordReplayChannel(
                channelName = "test",
                mode = SessionRecordingMode.RECORD,
                file = file,
            )
            val seq = channel.nextSequenceIndex()
            val hash = RecordReplayChannel.hashContent("signal:$seq")
            channel.recordEntry(
                SessionRecordEntry(
                    seq = seq,
                    hash = hash,
                    channel = "test",
                    data = dataNode("hello"),
                )
            )
            channel.close()

            val lines = Files.readAllLines(file).filter { it.isNotBlank() }
            assertEquals(1, lines.size)
            val parsed = mapper.readTree(lines[0])
            assertEquals(0, parsed.path("seq").asInt())
            assertEquals("hello", parsed.path("data").path("content").asText())
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `REPLAY returns cached data on hash match`() {
        val file = Files.createTempFile("session-replay-", ".jsonl")
        try {
            // First record an entry
            val recordChannel = RecordReplayChannel(
                channelName = "test",
                mode = SessionRecordingMode.RECORD,
                file = file,
            )
            val hash = RecordReplayChannel.hashContent("signal:0")
            recordChannel.recordEntry(
                SessionRecordEntry(
                    seq = 0,
                    hash = hash,
                    channel = "test",
                    data = dataNode("cached-content"),
                )
            )
            recordChannel.close()

            // Now replay
            val instrumentation = RecordingInstrumentation()
            val replayChannel = RecordReplayChannel(
                channelName = "test",
                mode = SessionRecordingMode.REPLAY,
                file = file,
                instrumentation = instrumentation,
            )
            assertEquals(1, replayChannel.entryCount)

            val seq = replayChannel.nextSequenceIndex()
            val replayHash = RecordReplayChannel.hashContent("signal:$seq")
            val data = replayChannel.replayOrDiverge(seq, replayHash)

            assertNotNull(data)
            assertEquals("cached-content", data.path("content").asText())
            assertFalse(replayChannel.passthroughMode)

            val hitEvents = instrumentation.events.filter { it.type == "session_channel_replay_hit" }
            assertEquals(1, hitEvents.size)
            assertEquals("test", hitEvents[0].data["channel"])
            assertEquals(0, hitEvents[0].data["sequence_index"])

            replayChannel.close()
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `REPLAY diverges on hash mismatch`() {
        val file = Files.createTempFile("session-div-", ".jsonl")
        try {
            val recordChannel = RecordReplayChannel(
                channelName = "test",
                mode = SessionRecordingMode.RECORD,
                file = file,
            )
            recordChannel.recordEntry(
                SessionRecordEntry(
                    seq = 0,
                    hash = RecordReplayChannel.hashContent("signal:0"),
                    channel = "test",
                    data = dataNode("cached"),
                )
            )
            recordChannel.close()

            val instrumentation = RecordingInstrumentation()
            val replayChannel = RecordReplayChannel(
                channelName = "test",
                mode = SessionRecordingMode.REPLAY,
                file = file,
                instrumentation = instrumentation,
            )
            val seq = replayChannel.nextSequenceIndex()
            val wrongHash = RecordReplayChannel.hashContent("different-input")
            val data = replayChannel.replayOrDiverge(seq, wrongHash)

            assertNull(data)
            assertTrue(replayChannel.passthroughMode)

            val divEvents = instrumentation.events.filter { it.type == "session_channel_divergence" }
            assertEquals(1, divEvents.size)
            assertEquals("test", divEvents[0].data["channel"])

            replayChannel.close()
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `REPLAY diverges on index exhaustion`() {
        val file = Files.createTempFile("session-exhaust-", ".jsonl")
        try {
            // Record one entry
            val recordChannel = RecordReplayChannel(
                channelName = "test",
                mode = SessionRecordingMode.RECORD,
                file = file,
            )
            recordChannel.recordEntry(
                SessionRecordEntry(
                    seq = 0,
                    hash = RecordReplayChannel.hashContent("signal:0"),
                    channel = "test",
                    data = dataNode("only-entry"),
                )
            )
            recordChannel.close()

            val instrumentation = RecordingInstrumentation()
            val replayChannel = RecordReplayChannel(
                channelName = "test",
                mode = SessionRecordingMode.REPLAY,
                file = file,
                instrumentation = instrumentation,
            )

            // Consume the one entry
            val seq0 = replayChannel.nextSequenceIndex()
            val data0 = replayChannel.replayOrDiverge(
                seq0, RecordReplayChannel.hashContent("signal:$seq0")
            )
            assertNotNull(data0)
            assertFalse(replayChannel.passthroughMode)

            // Now try a second — should exhaust
            val seq1 = replayChannel.nextSequenceIndex()
            val data1 = replayChannel.replayOrDiverge(
                seq1, RecordReplayChannel.hashContent("signal:$seq1")
            )
            assertNull(data1)
            assertTrue(replayChannel.passthroughMode)

            replayChannel.close()
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `hashContent is deterministic`() {
        val h1 = RecordReplayChannel.hashContent("signal:0")
        val h2 = RecordReplayChannel.hashContent("signal:0")
        assertEquals(h1, h2)

        val h3 = RecordReplayChannel.hashContent("signal:1")
        assertTrue(h1 != h3)
    }

    @Test
    fun `RECORD mode sequential indices`() {
        val file = Files.createTempFile("session-seq-", ".jsonl")
        try {
            val channel = RecordReplayChannel(
                channelName = "test",
                mode = SessionRecordingMode.RECORD,
                file = file,
            )
            assertEquals(0, channel.nextSequenceIndex())
            assertEquals(1, channel.nextSequenceIndex())
            assertEquals(2, channel.nextSequenceIndex())
            channel.close()
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `passthrough stays in passthrough after divergence`() {
        val file = Files.createTempFile("session-passthrough-", ".jsonl")
        try {
            val recordChannel = RecordReplayChannel(
                channelName = "test",
                mode = SessionRecordingMode.RECORD,
                file = file,
            )
            recordChannel.recordEntry(
                SessionRecordEntry(
                    seq = 0,
                    hash = RecordReplayChannel.hashContent("signal:0"),
                    channel = "test",
                    data = dataNode("entry"),
                )
            )
            recordChannel.close()

            val replayChannel = RecordReplayChannel(
                channelName = "test",
                mode = SessionRecordingMode.REPLAY,
                file = file,
            )
            // Force divergence
            replayChannel.nextSequenceIndex()
            replayChannel.replayOrDiverge(0, "wrong-hash")
            assertTrue(replayChannel.passthroughMode)

            // Subsequent calls also return null
            val result = replayChannel.replayOrDiverge(1, "anything")
            assertNull(result)
            assertTrue(replayChannel.passthroughMode)

            replayChannel.close()
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
