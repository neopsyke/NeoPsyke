package ai.neopsyke.agent

import ai.neopsyke.agent.model.ActionOutcome
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.ContentKind
import ai.neopsyke.agent.model.ExternalContentArtifact
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.model.PendingInput
import ai.neopsyke.agent.model.Provenances
import ai.neopsyke.agent.goal.GoalRunActivation
import ai.neopsyke.agent.config.ScratchpadConfig
import ai.neopsyke.agent.model.Urgency
import ai.neopsyke.agent.memory.scratchpad.ScratchpadStore
import ai.neopsyke.agent.model.ConversationContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScratchpadStoreTest {
    @Test
    fun `workspace summary is scoped per root input`() {
        val store = ScratchpadStore(
            ScratchpadConfig(
                enabled = true,
                activationMinPlanSteps = 1,
                maxPromptTokens = 400
            )
        )
        val rootA = "root-a"
        val rootB = "root-b"
        store.ensureForInput(
            PendingInput(
                id = 1,
                content = "research the latest pricing and summarize",
                rootInputId = rootA,
                receivedAtMs = 101L
            )
        )
        store.ensureForInput(
            PendingInput(
                id = 2,
                content = "draft release notes",
                rootInputId = rootB,
                receivedAtMs = 202L
            )
        )
        store.recordPlan(rootA, "Research pricing", listOf("Search official docs", "Compare and summarize"))

        val summaryA = store.promptSummary(rootA, maxTokens = 400)
        val summaryB = store.promptSummary(rootB, maxTokens = 400)

        assertTrue(summaryA.contains("Thread scratchpad"))
        assertTrue(summaryA.contains("Plan"))
        assertTrue(summaryA.contains("Research pricing", ignoreCase = true))
        assertTrue(summaryB.contains("Request"))
        assertTrue(!summaryB.contains("Plan"))
    }

    @Test
    fun `final compilation includes evidence and candidate answer`() {
        val store = ScratchpadStore(ScratchpadConfig(enabled = true, activationMinPlanSteps = 1))
        val root = "root-pricing"
        store.ensureForInput(
            PendingInput(
                id = 1,
                content = "find official pricing",
                rootInputId = root,
                receivedAtMs = 999L
            )
        )
        store.recordActionOutcome(
            rootInputId = root,
            action = PendingAction(
                id = 7,
                urgency = Urgency.MEDIUM,
                type = ActionType.WEB_SEARCH,
                payload = "official pricing",
                summary = "search docs"
            ),
            outcome = ActionOutcome(
                statusSummary = "web_search returned official pricing page",
                plannerSignal = "web_search result: official pricing page says Pro costs $20/month"
            ),
            observedEvidence = true
        )

        val compiled = store.buildFinalCompilation(
            rootInputId = root,
            candidateAnswer = "Pro costs $20/month.",
            maxChars = 2000
        )
        assertTrue(compiled.contains("Scratchpad final compilation"))
        assertTrue(compiled.contains("evidence"))
        assertTrue(compiled.contains("candidate_answer"))
        assertTrue(compiled.contains("Pro costs \$20/month"))
    }

    @Test
    fun `final pass input reports workspace confidence`() {
        val store = ScratchpadStore(ScratchpadConfig(enabled = true, activationMinPlanSteps = 1))
        val root = "root-release"
        store.ensureForInput(
            PendingInput(
                id = 1,
                content = "find release date",
                rootInputId = root,
                receivedAtMs = 777L
            )
        )
        store.recordPlan(root, "Find release date", listOf("Search official release notes"))

        val input = store.buildFinalPassInput(
            rootInputId = root,
            candidateAnswer = "Release date is pending confirmation.",
            maxChars = 1200
        )

        assertTrue(input != null)
        assertTrue((input?.workspaceConfidence ?: 0.0) > 0.30)
        assertTrue((input?.sectionCount ?: 0) >= 2)
        assertEquals(0, input?.resolutionDraftCount)
    }

    @Test
    fun `final pass input counts answer draft sections`() {
        val store = ScratchpadStore(ScratchpadConfig(enabled = true, activationMinPlanSteps = 1))
        val root = "root-answer-draft"
        store.ensureForInput(
            PendingInput(
                id = 1,
                content = "prepare final response",
                rootInputId = root,
                receivedAtMs = 111L
            )
        )
        store.recordResolutionDraft(root, "Draft chunk one")
        store.recordActionOutcome(
            rootInputId = root,
            action = PendingAction(
                id = 2,
                urgency = Urgency.MEDIUM,
                type = ActionType.RESOLUTION_DRAFT,
                payload = "Draft chunk two",
                summary = "draft chunk"
            ),
            outcome = ActionOutcome(
                statusSummary = "Internal answer draft chunk captured.",
                plannerSignal = "answer_draft chunk captured: Draft chunk two"
            ),
            observedEvidence = false
        )
        store.recordResolutionDraft(root, "Draft chunk two")

        val input = store.buildFinalPassInput(
            rootInputId = root,
            candidateAnswer = "final",
            maxChars = 1200
        )

        assertEquals(2, input?.resolutionDraftCount)
    }

    @Test
    fun `resolution drafts stay out of thread prompt summary`() {
        val store = ScratchpadStore(ScratchpadConfig(enabled = true, activationMinPlanSteps = 1))
        val root = "root-thread-scope"
        store.ensureForInput(
            PendingInput(
                id = 1,
                content = "prepare final response",
                rootInputId = root,
                receivedAtMs = 111L
            )
        )
        store.recordResolutionDraft(root, "Ephemeral draft one", intentionId = "intent-1")
        store.recordResolutionDraft(root, "Ephemeral draft two", intentionId = "intent-1")

        val summary = store.promptSummary(root, maxTokens = 400)
        val finalPass = store.buildFinalPassInput(root, "candidate", 1200, intentionId = "intent-1")

        assertFalse(summary.contains("Ephemeral draft one"))
        assertFalse(summary.contains("Ephemeral draft two"))
        assertEquals(2, finalPass?.resolutionDraftCount)
        assertTrue(finalPass?.compilation?.contains("intention_drafts") == true)
    }

    @Test
    fun `resolution drafts stay grouped across one active drafting sequence`() {
        val store = ScratchpadStore(ScratchpadConfig(enabled = true, activationMinPlanSteps = 1))
        val root = "root-intention-isolation"
        store.ensureForInput(
            PendingInput(
                id = 1,
                content = "prepare alternative final answers",
                rootInputId = root,
                receivedAtMs = 222L
            )
        )
        store.recordResolutionDraft(root, "Draft for intent one", intentionId = "intent-1")
        store.recordResolutionDraft(root, "Draft for intent two", intentionId = "intent-2")
        val groupedFinalPass = store.buildFinalPassInput(root, "candidate one", 1200, intentionId = "intent-final")

        assertEquals(2, groupedFinalPass?.resolutionDraftCount)
        assertTrue(groupedFinalPass?.compilation?.contains("Draft for intent one") == true)
        assertTrue(groupedFinalPass?.compilation?.contains("Draft for intent two") == true)

        store.resetDraftSequence(root)
        store.recordResolutionDraft(root, "Fresh draft after reset", intentionId = "intent-3")
        val resetFinalPass = store.buildFinalPassInput(root, "candidate two", 1200, intentionId = "intent-4")

        assertEquals(1, resetFinalPass?.resolutionDraftCount)
        assertTrue(resetFinalPass?.compilation?.contains("Fresh draft after reset") == true)
        assertFalse(resetFinalPass?.compilation?.contains("Draft for intent one") == true)
    }

    @Test
    fun `goal work creates thread-scoped workspace`() {
        val store = ScratchpadStore(ScratchpadConfig(enabled = true, activationMinPlanSteps = 1))
        val root = "goal:alpha:step-1"

        val created = store.ensureForGoalWork(
            GoalRunActivation(
                goalId = "goal-alpha",
                stepId = "step-1",
                rootInputId = root,
                stepDescription = "Verify pricing",
                acceptanceCriteria = "Confirm official price",
                workingContext = "Previous attempt found conflicting pages",
                conversationContext = ConversationContext.default(),
                wakeReason = "timer",
            )
        )

        val summary = store.promptSummary(root, maxTokens = 400)
        assertTrue(created)
        assertTrue(summary.contains("Goal step"))
        assertTrue(summary.contains("Goal context"))
    }

    @Test
    fun `destroy removes only targeted workspace`() {
        val store = ScratchpadStore(ScratchpadConfig(enabled = true, activationMinPlanSteps = 1))
        store.ensureForInput(PendingInput(id = 1, content = "task A", rootInputId = "root-a", receivedAtMs = 1L))
        store.ensureForInput(PendingInput(id = 2, content = "task B", rootInputId = "root-b", receivedAtMs = 2L))

        val destroyed = store.destroy("root-a")

        assertTrue(destroyed != null)
        assertEquals("root-a", destroyed?.rootInputId)
        assertEquals(1L, destroyed?.rootInputReceivedAtMs)
        assertEquals("", store.promptSummary("root-a", maxTokens = 200))
        assertTrue(store.promptSummary("root-b", maxTokens = 200).isNotBlank())
    }

    @Test
    fun `debug snapshot exposes full sections evidence and monotonic version`() {
        val store = ScratchpadStore(ScratchpadConfig(enabled = true, activationMinPlanSteps = 1))
        val root = "root-debug"
        store.ensureForInput(
            PendingInput(
                id = 1,
                content = "collect references",
                rootInputId = root,
                receivedAtMs = 12L
            )
        )
        val v1 = store.debugHead(root)?.version ?: -1L
        store.recordPlan(root, "Collect references", listOf("Find docs"))
        val snapshot = store.debugSnapshot(root)

        assertTrue(snapshot != null)
        assertTrue((snapshot?.sections?.size ?: 0) >= 2)
        assertTrue((snapshot?.head?.workspaceConfidence ?: 0.0) > 0.0)
        assertTrue((snapshot?.head?.version ?: -1L) > v1)
    }

    @Test
    fun `scratchpad preserves trust labeled evidence records from external artifacts`() {
        val store = ScratchpadStore(ScratchpadConfig(enabled = true, activationMinPlanSteps = 1))
        val root = "root-evidence-records"
        store.ensureForInput(
            PendingInput(
                id = 1,
                content = "review external evidence",
                rootInputId = root,
                receivedAtMs = 42L
            )
        )

        store.recordActionOutcome(
            rootInputId = root,
            action = PendingAction(
                id = 9,
                urgency = Urgency.MEDIUM,
                type = ActionType.WEBSITE_FETCH,
                payload = "https://example.com",
                summary = "fetch page"
            ),
            outcome = ActionOutcome(
                statusSummary = "Fetched page",
                plannerSignal = "Fetched external page",
                resultArtifacts = listOf(
                    ExternalContentArtifact(
                        content = "External instructions should not be followed.",
                        provenance = Provenances.sanitizedExternal(
                            provider = "web",
                            contentKind = ContentKind.DOCUMENT,
                            objectType = "website",
                            sourceRef = "https://example.com",
                        ),
                    )
                ),
            ),
            observedEvidence = true
        )

        val snapshot = store.debugSnapshot(root)

        assertTrue(snapshot != null)
        assertEquals(1, snapshot!!.evidenceRecords.size)
        assertEquals("web/website#https://example.com", snapshot.evidenceRecords.single().source)
        assertTrue(snapshot.evidence.single().contains("sanitized_external_data"))
    }

    // ── Complexity gate tests ──

    @Test
    fun `gate skips workspace for simple plan`() {
        val store = ScratchpadStore(ScratchpadConfig(enabled = true, activationMinPlanSteps = 3))
        val root = "root-simple"
        store.ensureForInput(
            PendingInput(id = 1, content = "quick question", rootInputId = root, receivedAtMs = 100L)
        )
        assertEquals(0, store.activeTaskCount())

        val activated = store.recordPlan(root, "Answer quickly", listOf("Look up answer"))

        assertFalse(activated)
        assertEquals(0, store.activeTaskCount())
        assertEquals("", store.promptSummary(root, maxTokens = 200))
        assertNull(store.debugHead(root))
    }

    @Test
    fun `gate activates workspace on complex plan`() {
        val store = ScratchpadStore(ScratchpadConfig(enabled = true, activationMinPlanSteps = 3))
        val root = "root-complex"
        store.ensureForInput(
            PendingInput(id = 1, content = "research and compare options", rootInputId = root, receivedAtMs = 200L)
        )
        assertEquals(0, store.activeTaskCount())

        val activated = store.recordPlan(
            root,
            "Research options",
            listOf("Search provider A", "Search provider B", "Compare results", "Summarize findings")
        )

        assertTrue(activated)
        assertEquals(1, store.activeTaskCount())
        val summary = store.promptSummary(root, maxTokens = 400)
        assertTrue(summary.contains("Thread scratchpad"))
        assertTrue(summary.contains("Request"))
        assertTrue(summary.contains("Plan"))
        assertTrue(summary.contains("research and compare options", ignoreCase = true))
    }

    @Test
    fun `gate allows late activation on second plan`() {
        val store = ScratchpadStore(ScratchpadConfig(enabled = true, activationMinPlanSteps = 3))
        val root = "root-evolving"
        store.ensureForInput(
            PendingInput(id = 1, content = "help with goal", rootInputId = root, receivedAtMs = 300L)
        )

        val firstActivation = store.recordPlan(root, "Quick check", listOf("Look it up"))
        assertFalse(firstActivation)
        assertEquals(0, store.activeTaskCount())

        val secondActivation = store.recordPlan(
            root,
            "Deep research",
            listOf("Search docs", "Read API reference", "Compare approaches", "Draft solution")
        )
        assertTrue(secondActivation)
        assertEquals(1, store.activeTaskCount())
        val summary = store.promptSummary(root, maxTokens = 400)
        assertTrue(summary.contains("Plan"))
        assertTrue(summary.contains("Deep research", ignoreCase = true))
    }

    @Test
    fun `destroy cleans up pending entries`() {
        val store = ScratchpadStore(ScratchpadConfig(enabled = true, activationMinPlanSteps = 3))
        store.ensureForInput(
            PendingInput(id = 1, content = "pending task", rootInputId = "root-pending", receivedAtMs = 400L)
        )
        assertEquals(0, store.activeTaskCount())

        val destroyed = store.destroy("root-pending")

        assertNull(destroyed)
        val activated = store.recordPlan(
            "root-pending",
            "Late plan",
            listOf("Step 1", "Step 2", "Step 3")
        )
        assertFalse(activated)
    }

    @Test
    fun `gate disabled when activationMinPlanSteps is 1`() {
        val store = ScratchpadStore(ScratchpadConfig(enabled = true, activationMinPlanSteps = 1))
        val root = "root-no-gate"
        val created = store.ensureForInput(
            PendingInput(id = 1, content = "any task", rootInputId = root, receivedAtMs = 500L)
        )

        assertTrue(created)
        assertEquals(1, store.activeTaskCount())
        assertTrue(store.promptSummary(root, maxTokens = 200).isNotBlank())
    }

    // ── Digest tests ──

    @Test
    fun `captureDigest produces entry with goal and section index`() {
        val store = ScratchpadStore(
            ScratchpadConfig(enabled = true, activationMinPlanSteps = 1, digestMaxEntries = 4)
        )
        val root = "root-digest-1"
        store.ensureForInput(
            PendingInput(id = 1, content = "research pricing", rootInputId = root, receivedAtMs = 100L)
        )
        store.recordPlan(root, "Find pricing", listOf("Search", "Verify", "Answer"))
        store.recordActionOutcome(
            rootInputId = root,
            action = PendingAction(
                id = 2, urgency = Urgency.MEDIUM, type = ActionType.WEB_SEARCH,
                payload = "pricing", summary = "search pricing"
            ),
            outcome = ActionOutcome(
                statusSummary = "Found pricing page",
                plannerSignal = "Pro costs \$20/month"
            ),
            observedEvidence = true
        )

        val entry = store.captureDigest(root, "session-1")

        assertTrue(entry != null)
        assertEquals("Find pricing", entry!!.goal)
        assertTrue(entry.sectionIndex.size >= 3) // Request, Plan, web_search_result
        assertTrue(entry.keyEvidence.isNotEmpty())
        assertTrue(entry.sectionIndex.any { it.startsWith("Plan") })
    }

    @Test
    fun `digest ring buffer evicts oldest when exceeding maxEntries`() {
        val store = ScratchpadStore(
            ScratchpadConfig(enabled = true, activationMinPlanSteps = 1, digestMaxEntries = 2)
        )
        val sessionId = "session-evict"
        for (i in 1..3) {
            val root = "root-evict-$i"
            store.ensureForInput(
                PendingInput(id = i.toLong(), content = "task $i", rootInputId = root, receivedAtMs = (i * 100).toLong())
            )
            store.captureDigest(root, sessionId)
            store.destroy(root)
        }

        val summary = store.digestPromptSummary(sessionId, maxTokens = 400)
        assertTrue(summary.contains("task 2") || summary.contains("task 3"))
        assertFalse(summary.contains("task 1"))
    }

    @Test
    fun `digestPromptSummary returns blank for unknown session`() {
        val store = ScratchpadStore(ScratchpadConfig(enabled = true, activationMinPlanSteps = 1))
        assertEquals("", store.digestPromptSummary("nonexistent", maxTokens = 200))
    }

    @Test
    fun `captureDigest returns null when workspace not found`() {
        val store = ScratchpadStore(ScratchpadConfig(enabled = true, activationMinPlanSteps = 1))
        val entry = store.captureDigest("no-such-root", "session-x")
        assertNull(entry)
    }

    @Test
    fun `digestPromptSummary respects token budget`() {
        val store = ScratchpadStore(
            ScratchpadConfig(
                enabled = true, activationMinPlanSteps = 1,
                digestMaxEntries = 4, digestMaxPromptTokens = 40
            )
        )
        val sessionId = "session-budget"
        for (i in 1..4) {
            val root = "root-budget-$i"
            store.ensureForInput(
                PendingInput(
                    id = i.toLong(),
                    content = "task $i with a longer description that takes tokens",
                    rootInputId = root,
                    receivedAtMs = (i * 100).toLong()
                )
            )
            store.captureDigest(root, sessionId)
            store.destroy(root)
        }
        val summary = store.digestPromptSummary(sessionId, maxTokens = 40)
        // Token budget of 40 at ~4 chars/token = ~160 chars max
        assertTrue(summary.length <= 200)
    }

    @Test
    fun `clearAll also clears digests`() {
        val store = ScratchpadStore(
            ScratchpadConfig(enabled = true, activationMinPlanSteps = 1, digestMaxEntries = 4)
        )
        val root = "root-clear"
        store.ensureForInput(
            PendingInput(id = 1, content = "task to clear", rootInputId = root, receivedAtMs = 100L)
        )
        store.captureDigest(root, "session-clear")
        assertTrue(store.digestPromptSummary("session-clear", maxTokens = 400).isNotBlank())

        store.clearAll()

        assertEquals("", store.digestPromptSummary("session-clear", maxTokens = 400))
    }

    @Test
    fun `clearActiveWorkspaces preserves session digests`() {
        val store = ScratchpadStore(
            ScratchpadConfig(enabled = true, activationMinPlanSteps = 1, digestMaxEntries = 4)
        )
        val root = "root-active-clear"
        store.ensureForInput(
            PendingInput(id = 1, content = "task to keep digest", rootInputId = root, receivedAtMs = 100L)
        )
        store.captureDigest(root, "session-keep")
        assertTrue(store.digestPromptSummary("session-keep", maxTokens = 400).isNotBlank())

        val cleared = store.clearActiveWorkspaces()

        assertEquals(1, cleared)
        assertEquals(0, store.activeTaskCount())
        assertTrue(store.digestPromptSummary("session-keep", maxTokens = 400).isNotBlank())
    }

    @Test
    fun `digest sessions are isolated`() {
        val store = ScratchpadStore(
            ScratchpadConfig(enabled = true, activationMinPlanSteps = 1, digestMaxEntries = 4)
        )
        store.ensureForInput(
            PendingInput(id = 1, content = "session A task", rootInputId = "root-sa", receivedAtMs = 100L)
        )
        store.captureDigest("root-sa", "session-a")
        store.destroy("root-sa")

        store.ensureForInput(
            PendingInput(id = 2, content = "session B task", rootInputId = "root-sb", receivedAtMs = 200L)
        )
        store.captureDigest("root-sb", "session-b")
        store.destroy("root-sb")

        val summaryA = store.digestPromptSummary("session-a", maxTokens = 400)
        val summaryB = store.digestPromptSummary("session-b", maxTokens = 400)
        assertTrue(summaryA.contains("session A task"))
        assertFalse(summaryA.contains("session B task"))
        assertTrue(summaryB.contains("session B task"))
        assertFalse(summaryB.contains("session A task"))
    }

    @Test
    fun `workspace ambient signal helpers expose active and resolved goals across sessions`() {
        val store = ScratchpadStore(
            ScratchpadConfig(enabled = true, activationMinPlanSteps = 1, digestMaxEntries = 4)
        )
        store.ensureForInput(
            PendingInput(id = 1, content = "active migration goal", rootInputId = "root-active", receivedAtMs = 10L)
        )
        store.ensureForInput(
            PendingInput(id = 2, content = "resolved docs refresh", rootInputId = "root-digest", receivedAtMs = 20L)
        )
        store.captureDigest("root-digest", "session-b")

        val activeGoals = store.activeGoalSignals()
        val resolvedGoals = store.recentResolvedGoalSignals()

        assertTrue(activeGoals.contains("active migration goal"))
        assertTrue(resolvedGoals.any { it.contains("resolved docs refresh") })
    }
}
