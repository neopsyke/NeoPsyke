package psyke.agent

import psyke.agent.core.ActionOutcome
import psyke.agent.core.ActionType
import psyke.agent.core.PendingAction
import psyke.agent.core.PendingInput
import psyke.agent.core.TaskWorkspaceConfig
import psyke.agent.core.Urgency
import psyke.agent.memory.workspace.TaskWorkspaceStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskWorkspaceStoreTest {
    @Test
    fun `workspace summary is scoped per root input`() {
        val store = TaskWorkspaceStore(
            TaskWorkspaceConfig(
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

        assertTrue(summaryA.contains("Ephemeral task workspace"))
        assertTrue(summaryA.contains("Plan"))
        assertTrue(summaryA.contains("Research pricing", ignoreCase = true))
        assertTrue(summaryB.contains("Request"))
        assertTrue(!summaryB.contains("Plan"))
    }

    @Test
    fun `final compilation includes evidence and candidate answer`() {
        val store = TaskWorkspaceStore(TaskWorkspaceConfig(enabled = true, activationMinPlanSteps = 1))
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
        assertTrue(compiled.contains("Task workspace final compilation"))
        assertTrue(compiled.contains("evidence"))
        assertTrue(compiled.contains("candidate_answer"))
        assertTrue(compiled.contains("Pro costs \$20/month"))
    }

    @Test
    fun `final pass input reports workspace confidence`() {
        val store = TaskWorkspaceStore(TaskWorkspaceConfig(enabled = true, activationMinPlanSteps = 1))
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
        assertEquals(0, input?.answerDraftCount)
    }

    @Test
    fun `final pass input counts answer draft sections`() {
        val store = TaskWorkspaceStore(TaskWorkspaceConfig(enabled = true, activationMinPlanSteps = 1))
        val root = "root-answer-draft"
        store.ensureForInput(
            PendingInput(
                id = 1,
                content = "prepare final response",
                rootInputId = root,
                receivedAtMs = 111L
            )
        )
        store.recordAnswerDraft(root, "Draft chunk one")
        store.recordActionOutcome(
            rootInputId = root,
            action = PendingAction(
                id = 2,
                urgency = Urgency.MEDIUM,
                type = ActionType.ANSWER_DRAFT,
                payload = "Draft chunk two",
                summary = "draft chunk"
            ),
            outcome = ActionOutcome(
                statusSummary = "Internal answer draft chunk captured.",
                plannerSignal = "answer_draft chunk captured: Draft chunk two"
            ),
            observedEvidence = false
        )
        store.recordAnswerDraft(root, "Draft chunk two")

        val input = store.buildFinalPassInput(
            rootInputId = root,
            candidateAnswer = "final",
            maxChars = 1200
        )

        assertEquals(2, input?.answerDraftCount)
    }

    @Test
    fun `destroy removes only targeted workspace`() {
        val store = TaskWorkspaceStore(TaskWorkspaceConfig(enabled = true, activationMinPlanSteps = 1))
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
        val store = TaskWorkspaceStore(TaskWorkspaceConfig(enabled = true, activationMinPlanSteps = 1))
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

    // ── Complexity gate tests ──

    @Test
    fun `gate skips workspace for simple plan`() {
        val store = TaskWorkspaceStore(TaskWorkspaceConfig(enabled = true, activationMinPlanSteps = 3))
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
        val store = TaskWorkspaceStore(TaskWorkspaceConfig(enabled = true, activationMinPlanSteps = 3))
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
        assertTrue(summary.contains("Ephemeral task workspace"))
        assertTrue(summary.contains("Request"))
        assertTrue(summary.contains("Plan"))
        assertTrue(summary.contains("research and compare options", ignoreCase = true))
    }

    @Test
    fun `gate allows late activation on second plan`() {
        val store = TaskWorkspaceStore(TaskWorkspaceConfig(enabled = true, activationMinPlanSteps = 3))
        val root = "root-evolving"
        store.ensureForInput(
            PendingInput(id = 1, content = "help with project", rootInputId = root, receivedAtMs = 300L)
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
        val store = TaskWorkspaceStore(TaskWorkspaceConfig(enabled = true, activationMinPlanSteps = 3))
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
        val store = TaskWorkspaceStore(TaskWorkspaceConfig(enabled = true, activationMinPlanSteps = 1))
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
        val store = TaskWorkspaceStore(
            TaskWorkspaceConfig(enabled = true, activationMinPlanSteps = 1, digestMaxEntries = 4)
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
        val store = TaskWorkspaceStore(
            TaskWorkspaceConfig(enabled = true, activationMinPlanSteps = 1, digestMaxEntries = 2)
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
        val store = TaskWorkspaceStore(TaskWorkspaceConfig(enabled = true, activationMinPlanSteps = 1))
        assertEquals("", store.digestPromptSummary("nonexistent", maxTokens = 200))
    }

    @Test
    fun `captureDigest returns null when workspace not found`() {
        val store = TaskWorkspaceStore(TaskWorkspaceConfig(enabled = true, activationMinPlanSteps = 1))
        val entry = store.captureDigest("no-such-root", "session-x")
        assertNull(entry)
    }

    @Test
    fun `digestPromptSummary respects token budget`() {
        val store = TaskWorkspaceStore(
            TaskWorkspaceConfig(
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
        val store = TaskWorkspaceStore(
            TaskWorkspaceConfig(enabled = true, activationMinPlanSteps = 1, digestMaxEntries = 4)
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
        val store = TaskWorkspaceStore(
            TaskWorkspaceConfig(enabled = true, activationMinPlanSteps = 1, digestMaxEntries = 4)
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
        val store = TaskWorkspaceStore(
            TaskWorkspaceConfig(enabled = true, activationMinPlanSteps = 1, digestMaxEntries = 4)
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
}
