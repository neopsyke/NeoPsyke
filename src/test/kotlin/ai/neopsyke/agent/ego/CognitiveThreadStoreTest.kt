package ai.neopsyke.agent.ego

import ai.neopsyke.agent.goal.GoalRunActivation
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.ConversationSecurityContexts
import ai.neopsyke.agent.model.CognitiveThreadKind
import ai.neopsyke.agent.model.CognitiveThreadStatus
import ai.neopsyke.agent.model.DataTrust
import ai.neopsyke.agent.model.ExternalContentArtifact
import ai.neopsyke.agent.model.Interlocutor
import ai.neopsyke.agent.model.PendingImpulse
import ai.neopsyke.agent.model.PendingInput
import ai.neopsyke.agent.model.Percept
import ai.neopsyke.agent.model.PerceptFamily
import ai.neopsyke.agent.model.Provenances
import ai.neopsyke.agent.model.RootInputIds
import ai.neopsyke.agent.model.StimulusTrustLevel
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CognitiveThreadStoreTest {
    @Test
    fun `bindPercept creates thread and keeps latest percept`() {
        val store = CognitiveThreadStore()
        val context = ConversationContext.default()
        val percept = Percept(
            id = RootInputIds.next(),
            family = PerceptFamily.REQUEST,
            summary = "Summarize the latest report",
            source = "chat:web",
            occurredAt = Instant.now(),
            conversationContext = context,
            rootStimulusId = "stimulus-1",
        )

        val thread = store.bindPercept(percept, rootInputId = "stimulus-1")
        val latestPercept = store.latestPercept("stimulus-1", context)

        assertEquals(CognitiveThreadKind.CONVERSATION, thread.kind)
        assertEquals(thread.id, latestPercept?.cognitiveThreadId)
        assertEquals(PerceptFamily.REQUEST, latestPercept?.family)
        assertEquals(thread.id, store.thread("stimulus-1", context)?.id)
    }

    @Test
    fun `observeArtifacts degrades thread security on the owning thread`() {
        val store = CognitiveThreadStore()
        val context = ConversationContext.default()
        store.bindInput(
            PendingInput(
                id = 1L,
                content = "Check external data",
                rootInputId = "root-1",
                conversationContext = context,
                percept = Percept(
                    id = RootInputIds.next(),
                    family = PerceptFamily.REQUEST,
                    summary = "Check external data",
                    source = "chat:web",
                    occurredAt = Instant.now(),
                    conversationContext = context,
                    rootStimulusId = "root-1",
                ),
            )
        )

        store.observeArtifacts(
            rootInputId = "root-1",
            conversationContext = context,
            artifacts = listOf(
                ExternalContentArtifact(
                    content = "remote result",
                    provenance = Provenances.fromStimulusTrustLevel(
                        source = "web",
                        trustLevel = StimulusTrustLevel.UNTRUSTED_EXTERNAL,
                        sourceRef = "https://example.test",
                    ),
                )
            ),
        )

        val security = store.threadSecurityContext("root-1", context)
        assertEquals(DataTrust.EXTERNAL_DATA, security.aggregatedDataTrust)
        assertTrue(security.taintSourceSummaries.isNotEmpty())
    }

    @Test
    fun `impulse and goal work create typed threads`() {
        val store = CognitiveThreadStore()
        val automationContext = ConversationContext(
            sessionId = "goal-session",
            interlocutor = Interlocutor.named("goal-runtime"),
            security = ConversationSecurityContexts.internalAutomation(
                provider = "goal-runtime",
                channelId = "goal-session",
            ),
        )

        val impulseThread = store.ensureForImpulse(
            PendingImpulse(
                id = 1L,
                needId = "curiosity",
                prompt = "Look for new facts",
                tension = 0.8,
                rawValue = 0.9,
                rootImpulseId = "impulse-1",
                conversationContext = automationContext,
            )
        )
        val goalThread = store.ensureForGoalWork(
            GoalRunActivation(
                goalId = "goal-1",
                stepId = "step-1",
                rootInputId = "goal-root-1",
                stepDescription = "Verify latest pricing",
                acceptanceCriteria = "Provide verified pricing",
                workingContext = "pricing",
                conversationContext = automationContext,
                wakeReason = "timer",
            )
        )

        assertEquals(CognitiveThreadKind.DRIVE, impulseThread.kind)
        assertEquals(CognitiveThreadKind.GOAL_DIRECTED, goalThread.kind)
        assertNotNull(store.thread("goal-root-1", automationContext))
    }

    @Test
    fun `goal continuation survives opportunity generation and thread status transitions`() {
        val store = CognitiveThreadStore()
        val automationContext = ConversationContext(
            sessionId = "goal-session",
            interlocutor = Interlocutor.named("goal-runtime"),
            security = ConversationSecurityContexts.internalAutomation(
                provider = "goal-runtime",
                channelId = "goal-session",
            ),
        )
        val work = GoalRunActivation(
            goalId = "goal-1",
            stepId = "step-1",
            rootInputId = "goal:goal-1:step-1",
            stepDescription = "Verify latest pricing",
            acceptanceCriteria = "Provide verified pricing",
            workingContext = "pricing",
            conversationContext = automationContext,
            wakeReason = "timer",
        )

        store.bindGoalWork(work)
        store.goalOpportunity(work)
        store.markWaiting(work.rootInputId, automationContext, reason = "await_timer")
        val waiting = store.thread(work.rootInputId, automationContext)
        val continuation = store.goalWork(work.rootInputId, automationContext)
        store.markResolved(work.rootInputId, automationContext)

        assertEquals(work, continuation)
        assertEquals(CognitiveThreadStatus.WAITING, waiting?.status)
        assertEquals(CognitiveThreadStatus.RESOLVED, store.thread(work.rootInputId, automationContext)?.status)
    }
}
