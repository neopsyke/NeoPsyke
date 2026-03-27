package ai.neopsyke.session

import ai.neopsyke.agent.memory.longterm.Logbook
import ai.neopsyke.agent.memory.longterm.LogbookEntry
import ai.neopsyke.agent.memory.longterm.LogbookQuery
import ai.neopsyke.agent.memory.longterm.LogbookRecall
import ai.neopsyke.agent.memory.longterm.MemoryEventType
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecordingLogbookTest {

    private class FakeLogbook(
        private val result: LogbookRecall,
    ) : Logbook {
        var queryCount = 0
            private set

        override fun record(entry: LogbookEntry): Long = 1L
        override fun query(query: LogbookQuery): LogbookRecall {
            queryCount++
            return result
        }
        override fun pruneOlderThan(retentionDays: Int): Int = 0
        override fun close() {}
    }

    private val testRecall = LogbookRecall(
        entries = listOf(
            LogbookEntry(
                id = 1,
                ts = Instant.parse("2026-03-26T12:00:00Z"),
                eventType = MemoryEventType.INPUT_RECEIVED,
                summary = "User asked about weather",
                keywords = listOf("weather", "question"),
                actionType = "contact_user",
                sessionId = "session-1",
            ),
            LogbookEntry(
                id = 2,
                ts = Instant.parse("2026-03-26T12:01:00Z"),
                eventType = MemoryEventType.ACTION_EXECUTED,
                summary = "Responded with weather info",
                keywords = listOf("weather", "response"),
                metadata = mapOf("tokens" to 42, "source" to "web_search"),
            ),
        ),
        totalMatched = 2,
        truncated = false,
    )

    private val testQuery = LogbookQuery(
        keywordSearch = "weather",
        maxResults = 10,
    )

    @Test
    fun `RECORD mode delegates and records query`() {
        val file = Files.createTempFile("session-logbook-rec-", ".jsonl")
        try {
            val fake = FakeLogbook(testRecall)
            val channel = RecordReplayChannel(
                channelName = "logbook_recall",
                mode = SessionRecordingMode.RECORD,
                file = file,
            )
            val recording = RecordingLogbook(delegate = fake, channel = channel)

            val result = recording.query(testQuery)
            assertEquals(2, result.totalMatched)
            assertEquals(1, fake.queryCount)

            channel.close()
            val lines = Files.readAllLines(file).filter { it.isNotBlank() }
            assertEquals(1, lines.size)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `REPLAY returns cached result on hash match`() {
        val file = Files.createTempFile("session-logbook-replay-", ".jsonl")
        try {
            val recFake = FakeLogbook(testRecall)
            val recChannel = RecordReplayChannel(
                channelName = "logbook_recall",
                mode = SessionRecordingMode.RECORD,
                file = file,
            )
            RecordingLogbook(delegate = recFake, channel = recChannel).query(testQuery)
            recChannel.close()

            val replayFake = FakeLogbook(testRecall)
            val replayChannel = RecordReplayChannel(
                channelName = "logbook_recall",
                mode = SessionRecordingMode.REPLAY,
                file = file,
            )
            val replaying = RecordingLogbook(delegate = replayFake, channel = replayChannel)
            val result = replaying.query(testQuery)

            assertEquals(2, result.totalMatched)
            assertFalse(result.truncated)
            assertEquals(2, result.entries.size)
            assertEquals("User asked about weather", result.entries[0].summary)
            assertEquals(MemoryEventType.INPUT_RECEIVED, result.entries[0].eventType)
            assertEquals(listOf("weather", "question"), result.entries[0].keywords)
            assertEquals("contact_user", result.entries[0].actionType)
            assertEquals("session-1", result.entries[0].sessionId)
            assertEquals("Responded with weather info", result.entries[1].summary)
            assertEquals(MemoryEventType.ACTION_EXECUTED, result.entries[1].eventType)
            assertEquals(0, replayFake.queryCount)
            assertFalse(replayChannel.passthroughMode)

            replayChannel.close()
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `REPLAY diverges on different query and falls back to live`() {
        val file = Files.createTempFile("session-logbook-div-", ".jsonl")
        try {
            val recFake = FakeLogbook(testRecall)
            val recChannel = RecordReplayChannel(
                channelName = "logbook_recall",
                mode = SessionRecordingMode.RECORD,
                file = file,
            )
            RecordingLogbook(delegate = recFake, channel = recChannel).query(testQuery)
            recChannel.close()

            val replayFake = FakeLogbook(testRecall)
            val replayChannel = RecordReplayChannel(
                channelName = "logbook_recall",
                mode = SessionRecordingMode.REPLAY,
                file = file,
            )
            val replaying = RecordingLogbook(delegate = replayFake, channel = replayChannel)
            replaying.query(LogbookQuery(keywordSearch = "different", maxResults = 10))

            assertTrue(replayChannel.passthroughMode)
            assertEquals(1, replayFake.queryCount)

            replayChannel.close()
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `REPLAY preserves metadata field`() {
        val file = Files.createTempFile("session-logbook-meta-", ".jsonl")
        try {
            val recFake = FakeLogbook(testRecall)
            val recChannel = RecordReplayChannel(
                channelName = "logbook_recall",
                mode = SessionRecordingMode.RECORD,
                file = file,
            )
            RecordingLogbook(delegate = recFake, channel = recChannel).query(testQuery)
            recChannel.close()

            val replayFake = FakeLogbook(testRecall)
            val replayChannel = RecordReplayChannel(
                channelName = "logbook_recall",
                mode = SessionRecordingMode.REPLAY,
                file = file,
            )
            val result = RecordingLogbook(delegate = replayFake, channel = replayChannel).query(testQuery)

            val metaEntry = result.entries[1]
            assertEquals(42, (metaEntry.metadata?.get("tokens") as? Number)?.toInt())
            assertEquals("web_search", metaEntry.metadata?.get("source"))

            replayChannel.close()
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
