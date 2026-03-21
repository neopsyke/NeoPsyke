package ai.neopsyke.agent.ego

import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.config.MemoryConfig
import ai.neopsyke.agent.model.ActionOrigin
import ai.neopsyke.agent.memory.episodic.EpisodicEventType
import ai.neopsyke.agent.memory.episodic.Logbook
import ai.neopsyke.agent.memory.episodic.LogbookEntry
import ai.neopsyke.agent.memory.episodic.LogbookQuery
import ai.neopsyke.agent.memory.episodic.LogbookRecall
import ai.neopsyke.agent.memory.longterm.Hippocampus
import ai.neopsyke.agent.memory.longterm.LongTermMemoryAdvisor
import ai.neopsyke.agent.memory.longterm.LongTermMemoryAssessmentContext
import ai.neopsyke.agent.memory.longterm.LongTermMemoryAssessmentDecision
import ai.neopsyke.agent.memory.longterm.MemoryImprint
import ai.neopsyke.agent.memory.longterm.MemoryRecall
import ai.neopsyke.agent.memory.longterm.MemoryRecallQuery
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.Interlocutor
import ai.neopsyke.agent.model.DeliberationState
import ai.neopsyke.agent.model.DialogueRole
import ai.neopsyke.agent.model.DialogueTurn
import ai.neopsyke.agent.model.OriginSource
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.model.Urgency
import ai.neopsyke.instrumentation.NoopAgentInstrumentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemorySystemLogbookNarrativeTest {
    @Test
    fun `successful memory imprint journals first person summary`() {
        val logbook = RecordingLogbook()
        val hippocampus = RecordingHippocampus()
        val coordinator = MemorySystem(
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
        val coordinator = MemorySystem(
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
        val coordinator = MemorySystem(
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
        val coordinator = MemorySystem(
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

    @Test
    fun `recent learning topics keep exact topics and dedupe exact repeats`() {
        val coordinator = MemorySystem(
            hippocampus = RecordingHippocampus(),
            longTermMemoryAdvisor = object : LongTermMemoryAdvisor {
                override fun assess(context: LongTermMemoryAssessmentContext): LongTermMemoryAssessmentDecision =
                    LongTermMemoryAssessmentDecision(false, "", 0.0, "unused")
            },
            config = AgentConfig(),
            instrumentation = NoopAgentInstrumentation,
            logbook = RecordingLogbook(),
        )

        val learningOrigin = ActionOrigin(
            source = OriginSource.ID,
            needId = "learn-something",
            rootImpulseId = "impulse-learn",
        )
        coordinator.recordReflection(
            action = PendingAction(
                id = 1,
                urgency = Urgency.MEDIUM,
                type = ActionType.REFLECT,
                payload = """{"summary":"I learned about Kotlin coroutines"}""",
                summary = "reflect",
                origin = learningOrigin,
            ),
            summary = "I learned about Kotlin coroutines",
            keywords = listOf("kotlin", "coroutines"),
        )
        coordinator.recordReflection(
            action = PendingAction(
                id = 2,
                urgency = Urgency.MEDIUM,
                type = ActionType.REFLECT,
                payload = """{"summary":"I learned about Kotlin coroutines again"}""",
                summary = "reflect",
                origin = learningOrigin,
            ),
            summary = "I learned about Kotlin coroutines again",
            keywords = listOf("coroutines", "kotlin"),
        )
        coordinator.recordReflection(
            action = PendingAction(
                id = 3,
                urgency = Urgency.MEDIUM,
                type = ActionType.REFLECT,
                payload = """{"summary":"I dug into coroutine cancellation"}""",
                summary = "reflect",
                origin = learningOrigin,
            ),
            summary = "I dug into coroutine cancellation",
            keywords = listOf("kotlin", "coroutine cancellation"),
        )

        val topics = coordinator.recentExactLearningTopics()

        assertTrue(topics.contains("coroutines, kotlin"))
        assertTrue(topics.contains("kotlin, coroutine cancellation"))
        assertEquals(1, topics.count { it == "coroutines, kotlin" })
    }

    @Test
    fun `recent useful actions or updates reads useful episodic events`() {
        val logbook = RecordingLogbook().apply {
            record(
                LogbookEntry(
                    ts = java.time.Instant.now(),
                    eventType = EpisodicEventType.CONTACT_DELIVERED,
                    summary = "Shared a useful update about the memory subsystem",
                )
            )
            record(
                LogbookEntry(
                    ts = java.time.Instant.now().minusSeconds(5),
                    eventType = EpisodicEventType.ACTION_DENIED,
                    summary = "Denied external action",
                )
            )
        }
        val coordinator = MemorySystem(
            hippocampus = RecordingHippocampus(),
            longTermMemoryAdvisor = object : LongTermMemoryAdvisor {
                override fun assess(context: LongTermMemoryAssessmentContext): LongTermMemoryAssessmentDecision =
                    LongTermMemoryAssessmentDecision(false, "", 0.0, "unused")
            },
            config = AgentConfig(),
            instrumentation = NoopAgentInstrumentation,
            logbook = logbook,
        )

        val updates = coordinator.recentUsefulActionsOrUpdates()

        assertEquals(1, updates.size)
        assertTrue(updates.single().contains("memory subsystem"))
    }

    private class RecordingLogbook : Logbook {
        val entries = mutableListOf<LogbookEntry>()

        override fun record(entry: LogbookEntry): Long {
            entries += entry
            return entries.size.toLong()
        }

        override fun query(query: LogbookQuery): LogbookRecall {
            val filtered = entries
                .asReversed()
                .filter { entry ->
                    (query.eventTypes.isNullOrEmpty() || query.eventTypes.contains(entry.eventType)) &&
                        (query.actionTypes.isNullOrEmpty() || query.actionTypes.contains(entry.actionType)) &&
                        (query.sessionId == null || query.sessionId == entry.sessionId) &&
                        (query.interlocutorId == null || query.interlocutorId == entry.interlocutorId)
                }
                .take(query.maxResults)
            return LogbookRecall(entries = filtered, totalMatched = filtered.size, truncated = false)
        }

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
