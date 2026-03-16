package psyke.agent.actions.builtin

import kotlinx.coroutines.runBlocking
import psyke.agent.actions.ActionExecutionContext
import psyke.agent.memory.episodic.EpisodicEventType
import psyke.agent.memory.episodic.Logbook
import psyke.agent.memory.episodic.LogbookEntry
import psyke.agent.memory.episodic.LogbookQuery
import psyke.agent.memory.episodic.LogbookRecall
import psyke.agent.memory.longterm.Hippocampus
import psyke.agent.memory.longterm.MemoryImprint
import psyke.agent.memory.longterm.MemoryRecall
import psyke.agent.memory.longterm.MemoryRecallQuery
import psyke.agent.model.ActionType
import psyke.agent.model.PendingAction
import psyke.agent.model.Urgency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReflectActionPluginTest {

    private val recordedEntries = mutableListOf<LogbookEntry>()
    private val recordedImprints = mutableListOf<MemoryImprint>()

    private val mockLogbook = object : Logbook {
        override fun record(entry: LogbookEntry): Long {
            recordedEntries.add(entry)
            return recordedEntries.size.toLong()
        }
        override fun query(query: LogbookQuery): LogbookRecall =
            LogbookRecall(emptyList(), 0, false)
        override fun pruneOlderThan(retentionDays: Int): Int = 0
        override fun clearAll(): Int = 0
        override fun close() {}
    }

    private val mockHippocampus = object : Hippocampus {
        override val providerName = "test"
        override val enabled = true
        override fun recall(query: MemoryRecallQuery): MemoryRecall =
            MemoryRecall(provider = "test", text = "", hitCount = 0)
        override fun imprint(imprint: MemoryImprint): Boolean {
            recordedImprints.add(imprint)
            return true
        }
    }

    private fun plugin(
        hippocampus: Hippocampus? = mockHippocampus,
        logbook: Logbook? = mockLogbook,
    ) = ReflectActionPlugin(hippocampus = hippocampus, logbook = logbook)

    private fun action(payload: String) = PendingAction(
        id = 1,
        urgency = Urgency.MEDIUM,
        type = ActionType.REFLECT,
        payload = payload,
        summary = "reflect test",
    )

    @Test
    fun `descriptor has correct action type and no user output capability`() {
        val p = plugin()
        assertEquals(ActionType.REFLECT, p.descriptor.actionType)
        assertTrue(p.descriptor.capabilities.isEmpty())
        assertTrue(p.descriptor.dispatchable)
    }

    @Test
    fun `execute records to logbook and hippocampus`() = runBlocking {
        val p = plugin()
        val outcome = p.execute(
            action("""{"summary": "I learned about coroutines", "keywords": ["kotlin", "coroutines"]}"""),
            ActionExecutionContext(searchResultCount = 0),
        )

        assertEquals("Reflection recorded to memory.", outcome.statusSummary)
        assertNull(outcome.assistantOutput)

        // Logbook entry
        assertEquals(1, recordedEntries.size)
        val entry = recordedEntries.first()
        assertEquals(EpisodicEventType.SELF_INITIATED, entry.eventType)
        assertEquals("I learned about coroutines", entry.summary)
        assertEquals(listOf("kotlin", "coroutines"), entry.keywords)
        assertEquals("reflect", entry.actionType)
        assertEquals(true, entry.metadata?.get("self_initiated"))

        // Hippocampus imprint
        assertEquals(1, recordedImprints.size)
        val imprint = recordedImprints.first()
        assertEquals("I learned about coroutines", imprint.summary)
        assertEquals("self_initiated_reflection", imprint.source)
        assertTrue(imprint.tags.contains("self_initiated"))
        assertTrue(imprint.tags.contains("kotlin"))
    }

    @Test
    fun `execute works without hippocampus and logbook`() = runBlocking {
        val p = plugin(hippocampus = null, logbook = null)
        val outcome = p.execute(
            action("""{"summary": "Test insight", "keywords": []}"""),
            ActionExecutionContext(searchResultCount = 0),
        )

        assertEquals("Reflection recorded to memory.", outcome.statusSummary)
    }

    @Test
    fun `execute returns error for invalid JSON payload`() = runBlocking {
        val p = plugin()
        val outcome = p.execute(
            action("not json at all"),
            ActionExecutionContext(searchResultCount = 0),
        )

        assertNotNull(outcome.actionErrorCategory)
    }

    @Test
    fun `deterministicReview rejects blank summary`() {
        val p = plugin()
        val review = p.deterministicReview(
            action("""{"summary": "", "keywords": []}"""),
            psyke.agent.model.SuperegoContext(recentDialogue = emptyList()),
            psyke.agent.config.AgentConfig(),
        )
        assertNotNull(review)
        assertEquals(false, review.allow)
        assertEquals("reflect_summary_blank", review.ruleId)
    }

    @Test
    fun `deterministicReview rejects invalid JSON`() {
        val p = plugin()
        val review = p.deterministicReview(
            action("not json"),
            psyke.agent.model.SuperegoContext(recentDialogue = emptyList()),
            psyke.agent.config.AgentConfig(),
        )
        assertNotNull(review)
        assertEquals(false, review.allow)
        assertEquals("reflect_payload_invalid", review.ruleId)
    }

    @Test
    fun `deterministicReview allows valid payload`() {
        val p = plugin()
        val review = p.deterministicReview(
            action("""{"summary": "I learned something", "keywords": ["test"]}"""),
            psyke.agent.model.SuperegoContext(recentDialogue = emptyList()),
            psyke.agent.config.AgentConfig(),
        )
        assertNotNull(review)
        assertEquals(true, review.allow)
    }

    @Test
    fun `repairPlannerPayload wraps plain text as JSON`() {
        val p = plugin()
        val repaired = p.repairPlannerPayload("I learned something cool")
        assertTrue(repaired.startsWith("{"))
        assertTrue(repaired.contains("summary"))
        assertTrue(repaired.contains("I learned something cool"))
    }

    @Test
    fun `repairPlannerPayload leaves valid JSON untouched`() {
        val p = plugin()
        val valid = """{"summary": "test", "keywords": []}"""
        assertEquals(valid, p.repairPlannerPayload(valid))
    }
}
