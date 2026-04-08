package ai.neopsyke.agent.ego

import ai.neopsyke.agent.deferredTrigger
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.config.MemoryConfig
import ai.neopsyke.agent.model.ActionOrigin
import ai.neopsyke.agent.memory.episodic.EpisodicEventType
import ai.neopsyke.agent.memory.episodic.Logbook
import ai.neopsyke.agent.memory.episodic.LogbookEntry
import ai.neopsyke.agent.memory.episodic.LogbookQuery
import ai.neopsyke.agent.memory.episodic.LogbookRecall
import ai.neopsyke.agent.memory.longterm.Hippocampus
import ai.neopsyke.agent.memory.longterm.ImprintRequest
import ai.neopsyke.agent.memory.longterm.ImprintResult
import ai.neopsyke.agent.memory.longterm.LongTermMemoryAdvisor
import ai.neopsyke.agent.memory.longterm.LongTermMemoryAssessmentContext
import ai.neopsyke.agent.memory.longterm.LongTermMemoryAssessmentDecision
import ai.neopsyke.agent.memory.longterm.MemoryCapability
import ai.neopsyke.agent.memory.longterm.MemoryItem
import ai.neopsyke.agent.memory.longterm.MemoryKind
import ai.neopsyke.agent.memory.longterm.MemoryImprint
import ai.neopsyke.agent.memory.longterm.MemoryRecall
import ai.neopsyke.agent.memory.longterm.MemoryRecallQuery
import ai.neopsyke.agent.memory.longterm.RecallResult
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.ContentKind
import ai.neopsyke.agent.model.Interlocutor
import ai.neopsyke.agent.model.DeliberationState
import ai.neopsyke.agent.model.DialogueRole
import ai.neopsyke.agent.model.DialogueTurn
import ai.neopsyke.agent.model.OriginSource
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.model.PendingThought
import ai.neopsyke.agent.model.Provenances
import ai.neopsyke.agent.model.Urgency
import ai.neopsyke.agent.model.ExternalContentArtifact
import ai.neopsyke.agent.model.EgoTrigger
import ai.neopsyke.agent.model.GroundingMetadata
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

        val saved = coordinator.recordInternalReflection(
            action = PendingAction(
                id = 7,
                urgency = Urgency.MEDIUM,
                type = ActionType.REFLECT_INTERNAL,
                payload = """{"summary":"The agent learned about Kotlin"}""",
                summary = "reflect",
                rootInputId = "root-input-9",
                origin = ActionOrigin(
                    source = OriginSource.ID,
                    needId = "be-useful",
                    rootImpulseId = "impulse-1",
                ),
            groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
            ),
            summary = "The agent learned about Kotlin",
            keywords = listOf("kotlin", "learning"),
        )

        assertTrue(saved)
        val entry = logbook.entries.single()
        assertEquals(EpisodicEventType.SELF_INITIATED, entry.eventType)
        assertEquals("I learned: The agent learned about Kotlin", entry.summary)
        assertEquals("reflect_internal", entry.actionType)
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

        val saved = coordinator.recordInternalReflection(
            action = PendingAction(
                id = 8,
                urgency = Urgency.MEDIUM,
                type = ActionType.REFLECT_INTERNAL,
                payload = """{"summary":"Failed save"}""",
                summary = "reflect",
            groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
            ),
            summary = "Failed save",
            keywords = listOf("failure"),
        )

        assertFalse(saved)
        assertEquals(1, logbook.entries.size, "Reflection should still be journaled for diagnostics")
    }

    @Test
    fun `recordEvidenceReflection stores quarantined evidence memory with explicit tags`() {
        val hippocampus = RecordingHippocampus()
        val coordinator = MemorySystem(
            hippocampus = hippocampus,
            longTermMemoryAdvisor = object : LongTermMemoryAdvisor {
                override fun assess(context: LongTermMemoryAssessmentContext): LongTermMemoryAssessmentDecision =
                    LongTermMemoryAssessmentDecision(false, "", 0.0, "unused")
            },
            config = AgentConfig(),
            instrumentation = NoopAgentInstrumentation,
            logbook = RecordingLogbook(),
        )

        val saved = coordinator.recordEvidenceReflection(
            action = PendingAction(
                id = 19,
                urgency = Urgency.MEDIUM,
                type = ActionType.REFLECT_EVIDENCE,
                payload = """{"artifact_ids":["artifact-1"],"summary_hint":"Kotlin coroutines are useful","keywords":["kotlin","coroutines"]}""",
                summary = "reflect evidence",
            groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
            ),
            summaryHint = "Kotlin coroutines are useful",
            keywords = listOf("kotlin", "coroutines"),
            artifacts = listOf(
                ExternalContentArtifact(
                    id = "artifact-1",
                    content = "Kotlin coroutines simplify async flows.",
                    provenance = Provenances.sanitizedExternal(
                        provider = "web",
                        contentKind = ContentKind.DOCUMENT,
                        objectType = "website",
                        sourceRef = "https://kotlinlang.org",
                    ),
                )
            ),
        )

        assertTrue(saved)
        val imprint = hippocampus.imprints.single()
        assertEquals("evidence_backed_reflection", imprint.source)
        assertTrue(imprint.tags.contains("memory_visibility:quarantined"))
        assertTrue(imprint.tags.contains("memory_lane:evidence_quarantine"))
        assertTrue(imprint.tags.contains("artifact_source:web:website"))
        assertTrue(imprint.summary.contains("quarantined external evidence"))
    }

    @Test
    fun `evidence recall returns quarantined memories and filters trusted lane`() {
        val recallResult = RecallResult(
            provider = "recording",
            items = listOf(
                MemoryItem(
                    id = "trusted-1",
                    kind = MemoryKind.NARRATIVE,
                    summary = "Trusted preference memory",
                    content = "Trusted preference memory",
                    tags = listOf("memory_visibility:normal"),
                ),
                MemoryItem(
                    id = "evidence-1",
                    kind = MemoryKind.NARRATIVE,
                    summary = "External evidence memory",
                    content = "External evidence memory",
                    tags = listOf("memory_visibility:quarantined", "memory_lane:evidence_quarantine"),
                ),
            ),
            renderedText = "Trusted preference memory\nExternal evidence memory",
            hitCount = 2,
            truncated = false,
        )
        val coordinator = MemorySystem(
            hippocampus = RecordingHippocampus(recallResult = recallResult),
            longTermMemoryAdvisor = object : LongTermMemoryAdvisor {
                override fun assess(context: LongTermMemoryAssessmentContext): LongTermMemoryAssessmentDecision =
                    LongTermMemoryAssessmentDecision(false, "", 0.0, "unused")
            },
            config = AgentConfig(),
            instrumentation = NoopAgentInstrumentation,
            logbook = RecordingLogbook(),
        )

        val recalled = coordinator.recall(
            trigger = deferredTrigger(
                PendingThought(
                    id = 91,
                    urgency = Urgency.MEDIUM,
                    content = "review evidence",
                    longTermMemoryRecallQuery = "evidence: gmail cleanup"
                )
            ),
            shortTermSummary = "",
            recentDialogue = emptyList(),
        )

        assertTrue(recalled.contains("External evidence memory"))
        assertFalse(recalled.contains("Trusted preference memory"))
    }

    @Test
    fun `internal assessment saves self-origin memory with self source and tags`() {
        val logbook = RecordingLogbook()
        val hippocampus = RecordingHippocampus()
        val coordinator = MemorySystem(
            hippocampus = hippocampus,
            longTermMemoryAdvisor = object : LongTermMemoryAdvisor {
                override fun assess(context: LongTermMemoryAssessmentContext): LongTermMemoryAssessmentDecision =
                    LongTermMemoryAssessmentDecision(
                        shouldSave = true,
                        summary = "I want to remember that I feel curious about new learning topics.",
                        confidence = 0.93,
                        reason = "Agent curiosity indicates a stable self preference.",
                        tags = listOf("learning", "self preference"),
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
        coordinator.setActiveSession("id:internal", Interlocutor.named("Id"))

        coordinator.maybeAssessLongTermMemory(
            trigger = "interval",
            force = true,
            deliberation = DeliberationState(stepIndex = 16),
            recentDialogue = listOf(DialogueTurn(DialogueRole.INTERNAL, "I want to explore engaging new learning topics"))
        )

        val imprint = hippocampus.imprints.single()
        assertEquals("ego_self_memory_assessment", imprint.source)
        assertTrue(imprint.tags.contains("self_initiated"))
        assertTrue(imprint.tags.contains("subject:self"))
        assertTrue(imprint.tags.contains("self preference"))
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
        coordinator.recordInternalReflection(
            action = PendingAction(
                id = 1,
                urgency = Urgency.MEDIUM,
                type = ActionType.REFLECT_INTERNAL,
                payload = """{"summary":"I learned about Kotlin coroutines"}""",
                summary = "reflect",
                origin = learningOrigin,
            groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
            ),
            summary = "I learned about Kotlin coroutines",
            keywords = listOf("kotlin", "coroutines"),
        )
        coordinator.recordInternalReflection(
            action = PendingAction(
                id = 2,
                urgency = Urgency.MEDIUM,
                type = ActionType.REFLECT_INTERNAL,
                payload = """{"summary":"I learned about Kotlin coroutines again"}""",
                summary = "reflect",
                origin = learningOrigin,
            groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
            ),
            summary = "I learned about Kotlin coroutines again",
            keywords = listOf("coroutines", "kotlin"),
        )
        coordinator.recordInternalReflection(
            action = PendingAction(
                id = 3,
                urgency = Urgency.MEDIUM,
                type = ActionType.REFLECT_INTERNAL,
                payload = """{"summary":"I dug into coroutine cancellation"}""",
                summary = "reflect",
                origin = learningOrigin,
            groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
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
        private val recallResult: RecallResult = RecallResult(provider = "recording", text = ""),
    ) : Hippocampus {
        override val providerName: String = "recording"
        override val capabilities: Set<MemoryCapability> = setOf(
            MemoryCapability.SEMANTIC_RECALL,
            MemoryCapability.NARRATIVE_IMPRINT,
        )
        override val enabled: Boolean = true
        val imprints = mutableListOf<MemoryImprint>()

        override fun recall(request: MemoryRecallQuery): MemoryRecall =
            MemoryRecall(
                provider = recallResult.provider,
                items = recallResult.items,
                renderedText = recallResult.renderedText,
                hitCount = recallResult.hitCount,
                truncated = recallResult.truncated,
            )

        override fun imprint(request: ImprintRequest): ImprintResult {
            val narrative = request as? MemoryImprint
                ?: return ImprintResult(provider = providerName, accepted = false, detail = "unsupported")
            imprints += narrative
            return ImprintResult(
                provider = providerName,
                accepted = imprintResult,
                storedCount = if (imprintResult) 1 else 0,
                detail = if (imprintResult) "" else "imprint_failed"
            )
        }
    }
}
