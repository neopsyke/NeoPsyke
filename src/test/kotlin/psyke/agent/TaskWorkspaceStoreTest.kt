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
import kotlin.test.assertTrue

class TaskWorkspaceStoreTest {
    @Test
    fun `workspace summary is scoped per root input`() {
        val store = TaskWorkspaceStore(
            TaskWorkspaceConfig(
                enabled = true,
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
        val store = TaskWorkspaceStore(TaskWorkspaceConfig(enabled = true))
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
        val store = TaskWorkspaceStore(TaskWorkspaceConfig(enabled = true))
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
    }

    @Test
    fun `destroy removes only targeted workspace`() {
        val store = TaskWorkspaceStore(TaskWorkspaceConfig(enabled = true))
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
        val store = TaskWorkspaceStore(TaskWorkspaceConfig(enabled = true))
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
}
