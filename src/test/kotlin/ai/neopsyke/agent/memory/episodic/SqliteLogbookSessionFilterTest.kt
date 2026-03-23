package ai.neopsyke.agent.memory.longterm

import ai.neopsyke.agent.config.LogbookConfig
import java.nio.file.Files
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests session_id and interlocutor_id filtering on SqliteLogbook.
 *
 * Entries with different sessionId/interlocutorId values should be
 * filterable via LogbookQuery, but global queries (no filter) should
 * return all entries regardless.
 */
class SqliteLogbookSessionFilterTest {

    private fun createTempLogbook(): SqliteLogbook {
        val tempDir = Files.createTempDirectory("logbook-session-test")
        val dbPath = tempDir.resolve("test-logbook.db").toAbsolutePath().toString()
        return SqliteLogbook(LogbookConfig(dbPath = dbPath))
    }

    @Test
    fun `entries with session_id are persisted and returned`() {
        createTempLogbook().use { logbook ->
            logbook.record(
                LogbookEntry(
                    ts = Instant.now(),
                    eventType = EpisodicEventType.INPUT_RECEIVED,
                    summary = "Hello from session A",
                    sessionId = "session-A",
                    interlocutorId = "alice",
                )
            )

            val result = logbook.query(LogbookQuery(maxResults = 10))
            assertEquals(1, result.totalMatched)
            val entry = result.entries.first()
            assertEquals("session-A", entry.sessionId)
            assertEquals("alice", entry.interlocutorId)
        }
    }

    @Test
    fun `query by sessionId filters correctly`() {
        createTempLogbook().use { logbook ->
            val now = Instant.now()
            logbook.record(
                LogbookEntry(
                    ts = now.minus(2, ChronoUnit.MINUTES),
                    eventType = EpisodicEventType.INPUT_RECEIVED,
                    summary = "From session A",
                    sessionId = "session-A",
                )
            )
            logbook.record(
                LogbookEntry(
                    ts = now.minus(1, ChronoUnit.MINUTES),
                    eventType = EpisodicEventType.INPUT_RECEIVED,
                    summary = "From session B",
                    sessionId = "session-B",
                )
            )

            val resultA = logbook.query(LogbookQuery(sessionId = "session-A", maxResults = 10))
            assertEquals(1, resultA.totalMatched)
            assertEquals("From session A", resultA.entries.first().summary)

            val resultB = logbook.query(LogbookQuery(sessionId = "session-B", maxResults = 10))
            assertEquals(1, resultB.totalMatched)
            assertEquals("From session B", resultB.entries.first().summary)
        }
    }

    @Test
    fun `query by interlocutorId filters correctly`() {
        createTempLogbook().use { logbook ->
            val now = Instant.now()
            logbook.record(
                LogbookEntry(
                    ts = now.minus(2, ChronoUnit.MINUTES),
                    eventType = EpisodicEventType.INPUT_RECEIVED,
                    summary = "Alice says hello",
                    interlocutorId = "alice",
                )
            )
            logbook.record(
                LogbookEntry(
                    ts = now.minus(1, ChronoUnit.MINUTES),
                    eventType = EpisodicEventType.INPUT_RECEIVED,
                    summary = "Bob says hello",
                    interlocutorId = "bob",
                )
            )

            val resultAlice = logbook.query(LogbookQuery(interlocutorId = "alice", maxResults = 10))
            assertEquals(1, resultAlice.totalMatched)
            assertEquals("Alice says hello", resultAlice.entries.first().summary)

            val resultBob = logbook.query(LogbookQuery(interlocutorId = "bob", maxResults = 10))
            assertEquals(1, resultBob.totalMatched)
            assertEquals("Bob says hello", resultBob.entries.first().summary)
        }
    }

    @Test
    fun `global query returns all entries regardless of session`() {
        createTempLogbook().use { logbook ->
            val now = Instant.now()
            logbook.record(
                LogbookEntry(
                    ts = now.minus(3, ChronoUnit.MINUTES),
                    eventType = EpisodicEventType.INPUT_RECEIVED,
                    summary = "No session entry",
                )
            )
            logbook.record(
                LogbookEntry(
                    ts = now.minus(2, ChronoUnit.MINUTES),
                    eventType = EpisodicEventType.INPUT_RECEIVED,
                    summary = "Session A entry",
                    sessionId = "session-A",
                )
            )
            logbook.record(
                LogbookEntry(
                    ts = now.minus(1, ChronoUnit.MINUTES),
                    eventType = EpisodicEventType.INPUT_RECEIVED,
                    summary = "Session B entry",
                    sessionId = "session-B",
                )
            )

            val result = logbook.query(LogbookQuery(maxResults = 10))
            assertEquals(3, result.totalMatched, "Global query should return all entries")
        }
    }

    @Test
    fun `entries without session fields have null session and interlocutor`() {
        createTempLogbook().use { logbook ->
            logbook.record(
                LogbookEntry(
                    ts = Instant.now(),
                    eventType = EpisodicEventType.INPUT_RECEIVED,
                    summary = "Plain entry",
                )
            )

            val result = logbook.query(LogbookQuery(maxResults = 10))
            assertEquals(1, result.totalMatched)
            val entry = result.entries.first()
            assertNull(entry.sessionId, "Session ID should be null for entries without session")
            assertNull(entry.interlocutorId, "Interlocutor ID should be null for entries without interlocutor")
        }
    }

    @Test
    fun `combined session and interlocutor filter`() {
        createTempLogbook().use { logbook ->
            val now = Instant.now()
            logbook.record(
                LogbookEntry(
                    ts = now.minus(3, ChronoUnit.MINUTES),
                    eventType = EpisodicEventType.INPUT_RECEIVED,
                    summary = "Alice in session A",
                    sessionId = "session-A",
                    interlocutorId = "alice",
                )
            )
            logbook.record(
                LogbookEntry(
                    ts = now.minus(2, ChronoUnit.MINUTES),
                    eventType = EpisodicEventType.INPUT_RECEIVED,
                    summary = "Bob in session A",
                    sessionId = "session-A",
                    interlocutorId = "bob",
                )
            )
            logbook.record(
                LogbookEntry(
                    ts = now.minus(1, ChronoUnit.MINUTES),
                    eventType = EpisodicEventType.INPUT_RECEIVED,
                    summary = "Alice in session B",
                    sessionId = "session-B",
                    interlocutorId = "alice",
                )
            )

            val result = logbook.query(
                LogbookQuery(sessionId = "session-A", interlocutorId = "alice", maxResults = 10)
            )
            assertEquals(1, result.totalMatched)
            assertEquals("Alice in session A", result.entries.first().summary)
        }
    }

    @Test
    fun `schema migration is safe to run on fresh database`() {
        // Simply creating a new logbook should succeed (migration runs in init)
        createTempLogbook().use { logbook ->
            logbook.record(
                LogbookEntry(
                    ts = Instant.now(),
                    eventType = EpisodicEventType.INPUT_RECEIVED,
                    summary = "Smoke test",
                    sessionId = "s1",
                    interlocutorId = "u1",
                )
            )
            val result = logbook.query(LogbookQuery(maxResults = 10))
            assertEquals(1, result.totalMatched)
        }
    }

    @Test
    fun `schema migration is idempotent`() {
        val tempDir = Files.createTempDirectory("logbook-migration-test")
        val dbPath = tempDir.resolve("test-logbook.db").toAbsolutePath().toString()
        val config = LogbookConfig(dbPath = dbPath)

        // Create logbook, record, close
        SqliteLogbook(config).use { logbook ->
            logbook.record(
                LogbookEntry(
                    ts = Instant.now(),
                    eventType = EpisodicEventType.INPUT_RECEIVED,
                    summary = "First open",
                    sessionId = "s1",
                )
            )
        }

        // Reopen — migration should run again without errors
        SqliteLogbook(config).use { logbook ->
            logbook.record(
                LogbookEntry(
                    ts = Instant.now(),
                    eventType = EpisodicEventType.INPUT_RECEIVED,
                    summary = "Second open",
                    sessionId = "s2",
                )
            )
            val result = logbook.query(LogbookQuery(maxResults = 10))
            assertEquals(2, result.totalMatched, "Both entries should be present after re-open")
        }
    }
}
