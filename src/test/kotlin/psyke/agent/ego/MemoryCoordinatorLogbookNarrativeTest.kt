package psyke.agent.ego

import psyke.agent.config.AgentConfig
import psyke.agent.config.MemoryConfig
import psyke.agent.model.ActionOrigin
import psyke.agent.memory.episodic.EpisodicEventType
import psyke.agent.memory.episodic.Logbook
import psyke.agent.memory.episodic.LogbookEntry
import psyke.agent.memory.episodic.LogbookQuery
import psyke.agent.memory.episodic.LogbookRecall
import psyke.agent.memory.longterm.Hippocampus
import psyke.agent.memory.longterm.LongTermMemoryAdvisor
import psyke.agent.memory.longterm.LongTermMemoryAssessmentContext
import psyke.agent.memory.longterm.LongTermMemoryAssessmentDecision
import psyke.agent.memory.longterm.MemoryImprint
import psyke.agent.memory.longterm.MemoryRecall
import psyke.agent.memory.longterm.MemoryRecallQuery
import psyke.agent.model.ActionType
import psyke.agent.model.Interlocutor
import psyke.agent.model.DeliberationState
import psyke.agent.model.DialogueRole
import psyke.agent.model.DialogueTurn
import psyke.agent.model.OriginSource
import psyke.agent.model.PendingAction
import psyke.agent.model.Urgency
import psyke.instrumentation.NoopAgentInstrumentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryCoordinatorLogbookNarrativeTest {
    @Test
    fun `successful memory imprint journals first person summary`() {
        val logbook = RecordingLogbook()
        val hippocampus = RecordingHippocampus()
        val coordinator = MemoryCoordinator(
            hippocampus = hippocampus,
            longTermMemoryAdvisor = object : LongTermMemoryAdvisor {
                override fun assess(context: LongTermMemoryAssessmentContext): LongTermMemoryAssessmentDecision =
                    LongTermMemoryAssessmentDecision(
                        shouldSave = true,
                        summary = "User prefers concise answers.",
                        confidence = 0.95,
                        reason = "durable preference",
                    )
            },
            config = AgentConfig(
                memory = MemoryConfig(
                    longTermMemoryMinConfidence = 0.5
                )
            ),
            instrumentation = NoopAgentInstrumentation,
            logbook = logbook,
        )

        coordinator.maybeAssessLongTermMemory(
            trigger = "post_terminal_answer",
            force = true,
            deliberation = DeliberationState(stepIndex = 1),
            recentDialogue = listOf(DialogueTurn(DialogueRole.USER, "remember that I prefer concise answers"))
        )

        assertTrue(hippocampus.imprints.isNotEmpty())
        val entry = logbook.entries.single()
        assertEquals(EpisodicEventType.MEMORY_IMPRINT, entry.eventType)
        assertEquals("I learned: User prefers concise answers.", entry.summary)
    }

    @Test
    fun `lesson imprint journals first person lesson summary`() {
        val logbook = RecordingLogbook()
        val coordinator = MemoryCoordinator(
            hippocampus = RecordingHippocampus(),
            longTermMemoryAdvisor = object : LongTermMemoryAdvisor {
                override fun assess(context: LongTermMemoryAssessmentContext): LongTermMemoryAssessmentDecision =
                    LongTermMemoryAssessmentDecision(false, "", 0.0, "unused")
            },
            config = AgentConfig(),
            instrumentation = NoopAgentInstrumentation,
            logbook = logbook,
        )

        coordinator.maybeRecordLesson(
            trigger = "action_denied_superego",
            actionType = ActionType.WEB_SEARCH,
            reasonCode = "INSUFFICIENT_EVIDENCE",
            reason = "Need verified evidence before answering.",
            deniedPayload = "latest pricing",
            recentDialogue = listOf(DialogueTurn(DialogueRole.USER, "what is the latest pricing?")),
            stepIndex = 3,
        )

        val entry = logbook.entries.single()
        assertEquals(EpisodicEventType.MEMORY_IMPRINT, entry.eventType)
        assertTrue(entry.summary.startsWith("I learned a lesson: "))
        assertFalse(entry.summary.startsWith("Lesson: LESSON:"))
    }

    @Test
    fun `recordReflection persists first person durable memory with provenance`() {
        val logbook = RecordingLogbook()
        val hippocampus = RecordingHippocampus()
        val coordinator = MemoryCoordinator(
            hippocampus = hippocampus,
            longTermMemoryAdvisor = object : LongTermMemoryAdvisor {
                override fun assess(context: LongTermMemoryAssessmentContext): LongTermMemoryAssessmentDecision =
                    LongTermMemoryAssessmentDecision(false, "", 0.0, "unused")
            },
            config = AgentConfig(),
            instrumentation = NoopAgentInstrumentation,
            logbook = logbook,
            runId = "run-123",
        )
        coordinator.setActiveSession("session-42", Interlocutor.named("Victor"))

        val saved = coordinator.recordReflection(
            action = PendingAction(
                id = 7,
                urgency = Urgency.MEDIUM,
                type = ActionType.REFLECT,
                payload = """{"summary":"The agent learned about Kotlin"}""",
                summary = "reflect",
                rootInputId = "root-input-9",
                origin = ActionOrigin(
                    source = OriginSource.ID,
                    needId = "be-useful",
                    rootImpulseId = "impulse-1",
                ),
            ),
            summary = "The agent learned about Kotlin",
            keywords = listOf("kotlin", "learning"),
        )

        assertTrue(saved)
        val entry = logbook.entries.single()
        assertEquals(EpisodicEventType.SELF_INITIATED, entry.eventType)
        assertEquals("I learned: The agent learned about Kotlin", entry.summary)
        assertEquals("reflect", entry.actionType)
        assertEquals("run-123", entry.runId)
        assertEquals("session-42", entry.sessionId)
        assertEquals("Victor", entry.interlocutorId)
        assertEquals(true, entry.metadata?.get("self_initiated"))
        assertEquals("be-useful", entry.metadata?.get("need_id"))
        assertEquals("impulse-1", entry.metadata?.get("root_impulse_id"))
        assertEquals("root-input-9", entry.metadata?.get("root_input_id"))

        val imprint = hippocampus.imprints.single()
        assertEquals("I learned: The agent learned about Kotlin", imprint.summary)
        assertEquals("self_initiated_reflection", imprint.source)
        assertTrue(imprint.tags.contains("self_initiated"))
        assertTrue(imprint.tags.contains("session:session-42"))
        assertTrue(imprint.tags.contains("interlocutor:Victor"))
        assertTrue(imprint.tags.contains("need:be-useful"))
        assertTrue(imprint.tags.contains("root_impulse:impulse-1"))
        assertTrue(imprint.tags.contains("root_input:root-input-9"))
        assertTrue(imprint.tags.contains("kotlin"))
    }

    @Test
    fun `recordReflection returns false when durable memory save fails`() {
        val logbook = RecordingLogbook()
        val coordinator = MemoryCoordinator(
            hippocampus = RecordingHippocampus(imprintResult = false),
            longTermMemoryAdvisor = object : LongTermMemoryAdvisor {
                override fun assess(context: LongTermMemoryAssessmentContext): LongTermMemoryAssessmentDecision =
                    LongTermMemoryAssessmentDecision(false, "", 0.0, "unused")
            },
            config = AgentConfig(),
            instrumentation = NoopAgentInstrumentation,
            logbook = logbook,
        )

        val saved = coordinator.recordReflection(
            action = PendingAction(
                id = 8,
                urgency = Urgency.MEDIUM,
                type = ActionType.REFLECT,
                payload = """{"summary":"Failed save"}""",
                summary = "reflect",
            ),
            summary = "Failed save",
            keywords = listOf("failure"),
        )

        assertFalse(saved)
        assertEquals(1, logbook.entries.size, "Reflection should still be journaled for diagnostics")
    }

    private class RecordingLogbook : Logbook {
        val entries = mutableListOf<LogbookEntry>()

        override fun record(entry: LogbookEntry): Long {
            entries += entry
            return entries.size.toLong()
        }

        override fun query(query: LogbookQuery): LogbookRecall =
            LogbookRecall(entries = emptyList(), totalMatched = 0, truncated = false)

        override fun pruneOlderThan(retentionDays: Int): Int = 0

        override fun close() {}
    }

    private class RecordingHippocampus(
        private val imprintResult: Boolean = true,
    ) : Hippocampus {
        override val providerName: String = "recording"
        override val enabled: Boolean = true
        val imprints = mutableListOf<MemoryImprint>()

        override fun recall(query: MemoryRecallQuery): MemoryRecall =
            MemoryRecall(provider = providerName, text = "")

        override fun imprint(imprint: MemoryImprint): Boolean {
            imprints += imprint
            return imprintResult
        }
    }
}
