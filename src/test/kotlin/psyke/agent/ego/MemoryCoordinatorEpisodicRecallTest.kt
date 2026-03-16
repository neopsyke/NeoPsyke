package psyke.agent.ego

import psyke.agent.config.AgentConfig
import psyke.agent.model.DialogueRole
import psyke.agent.model.DialogueTurn
import psyke.agent.model.EgoTrigger
import psyke.agent.model.Interlocutor
import psyke.agent.config.LogbookConfig
import psyke.agent.model.PendingInput
import psyke.agent.memory.episodic.EpisodicEventType
import psyke.agent.memory.episodic.Logbook
import psyke.agent.memory.episodic.LogbookEntry
import psyke.agent.memory.episodic.LogbookQuery
import psyke.agent.memory.episodic.LogbookRecall
import psyke.agent.memory.episodic.SqliteLogbook
import psyke.agent.memory.longterm.Hippocampus
import psyke.agent.memory.longterm.MemoryRecall
import psyke.agent.memory.longterm.MemoryRecallQuery
import psyke.agent.memory.longterm.NoopHippocampus
import psyke.agent.memory.longterm.NoopLongTermMemoryAdvisor
import psyke.agent.memory.shortterm.MemoryStore
import psyke.instrumentation.NoopAgentInstrumentation
import java.nio.file.Files
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryCoordinatorEpisodicRecallTest {

    private fun createTempLogbook(): SqliteLogbook {
        val tempDir = Files.createTempDirectory("mc-episodic-test")
        val dbPath = tempDir.resolve("test-logbook.db").toAbsolutePath().toString()
        return SqliteLogbook(LogbookConfig(dbPath = dbPath))
    }

    private fun createCoordinator(
        logbook: Logbook? = null,
        config: AgentConfig = AgentConfig(),
        hippocampus: Hippocampus = NoopHippocampus,
    ): MemoryCoordinator {
        return MemoryCoordinator(
            hippocampus = hippocampus,
            longTermMemoryAdvisor = NoopLongTermMemoryAdvisor,
            config = config,
            instrumentation = NoopAgentInstrumentation,
            initialMemoryStore = MemoryStore(maxChars = 20000),
            logbook = logbook,
        )
    }

    private fun userTurn(content: String): DialogueTurn =
        DialogueTurn(role = DialogueRole.USER, content = content)

    private fun assistantTurn(content: String): DialogueTurn =
        DialogueTurn(role = DialogueRole.ASSISTANT, content = content)

    private fun dummyTrigger(): EgoTrigger =
        EgoTrigger.IncomingInput(PendingInput(id = 1L, content = "test"))

    private fun seedLogbook(logbook: SqliteLogbook, entries: List<LogbookEntry>) {
        entries.forEach { logbook.record(it) }
    }

    // --- recallEpisodic tests ---

    @Test
    fun `recallEpisodic detects what-did-i-ask and returns timeline`() {
        createTempLogbook().use { logbook ->
            val now = Instant.now()
            seedLogbook(logbook, listOf(
                LogbookEntry(
                    ts = now.minus(1, ChronoUnit.HOURS),
                    eventType = EpisodicEventType.INPUT_RECEIVED,
                    summary = "User: what is the weather in Berlin?",
                    keywords = listOf("weather", "berlin"),
                ),
                LogbookEntry(
                    ts = now.minus(50, ChronoUnit.MINUTES),
                    eventType = EpisodicEventType.ACTION_EXECUTED,
                    summary = "Searched weather Berlin",
                    keywords = listOf("weather", "berlin"),
                    actionType = "web_search",
                ),
                LogbookEntry(
                    ts = now.minus(45, ChronoUnit.MINUTES),
                    eventType = EpisodicEventType.CONTACT_DELIVERED,
                    summary = "Berlin: 12C, partly cloudy",
                    keywords = listOf("berlin", "weather", "answer"),
                    actionType = "contact_user",
                ),
            ))

            val mc = createCoordinator(logbook = logbook)
            val dialogue = listOf(
                userTurn("what is the weather in Berlin?"),
                assistantTurn("Berlin: 12C, partly cloudy"),
                userTurn("what did I ask earlier?"),
            )
            val result = mc.recallEpisodic(dummyTrigger(), dialogue)

            assertTrue(result.isNotBlank(), "Expected non-empty episodic recall")
            assertTrue(result.startsWith("Episodic timeline"), "Expected timeline header")
            assertTrue(result.contains("input_received"), "Expected input_received event")
            assertTrue(result.contains("contact_delivered"), "Expected contact_delivered event")
        }
    }

    @Test
    fun `recallEpisodic detects yesterday and returns entries from correct window`() {
        createTempLogbook().use { logbook ->
            val now = Instant.now()
            // Entry from yesterday (30 hours ago)
            seedLogbook(logbook, listOf(
                LogbookEntry(
                    ts = now.minus(30, ChronoUnit.HOURS),
                    eventType = EpisodicEventType.INPUT_RECEIVED,
                    summary = "User: tell me about quantum computing",
                    keywords = listOf("quantum", "computing"),
                ),
            ))
            // Entry from right now (should be excluded for "yesterday")
            seedLogbook(logbook, listOf(
                LogbookEntry(
                    ts = now.minus(5, ChronoUnit.MINUTES),
                    eventType = EpisodicEventType.INPUT_RECEIVED,
                    summary = "User: what is the current time",
                    keywords = listOf("time", "current"),
                ),
            ))

            val mc = createCoordinator(logbook = logbook)
            // Use text that triggers "relative_time_period" pattern (not "what_did_i_ask")
            val dialogue = listOf(userTurn("remind me of yesterday's topics"))
            val result = mc.recallEpisodic(dummyTrigger(), dialogue)

            assertTrue(result.isNotBlank(), "Expected non-empty episodic recall")
            assertTrue(result.contains("quantum"), "Expected yesterday's entry about quantum computing")
            // The recent entry (5 min ago) should be excluded because "yesterday" maps to -48h..-24h
        }
    }

    @Test
    fun `recallEpisodic detects topic recall with keyword search`() {
        createTempLogbook().use { logbook ->
            val now = Instant.now()
            seedLogbook(logbook, listOf(
                LogbookEntry(
                    ts = now.minus(2, ChronoUnit.HOURS),
                    eventType = EpisodicEventType.INPUT_RECEIVED,
                    summary = "User: what is the weather in Berlin?",
                    keywords = listOf("weather", "berlin"),
                ),
                LogbookEntry(
                    ts = now.minus(1, ChronoUnit.HOURS),
                    eventType = EpisodicEventType.INPUT_RECEIVED,
                    summary = "User: tell me about cats",
                    keywords = listOf("cats"),
                ),
            ))

            val mc = createCoordinator(logbook = logbook)
            val dialogue = listOf(userTurn("what did I discuss about weather?"))
            val result = mc.recallEpisodic(dummyTrigger(), dialogue)

            assertTrue(result.isNotBlank(), "Expected non-empty episodic recall for topic query")
            assertTrue(result.contains("weather"), "Expected weather-related entry")
        }
    }

    @Test
    fun `recallEpisodic returns empty for non-temporal input`() {
        createTempLogbook().use { logbook ->
            seedLogbook(logbook, listOf(
                LogbookEntry(
                    ts = Instant.now().minus(1, ChronoUnit.HOURS),
                    eventType = EpisodicEventType.INPUT_RECEIVED,
                    summary = "User: hello world",
                    keywords = listOf("hello", "world"),
                ),
            ))

            val mc = createCoordinator(logbook = logbook)
            val dialogue = listOf(userTurn("tell me a joke"))
            val result = mc.recallEpisodic(dummyTrigger(), dialogue)

            assertEquals("", result, "Expected empty string for non-temporal input")
        }
    }

    @Test
    fun `recallEpisodic returns empty when logbook is null`() {
        val mc = createCoordinator(logbook = null)
        val dialogue = listOf(userTurn("what did I ask earlier?"))
        val result = mc.recallEpisodic(dummyTrigger(), dialogue)

        assertEquals("", result, "Expected empty string when logbook is null")
    }

    @Test
    fun `recallEpisodic returns empty when no entries match`() {
        createTempLogbook().use { logbook ->
            // Logbook is empty — no entries to match
            val mc = createCoordinator(logbook = logbook)
            val dialogue = listOf(userTurn("what did I ask earlier?"))
            val result = mc.recallEpisodic(dummyTrigger(), dialogue)

            assertEquals("", result, "Expected empty string when no entries match")
        }
    }

    @Test
    fun `recallEpisodic handles logbook query exception gracefully`() {
        val failingLogbook = object : Logbook {
            override fun record(entry: LogbookEntry): Long = 1L
            override fun query(query: LogbookQuery): LogbookRecall = throw RuntimeException("DB error")
            override fun pruneOlderThan(retentionDays: Int): Int = 0
            override fun close() {}
        }

        val mc = createCoordinator(logbook = failingLogbook)
        val dialogue = listOf(userTurn("what did I ask earlier?"))
        val result = mc.recallEpisodic(dummyTrigger(), dialogue)

        assertEquals("", result, "Expected empty string on logbook exception")
    }

    @Test
    fun `formatted output respects max chars config`() {
        createTempLogbook().use { logbook ->
            val now = Instant.now()
            // Seed many entries to produce a long formatted output
            val entries = (1..30).map { i ->
                LogbookEntry(
                    ts = now.minus(i.toLong(), ChronoUnit.MINUTES),
                    eventType = EpisodicEventType.INPUT_RECEIVED,
                    summary = "User: question number $i about a somewhat lengthy topic that fills up space nicely",
                    keywords = listOf("question", "number", "topic"),
                )
            }
            seedLogbook(logbook, entries)

            val smallConfig = AgentConfig(
                logbook = LogbookConfig(
                    episodicRecallMaxChars = 300,
                    episodicRecallMaxResults = 30,
                ),
            )
            val mc = createCoordinator(logbook = logbook, config = smallConfig)
            val dialogue = listOf(userTurn("what did I ask earlier?"))
            val result = mc.recallEpisodic(dummyTrigger(), dialogue)

            assertTrue(result.isNotBlank(), "Expected non-empty result")
            assertTrue(result.length <= 300, "Expected output clamped to maxChars=${smallConfig.logbook.episodicRecallMaxChars}, got ${result.length}")
        }
    }

    @Test
    fun `recallEpisodic detects summarize session pattern`() {
        createTempLogbook().use { logbook ->
            val now = Instant.now()
            seedLogbook(logbook, listOf(
                LogbookEntry(
                    ts = now.minus(2, ChronoUnit.HOURS),
                    eventType = EpisodicEventType.INPUT_RECEIVED,
                    summary = "User: help me with taxes",
                    keywords = listOf("taxes", "help"),
                ),
            ))

            val mc = createCoordinator(logbook = logbook)
            val dialogue = listOf(userTurn("summarize our conversation"))
            val result = mc.recallEpisodic(dummyTrigger(), dialogue)

            assertTrue(result.isNotBlank(), "Expected non-empty result for summarize session")
            assertTrue(result.contains("taxes"), "Expected entry about taxes")
        }
    }

    @Test
    fun `recallEpisodic applies current session filter when user requests this session`() {
        createTempLogbook().use { logbook ->
            val now = Instant.now()
            seedLogbook(logbook, listOf(
                LogbookEntry(
                    ts = now.minus(10, ChronoUnit.MINUTES),
                    eventType = EpisodicEventType.INPUT_RECEIVED,
                    summary = "Session A note",
                    sessionId = "session-A",
                ),
                LogbookEntry(
                    ts = now.minus(9, ChronoUnit.MINUTES),
                    eventType = EpisodicEventType.INPUT_RECEIVED,
                    summary = "Session B note",
                    sessionId = "session-B",
                ),
            ))

            val mc = createCoordinator(logbook = logbook)
            mc.setActiveSession("session-A", Interlocutor.named("Victor"))
            val dialogue = listOf(userTurn("what did I ask in this session?"))
            val result = mc.recallEpisodic(dummyTrigger(), dialogue)

            assertTrue(result.contains("Session A note"), "Expected current session entries to be included")
            assertFalse(result.contains("Session B note"), "Expected other-session entries to be excluded")
        }
    }

    @Test
    fun `recallEpisodic applies explicit interlocutor filter when requested`() {
        createTempLogbook().use { logbook ->
            val now = Instant.now()
            seedLogbook(logbook, listOf(
                LogbookEntry(
                    ts = now.minus(10, ChronoUnit.MINUTES),
                    eventType = EpisodicEventType.INPUT_RECEIVED,
                    summary = "Alice memory",
                    interlocutorId = "alice",
                ),
                LogbookEntry(
                    ts = now.minus(9, ChronoUnit.MINUTES),
                    eventType = EpisodicEventType.INPUT_RECEIVED,
                    summary = "Bob memory",
                    interlocutorId = "bob",
                ),
            ))

            val mc = createCoordinator(logbook = logbook)
            mc.setActiveSession("session-A", Interlocutor.named("Victor"))
            val dialogue = listOf(userTurn("what did I ask earlier with interlocutor:alice?"))
            val result = mc.recallEpisodic(dummyTrigger(), dialogue)

            assertTrue(result.contains("Alice memory"), "Expected alice entries to be included")
            assertFalse(result.contains("Bob memory"), "Expected non-matching interlocutor entries to be excluded")
        }
    }
}
