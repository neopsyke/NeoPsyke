package ai.neopsyke.agent.ego

import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.DialogueRole
import ai.neopsyke.agent.model.DialogueTurn
import ai.neopsyke.agent.model.Interlocutor
import ai.neopsyke.agent.config.MemoryConfig
import ai.neopsyke.agent.model.DeliberationState
import ai.neopsyke.agent.memory.longterm.NoopHippocampus
import ai.neopsyke.agent.memory.longterm.NoopLongTermMemoryAdvisor
import ai.neopsyke.agent.memory.longterm.Hippocampus
import ai.neopsyke.agent.memory.longterm.LongTermMemoryAdvisor
import ai.neopsyke.agent.memory.longterm.LongTermMemoryAssessmentContext
import ai.neopsyke.agent.memory.longterm.LongTermMemoryAssessmentDecision
import ai.neopsyke.agent.memory.longterm.MemoryRecall
import ai.neopsyke.agent.memory.longterm.MemoryRecallQuery
import ai.neopsyke.agent.memory.shortterm.MemoryStore
import ai.neopsyke.instrumentation.NoopAgentInstrumentation
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies that MemoryCoordinator isolates short-term memory per session.
 * Two sessions calling `remember()` should have independent `currentShortTermSummary()`.
 */
class SessionScopedMemoryStoreTest {

    private fun createCoordinator(): MemoryCoordinator {
        return MemoryCoordinator(
            hippocampus = NoopHippocampus,
            longTermMemoryAdvisor = NoopLongTermMemoryAdvisor,
            config = AgentConfig(),
            instrumentation = NoopAgentInstrumentation,
            initialMemoryStore = null,
        )
    }

    private fun userTurn(content: String, sessionId: String = ConversationContext.DEFAULT_SESSION_ID): DialogueTurn =
        DialogueTurn(role = DialogueRole.USER, content = content, sessionId = sessionId)

    @Test
    fun `remember in session A does not appear in session B summary`() {
        val mc = createCoordinator()

        mc.setActiveSession("session-A", Interlocutor.named("Alice"))
        mc.remember(userTurn("Tell me about quantum physics", "session-A"))

        mc.setActiveSession("session-B", Interlocutor.named("Bob"))
        mc.remember(userTurn("Tell me about cooking pasta", "session-B"))

        // Check session A summary
        mc.setActiveSession("session-A")
        val summaryA = mc.currentShortTermSummary()
        assertContains(summaryA, "quantum", message = "Session A summary should contain quantum")
        assertFalse(summaryA.contains("pasta"), "Session A summary should NOT contain pasta")

        // Check session B summary
        mc.setActiveSession("session-B")
        val summaryB = mc.currentShortTermSummary()
        assertContains(summaryB, "pasta", message = "Session B summary should contain pasta")
        assertFalse(summaryB.contains("quantum"), "Session B summary should NOT contain quantum")
    }

    @Test
    fun `default session receives initialMemoryStore content`() {
        val initialStore = MemoryStore(maxChars = 20000)
        initialStore.remember(DialogueTurn(role = DialogueRole.USER, content = "preloaded content about galaxies"))

        val mc = MemoryCoordinator(
            hippocampus = NoopHippocampus,
            longTermMemoryAdvisor = NoopLongTermMemoryAdvisor,
            config = AgentConfig(),
            instrumentation = NoopAgentInstrumentation,
            initialMemoryStore = initialStore,
        )

        mc.setActiveSession(ConversationContext.DEFAULT_SESSION_ID)
        val summary = mc.currentShortTermSummary()
        assertContains(summary, "galaxies", message = "Default session should have preloaded content")
    }

    @Test
    fun `new session gets fresh empty memory store`() {
        val mc = createCoordinator()

        mc.setActiveSession("fresh-session")
        val summary = mc.currentShortTermSummary()
        assertTrue(summary.isEmpty(), "Fresh session should have empty summary, got: $summary")
    }

    @Test
    fun `destroySession removes session memory store`() {
        val mc = createCoordinator()

        mc.setActiveSession("temp-session")
        mc.remember(userTurn("important data about cats", "temp-session"))

        // Verify data is there
        mc.setActiveSession("temp-session")
        val before = mc.currentShortTermSummary()
        assertContains(before, "cats")

        // Destroy and verify it's gone
        mc.destroySession("temp-session")
        mc.setActiveSession("temp-session")
        val after = mc.currentShortTermSummary()
        assertTrue(after.isEmpty(), "After destroySession, summary should be empty, got: $after")
    }

    @Test
    fun `multiple sessions coexist independently`() {
        val mc = createCoordinator()
        val sessions = listOf("s1", "s2", "s3")
        val topics = listOf("astronomy", "botany", "chemistry")

        // Seed each session
        sessions.forEachIndexed { idx, sessionId ->
            mc.setActiveSession(sessionId)
            mc.remember(userTurn("Tell me about ${topics[idx]}", sessionId))
        }

        // Verify each session only has its own topic
        sessions.forEachIndexed { idx, sessionId ->
            mc.setActiveSession(sessionId)
            val summary = mc.currentShortTermSummary()
            assertContains(summary, topics[idx], message = "Session $sessionId should contain ${topics[idx]}")
            topics.filterIndexed { i, _ -> i != idx }.forEach { otherTopic ->
                assertFalse(summary.contains(otherTopic), "Session $sessionId should NOT contain $otherTopic")
            }
        }
    }

    @Test
    fun `long-term memory assessment cooldown is isolated per session`() {
        var assessCalls = 0
        val advisor = object : LongTermMemoryAdvisor {
            override val enabled: Boolean = true
            override fun assess(context: LongTermMemoryAssessmentContext): LongTermMemoryAssessmentDecision {
                assessCalls += 1
                return LongTermMemoryAssessmentDecision(
                    shouldSave = false,
                    summary = "",
                    confidence = 0.0,
                    reason = "test"
                )
            }
        }
        val hippocampus = object : Hippocampus {
            override val providerName: String = "test"
            override val enabled: Boolean = true
            override fun recall(query: MemoryRecallQuery): MemoryRecall =
                MemoryRecall(provider = providerName, text = "")
        }
        val config = AgentConfig(
            memory = MemoryConfig(
                longTermMemoryAssessEverySteps = 1,
                longTermMemoryAssessCooldownSteps = 50,
            )
        )
        val mc = MemoryCoordinator(
            hippocampus = hippocampus,
            longTermMemoryAdvisor = advisor,
            config = config,
            instrumentation = NoopAgentInstrumentation,
        )
        val deliberation = DeliberationState(stepIndex = 10)

        mc.setActiveSession("session-A", Interlocutor.named("Alice"))
        mc.maybeAssessLongTermMemory(
            trigger = "interval",
            deliberation = deliberation,
            recentDialogue = listOf(userTurn("remember A", "session-A"))
        )

        mc.setActiveSession("session-B", Interlocutor.named("Bob"))
        mc.maybeAssessLongTermMemory(
            trigger = "interval",
            deliberation = deliberation,
            recentDialogue = listOf(userTurn("remember B", "session-B"))
        )

        assertEquals(2, assessCalls, "Each session should run its own cooldown window")
    }
}
