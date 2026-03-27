package ai.neopsyke.session

import ai.neopsyke.agent.cortex.motor.actions.websearch.WebSearchEngine
import ai.neopsyke.agent.cortex.motor.actions.websearch.WebSearchResult
import ai.neopsyke.agent.cortex.motor.actions.websearch.WebSearchSource
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecordingWebSearchEngineTest {

    private class FakeWebSearchEngine(
        private val result: WebSearchResult,
    ) : WebSearchEngine {
        var searchCount = 0
            private set

        override fun search(query: String, maxResults: Int): WebSearchResult {
            searchCount++
            return result
        }
    }

    private val testResult = WebSearchResult(
        summary = "Kotlin is a modern programming language.",
        snippets = listOf("Kotlin is developed by JetBrains.", "Kotlin runs on the JVM."),
        sources = listOf(
            WebSearchSource(title = "Kotlin Official", url = "https://kotlinlang.org", snippet = "Official site"),
            WebSearchSource(title = "Wikipedia", url = "https://en.wikipedia.org/wiki/Kotlin", snippet = null),
        ),
    )

    @Test
    fun `RECORD mode delegates and records search`() {
        val file = Files.createTempFile("session-web-rec-", ".jsonl")
        try {
            val fake = FakeWebSearchEngine(testResult)
            val channel = RecordReplayChannel(
                channelName = "web_results",
                mode = SessionRecordingMode.RECORD,
                file = file,
            )
            val recording = RecordingWebSearchEngine(delegate = fake, channel = channel)

            val result = recording.search("kotlin language", 5)
            assertEquals("Kotlin is a modern programming language.", result.summary)
            assertEquals(1, fake.searchCount)

            channel.close()
            val lines = Files.readAllLines(file).filter { it.isNotBlank() }
            assertEquals(1, lines.size)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `REPLAY returns cached result on hash match`() {
        val file = Files.createTempFile("session-web-replay-", ".jsonl")
        try {
            // Record
            val fake = FakeWebSearchEngine(testResult)
            val recChannel = RecordReplayChannel(
                channelName = "web_results",
                mode = SessionRecordingMode.RECORD,
                file = file,
            )
            RecordingWebSearchEngine(delegate = fake, channel = recChannel).search("kotlin language", 5)
            recChannel.close()

            // Replay
            val replayFake = FakeWebSearchEngine(testResult)
            val replayChannel = RecordReplayChannel(
                channelName = "web_results",
                mode = SessionRecordingMode.REPLAY,
                file = file,
            )
            val replaying = RecordingWebSearchEngine(delegate = replayFake, channel = replayChannel)
            val result = replaying.search("kotlin language", 5)

            assertEquals("Kotlin is a modern programming language.", result.summary)
            assertEquals(2, result.snippets.size)
            assertEquals(2, result.sources.size)
            assertEquals("Kotlin Official", result.sources[0].title)
            assertEquals("https://kotlinlang.org", result.sources[0].url)
            assertEquals("Official site", result.sources[0].snippet)
            assertEquals("Wikipedia", result.sources[1].title)
            assertEquals(null, result.sources[1].snippet)
            // Delegate was NOT called
            assertEquals(0, replayFake.searchCount)
            assertFalse(replayChannel.passthroughMode)

            replayChannel.close()
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `REPLAY diverges on different query and falls back to live`() {
        val file = Files.createTempFile("session-web-div-", ".jsonl")
        try {
            val fake = FakeWebSearchEngine(testResult)
            val recChannel = RecordReplayChannel(
                channelName = "web_results",
                mode = SessionRecordingMode.RECORD,
                file = file,
            )
            RecordingWebSearchEngine(delegate = fake, channel = recChannel).search("kotlin language", 5)
            recChannel.close()

            val replayFake = FakeWebSearchEngine(testResult)
            val replayChannel = RecordReplayChannel(
                channelName = "web_results",
                mode = SessionRecordingMode.REPLAY,
                file = file,
            )
            val replaying = RecordingWebSearchEngine(delegate = replayFake, channel = replayChannel)
            replaying.search("java language", 5) // different query

            assertTrue(replayChannel.passthroughMode)
            assertEquals(1, replayFake.searchCount)

            replayChannel.close()
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `REPLAY diverges on different maxResults and falls back to live`() {
        val file = Files.createTempFile("session-web-maxr-", ".jsonl")
        try {
            val fake = FakeWebSearchEngine(testResult)
            val recChannel = RecordReplayChannel(
                channelName = "web_results",
                mode = SessionRecordingMode.RECORD,
                file = file,
            )
            RecordingWebSearchEngine(delegate = fake, channel = recChannel).search("kotlin language", 5)
            recChannel.close()

            val replayFake = FakeWebSearchEngine(testResult)
            val replayChannel = RecordReplayChannel(
                channelName = "web_results",
                mode = SessionRecordingMode.REPLAY,
                file = file,
            )
            val replaying = RecordingWebSearchEngine(delegate = replayFake, channel = replayChannel)
            replaying.search("kotlin language", 10) // different maxResults

            assertTrue(replayChannel.passthroughMode)
            assertEquals(1, replayFake.searchCount)

            replayChannel.close()
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
