package psyke.agent.actions.builtin

import kotlinx.coroutines.runBlocking
import psyke.agent.actions.ActionExecutionContext
import psyke.agent.actions.NoopReflectionMemoryRecorder
import psyke.agent.actions.ReflectionMemoryRecorder
import psyke.agent.model.ActionType
import psyke.agent.model.PendingAction
import psyke.agent.model.Urgency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReflectActionPluginTest {

    private val recordedReflections = mutableListOf<RecordedReflection>()

    private val recordingReflectionMemoryRecorder = object : ReflectionMemoryRecorder {
        override fun recordReflection(action: PendingAction, summary: String, keywords: List<String>): Boolean {
            recordedReflections += RecordedReflection(
                actionType = action.type,
                summary = summary,
                keywords = keywords,
            )
            return true
        }
    }

    private fun plugin(
        reflectionMemoryRecorder: ReflectionMemoryRecorder = recordingReflectionMemoryRecorder,
    ) = ReflectActionPlugin(reflectionMemoryRecorder = reflectionMemoryRecorder)

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
    fun `execute delegates reflection persistence to recorder`() = runBlocking {
        val p = plugin()
        val outcome = p.execute(
            action("""{"summary": "I learned about coroutines", "keywords": ["kotlin", "coroutines"]}"""),
            ActionExecutionContext(searchResultCount = 0),
        )

        assertEquals("Reflection recorded to memory.", outcome.statusSummary)
        assertNull(outcome.assistantOutput)
        assertEquals(1, recordedReflections.size)
        assertEquals(
            RecordedReflection(
                actionType = ActionType.REFLECT,
                summary = "I learned about coroutines",
                keywords = listOf("kotlin", "coroutines"),
            ),
            recordedReflections.single()
        )
    }

    @Test
    fun `execute returns error when reflection is not saved to memory`() = runBlocking {
        val p = plugin(reflectionMemoryRecorder = NoopReflectionMemoryRecorder)
        val outcome = p.execute(
            action("""{"summary": "Test insight", "keywords": []}"""),
            ActionExecutionContext(searchResultCount = 0),
        )

        assertEquals("Reflection failed: memory save unsuccessful.", outcome.statusSummary)
        assertEquals("reflect_memory_save_failed", outcome.actionErrorCategory)
    }

    @Test
    fun `execute preserves raw summary for recorder owned normalization`() = runBlocking {
        val p = plugin()
        p.execute(
            action("""{"summary": "Kotlin coroutines use structured concurrency by default", "keywords": ["kotlin"]}"""),
            ActionExecutionContext(searchResultCount = 0),
        )

        assertEquals(
            "Kotlin coroutines use structured concurrency by default",
            recordedReflections.single().summary
        )
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

    private data class RecordedReflection(
        val actionType: ActionType,
        val summary: String,
        val keywords: List<String>,
    )
}
