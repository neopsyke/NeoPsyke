package ai.neopsyke.agent

import ai.neopsyke.agent.model.Opportunity
import ai.neopsyke.agent.model.OpportunityKind
import ai.neopsyke.agent.model.PendingInput
import ai.neopsyke.agent.model.Intention
import ai.neopsyke.agent.model.IntentionKind
import ai.neopsyke.agent.model.GroundingMetadata
import ai.neopsyke.agent.model.QueuedIntention
import ai.neopsyke.agent.model.RootInputIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttentionSchedulerTest {
    private val config = AgentConfig(
        maxPendingInputs = 2,
        maxPendingThoughts = 2,
        maxPendingActions = 2
    )

    private fun testInput(
        content: String,
        priority: InputPriority = InputPriority.MEDIUM,
        rootInputId: String = RootInputIds.next(),
    ): PendingInput =
        PendingInput(
            id = 1L,
            content = content,
            priority = priority,
            rootInputId = rootInputId,
        )

    private fun testOpportunity(
        input: PendingInput,
        kind: OpportunityKind = OpportunityKind.RESPOND,
        salience: Double = input.priority.level.toDouble(),
    ): Opportunity =
        Opportunity(
            id = RootInputIds.next(),
            cognitiveThreadId = input.cognitiveThreadId ?: RootInputIds.next(),
            kind = kind,
            summary = input.content,
            salience = salience,
            createdAt = java.time.Instant.now(),
            conversationContext = input.conversationContext,
            rootStimulusId = input.rootInputId,
        )

    private fun testIntention(
        summary: String = "prepare action",
        urgency: Urgency = Urgency.MEDIUM,
        rootInputId: String = RootInputIds.next(),
    ): QueuedIntention =
        QueuedIntention(
            intention = Intention(
                id = RootInputIds.next(),
                cognitiveThreadId = RootInputIds.next(),
                kind = IntentionKind.PREPARE,
                summary = summary,
                createdAt = java.time.Instant.now(),
                rootStimulusId = rootInputId,
            ),
            urgency = urgency,
            rootInputReceivedAtMs = 1L,
            proposedActionType = ActionType.CONTACT_USER,
            proposedActionPayload = "hello",
            proposedActionSummary = summary,
            groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
        )

    @Test
    fun `inputs always take priority over thoughts and actions`() {
        val scheduler = AttentionScheduler(config)
        scheduler.enqueueThought("process this", Urgency.HIGH, groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER)
        scheduler.enqueueAction(ActionType.CONTACT_USER, "hello", "reply", Urgency.HIGH, groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER)
        val input = testInput("new input")
        scheduler.enqueueInput(input, testOpportunity(input))

        val task = scheduler.nextTask()
        val opportunity = assertIs<ai.neopsyke.agent.model.LoopTask.AttendOpportunity>(task)
        val trigger = assertIs<ai.neopsyke.agent.model.OpportunityTrigger.Input>(opportunity.item.trigger)
        assertEquals("new input", trigger.input.content)
    }

    @Test
    fun `deferred continuations outrank equally urgent actions and keep urgency order`() {
        val scheduler = AttentionScheduler(config)
        scheduler.enqueueThought("low", Urgency.LOW, groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER)
        scheduler.enqueueThought("high", Urgency.HIGH, groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER)
        scheduler.enqueueAction(ActionType.WEB_SEARCH, "q", "s", Urgency.HIGH, groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER)

        val first = scheduler.nextTask()
        val second = scheduler.nextTask()
        val third = scheduler.nextTask()

        val firstIntention = assertIs<ai.neopsyke.agent.model.LoopTask.ProcessIntention>(first)
        assertEquals(IntentionKind.DEFER, firstIntention.item.intention.kind)
        assertEquals("high", firstIntention.item.deferredContent)
        val secondIntention = assertIs<ai.neopsyke.agent.model.LoopTask.PerformAction>(second)
        assertEquals(ActionType.WEB_SEARCH, secondIntention.item.type)
        val thirdIntention = assertIs<ai.neopsyke.agent.model.LoopTask.ProcessIntention>(third)
        assertEquals(IntentionKind.DEFER, thirdIntention.item.intention.kind)
        assertEquals("low", thirdIntention.item.deferredContent)
    }

    @Test
    fun `blocked roots are skipped until unblocked`() {
        val scheduler = AttentionScheduler(config.copy(maxPendingThoughts = 4, maxPendingActions = 4))
        val blockedRoot = "blocked-root"
        val freeRoot = "free-root"
        scheduler.enqueueThought("blocked", Urgency.HIGH, rootInputId = blockedRoot, groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER)
        scheduler.enqueueAction(ActionType.CONTACT_USER, "free", "free", Urgency.MEDIUM, rootInputId = freeRoot, groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER)

        val first = scheduler.nextTask { rootInputId, _ -> rootInputId == blockedRoot }
        val second = scheduler.nextTask { _, _ -> false }

        val firstAction = assertIs<ai.neopsyke.agent.model.LoopTask.PerformAction>(first)
        assertEquals("free", firstAction.item.payload)
        val secondThought = assertIs<ai.neopsyke.agent.model.LoopTask.ProcessIntention>(second)
        assertEquals("blocked", secondThought.item.deferredContent)
    }

    @Test
    fun `inputs are selected by priority then insertion order`() {
        val scheduler = AttentionScheduler(config)
        val medium = testInput("medium-first")
        val high = testInput("high-second", InputPriority.HIGH)
        scheduler.enqueueInput(medium, testOpportunity(medium))
        scheduler.enqueueInput(high, testOpportunity(high))

        val first = scheduler.nextTask()
        val second = scheduler.nextTask()

        val firstOpportunity = assertIs<ai.neopsyke.agent.model.LoopTask.AttendOpportunity>(first)
        val secondOpportunity = assertIs<ai.neopsyke.agent.model.LoopTask.AttendOpportunity>(second)
        val firstInput = assertIs<ai.neopsyke.agent.model.OpportunityTrigger.Input>(firstOpportunity.item.trigger)
        val secondInput = assertIs<ai.neopsyke.agent.model.OpportunityTrigger.Input>(secondOpportunity.item.trigger)
        assertEquals("high-second", firstInput.input.content)
        assertEquals(InputPriority.HIGH, firstInput.input.priority)
        assertEquals("medium-first", secondInput.input.content)
    }

    @Test
    fun `queue limits are enforced`() {
        val scheduler = AttentionScheduler(config)

        val input1 = testInput("1")
        val input2 = testInput("2")
        val input3 = testInput("3")
        assertTrue(scheduler.enqueueInput(input1, testOpportunity(input1)))
        assertTrue(scheduler.enqueueInput(input2, testOpportunity(input2)))
        assertFalse(scheduler.enqueueInput(input3, testOpportunity(input3)))

        assertNotNull(scheduler.enqueueThought("t1", Urgency.MEDIUM, groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER))
        assertNotNull(scheduler.enqueueThought("t2", Urgency.MEDIUM, groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER))
        assertNull(scheduler.enqueueThought("t3", Urgency.MEDIUM, groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER))

        assertTrue(scheduler.enqueueAction(ActionType.CONTACT_USER, "a1", "s1", Urgency.MEDIUM, groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER))
        assertTrue(scheduler.enqueueAction(ActionType.CONTACT_USER, "a2", "s2", Urgency.MEDIUM, groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER))
        assertFalse(scheduler.enqueueAction(ActionType.CONTACT_USER, "a3", "s3", Urgency.MEDIUM, groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER))
    }

    @Test
    fun `queue snapshot reports current queue counts`() {
        val scheduler = AttentionScheduler(config)
        val input = testInput("line-1")
        scheduler.enqueueInput(input, testOpportunity(input))
        scheduler.enqueueIntention(testIntention())
        scheduler.enqueueThought("line-2", Urgency.HIGH, groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER)
        scheduler.enqueueAction(ActionType.CONTACT_USER, "line-3", "summary", Urgency.MEDIUM, groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER)

        val snapshot = scheduler.queueSnapshot()
        assertEquals(1, snapshot.pendingInputCount)
        assertEquals(2, snapshot.pendingIntentionCount)
        assertEquals(1, snapshot.deferredIntentionCount)
        assertEquals(1, snapshot.pendingActionCount)
    }

    @Test
    fun `intentions are processed before lower priority thoughts`() {
        val scheduler = AttentionScheduler(config)
        scheduler.enqueueThought("low-thought", Urgency.LOW, groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER)
        scheduler.enqueueIntention(testIntention(summary = "prepare reply", urgency = Urgency.HIGH))

        val first = scheduler.nextTask()

        val intention = assertIs<ai.neopsyke.agent.model.LoopTask.ProcessIntention>(first)
        assertEquals("prepare reply", intention.item.intention.summary)
    }

    @Test
    fun `non defer intentions outrank equally urgent deferred continuations`() {
        val scheduler = AttentionScheduler(config)
        val rootInputId = "root-priority"
        scheduler.enqueueIntention(
            QueuedIntention(
                intention = Intention(
                    id = RootInputIds.next(),
                    cognitiveThreadId = RootInputIds.next(),
                    kind = IntentionKind.DEFER,
                    summary = "continue thinking",
                    createdAt = java.time.Instant.now(),
                    rootStimulusId = rootInputId,
                ),
                urgency = Urgency.HIGH,
                deferredContent = "continue thinking",
                groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
            )
        )
        scheduler.enqueueIntention(
            testIntention(
                summary = "execute chosen move",
                urgency = Urgency.HIGH,
                rootInputId = rootInputId,
            )
        )

        val first = assertIs<ai.neopsyke.agent.model.LoopTask.ProcessIntention>(scheduler.nextTask())

        assertEquals(IntentionKind.PREPARE, first.item.intention.kind)
        assertEquals("execute chosen move", first.item.intention.summary)
    }

    @Test
    fun `deferred plan intentions count as pending plan follow up for root`() {
        val scheduler = AttentionScheduler(config)
        val rootInputId = "root-plan"
        val sessionId = "default"
        scheduler.enqueueIntention(
            QueuedIntention(
                intention = Intention(
                    id = RootInputIds.next(),
                    cognitiveThreadId = RootInputIds.next(),
                    kind = IntentionKind.DEFER,
                    summary = "step 1",
                    createdAt = java.time.Instant.now(),
                    conversationContext = ai.neopsyke.agent.model.ConversationContext.default().copy(sessionId = sessionId),
                    rootStimulusId = rootInputId,
                ),
                urgency = Urgency.MEDIUM,
                deferredContent = "Plan step 1/2: gather evidence",
                deferredPlanContext = ai.neopsyke.agent.model.PlanContext(
                    planId = "plan-1",
                    planGoal = "goal",
                    stepIndex = 0,
                    totalSteps = 2,
                    stepDescription = "gather evidence",
                ),
                groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
            )
        )

        assertTrue(scheduler.hasPendingPlanThoughtsForInput(rootInputId, sessionId))
    }

    @Test
    fun `queue state returns sorted thoughts and actions`() {
        val scheduler = AttentionScheduler(config)
        val medium = testInput("medium-input", InputPriority.MEDIUM)
        val high = testInput("high-input", InputPriority.HIGH)
        scheduler.enqueueInput(medium, testOpportunity(medium))
        scheduler.enqueueInput(high, testOpportunity(high))
        scheduler.enqueueThought("low", Urgency.LOW, groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER)
        scheduler.enqueueThought("high", Urgency.HIGH, groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER)
        scheduler.enqueueAction(ActionType.CONTACT_USER, "low-action", "s1", Urgency.LOW, groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER)
        scheduler.enqueueAction(ActionType.CONTACT_USER, "high-action", "s2", Urgency.HIGH, groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER)

        val state = scheduler.queueState()
        assertEquals(listOf("high-input", "medium-input"), state.inputs.map { it.content })
        assertEquals(listOf("high", "low"), state.thoughts.map { it.content })
        assertEquals(listOf("high-action", "low-action"), state.actions.map { it.payload })
        assertNotNull(scheduler.nextTask())
    }

    @Test
    fun `scheduler can clear pending thoughts and actions for a resolved input`() {
        val scheduler = AttentionScheduler(config.copy(maxPendingThoughts = 8, maxPendingActions = 8))
        val rootA = "root-a"
        val rootB = "root-b"
        scheduler.enqueueThought("a-thought", Urgency.HIGH, rootInputId = rootA, groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER)
        scheduler.enqueueThought("b-thought", Urgency.HIGH, rootInputId = rootB, groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER)
        scheduler.enqueueAction(
            ActionType.WEB_SEARCH,
            "a-action",
            "a-summary",
            Urgency.HIGH,
            rootInputId = rootA,
            groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
        )
        scheduler.enqueueAction(
            ActionType.WEB_SEARCH,
            "b-action",
            "b-summary",
            Urgency.HIGH,
            rootInputId = rootB,
            groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
        )

        val cleared = scheduler.clearPendingWorkForInput(rootA, "default")

        assertEquals(1, cleared.thoughtsRemoved)
        assertEquals(1, cleared.actionsRemoved)
        val state = scheduler.queueState()
        assertEquals(listOf("b-thought"), state.thoughts.map { it.content })
        assertEquals(listOf("b-action"), state.actions.map { it.payload })
    }

    @Test
    fun `scheduler clear pending work is scoped by session for same root input`() {
        val scheduler = AttentionScheduler(config.copy(maxPendingThoughts = 8, maxPendingActions = 8))
        val root = "root-shared"
        val sessionA = "session-a"
        val sessionB = "session-b"
        val ctxA = ai.neopsyke.agent.model.ConversationContext(sessionA, ai.neopsyke.agent.model.Interlocutor.named("a"))
        val ctxB = ai.neopsyke.agent.model.ConversationContext(sessionB, ai.neopsyke.agent.model.Interlocutor.named("b"))
        scheduler.enqueueThought(
            content = "a-thought",
            urgency = Urgency.HIGH,
            rootInputId = root,
            conversationContext = ctxA,
            groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
        )
        scheduler.enqueueThought(
            content = "b-thought",
            urgency = Urgency.HIGH,
            rootInputId = root,
            conversationContext = ctxB,
            groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
        )
        scheduler.enqueueAction(
            type = ActionType.WEB_SEARCH,
            payload = "a-action",
            summary = "a-summary",
            urgency = Urgency.HIGH,
            rootInputId = root,
            conversationContext = ctxA,
            groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
        )
        scheduler.enqueueAction(
            type = ActionType.WEB_SEARCH,
            payload = "b-action",
            summary = "b-summary",
            urgency = Urgency.HIGH,
            rootInputId = root,
            conversationContext = ctxB,
            groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
        )

        val cleared = scheduler.clearPendingWorkForInput(rootInputId = root, sessionId = sessionA)

        assertEquals(1, cleared.thoughtsRemoved)
        assertEquals(1, cleared.actionsRemoved)
        val state = scheduler.queueState()
        assertEquals(listOf("b-thought"), state.thoughts.map { it.content })
        assertEquals(listOf("b-action"), state.actions.map { it.payload })
    }

    @Test
    fun `scheduler can detect pending fallback and plan thoughts for an input`() {
        val scheduler = AttentionScheduler(config.copy(maxPendingThoughts = 8, maxPendingActions = 8))
        val root = "root-plan"
        scheduler.enqueueThought(
            content = "step 1",
            urgency = Urgency.MEDIUM,
            rootInputId = root,
            planContext = ai.neopsyke.agent.model.PlanContext(
                planId = "p1",
                planGoal = "goal",
                stepIndex = 0,
                totalSteps = 2,
                stepDescription = "step"
            ),
            groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
        )
        scheduler.enqueueAction(
            type = ActionType.CONTACT_USER,
            payload = "fallback",
            summary = "fallback",
            urgency = Urgency.HIGH,
            isFallbackExplanation = true,
            rootInputId = root,
            groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
        )

        assertTrue(scheduler.hasPendingPlanThoughtsForInput(root, "default"))
        assertTrue(scheduler.hasPendingFallbackExplanationAction(root, "default"))
        assertFalse(scheduler.hasPendingPlanThoughtsForInput("root-missing", "default"))
        assertFalse(scheduler.hasPendingFallbackExplanationAction("root-missing", "default"))
    }

    @Test
    fun `scheduler pending checks are scoped by session for same root input`() {
        val scheduler = AttentionScheduler(config.copy(maxPendingThoughts = 8, maxPendingActions = 8))
        val root = "root-shared-2"
        val ctxA = ai.neopsyke.agent.model.ConversationContext("session-a", ai.neopsyke.agent.model.Interlocutor.named("a"))
        val ctxB = ai.neopsyke.agent.model.ConversationContext("session-b", ai.neopsyke.agent.model.Interlocutor.named("b"))
        scheduler.enqueueThought(
            content = "step one",
            urgency = Urgency.MEDIUM,
            rootInputId = root,
            planContext = ai.neopsyke.agent.model.PlanContext(
                planId = "p1",
                planGoal = "goal-a",
                stepIndex = 0,
                totalSteps = 1,
                stepDescription = "step-a"
            ),
            conversationContext = ctxA,
            groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
        )
        scheduler.enqueueAction(
            type = ActionType.CONTACT_USER,
            payload = "fallback-a",
            summary = "fallback-a",
            urgency = Urgency.HIGH,
            isFallbackExplanation = true,
            rootInputId = root,
            conversationContext = ctxA,
            groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
        )
        scheduler.enqueueThought(
            content = "${AttentionScheduler.CONVERGENCE_THOUGHT_PREFIX}other session",
            urgency = Urgency.MEDIUM,
            rootInputId = root,
            conversationContext = ctxB,
            groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
        )

        assertTrue(scheduler.hasPendingPlanThoughtsForInput(root, "session-a"))
        assertTrue(scheduler.hasPendingFallbackExplanationAction(root, "session-a"))
        assertFalse(scheduler.hasPendingPlanThoughtsForInput(root, "session-b"))
        assertFalse(scheduler.hasPendingFallbackExplanationAction(root, "session-b"))
        assertFalse(scheduler.hasPendingConvergenceThoughtForInput(root, "session-a"))
        assertTrue(scheduler.hasPendingConvergenceThoughtForInput(root, "session-b"))
    }
}
