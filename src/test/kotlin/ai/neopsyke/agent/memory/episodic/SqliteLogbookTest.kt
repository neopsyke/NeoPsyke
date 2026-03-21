package ai.neopsyke.agent.memory.episodic

import ai.neopsyke.agent.config.LogbookConfig
import java.nio.file.Files
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SqliteLogbookTest {
    private fun createTempLogbook(): SqliteLogbook {
        val tempDir = Files.createTempDirectory("logbook-test")
        val dbPath = tempDir.resolve("test-logbook.db").toAbsolutePath().toString()
        return SqliteLogbook(LogbookConfig(dbPath = dbPath))
    }

    @Test
    fun `record and query basic entry`() {
        createTempLogbook().use { logbook ->
            val now = Instant.now()
            val id = logbook.record(
                LogbookEntry(
                    ts = now,
                    eventType = EpisodicEventType.INPUT_RECEIVED,
                    summary = "User: hello world",
                    keywords = listOf("hello", "world"),
                    runId = "test-run",
                )
            )
            assertTrue(id > 0)

            val result = logbook.query(LogbookQuery(maxResults = 10))
            assertEquals(1, result.totalMatched)
            assertEquals(1, result.entries.size)
            val entry = result.entries.first()
            assertEquals("User: hello world", entry.summary)
            assertEquals(EpisodicEventType.INPUT_RECEIVED, entry.eventType)
            assertEquals(listOf("hello", "world"), entry.keywords)
            assertEquals("test-run", entry.runId)
        }
    }

    @Test
    fun `query by time range`() {
        createTempLogbook().use { logbook ->
            val old = Instant.now().minus(2, ChronoUnit.DAYS)
            val recent = Instant.now()

            logbook.record(
                LogbookEntry(ts = old, eventType = EpisodicEventType.INPUT_RECEIVED, summary = "Old entry")
            )
            logbook.record(
                LogbookEntry(ts = recent, eventType = EpisodicEventType.CONTACT_DELIVERED, summary = "Recent entry")
            )

            val result = logbook.query(
                LogbookQuery(
                    startTime = Instant.now().minus(1, ChronoUnit.DAYS),
                    maxResults = 10,
                )
            )
            assertEquals(1, result.totalMatched)
            assertEquals("Recent entry", result.entries.first().summary)
        }
    }

    @Test
    fun `query by event type filter`() {
        createTempLogbook().use { logbook ->
            val now = Instant.now()
            logbook.record(
                LogbookEntry(ts = now, eventType = EpisodicEventType.INPUT_RECEIVED, summary = "Input")
            )
            logbook.record(
                LogbookEntry(ts = now, eventType = EpisodicEventType.CONTACT_DELIVERED, summary = "Answer")
            )
            logbook.record(
                LogbookEntry(ts = now, eventType = EpisodicEventType.ACTION_EXECUTED, summary = "Action")
            )

            val result = logbook.query(
                LogbookQuery(eventTypes = setOf(EpisodicEventType.CONTACT_DELIVERED))
            )
            assertEquals(1, result.totalMatched)
            assertEquals("Answer", result.entries.first().summary)
        }
    }

    @Test
    fun `FTS keyword search`() {
        createTempLogbook().use { logbook ->
            val now = Instant.now()
            logbook.record(
                LogbookEntry(
                    ts = now,
                    eventType = EpisodicEventType.INPUT_RECEIVED,
                    summary = "User asked about weather forecast",
                    keywords = listOf("weather", "forecast"),
                )
            )
            logbook.record(
                LogbookEntry(
                    ts = now,
                    eventType = EpisodicEventType.ACTION_EXECUTED,
                    summary = "Executed web search about python programming",
                    keywords = listOf("python", "programming"),
                )
            )

            val result = logbook.query(LogbookQuery(keywordSearch = "weather"))
            assertEquals(1, result.totalMatched)
            assertTrue(result.entries.first().summary.contains("weather"))
        }
    }

    @Test
    fun `prune older than retention days`() {
        createTempLogbook().use { logbook ->
            val old = Instant.now().minus(100, ChronoUnit.DAYS)
            val recent = Instant.now()

            logbook.record(
                LogbookEntry(ts = old, eventType = EpisodicEventType.INPUT_RECEIVED, summary = "Very old")
            )
            logbook.record(
                LogbookEntry(ts = recent, eventType = EpisodicEventType.INPUT_RECEIVED, summary = "Recent")
            )

            val pruned = logbook.pruneOlderThan(retentionDays = 90)
            assertEquals(1, pruned)

            val remaining = logbook.query(LogbookQuery(maxResults = 10))
            assertEquals(1, remaining.totalMatched)
            assertEquals("Recent", remaining.entries.first().summary)
        }
    }

    @Test
    fun `query results are ordered by timestamp descending`() {
        createTempLogbook().use { logbook ->
            val t1 = Instant.now().minus(3, ChronoUnit.HOURS)
            val t2 = Instant.now().minus(2, ChronoUnit.HOURS)
            val t3 = Instant.now().minus(1, ChronoUnit.HOURS)

            logbook.record(LogbookEntry(ts = t2, eventType = EpisodicEventType.INPUT_RECEIVED, summary = "Second"))
            logbook.record(LogbookEntry(ts = t1, eventType = EpisodicEventType.INPUT_RECEIVED, summary = "First"))
            logbook.record(LogbookEntry(ts = t3, eventType = EpisodicEventType.INPUT_RECEIVED, summary = "Third"))

            val result = logbook.query(LogbookQuery(maxResults = 10))
            assertEquals(listOf("Third", "Second", "First"), result.entries.map { it.summary })
        }
    }

    @Test
    fun `query truncation flag set when more results exist`() {
        createTempLogbook().use { logbook ->
            repeat(5) { i ->
                logbook.record(
                    LogbookEntry(
                        ts = Instant.now(),
                        eventType = EpisodicEventType.INPUT_RECEIVED,
                        summary = "Entry $i"
                    )
                )
            }

            val result = logbook.query(LogbookQuery(maxResults = 3))
            assertEquals(5, result.totalMatched)
            assertEquals(3, result.entries.size)
            assertTrue(result.truncated)
        }
    }

    @Test
    fun `action type filter works`() {
        createTempLogbook().use { logbook ->
            val now = Instant.now()
            logbook.record(
                LogbookEntry(
                    ts = now, eventType = EpisodicEventType.ACTION_EXECUTED,
                    summary = "Search", actionType = "web_search"
                )
            )
            logbook.record(
                LogbookEntry(
                    ts = now, eventType = EpisodicEventType.CONTACT_DELIVERED,
                    summary = "Answer", actionType = "contact_user"
                )
            )

            val result = logbook.query(LogbookQuery(actionTypes = setOf("web_search")))
            assertEquals(1, result.totalMatched)
            assertEquals("Search", result.entries.first().summary)
        }
    }
}
