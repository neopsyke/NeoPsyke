package psyke.agent.ego

import psyke.agent.config.AgentConfig
import psyke.agent.model.DialogueRole
import psyke.agent.model.DialogueTurn
import psyke.agent.model.EgoTrigger
import psyke.agent.model.Interlocutor
import psyke.agent.config.LogbookConfig
import psyke.agent.model.PendingInput
import psyke.agent.memory.episodic.EpisodicEventType
import psyke.agent.memory.episodic.LogbookEntry
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

class MemoryCoordinatorTemporalVectorTest {

    private fun createTempLogbook(): SqliteLogbook {
        val tempDir = Files.createTempDirectory("mc-vector-test")
        val dbPath = tempDir.resolve("test-logbook.db").toAbsolutePath().toString()
        return SqliteLogbook(LogbookConfig(dbPath = dbPath))
    }

    private fun createCoordinator(
        logbook: SqliteLogbook? = null,
        hippocampus: Hippocampus = NoopHippocampus,
    ): MemoryCoordinator {
        return MemoryCoordinator(
            hippocampus = hippocampus,
            longTermMemoryAdvisor = NoopLongTermMemoryAdvisor,
            config = AgentConfig(),
            instrumentation = NoopAgentInstrumentation,
            initialMemoryStore = MemoryStore(maxChars = 20000),
            logbook = logbook,
        )
    }

    private fun userTurn(content: String): DialogueTurn =
        DialogueTurn(role = DialogueRole.USER, content = content)

    private fun dummyTrigger(): EgoTrigger =
        EgoTrigger.IncomingInput(PendingInput(id = 1L, content = "test"))

    // --- recallEpisodicAsVectorCues tests ---

    @Test
    fun `recallEpisodicAsVectorCues returns summaries for temporal intent`() {
        createTempLogbook().use { logbook ->
            val now = Instant.now()
            logbook.record(LogbookEntry(
                ts = now.minus(1, ChronoUnit.HOURS),
                eventType = EpisodicEventType.INPUT_RECEIVED,
                summary = "User: what is the weather in Berlin?",
                keywords = listOf("weather", "berlin"),
            ))
            logbook.record(LogbookEntry(
                ts = now.minus(50, ChronoUnit.MINUTES),
                eventType = EpisodicEventType.PLANNER_DECISION,
                summary = "Decision: thought — need to search",
                keywords = listOf("decision", "thought"),
            ))
            logbook.record(LogbookEntry(
                ts = now.minus(45, ChronoUnit.MINUTES),
                eventType = EpisodicEventType.ANSWER_DELIVERED,
                summary = "Berlin: 12C, partly cloudy",
                keywords = listOf("berlin", "weather"),
                actionType = "answer",
            ))

            val mc = createCoordinator(logbook = logbook)
            val dialogue = listOf(userTurn("what did I ask earlier?"))
            val cues = mc.recallEpisodicAsVectorCues(dialogue)

            assertTrue(cues.isNotEmpty(), "Expected non-empty vector cues")
            // Should only include INPUT_RECEIVED and ANSWER_DELIVERED, not PLANNER_DECISION
            assertTrue(cues.any { it.contains("weather") }, "Expected weather-related cue")
            assertTrue(cues.none { it.contains("Decision: thought") }, "Should exclude PLANNER_DECISION events")
        }
    }

    @Test
    fun `recallEpisodicAsVectorCues returns empty for non-temporal input`() {
        createTempLogbook().use { logbook ->
            logbook.record(LogbookEntry(
                ts = Instant.now().minus(1, ChronoUnit.HOURS),
                eventType = EpisodicEventType.INPUT_RECEIVED,
                summary = "User: hello world",
                keywords = listOf("hello", "world"),
            ))

            val mc = createCoordinator(logbook = logbook)
            val dialogue = listOf(userTurn("tell me a joke"))
            val cues = mc.recallEpisodicAsVectorCues(dialogue)

            assertTrue(cues.isEmpty(), "Expected empty cues for non-temporal input")
        }
    }

    @Test
    fun `recallEpisodicAsVectorCues caps at MAX_EPISODIC_VECTOR_CUES`() {
        createTempLogbook().use { logbook ->
            val now = Instant.now()
            // Seed 10 INPUT_RECEIVED entries
            (1..10).forEach { i ->
                logbook.record(LogbookEntry(
                    ts = now.minus(i.toLong(), ChronoUnit.MINUTES),
                    eventType = EpisodicEventType.INPUT_RECEIVED,
                    summary = "User: question number $i",
                    keywords = listOf("question", "number"),
                ))
            }

            val mc = createCoordinator(logbook = logbook)
            val dialogue = listOf(userTurn("what did I ask earlier?"))
            val cues = mc.recallEpisodicAsVectorCues(dialogue)

            assertTrue(cues.size <= 5, "Expected at most 5 vector cues (MAX_EPISODIC_VECTOR_CUES), got ${cues.size}")
        }
    }

    @Test
    fun `recall with episodic cues includes temporal_context in cue`() {
        createTempLogbook().use { logbook ->
            val now = Instant.now()
            logbook.record(LogbookEntry(
                ts = now.minus(1, ChronoUnit.HOURS),
                eventType = EpisodicEventType.INPUT_RECEIVED,
                summary = "User: what is quantum entanglement?",
                keywords = listOf("quantum", "entanglement"),
            ))

            // Use a recording hippocampus to capture the recall query cue
            var capturedCue: String? = null
            val recordingHippocampus = object : Hippocampus {
                override val providerName: String = "recording"
                override val enabled: Boolean = true
                override fun recall(query: MemoryRecallQuery): MemoryRecall {
                    capturedCue = query.cue
                    return MemoryRecall(provider = providerName, text = "", hitCount = 0)
                }
            }

            val mc = createCoordinator(logbook = logbook, hippocampus = recordingHippocampus)
            val dialogue = listOf(userTurn("what did I ask earlier?"))
            val episodicCues = mc.recallEpisodicAsVectorCues(dialogue)
            mc.recall(dummyTrigger(), "", dialogue, episodicCues)

            assertTrue(capturedCue != null, "Expected hippocampus to be called")
            assertTrue(
                capturedCue!!.contains("temporal_context"),
                "Expected recall cue to contain temporal_context section, got: $capturedCue"
            )
            assertTrue(
                capturedCue!!.contains("quantum"),
                "Expected temporal_context to contain episodic summary content"
            )
        }
    }

    @Test
    fun `recall without episodic cues does not include temporal_context`() {
        var capturedCue: String? = null
        val recordingHippocampus = object : Hippocampus {
            override val providerName: String = "recording"
            override val enabled: Boolean = true
            override fun recall(query: MemoryRecallQuery): MemoryRecall {
                capturedCue = query.cue
                return MemoryRecall(provider = providerName, text = "", hitCount = 0)
            }
        }

        val mc = createCoordinator(hippocampus = recordingHippocampus)
        val dialogue = listOf(userTurn("tell me about cats"))
        mc.recall(dummyTrigger(), "", dialogue)

        assertTrue(capturedCue != null, "Expected hippocampus to be called")
        assertTrue(
            !capturedCue!!.contains("temporal_context"),
            "Expected no temporal_context in regular recall, got: $capturedCue"
        )
    }

    @Test
    fun `recallEpisodicAsVectorCues applies this-session filter when requested`() {
        createTempLogbook().use { logbook ->
            val now = Instant.now()
            logbook.record(LogbookEntry(
                ts = now.minus(5, ChronoUnit.MINUTES),
                eventType = EpisodicEventType.INPUT_RECEIVED,
                summary = "Session A cue",
                sessionId = "session-A",
            ))
            logbook.record(LogbookEntry(
                ts = now.minus(4, ChronoUnit.MINUTES),
                eventType = EpisodicEventType.INPUT_RECEIVED,
                summary = "Session B cue",
                sessionId = "session-B",
            ))

            val mc = createCoordinator(logbook = logbook)
            mc.setActiveSession("session-A", Interlocutor.named("Victor"))
            val dialogue = listOf(userTurn("what did I ask in this session?"))
            val cues = mc.recallEpisodicAsVectorCues(dialogue)

            assertTrue(cues.any { it.contains("Session A cue") }, "Expected current-session cue")
            assertFalse(cues.any { it.contains("Session B cue") }, "Expected non-current-session cue to be excluded")
        }
    }
}
