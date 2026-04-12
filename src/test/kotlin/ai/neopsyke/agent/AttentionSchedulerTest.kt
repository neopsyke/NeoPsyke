package ai.neopsyke.agent

import ai.neopsyke.agent.model.Opportunity
import ai.neopsyke.agent.model.OpportunityKind
import ai.neopsyke.agent.model.PendingInput
import ai.neopsyke.agent.model.Intention
import ai.neopsyke.agent.model.IntentionKind
import ai.neopsyke.agent.model.Continuation
import ai.neopsyke.agent.model.GroundingMetadata
import ai.neopsyke.agent.model.QueuedContinuation
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
        maxPendingContinuations = 2,
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

    private fun enqueueTestContinuation(
        scheduler: AttentionScheduler,
        content: String,
        urgency: Urgency,
        rootInputId: String? = null,
        conversationContext: ai.neopsyke.agent.model.ConversationContext = ai.neopsyke.agent.model.ConversationContext.default(),
        planContext: ai.neopsyke.agent.model.PlanContext? = null,
    ): QueuedContinuation? =
        scheduler.enqueueContinuation(
            continuation = if (planContext != null) {
                Continuation.PlanStepContinuation(content = content, planContext = planContext)
            } else {
                Continuation.ConvergeNow(content = content, convergenceReason = "test")
            },
            urgency = urgency,
            rootInputId = rootInputId,
            conversationContext = conversationContext,
            groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
        )

    @Test
    fun `inputs always take priority over continuations and actions`() {
        val scheduler = AttentionScheduler(config)
        enqueueTestContinuation(scheduler, "process this", Urgency.HIGH)
        scheduler.enqueueAction(ActionType.CONTACT_USER, "hello", "reply", Urgency.HIGH, groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER)
        val input = testInput("new input")
        scheduler.enqueueInput(input, testOpportunity(input))

        val task = scheduler.nextTask()
        val opportunity = assertIs<ai.neopsyke.agent.model.LoopTask.AttendOpportunity>(task)
        val trigger = assertIs<ai.neopsyke.agent.model.OpportunityTrigger.Input>(opportunity.item.trigger)
        assertEquals("new input", trigger.input.content)
    }

    @Test
    fun `continuations outrank equally urgent actions and keep urgency order`() {
        val scheduler = AttentionScheduler(config)
        enqueueTestContinuation(scheduler, "low", Urgency.LOW)
        enqueueTestContinuation(scheduler, "high", Urgency.HIGH)
        scheduler.enqueueAction(ActionType.WEB_SEARCH, "q", "s", Urgency.HIGH, groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER)

        val first = scheduler.nextTask()
        val second = scheduler.nextTask()
        val third = scheduler.nextTask()

        val firstContinuation = assertIs<ai.neopsyke.agent.model.LoopTask.ProcessContinuation>(first)
        assertEquals("high", firstContinuation.item.content)
        val secondIntention = assertIs<ai.neopsyke.agent.model.LoopTask.PerformAction>(second)
        assertEquals(ActionType.WEB_SEARCH, secondIntention.item.type)
        val thirdContinuation = assertIs<ai.neopsyke.agent.model.LoopTask.ProcessContinuation>(third)
        assertEquals("low", thirdContinuation.item.content)
    }

    @Test
    fun `blocked roots are skipped until unblocked`() {
        val scheduler = AttentionScheduler(config.copy(maxPendingContinuations = 4, maxPendingActions = 4))
        val blockedRoot = "blocked-root"
        val freeRoot = "free-root"
        enqueueTestContinuation(scheduler, "blocked", Urgency.HIGH, rootInputId = blockedRoot)
        scheduler.enqueueAction(ActionType.CONTACT_USER, "free", "free", Urgency.MEDIUM, rootInputId = freeRoot, groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER)

        val first = scheduler.nextTask { rootInputId, _ -> rootInputId == blockedRoot }
        val second = scheduler.nextTask { _, _ -> false }

        val firstAction = assertIs<ai.neopsyke.agent.model.LoopTask.PerformAction>(first)
        assertEquals("free", firstAction.item.payload)
        val secondContinuation = assertIs<ai.neopsyke.agent.model.LoopTask.ProcessContinuation>(second)
        assertEquals("blocked", secondContinuation.item.content)
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

        assertNotNull(enqueueTestContinuation(scheduler, "t1", Urgency.MEDIUM))
        assertNotNull(enqueueTestContinuation(scheduler, "t2", Urgency.MEDIUM))
        assertNull(enqueueTestContinuation(scheduler, "t3", Urgency.MEDIUM))

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
        enqueueTestContinuation(scheduler, "line-2", Urgency.HIGH)
        scheduler.enqueueAction(ActionType.CONTACT_USER, "line-3", "summary", Urgency.MEDIUM, groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER)

        val snapshot = scheduler.queueSnapshot()
        assertEquals(1, snapshot.pendingInputCount)
        assertEquals(1, snapshot.pendingIntentionCount)
        assertEquals(1, snapshot.continuationCount)
        assertEquals(1, snapshot.pendingActionCount)
    }

    @Test
    fun `intentions are processed before lower priority continuations`() {
        val scheduler = AttentionScheduler(config)
        enqueueTestContinuation(scheduler, "low-thought", Urgency.LOW)
        scheduler.enqueueIntention(testIntention(summary = "prepare reply", urgency = Urgency.HIGH))

        val first = scheduler.nextTask()

        val intention = assertIs<ai.neopsyke.agent.model.LoopTask.ProcessIntention>(first)
        assertEquals("prepare reply", intention.item.intention.summary)
    }

    @Test
    fun `intentions outrank equally urgent continuations`() {
        val scheduler = AttentionScheduler(config)
        val rootInputId = "root-priority"
        enqueueTestContinuation(scheduler, "continue thinking", Urgency.HIGH, rootInputId = rootInputId)
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
    fun `plan continuations count as pending plan follow up for root`() {
        val scheduler = AttentionScheduler(config)
        val rootInputId = "root-plan"
        val sessionId = "default"
        enqueueTestContinuation(
            scheduler = scheduler,
            content = "Plan step 1/2: gather evidence",
            urgency = Urgency.MEDIUM,
            rootInputId = rootInputId,
            conversationContext = ai.neopsyke.agent.model.ConversationContext.default().copy(sessionId = sessionId),
            planContext = ai.neopsyke.agent.model.PlanContext(
                planId = "plan-1",
                planGoal = "goal",
                stepIndex = 0,
                totalSteps = 2,
                stepDescription = "gather evidence",
            ),
        )

        assertTrue(scheduler.hasPendingPlanContinuationsForInput(rootInputId, sessionId))
    }

    @Test
    fun `queue state returns sorted continuations and actions`() {
        val scheduler = AttentionScheduler(config)
        val medium = testInput("medium-input", InputPriority.MEDIUM)
        val high = testInput("high-input", InputPriority.HIGH)
        scheduler.enqueueInput(medium, testOpportunity(medium))
        scheduler.enqueueInput(high, testOpportunity(high))
        enqueueTestContinuation(scheduler, "low", Urgency.LOW)
        enqueueTestContinuation(scheduler, "high", Urgency.HIGH)
        scheduler.enqueueAction(ActionType.CONTACT_USER, "low-action", "s1", Urgency.LOW, groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER)
        scheduler.enqueueAction(ActionType.CONTACT_USER, "high-action", "s2", Urgency.HIGH, groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER)

        val state = scheduler.queueState()
        assertEquals(listOf("high-input", "medium-input"), state.inputs.map { it.content })
        assertEquals(listOf("high", "low"), state.continuations.map { it.content })
        assertEquals(listOf("high-action", "low-action"), state.actions.map { it.payload })
        assertNotNull(scheduler.nextTask())
    }

    @Test
    fun `scheduler can clear pending continuations and actions for a resolved input`() {
        val scheduler = AttentionScheduler(config.copy(maxPendingContinuations = 8, maxPendingActions = 8))
        val rootA = "root-a"
        val rootB = "root-b"
        enqueueTestContinuation(scheduler, "a-thought", Urgency.HIGH, rootInputId = rootA)
        enqueueTestContinuation(scheduler, "b-thought", Urgency.HIGH, rootInputId = rootB)
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

        assertEquals(1, cleared.continuationsRemoved)
        assertEquals(1, cleared.actionsRemoved)
        val state = scheduler.queueState()
        assertEquals(listOf("b-thought"), state.continuations.map { it.content })
        assertEquals(listOf("b-action"), state.actions.map { it.payload })
    }

    @Test
    fun `scheduler clear pending work is scoped by session for same root input`() {
        val scheduler = AttentionScheduler(config.copy(maxPendingContinuations = 8, maxPendingActions = 8))
        val root = "root-shared"
        val sessionA = "session-a"
        val sessionB = "session-b"
        val ctxA = ai.neopsyke.agent.model.ConversationContext(sessionA, ai.neopsyke.agent.model.Interlocutor.named("a"))
        val ctxB = ai.neopsyke.agent.model.ConversationContext(sessionB, ai.neopsyke.agent.model.Interlocutor.named("b"))
        enqueueTestContinuation(scheduler, "a-thought", Urgency.HIGH, rootInputId = root, conversationContext = ctxA)
        enqueueTestContinuation(scheduler, "b-thought", Urgency.HIGH, rootInputId = root, conversationContext = ctxB)
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

        assertEquals(1, cleared.continuationsRemoved)
        assertEquals(1, cleared.actionsRemoved)
        val state = scheduler.queueState()
        assertEquals(listOf("b-thought"), state.continuations.map { it.content })
        assertEquals(listOf("b-action"), state.actions.map { it.payload })
    }

    @Test
    fun `scheduler can detect pending fallback and plan thoughts for an input`() {
        val scheduler = AttentionScheduler(config.copy(maxPendingContinuations = 8, maxPendingActions = 8))
        val root = "root-plan"
        enqueueTestContinuation(
            scheduler = scheduler,
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

        assertTrue(scheduler.hasPendingPlanContinuationsForInput(root, "default"))
        assertTrue(scheduler.hasPendingFallbackExplanationAction(root, "default"))
        assertFalse(scheduler.hasPendingPlanContinuationsForInput("root-missing", "default"))
        assertFalse(scheduler.hasPendingFallbackExplanationAction("root-missing", "default"))
    }

    @Test
    fun `scheduler pending checks are scoped by session for same root input`() {
        val scheduler = AttentionScheduler(config.copy(maxPendingContinuations = 8, maxPendingActions = 8))
        val root = "root-shared-2"
        val ctxA = ai.neopsyke.agent.model.ConversationContext("session-a", ai.neopsyke.agent.model.Interlocutor.named("a"))
        val ctxB = ai.neopsyke.agent.model.ConversationContext("session-b", ai.neopsyke.agent.model.Interlocutor.named("b"))
        enqueueTestContinuation(
            scheduler = scheduler,
            content = "step one",
            urgency = Urgency.MEDIUM,
            rootInputId = root,
            conversationContext = ctxA,
            planContext = ai.neopsyke.agent.model.PlanContext(
                planId = "p1",
                planGoal = "goal-a",
                stepIndex = 0,
                totalSteps = 1,
                stepDescription = "step-a"
            ),
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
        scheduler.enqueueContinuation(
            continuation = Continuation.ConvergeNow(
                content = "${AttentionScheduler.CONVERGENCE_CONTINUATION_PREFIX}other session",
                convergenceReason = "test",
            ),
            urgency = Urgency.MEDIUM,
            rootInputId = root,
            conversationContext = ctxB,
            groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
        )

        assertTrue(scheduler.hasPendingPlanContinuationsForInput(root, "session-a"))
        assertTrue(scheduler.hasPendingFallbackExplanationAction(root, "session-a"))
        assertFalse(scheduler.hasPendingPlanContinuationsForInput(root, "session-b"))
        assertFalse(scheduler.hasPendingFallbackExplanationAction(root, "session-b"))
        assertFalse(scheduler.hasPendingConvergenceContinuationForInput(root, "session-a"))
        assertTrue(scheduler.hasPendingConvergenceContinuationForInput(root, "session-b"))
    }

    // ── RetryAlternative metadata propagation ──

    @Test
    fun `retry alternative preserves denial metadata through enqueue`() {
        val scheduler = AttentionScheduler(config)
        val rootInputId = "root-retry"
        val retryContinuation = Continuation.RetryAlternative(
            content = "Pick a different action",
            deniedActionType = ActionType.WEB_SEARCH,
            deniedActionPayload = """{"query":"test"}""",
            denialReason = "Action not allowed for external users",
            denialReasonCode = "policy_scope_restricted",
            allowFallbackExplanation = true,
            originActionType = ActionType.CONTACT_USER,
            originActionObservedEvidence = true,
        )

        val queued = scheduler.enqueueContinuation(
            continuation = retryContinuation,
            urgency = Urgency.HIGH,
            passes = 1,
            rootInputId = rootInputId,
            groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
        )

        assertNotNull(queued)
        assertIs<Continuation.RetryAlternative>(queued.continuation)
        assertEquals(ActionType.WEB_SEARCH, queued.deniedActionType)
        assertEquals("""{"query":"test"}""", queued.deniedActionPayload)
        assertEquals("Action not allowed for external users", queued.denialReason)
        assertEquals("policy_scope_restricted", queued.denialReasonCode)
        assertTrue(queued.allowFallbackExplanation)
        assertEquals(ActionType.CONTACT_USER, queued.originActionType)
        assertEquals(true, queued.originActionObservedEvidence)
        assertEquals(1, queued.passes)
        assertEquals(rootInputId, queued.rootInputId)
    }

    @Test
    fun `retry alternative accessors return null for non-retry continuations`() {
        val scheduler = AttentionScheduler(config)
        val queued = scheduler.enqueueContinuation(
            continuation = Continuation.ConvergeNow(
                content = "converge",
                convergenceReason = "test",
            ),
            urgency = Urgency.MEDIUM,
            groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
        )

        assertNotNull(queued)
        assertNull(queued.deniedActionType)
        assertNull(queued.deniedActionPayload)
        assertNull(queued.denialReason)
        assertNull(queued.denialReasonCode)
        assertNull(queued.planContext)
    }

    // ── ConvergeNow double-queue guard (recoverFromSuppressedPlan behaviour) ──

    @Test
    fun `convergence continuation blocks duplicate enqueue for same root and session`() {
        val scheduler = AttentionScheduler(config.copy(maxPendingContinuations = 8))
        val rootInputId = "root-converge"
        val sessionId = "default"
        val ctx = ai.neopsyke.agent.model.ConversationContext.default()

        val first = scheduler.enqueueContinuation(
            continuation = Continuation.ConvergeNow(
                content = "converge: plan suppressed",
                convergenceReason = "budget_exhausted",
            ),
            urgency = Urgency.HIGH,
            rootInputId = rootInputId,
            conversationContext = ctx,
            groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
        )
        assertNotNull(first)
        assertTrue(scheduler.hasPendingConvergenceContinuationForInput(rootInputId, sessionId))

        // The guard at the scheduler level: callers (DecisionDispatcher) check
        // hasPendingConvergenceContinuationForInput before enqueuing a second one.
        // Verify the check returns true so callers can skip the duplicate.
        assertTrue(scheduler.hasPendingConvergenceContinuationForInput(rootInputId, sessionId))
    }

    @Test
    fun `plan continuations prevent convergence recovery for same root`() {
        val scheduler = AttentionScheduler(config.copy(maxPendingContinuations = 8))
        val rootInputId = "root-plan-guard"
        val sessionId = "default"
        val ctx = ai.neopsyke.agent.model.ConversationContext.default()

        scheduler.enqueueContinuation(
            continuation = Continuation.PlanStepContinuation(
                content = "plan step 1",
                planContext = ai.neopsyke.agent.model.PlanContext(
                    planId = "p1",
                    planGoal = "goal",
                    stepIndex = 0,
                    totalSteps = 3,
                    stepDescription = "gather evidence",
                ),
            ),
            urgency = Urgency.MEDIUM,
            rootInputId = rootInputId,
            conversationContext = ctx,
            groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
        )

        // When plan steps are pending, the recoverFromSuppressedPlan guard
        // (hasPendingPlanContinuationsForInput) should return true, preventing
        // a ConvergeNow from being enqueued.
        assertTrue(scheduler.hasPendingPlanContinuationsForInput(rootInputId, sessionId))
        assertFalse(scheduler.hasPendingConvergenceContinuationForInput(rootInputId, sessionId))
    }

    @Test
    fun `convergence check is false for different session same root`() {
        val scheduler = AttentionScheduler(config.copy(maxPendingContinuations = 8))
        val rootInputId = "root-cross-session"
        val ctxA = ai.neopsyke.agent.model.ConversationContext("session-a", ai.neopsyke.agent.model.Interlocutor.named("a"))

        scheduler.enqueueContinuation(
            continuation = Continuation.ConvergeNow(
                content = "converge for session-a",
                convergenceReason = "hash_dedup",
            ),
            urgency = Urgency.HIGH,
            rootInputId = rootInputId,
            conversationContext = ctxA,
            groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
        )

        assertTrue(scheduler.hasPendingConvergenceContinuationForInput(rootInputId, "session-a"))
        assertFalse(scheduler.hasPendingConvergenceContinuationForInput(rootInputId, "session-b"))
    }
}
