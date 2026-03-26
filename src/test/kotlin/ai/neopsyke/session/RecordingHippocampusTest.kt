package ai.neopsyke.session

import ai.neopsyke.agent.memory.longterm.Hippocampus
import ai.neopsyke.agent.memory.longterm.MemoryCapability
import ai.neopsyke.agent.memory.longterm.MemoryHealth
import ai.neopsyke.agent.memory.longterm.MemoryItem
import ai.neopsyke.agent.memory.longterm.MemoryKind
import ai.neopsyke.agent.memory.longterm.RecallIntent
import ai.neopsyke.agent.memory.longterm.RecallLimits
import ai.neopsyke.agent.memory.longterm.RecallRequest
import ai.neopsyke.agent.memory.longterm.RecallResult
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecordingHippocampusTest {

    private class FakeHippocampus(
        private val result: RecallResult,
    ) : Hippocampus {
        override val providerName = "fake"
        override val capabilities: Set<MemoryCapability> = emptySet()
        var recallCount = 0
            private set

        override fun recall(request: RecallRequest): RecallResult {
            recallCount++
            return result
        }
    }

    private val testResult = RecallResult(
        provider = "fake",
        items = listOf(
            MemoryItem(
                id = "item-1",
                kind = MemoryKind.NARRATIVE,
                summary = "User likes coffee",
                score = 0.95,
            ),
            MemoryItem(
                id = "item-2",
                kind = MemoryKind.FACT,
                summary = "User is a developer",
                content = "Victor is a senior developer",
                score = 0.85,
                tags = listOf("user", "role"),
            ),
        ),
        renderedText = "User likes coffee\nUser is a developer",
        hitCount = 2,
        truncated = false,
    )

    private val testRequest = RecallRequest(
        cue = "what does the user like",
        intent = RecallIntent.GENERAL,
        limits = RecallLimits(maxItems = 4, maxChars = 1200),
    )

    @Test
    fun `RECORD mode delegates and records recall`() {
        val file = Files.createTempFile("session-hippo-rec-", ".jsonl")
        try {
            val fake = FakeHippocampus(testResult)
            val channel = RecordReplayChannel(
                channelName = "memory_recall",
                mode = SessionRecordingMode.RECORD,
                file = file,
            )
            val recording = RecordingHippocampus(delegate = fake, channel = channel)

            val result = recording.recall(testRequest)
            assertEquals("fake", result.provider)
            assertEquals(2, result.hitCount)
            assertEquals(1, fake.recallCount)

            channel.close()
            val lines = Files.readAllLines(file).filter { it.isNotBlank() }
            assertEquals(1, lines.size)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `REPLAY returns cached recall on hash match`() {
        val file = Files.createTempFile("session-hippo-replay-", ".jsonl")
        try {
            // Record
            val fake = FakeHippocampus(testResult)
            val recChannel = RecordReplayChannel(
                channelName = "memory_recall",
                mode = SessionRecordingMode.RECORD,
                file = file,
            )
            RecordingHippocampus(delegate = fake, channel = recChannel).recall(testRequest)
            recChannel.close()
            assertEquals(1, fake.recallCount)

            // Replay
            val replayFake = FakeHippocampus(testResult)
            val replayChannel = RecordReplayChannel(
                channelName = "memory_recall",
                mode = SessionRecordingMode.REPLAY,
                file = file,
            )
            val replaying = RecordingHippocampus(delegate = replayFake, channel = replayChannel)
            val result = replaying.recall(testRequest)

            assertEquals("fake", result.provider)
            assertEquals(2, result.hitCount)
            assertEquals("User likes coffee\nUser is a developer", result.renderedText)
            assertFalse(result.truncated)
            assertEquals(2, result.items.size)
            assertEquals("User likes coffee", result.items[0].summary)
            assertEquals(MemoryKind.NARRATIVE, result.items[0].kind)
            assertEquals(0.95, result.items[0].score)
            assertEquals("User is a developer", result.items[1].summary)
            assertEquals(MemoryKind.FACT, result.items[1].kind)
            assertEquals(listOf("user", "role"), result.items[1].tags)
            // Delegate was NOT called
            assertEquals(0, replayFake.recallCount)
            assertFalse(replayChannel.passthroughMode)

            replayChannel.close()
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `REPLAY diverges on different cue and falls back to live`() {
        val file = Files.createTempFile("session-hippo-div-", ".jsonl")
        try {
            // Record with one cue
            val fake = FakeHippocampus(testResult)
            val recChannel = RecordReplayChannel(
                channelName = "memory_recall",
                mode = SessionRecordingMode.RECORD,
                file = file,
            )
            RecordingHippocampus(delegate = fake, channel = recChannel).recall(testRequest)
            recChannel.close()

            // Replay with a different cue
            val replayFake = FakeHippocampus(testResult)
            val replayChannel = RecordReplayChannel(
                channelName = "memory_recall",
                mode = SessionRecordingMode.REPLAY,
                file = file,
            )
            val replaying = RecordingHippocampus(delegate = replayFake, channel = replayChannel)
            val differentRequest = RecallRequest(
                cue = "something completely different",
                intent = RecallIntent.GENERAL,
                limits = RecallLimits(maxItems = 4, maxChars = 1200),
            )
            replaying.recall(differentRequest)

            assertTrue(replayChannel.passthroughMode)
            assertEquals(1, replayFake.recallCount) // fell back to live

            replayChannel.close()
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `REPLAY exhaustion falls back to live`() {
        val file = Files.createTempFile("session-hippo-exhaust-", ".jsonl")
        try {
            // Record one entry
            val fake = FakeHippocampus(testResult)
            val recChannel = RecordReplayChannel(
                channelName = "memory_recall",
                mode = SessionRecordingMode.RECORD,
                file = file,
            )
            RecordingHippocampus(delegate = fake, channel = recChannel).recall(testRequest)
            recChannel.close()

            // Replay twice — second should exhaust and go live
            val replayFake = FakeHippocampus(testResult)
            val replayChannel = RecordReplayChannel(
                channelName = "memory_recall",
                mode = SessionRecordingMode.REPLAY,
                file = file,
            )
            val replaying = RecordingHippocampus(delegate = replayFake, channel = replayChannel)
            replaying.recall(testRequest) // from cache
            assertEquals(0, replayFake.recallCount)

            replaying.recall(testRequest) // exhausted — goes live
            assertEquals(1, replayFake.recallCount)

            replayChannel.close()
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
