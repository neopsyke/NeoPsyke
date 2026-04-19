package ai.neopsyke.agent.ego

import ai.neopsyke.agent.assignments.AssignmentActivation
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.ConversationSecurityContexts
import ai.neopsyke.agent.model.CognitiveThreadKind
import ai.neopsyke.agent.model.CognitiveThreadStatus
import ai.neopsyke.agent.model.Intention
import ai.neopsyke.agent.model.IntentionKind
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
import ai.neopsyke.agent.model.GroundingMetadata
import ai.neopsyke.agent.model.GroundingRequirement
import ai.neopsyke.agent.model.GroundingSource
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    fun `impulse and assignment work create typed threads`() {
        val store = CognitiveThreadStore()
        val automationContext = ConversationContext(
            sessionId = "assignment-session",
            interlocutor = Interlocutor.named("assignment-runtime"),
            security = ConversationSecurityContexts.internalAutomation(
                provider = "assignment-runtime",
                channelId = "assignment-session",
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
        val assignmentThread = store.ensureForAssignment(
            AssignmentActivation(
                workItemId = "assignment-1",
                stepId = "step-1",
                rootInputId = "assignment-root-1",
                stepDescription = "Verify latest pricing",
                acceptanceCriteria = "Provide verified pricing",
                workingContext = "pricing",
                conversationContext = automationContext,
                wakeReason = "timer",
            groundingMetadata = GroundingMetadata(requirement = GroundingRequirement.NOT_REQUIRED, source = GroundingSource.ASSIGNMENT_STEP_POLICY),
            )
        )

        assertEquals(CognitiveThreadKind.DRIVE, impulseThread.kind)
        assertEquals(CognitiveThreadKind.ASSIGNMENT_DIRECTED, assignmentThread.kind)
        assertNotNull(store.thread("assignment-root-1", automationContext))
    }

    @Test
    fun `assignment continuation survives opportunity generation and thread status transitions`() {
        val store = CognitiveThreadStore()
        val automationContext = ConversationContext(
            sessionId = "assignment-session",
            interlocutor = Interlocutor.named("assignment-runtime"),
            security = ConversationSecurityContexts.internalAutomation(
                provider = "assignment-runtime",
                channelId = "assignment-session",
            ),
        )
        val work = AssignmentActivation(
            workItemId = "assignment-1",
            stepId = "step-1",
            rootInputId = "work:assignment-1:step-1",
            stepDescription = "Verify latest pricing",
            acceptanceCriteria = "Provide verified pricing",
            workingContext = "pricing",
            conversationContext = automationContext,
            wakeReason = "timer",
        groundingMetadata = GroundingMetadata(requirement = GroundingRequirement.NOT_REQUIRED, source = GroundingSource.ASSIGNMENT_STEP_POLICY),
        )

        store.bindAssignment(work)
        store.assignmentOpportunity(work)
        store.markWaiting(work.rootInputId, automationContext, reason = "await_timer")
        val waiting = store.thread(work.rootInputId, automationContext)
        val continuation = store.assignment(work.rootInputId, automationContext)
        store.markResolved(work.rootInputId, automationContext)

        assertEquals(work, continuation)
        assertEquals(CognitiveThreadStatus.WAITING, waiting?.status)
        assertEquals(CognitiveThreadStatus.RESOLVED, store.thread(work.rootInputId, automationContext)?.status)
    }

    @Test
    fun `ordinary thread retains wait and terminal inspection state after cleanup`() {
        val store = CognitiveThreadStore()
        val context = ConversationContext.default()
        val input = PendingInput(
            id = 1L,
            content = "Find the latest filing",
            rootInputId = "root-user-1",
            conversationContext = context,
            percept = Percept(
                id = RootInputIds.next(),
                family = PerceptFamily.REQUEST,
                summary = "Find the latest filing",
                source = "chat:web",
                occurredAt = Instant.now(),
                conversationContext = context,
                rootStimulusId = "root-user-1",
            ),
        )

        store.bindInput(input)
        val opportunity = store.inputOpportunity(input)
        store.recordIntention(
            rootInputId = input.rootInputId,
            conversationContext = context,
            intention = Intention(
                id = "intention-1",
                cognitiveThreadId = store.thread(input.rootInputId, context)!!.id,
                kind = IntentionKind.OBSERVE,
                summary = "Observe the filing source",
                createdAt = Instant.now(),
                conversationContext = context,
                rootStimulusId = input.rootInputId,
            ),
        )

        store.markWaiting(input.rootInputId, context, reason = "await_http", resumeHint = "website_fetch")
        val waitingSnapshot = store.snapshot(input.rootInputId, context)
        assertNotNull(waitingSnapshot)
        assertEquals(CognitiveThreadStatus.WAITING, waitingSnapshot.thread.status)
        assertEquals("await_http", waitingSnapshot.waitState?.reason)
        assertEquals(opportunity.id, waitingSnapshot.latestOpportunity?.id)
        assertEquals(IntentionKind.OBSERVE, waitingSnapshot.latestIntention?.kind)

        store.markResolved(
            rootInputId = input.rootInputId,
            conversationContext = context,
            reason = "input_resolved",
            summary = "Delivered filing summary",
        )
        store.clearForInput(input.rootInputId, context.sessionId)

        val terminalSnapshot = store.snapshot(input.rootInputId, context)
        assertNotNull(terminalSnapshot)
        assertEquals(CognitiveThreadStatus.RESOLVED, terminalSnapshot.thread.status)
        assertEquals("Delivered filing summary", terminalSnapshot.terminalState?.summary)
        assertEquals("input_resolved", terminalSnapshot.terminalState?.reason)
        assertNull(terminalSnapshot.waitState)
        assertTrue(store.retainedRootInputIds().isEmpty())
        assertEquals(1, store.snapshots(includeTerminal = true).size)
    }
}
