package ai.neopsyke.agent.ego

import kotlinx.coroutines.delay
import mu.KotlinLogging
import ai.neopsyke.agent.cortex.motor.actions.control.ActionControlService
import ai.neopsyke.agent.cortex.motor.actions.control.LegacyCompatibleActionControlService
import ai.neopsyke.agent.config.*
import ai.neopsyke.agent.cortex.motor.actions.EvidenceArtifactStore
import ai.neopsyke.agent.cortex.motor.actions.InMemoryEvidenceArtifactStore
import ai.neopsyke.agent.model.*
import ai.neopsyke.agent.cortex.motor.MotorCortex
import ai.neopsyke.agent.cortex.motor.actions.ActionCapability
import ai.neopsyke.agent.cortex.sensory.CognitiveCueMetadata
import ai.neopsyke.agent.cortex.sensory.CognitiveSignal
import ai.neopsyke.agent.cortex.sensory.ActionFeedbackCue
import ai.neopsyke.agent.cortex.sensory.GoalRuntimeCue
import ai.neopsyke.agent.cortex.sensory.PerceptualAppraiser
import ai.neopsyke.agent.cortex.sensory.RuntimeControlSignal
import ai.neopsyke.agent.cortex.sensory.SensoryCortex
import ai.neopsyke.agent.memory.longterm.MemoryEventType
import ai.neopsyke.agent.memory.scratchpad.ScratchpadStore
import ai.neopsyke.agent.id.EmptyGoalRegistry
import ai.neopsyke.agent.id.GoalRegistry
import ai.neopsyke.agent.goal.NoopGoalsGateway
import ai.neopsyke.agent.goal.GoalRunActivation
import ai.neopsyke.agent.goal.GoalsGateway
import ai.neopsyke.agent.support.PromptInjectionDefense
import ai.neopsyke.agent.support.TextSecurity
import ai.neopsyke.agent.superego.Superego
import ai.neopsyke.instrumentation.AgentEvent
import ai.neopsyke.instrumentation.AgentEvents
import ai.neopsyke.instrumentation.AgentInstrumentation
import ai.neopsyke.instrumentation.NoopAgentInstrumentation
import ai.neopsyke.instrumentation.PhaseTimingCollector

private val logger = KotlinLogging.logger {}

class Ego(
    private val planner: Planner,
    private val superego: Superego,
    private val motorCortex: MotorCortex,
    private val config: AgentConfig,
    private val memory: MemorySystem,
    private val metaReasoner: MetaReasoner = NoopMetaReasoner,
    private val sensoryCortex: SensoryCortex = SensoryCortex.stdin(config),
    private val scratchpadStore: ScratchpadStore = ScratchpadStore(config.memory.scratchpad),
    private val scratchpadFinalizer: ScratchpadFinalizer = NoopScratchpadFinalizer,
    private val instrumentation: AgentInstrumentation = NoopAgentInstrumentation,
    private val actionControlService: ActionControlService = LegacyCompatibleActionControlService { action, authorization ->
        motorCortex.execute(action, config.searchResultCount, authorization)
    },
    private val goalRegistry: GoalRegistry = EmptyGoalRegistry,
    private val goalsGateway: GoalsGateway = NoopGoalsGateway,
    private val evidenceArtifactStore: EvidenceArtifactStore = InMemoryEvidenceArtifactStore(),
) {
    @Volatile private var id: ai.neopsyke.agent.id.Id? = null

    init {
        superego.setActionRegistry(motorCortex.actionRegistry())
    }

    fun setId(id: ai.neopsyke.agent.id.Id) {
        this.id = id
        impulseTracker.setId(id)
    }

    /** Delegates to the attention scheduler's impulse enqueue. Used by the Id module. */
    fun enqueueImpulse(impulse: PendingImpulse, maxPendingImpulses: Int): Boolean {
        val opportunity = cognitiveThreads.impulseOpportunity(impulse)
        return scheduler.enqueueImpulse(impulse, opportunity, maxPendingImpulses)
    }

    /** Checks whether the Ego has any pending work. Used by the Id module for idle detection. */
    fun hasPendingWork(): Boolean = scheduler.hasPendingWork()

    suspend fun processAutonomousStagedActions(limit: Int = config.actionControl.autonomousWorkerBatchSize): Int =
        actionPipeline.processAutonomousStagedActions(limit)

    interface Planner {
        fun decide(trigger: EgoTrigger, context: PlannerContext): EgoDecision
        fun resetForInput(rootInputId: String) {}
    }

    private val scheduler = AttentionScheduler(config)
    private val cognitiveThreads = CognitiveThreadStore()
    private val perceptualAppraiser = PerceptualAppraiser()
    private val impulseTracker = ImpulseLifecycleTracker(instrumentation, scheduler)
    private val dialogueBySession: MutableMap<String, ArrayDeque<DialogueTurn>> =
        object : LinkedHashMap<String, ArrayDeque<DialogueTurn>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArrayDeque<DialogueTurn>>): Boolean =
                size > MAX_TRACKED_SESSIONS
        }
    private val deliberation = DeliberationEngine(
        config, instrumentation, metaReasoner, cognitiveThreads, evidenceArtifactStore,
        isEvidenceActionType = { motorCortex.hasCapability(it, ActionCapability.GATHERS_EVIDENCE) }
    )
    private val telemetry = EgoTelemetry(instrumentation, scheduler, memory, scratchpadStore, config)
    private val fallbackHandler = FallbackHandler(
        scheduler, config, instrumentation, deliberation, memory, telemetry,
        dialogueFor = ::dialogueFor,
        resolveSessionId = ::resolveSessionId,
        processActionFallback = ::processAction,
    )
    private val dispatcher = DecisionDispatcher(
        scheduler, config, instrumentation, deliberation, memory, motorCortex,
        scratchpadStore, telemetry, fallbackHandler,
        dialogueFor = ::dialogueFor,
        resolveSessionId = ::resolveSessionId,
        inputScope = ::inputScope,
    )
    private val taskVerifier: DecisionVerifier = DeterministicDecisionVerifier()
    private val actionPipeline = ActionReviewPipeline(
        superego, motorCortex, config, instrumentation, scheduler,
        taskVerifier, scratchpadStore, scratchpadFinalizer,
        deliberation, memory, telemetry, fallbackHandler, impulseTracker,
        dialogueFor = ::dialogueFor,
        resolveSessionId = ::resolveSessionId,
        superegoContext = ::superegoContext,
        cleanupResolvedInputAfterAnswer = ::cleanupResolvedInputAfterAnswer,
        cleanupSatisfiedIdImpulse = ::cleanupSatisfiedIdImpulse,
        getId = { id },
        actionControlService = actionControlService,
        actionLifecycleObserver = goalsGateway,
        emitActionFeedback = sensoryCortex::offerActionFeedback,
    )

    private fun dialogueFor(sessionId: String): ArrayDeque<DialogueTurn> =
        dialogueBySession.getOrPut(sessionId) { ArrayDeque() }

    private fun resolveSessionId(conversationContext: ConversationContext): String =
        conversationContext.sessionId

    private fun inputScope(rootInputId: String?, conversationContext: ConversationContext): InputScope =
        InputScope(
            rootInputId = rootInputId,
            sessionId = resolveSessionId(conversationContext)
        )

    private fun activateSession(conversationContext: ConversationContext) {
        val sessionId = resolveSessionId(conversationContext)
        val interlocutor = conversationContext.interlocutor
        memory.setActiveSession(sessionId, interlocutor, conversationContext.security)
        deliberation.setActiveSession(sessionId)
    }

    suspend fun runInteractive() {
        logger.info { "Ego loop started. Web dashboard chat is the active input path." }
        instrumentation.emit(AgentEvents.loopStatus(status = "running", message = "Interactive loop started"))
        telemetry.emitQueueSnapshot("loop_start")
        while (true) {
            when (val signal = sensoryCortex.nextSignal()) {
                CognitiveSignal.NoStimulus -> continue

                is RuntimeControlSignal.SourceClosed -> {
                    val message = if (signal.source == "stdin") "stdin_closed" else "${signal.source}_closed"
                    instrumentation.emit(AgentEvents.loopStatus(status = "stopped", message = message))
                    break
                }

                is RuntimeControlSignal.ExitRequested -> {
                    logger.info { "Stopping Ego loop." }
                    val message = if (signal.source == "stdin") "exit_command" else "${signal.source}_exit_command"
                    instrumentation.emit(AgentEvents.loopStatus(status = "stopped", message = message))
                    break
                }

                is CognitiveSignal.StimulusReceived -> {
                    val stimulus = signal.stimulus
                    val percept = signal.percept ?: perceptualAppraiser.appraise(stimulus)
                    val goalCue = GoalRuntimeCue.fromStimulus(stimulus)
                    if (goalCue != null) {
                        val work = goalsGateway.nextWorkFromCue(goalCue)
                        if (work != null) {
                            logger.info { "Goal work picked: ${work.goalId}/${work.stepId}" }
                            cognitiveThreads.bindPercept(
                                percept = percept.copy(conversationContext = work.conversationContext),
                                rootInputId = work.rootInputId,
                                kind = CognitiveThreadKind.GOAL_DIRECTED,
                                title = work.stepDescription,
                            )
                            cognitiveThreads.bindGoalWork(work)
                            maybeCreateGoalScratchpad(work)
                            val opportunity = cognitiveThreads.goalOpportunity(work)
                            scheduler.enqueueThreadContinuation(
                                continuation = ThreadContinuation(
                                    rootInputId = work.rootInputId,
                                    conversationContext = work.conversationContext,
                                    reason = goalCue.reason,
                                ),
                                opportunity = opportunity,
                            )
                            runLoop()
                            cleanupAfterProjectAdvance(work.rootInputId, work.conversationContext)
                        }
                        continue
                    }
                    if (stimulus.metadata[CognitiveCueMetadata.METADATA_CUE_TYPE] ==
                        CognitiveCueMetadata.CUE_TYPE_ID_IMPULSE_READY
                    ) {
                        cognitiveThreads.bindPercept(
                            percept = percept,
                            rootInputId = stimulus.metadata[CognitiveCueMetadata.METADATA_ROOT_IMPULSE_ID] ?: stimulus.id,
                            kind = CognitiveThreadKind.DRIVE,
                            title = stimulus.content,
                        )
                        runLoop()
                        continue
                    }
                    val actionFeedbackCue = ActionFeedbackCue.fromStimulus(stimulus)
                    if (actionFeedbackCue != null) {
                        processActionFeedback(actionFeedbackCue, stimulus, percept)
                        continue
                    }
                    val thread = cognitiveThreads.bindPercept(
                        percept = percept,
                        rootInputId = stimulus.id,
                        kind = CognitiveThreadKind.CONVERSATION,
                        title = stimulus.content,
                    )
                    val input = PendingInput(
                        id = 0L,
                        content = stimulus.content,
                        priority = stimulus.metadata["priority"]
                            ?.let { runCatching { InputPriority.valueOf(it) }.getOrNull() }
                            ?: InputPriority.HIGH,
                        source = stimulus.source,
                        rootInputId = stimulus.id,
                        receivedAtMs = stimulus.receivedAt.toEpochMilli(),
                        conversationContext = stimulus.conversationContext,
                        percept = percept.copy(cognitiveThreadId = thread.id),
                        cognitiveThreadId = thread.id,
                    )
                    val opportunity = cognitiveThreads.inputOpportunity(input)
                    if (!scheduler.enqueueInput(input, opportunity)
                    ) {
                        logger.warn { "Input queue full; dropping input." }
                        instrumentation.emit(AgentEvents.warning("Input queue full; dropping input."))
                        telemetry.recordQueueSaturation(
                            queueType = "input",
                            capacity = config.maxPendingInputs,
                            reason = "enqueue_input_failed_full"
                        )
                        continue
                    }
                    scheduler.latestQueuedInput()?.let { queuedInput ->
                        instrumentation.emit(AgentEvents.inputQueued(queuedInput))
                    }
                    telemetry.emitQueueSnapshot("input_enqueued")
                    runLoop()
                }

                RuntimeControlSignal.ShutdownRequested -> {
                    logger.info { "Graceful shutdown requested." }
                    instrumentation.emit(AgentEvents.loopStatus(status = "stopped", message = "shutdown_requested"))
                    break
                }

                is RuntimeControlSignal.ConfigReloaded -> {
                    logger.info { "Config reloaded: key=${signal.key}" }
                }
            }
        }
    }

    private suspend fun processActionFeedback(
        cue: ActionFeedbackCue,
        stimulus: StimulusEnvelope,
        percept: Percept,
    ) {
        val thread = cognitiveThreads.bindPercept(
            percept = percept,
            rootInputId = cue.rootInputId,
            kind = CognitiveThreadKind.CONVERSATION,
            title = cue.actionSummary.ifBlank { stimulus.content },
        )
        val feedbackAction = PendingAction(
            id = cue.sourceActionId ?: 0L,
            urgency = Urgency.fromRaw(cue.urgency),
            type = cue.actionType,
            payload = "",
            summary = cue.actionSummary.ifBlank { cue.actionType.id },
            rootInputId = cue.rootInputId,
            rootInputReceivedAtMs = cue.rootInputReceivedAtMs,
            conversationContext = cue.conversationContext,
            requiresFollowUpThought = cue.requiresFollowUpThought,
            followUpPrefix = motorCortex.followUpPrefix(cue.actionType),
            origin = cue.origin,
        )
        val outcome = ActionOutcome(
            statusSummary = cue.statusSummary,
            plannerSignal = cue.plannerSignal.ifBlank { cue.feedbackContent },
            executionStatus = cue.executionStatus,
            observedEvidence = cue.observedEvidence,
            actionErrorCategory = cue.actionErrorCategory,
            fetchErrorCategory = cue.fetchErrorCategory,
        )
        val observed = cue.observedEvidence ?: deliberation.observedEvidence(feedbackAction, outcome)
        if (cue.executionStatus == ActionExecutionStatus.FAILED) {
            deliberation.markEvidenceFailure(feedbackAction)
        }
        deliberation.recordEvidenceProgress(feedbackAction, outcome, observed)
        deliberation.onActionExecuted(feedbackAction, observed)
        deliberation.recordActionOutcome(feedbackAction, outcome, observed)
        instrumentation.emit(
            AgentEvent(
                type = "action_feedback_integrated",
                data = mapOf(
                    "action_type" to cue.actionType.id,
                    "root_input_id" to cue.rootInputId,
                    "execution_status" to cue.executionStatus.name.lowercase(),
                    "continuation_required" to cue.plannerContinuationRequired,
                )
            )
        )
        if (!cue.plannerContinuationRequired) {
            return
        }

        val safePlannerSignal = PromptInjectionDefense.asUntrustedDataBlock(
            text = outcome.plannerSignal,
            maxChars = FOLLOW_UP_SIGNAL_MAX_CHARS
        )
        val followUpThought = TextSecurity.clamp(
            "${motorCortex.followUpPrefix(cue.actionType)}\n$safePlannerSignal\n" +
                "Produce the next planner decision as one raw JSON object only. " +
                "Do not use tool or function wrappers.",
            config.planner.maxThoughtChars
        )
        val queued = scheduler.enqueueIntention(
            QueuedIntention(
                intention = Intention(
                    id = RootInputIds.next(),
                    cognitiveThreadId = thread.id,
                    kind = IntentionKind.DEFER,
                    summary = TextSecurity.preview(followUpThought, 160),
                    createdAt = java.time.Instant.now(),
                    conversationContext = cue.conversationContext,
                    rootStimulusId = cue.rootInputId,
                ),
                urgency = Urgency.fromRaw(cue.urgency),
                rootInputReceivedAtMs = cue.rootInputReceivedAtMs ?: stimulus.receivedAt.toEpochMilli(),
                origin = cue.origin,
                deferredThoughtContent = followUpThought,
                deferredThoughtPasses = cue.attempts,
                deferredAllowFallbackExplanation = true,
                deferredOriginActionType = cue.actionType,
                deferredOriginActionObservedEvidence = observed,
            )
        )
        if (!queued) {
            instrumentation.emit(AgentEvents.warning("Feedback queue full; dropping action feedback continuation."))
            telemetry.recordQueueSaturation(
                queueType = "thought",
                capacity = config.maxPendingThoughts,
                reason = "enqueue_feedback_followup_failed_full"
            )
            return
        }
        telemetry.emitQueueSnapshot("feedback_followup_enqueued")
        runLoop()
    }

    private suspend fun runLoop() {
        var steps = 0
        while (steps < config.planner.maxLoopStepsPerInput) {
            val task = scheduler.nextTask() ?: break
            steps += 1
            instrumentation.emit(AgentEvents.loopStep(step = steps, taskType = taskType(task)))
            val taskConversationContext = taskConversationContext(task)
            activateSession(taskConversationContext)
            val state = deliberation.startStep()
            telemetry.emitDeliberationState(taskType(task), state)
            try {
                when (task) {
                    is LoopTask.AttendOpportunity -> processOpportunity(task.item)
                    is LoopTask.ProcessIntention -> processIntention(task.item)
                    is LoopTask.ProcessThought -> processThought(task.item)
                    is LoopTask.PerformAction -> processAction(task.item)
                }
            } catch (ex: Exception) {
                logger.warn(ex) { "Task processing failed for task_type=${taskType(task)}." }
                deliberation.onTaskFailure()
                instrumentation.emit(AgentEvents.warning("Task processing failed for ${taskType(task)}; continuing loop."))
            }
            deliberation.maybeForceTerminalAnswer(
                scheduler = scheduler,
                rootInputId = taskRootInputId(task),
                rootInputReceivedAtMs = taskRootInputReceivedAtMs(task),
                conversationContext = taskConversationContext
            )
            maybeRunLongTermMemoryAssessment(
                trigger = "interval",
                sessionId = resolveSessionId(taskConversationContext)
            )
            impulseTracker.maybeFinalizeLifecycle(taskRootInputId(task))
            telemetry.emitQueueSnapshot("task_processed")
            if (steps == 1 || steps % HEAP_SNAPSHOT_INTERVAL == 0) {
                telemetry.emitHeapSnapshot()
            }
        }

        if (steps >= config.planner.maxLoopStepsPerInput && scheduler.hasPendingWork()) {
            logger.warn { "Reached loop step limit with pending work still in queues." }
            instrumentation.emit(AgentEvents.warning("Loop step limit reached with pending work."))
            telemetry.emitQueueSnapshot("step_limit_reached")
            impulseTracker.forceDenyAll(reason = "step_limit_reached")
            val fallbackAction = scheduler.dequeueFallbackExplanationAction()
            if (fallbackAction != null) {
                steps += 1
                instrumentation.emit(AgentEvents.loopStep(step = steps, taskType = "action_fallback"))
                processAction(fallbackAction)
                telemetry.emitQueueSnapshot("fallback_explanation_step")
            }
        }

        if (!scheduler.hasPendingWork() && !sensoryCortex.hasPendingSyntheticSignals()) {
            impulseTracker.finalizeAllIdle()
            val retainedRootInputs = cognitiveThreads.retainedRootInputIds()
            deliberation.reset(retainThreadContinuity = retainedRootInputs.isNotEmpty())
            memory.resetForNewInput()
            dispatcher.resetForNewInput()
            val clearedThreadScratchpads = scratchpadStore.clearOrphanedThreadWorkspaces(retainedRootInputs)
            val clearedDrafts = scratchpadStore.clearIntentionDrafts()
            if (clearedThreadScratchpads > 0 || clearedDrafts > 0) {
                instrumentation.emit(
                    AgentEvent(
                        type = "scratchpad_cleared",
                        data = mapOf(
                            "cleared_count" to clearedThreadScratchpads,
                            "cleared_drafts" to clearedDrafts,
                            "reason" to "queues_drained"
                        )
                    )
                )
            }
        }

        if (config.loopDelayMs > 0) {
            delay(config.loopDelayMs.toLong())
        }
    }

    private suspend fun processInput(input: PendingInput, opportunity: Opportunity? = null) {
        val timing = PhaseTimingCollector("input", input.rootInputId)
        val convCtx = input.conversationContext
        val sessionId = resolveSessionId(convCtx)
        activateSession(convCtx)
        cognitiveThreads.bindInput(input)
        val isFeedbackInput = input.percept?.family == PerceptFamily.FEEDBACK
        if (!isFeedbackInput) {
            id?.onActivity("input_received")
        }

        timing.startPhase("input_processing")
        instrumentation.emit(AgentEvents.inputProcessing(input))
        if (!isFeedbackInput) {
            val userTurn = DialogueTurn(
                role = DialogueRole.USER,
                content = input.content,
                sessionId = sessionId,
                interlocutor = convCtx.interlocutor,
                timestamp = java.time.Instant.now()
            )
            dialogueFor(sessionId).addLast(userTurn)
            memory.remember(userTurn)
        }
        maybeCreateScratchpad(input)
        trimDialogue(sessionId)

        timing.startPhase("planner_context")
        val trigger = EgoTrigger.IncomingInput(input)
        val context = plannerContext(
            trigger = trigger,
            rootInputId = input.rootInputId,
            sessionId = sessionId,
            conversationContext = convCtx,
            opportunity = opportunity,
        )

        timing.startPhase("meta_assessment")
        val assessment = deliberation.maybeAssessAndUpdateGuidance(trigger, context)

        timing.startPhase("planner_decide")
        val decision = planner.decide(
            trigger = trigger,
            context = context.copy(metaGuidance = deliberation.guidance())
        )
        val finalDecision = deliberation.maybeApplyPressureOverride(decision, assessment)
        deliberation.onPlannerDecision(finalDecision)
        journalPlannerDecision(finalDecision)

        timing.startPhase("apply_decision")
        dispatcher.dispatch(
            finalDecision,
            nextPassCount = 0,
            originThought = null,
            rootInputId = input.rootInputId,
            rootInputReceivedAtMs = input.receivedAtMs,
            conversationContext = convCtx
        )

        instrumentation.emit(AgentEvents.phaseTimings(timing.build()))
    }

    private suspend fun processOpportunity(opportunity: ScheduledOpportunity) {
        when (val trigger = opportunity.trigger) {
            is OpportunityTrigger.Input -> processInput(trigger.input, opportunity.opportunity)
            is OpportunityTrigger.Impulse -> processImpulse(trigger.impulse, opportunity.opportunity)
            is OpportunityTrigger.ThreadWork -> processThreadContinuation(trigger.continuation, opportunity.opportunity)
        }
    }

    private suspend fun processThought(thought: PendingThought) {
        val timing = PhaseTimingCollector("thought", thought.rootInputId)
        val convCtx = thought.conversationContext
        val sessionId = resolveSessionId(convCtx)
        activateSession(convCtx)

        if (thought.passes >= config.planner.maxThoughtPasses) {
            logger.info { "Dropping thought ${thought.id} due to max thought passes." }
            instrumentation.emit(AgentEvents.thoughtDropped(thought = thought, reason = "max_passes_reached"))
            if (thought.allowFallbackExplanation) {
                fallbackHandler.enqueueFallbackExplanation(thought)
            }
            return
        }

        timing.startPhase("thought_processing")
        instrumentation.emit(AgentEvents.thoughtProcessing(thought))
        thought.planContext?.let { planContext ->
            instrumentation.emit(
                AgentEvents.planStepStarted(
                    planId = planContext.planId,
                    stepIndex = planContext.stepIndex,
                    totalSteps = planContext.totalSteps,
                    stepDescription = planContext.stepDescription,
                    rootInputId = thought.rootInputId,
                )
            )
        }

        timing.startPhase("planner_context")
        val trigger = EgoTrigger.PendingThoughtInput(thought)
        val baseContext = plannerContext(
            trigger,
            rootInputId = thought.rootInputId,
            sessionId = sessionId,
            conversationContext = convCtx
        )
        val context = applyIdConvergenceContextForOrigin(
            baseContext = baseContext,
            origin = thought.origin,
            triggeringTension = idNeedTension(thought.origin.needId),
        )

        timing.startPhase("meta_assessment")
        val assessment = deliberation.maybeAssessAndUpdateGuidance(trigger, context)

        timing.startPhase("planner_decide")
        val decision = planner.decide(
            trigger = trigger,
            context = context.copy(metaGuidance = deliberation.guidance())
        )
        val finalDecision = deliberation.maybeApplyPressureOverride(decision, assessment)
        deliberation.onPlannerDecision(finalDecision)
        journalPlannerDecision(finalDecision)

        timing.startPhase("apply_decision")
        dispatcher.dispatch(
            finalDecision,
            nextPassCount = thought.passes + 1,
            originThought = thought,
            rootInputId = thought.rootInputId,
            rootInputReceivedAtMs = thought.rootInputReceivedAtMs,
            conversationContext = convCtx,
            origin = thought.origin,
        )

        instrumentation.emit(AgentEvents.phaseTimings(timing.build()))
    }

    private suspend fun processIntention(intention: QueuedIntention) {
        instrumentation.emit(
            AgentEvent(
                type = "intention_processing",
                data = mapOf(
                    "intention_id" to intention.intention.id,
                    "intention_kind" to intention.intention.kind.name.lowercase(),
                    "action_type" to intention.proposedActionType?.id,
                    "root_input_id" to intention.rootInputId,
                )
            )
        )
        when (intention.intention.kind) {
            IntentionKind.DEFER -> {
                processThought(
                    PendingThought(
                        id = intention.queueId,
                        urgency = intention.urgency,
                        content = intention.deferredThoughtContent ?: intention.intention.summary,
                        passes = intention.deferredThoughtPasses,
                        longTermMemoryRecallQuery = intention.deferredThoughtRecallQuery,
                        rootInputId = intention.rootInputId,
                        rootInputReceivedAtMs = intention.rootInputReceivedAtMs,
                        deniedActionType = intention.deferredDeniedActionType,
                        deniedActionPayload = intention.deferredDeniedActionPayload,
                        denialReason = intention.deferredDenialReason,
                        allowFallbackExplanation = intention.deferredAllowFallbackExplanation,
                        planContext = intention.deferredPlanContext,
                        denialReasonCode = intention.deferredDenialReasonCode,
                        originActionType = intention.deferredOriginActionType,
                        originActionObservedEvidence = intention.deferredOriginActionObservedEvidence,
                        conversationContext = intention.conversationContext,
                        origin = intention.origin,
                    )
                )
            }

            else -> {
                val actionType = intention.proposedActionType ?: return
                val payload = intention.proposedActionPayload ?: return
                val summary = intention.proposedActionSummary ?: intention.intention.summary
                val pendingAction = PendingAction(
                    id = intention.queueId,
                    urgency = intention.urgency,
                    type = actionType,
                    payload = payload,
                    summary = summary,
                    rootInputId = intention.rootInputId,
                    rootInputReceivedAtMs = intention.rootInputReceivedAtMs,
                    conversationContext = intention.conversationContext,
                    requiresFollowUpThought = motorCortex.requiresFollowUpThought(actionType),
                    followUpPrefix = motorCortex.followUpPrefix(actionType),
                    argumentDataTrust = intention.argumentDataTrust,
                    origin = intention.origin,
                    intentionId = intention.intention.id,
                    intentionKind = intention.intention.kind,
                    requestedCommitMode = intention.intention.commitMode,
                )
                processAction(pendingAction)
            }
        }
    }

    private suspend fun processImpulse(impulse: PendingImpulse, opportunity: Opportunity? = null) {
        val timing = PhaseTimingCollector("impulse", impulse.rootImpulseId)
        val convCtx = impulse.conversationContext
        val sessionId = resolveSessionId(convCtx)
        activateSession(convCtx)
        cognitiveThreads.ensureForImpulse(impulse)
        impulseTracker.registerLifecycle(impulse.rootImpulseId, impulse.needId)

        timing.startPhase("impulse_processing")
        instrumentation.emit(
            AgentEvent(
                type = "impulse_processing",
                data = mapOf(
                    "need_id" to impulse.needId,
                    "tension" to impulse.tension,
                    "raw_value" to impulse.rawValue,
                    "root_impulse_id" to impulse.rootImpulseId,
                    "prompt" to impulse.prompt,
                ),
            )
        )

        // Record the impulse as an INTERNAL dialogue turn
        val internalTurn = DialogueTurn(
            role = DialogueRole.INTERNAL,
            content = impulse.prompt,
            sessionId = sessionId,
            interlocutor = convCtx.interlocutor,
            timestamp = java.time.Instant.now(),
        )
        dialogueFor(sessionId).addLast(internalTurn)
        trimDialogue(sessionId)

        timing.startPhase("planner_context")
        val trigger = EgoTrigger.IncomingImpulse(impulse)
        val baseContext = plannerContext(
            trigger = trigger,
            rootInputId = impulse.rootImpulseId,
            sessionId = sessionId,
            conversationContext = convCtx,
            opportunity = opportunity,
        )
        val idConstrainedContext = applyIdConvergenceContext(
            baseContext = baseContext,
            needId = impulse.needId,
            triggeringTension = impulse.tension,
        )
        val projectSummary = goalsGateway.pendingWorkSummary()
        val context = idConstrainedContext.copy(
            shortTermContextSummary = "",
            goalWorkSummary = projectSummary,
        )

        timing.startPhase("planner_decide")
        val decision = planner.decide(trigger = trigger, context = context)
        journalPlannerDecision(decision)

        timing.startPhase("apply_decision")
        val origin = ActionOrigin.id(needId = impulse.needId, rootImpulseId = impulse.rootImpulseId)

        when (decision) {
            is EgoDecision.Noop -> {
                instrumentation.emit(
                    AgentEvent(
                        type = "impulse_noop",
                        data = mapOf("need_id" to impulse.needId, "root_impulse_id" to impulse.rootImpulseId),
                    )
                )
            }
            else -> {
                id?.onImpulseAccepted(impulse.needId)
                dispatcher.dispatch(
                    decision = decision,
                    nextPassCount = 0,
                    originThought = null,
                    rootInputId = impulse.rootImpulseId,
                    rootInputReceivedAtMs = impulse.receivedAtMs,
                    conversationContext = convCtx,
                    origin = origin,
                )
            }
        }

        instrumentation.emit(AgentEvents.phaseTimings(timing.build()))
    }

    private suspend fun processAction(action: PendingAction) {
        actionPipeline.reviewAndExecute(action)
    }

    private suspend fun processThreadContinuation(
        continuation: ThreadContinuation,
        opportunity: Opportunity? = null,
    ) {
        val work = cognitiveThreads.goalWork(continuation.rootInputId, continuation.conversationContext)
            ?: return
        val timing = PhaseTimingCollector("goal_work", "goal:${work.goalId}")
        val convCtx = work.conversationContext
        val sessionId = resolveSessionId(convCtx)
        activateSession(convCtx)
        cognitiveThreads.bindGoalWork(work)

        timing.startPhase("goal_work_processing")
        instrumentation.emit(
            AgentEvent(
                type = "goal_work_processing",
                data = mapOf(
                    "goal_id" to work.goalId,
                    "step_id" to work.stepId,
                    "step_description" to work.stepDescription,
                ),
            )
        )

        timing.startPhase("planner_context")
        val trigger = EgoTrigger.GoalWork(work)
        val context = plannerContext(
            trigger = trigger,
            rootInputId = work.rootInputId,
            sessionId = sessionId,
            conversationContext = convCtx,
            opportunity = opportunity,
        )

        timing.startPhase("planner_decide")
        val decision = planner.decide(trigger = trigger, context = context)
        journalPlannerDecision(decision)

        timing.startPhase("apply_decision")
        val origin = ActionOrigin(OriginSource.GOAL)
        dispatcher.dispatch(
            decision = decision,
            nextPassCount = 0,
            originThought = null,
            rootInputId = work.rootInputId,
            rootInputReceivedAtMs = System.currentTimeMillis(),
            conversationContext = convCtx,
            origin = origin,
        )

        instrumentation.emit(AgentEvents.phaseTimings(timing.build()))
    }

    private fun trimDialogue(sessionId: String) {
        val deque = dialogueFor(sessionId)
        while (deque.size > 20) {
            deque.removeFirst()
        }
    }

    private fun maybeCreateScratchpad(input: PendingInput) {
        val created = scratchpadStore.ensureForInput(input)
        if (!created) return
        instrumentation.emit(
            AgentEvent(
                type = "scratchpad_created",
                data = mapOf(
                    "root_input_id" to input.rootInputId,
                    "root_input_received_at_ms" to input.receivedAtMs,
                    "input_id" to input.id,
                    "goal_preview" to TextSecurity.preview(input.content, 140),
                    "active_tasks" to scratchpadStore.activeTaskCount()
                )
            )
        )
        telemetry.emitScratchpadTelemetry(
            rootInputId = input.rootInputId,
            rootInputReceivedAtMs = input.receivedAtMs,
            updateType = "scratchpad_created"
        )
    }

    private fun maybeCreateGoalScratchpad(work: GoalRunActivation) {
        val created = scratchpadStore.ensureForGoalWork(work)
        if (!created) return
        instrumentation.emit(
            AgentEvent(
                type = "scratchpad_created",
                data = mapOf(
                    "root_input_id" to work.rootInputId,
                    "root_input_received_at_ms" to System.currentTimeMillis(),
                    "goal_preview" to TextSecurity.preview(work.stepDescription, 140),
                    "active_tasks" to scratchpadStore.activeTaskCount(),
                    "source" to "goal_runtime",
                )
            )
        )
        telemetry.emitScratchpadTelemetry(
            rootInputId = work.rootInputId,
            rootInputReceivedAtMs = System.currentTimeMillis(),
            updateType = "goal_scratchpad_created"
        )
    }

    private suspend fun plannerContext(
        trigger: EgoTrigger,
        rootInputId: String? = null,
        sessionId: String = ConversationContext.DEFAULT_SESSION_ID,
        conversationContext: ConversationContext = ConversationContext.default(),
        opportunity: Opportunity? = null,
    ): PlannerContext {
        val recentDialogue = dialogueFor(sessionId).takeLast(12)
        val shortTermSummary = memory.currentShortTermSummary()
        val episodicCues = memory.recallEpisodicAsVectorCues(recentDialogue)
        val ambientContext = buildAmbientContext(trigger)
        emitAmbientContextSnapshot(
            trigger = trigger,
            usage = "planner_context",
            ambientContext = ambientContext,
            sessionId = sessionId,
            rootInputId = rootInputIdForTrigger(trigger),
        )
        val longTermRecall = memory.recall(
            trigger = trigger,
            shortTermSummary = shortTermSummary,
            recentDialogue = recentDialogue,
            episodicCues = episodicCues,
            ambientContext = ambientContext,
        )
        val lessons = memory.recallLessons(trigger, recentDialogue)
        val episodicRecall = memory.recallEpisodic(trigger, recentDialogue)
        val scratchpadSummary = scratchpadStore.promptSummary(
            rootInputId = rootInputId,
            maxTokens = config.memory.scratchpad.maxPromptTokens
        )
        val sessionScratchpadDigest = scratchpadStore.digestPromptSummary(
            sessionId = sessionId,
            maxTokens = config.memory.scratchpad.digestMaxPromptTokens
        )
        val threadSecurityContext = deliberation.threadSecurityContext(rootInputId, conversationContext)
        val cognitiveThread = cognitiveThreads.thread(rootInputId, conversationContext)
        val latestPercept = cognitiveThreads.latestPercept(rootInputId, conversationContext)
        val disabled = deliberation.disabledActionTypes(rootInputId, sessionId)
        val plannerDescriptors = motorCortex.plannerDescriptors()
            .filter { descriptor ->
                descriptor.allowedInstructionTrust.contains(conversationContext.security.instructionTrust) &&
                    descriptor.allowedArgumentDataTrust.contains(threadSecurityContext.aggregatedDataTrust)
            }
        val implementedAvailableActions = motorCortex.availableActionTypes()
        val implementedDispatchableActions = motorCortex.dispatchableActionTypes()
        val shapedActionSurface = CognitivePolicyShaper.shapePlannerActions(
            conversationContext = conversationContext,
            threadSecurityContext = threadSecurityContext,
            descriptors = plannerDescriptors,
            disabledActions = disabled,
        )
        val availableActions = shapedActionSurface.availableActions intersect implementedAvailableActions
        val dispatchableActions = shapedActionSurface.dispatchableActions intersect implementedDispatchableActions
        val actionDefinitions = shapedActionSurface.actionDefinitions
            .filter { definition -> definition.actionType in availableActions }
        val evidenceHints = buildEvidenceHints(rootInputId, sessionId)
        val goalSummary = buildNumberedGoalSummary()
        return PlannerContext(
            recentDialogue = recentDialogue,
            queue = scheduler.queueSnapshot(),
            shortTermContextSummary = shortTermSummary,
            longTermMemoryRecall = longTermRecall,
            lessons = lessons,
            episodicRecall = episodicRecall,
            scratchpadSummary = scratchpadSummary,
            sessionScratchpadDigest = sessionScratchpadDigest,
            ambientContext = ambientContext,
            evidenceHints = evidenceHints,
            deliberation = deliberation.snapshot(),
            metaGuidance = deliberation.guidance(),
            conversationSecuritySummary = conversationContext.security.renderSummary(),
            threadSecuritySummary = threadSecurityContext.renderSummary(),
            triggerProvenanceSummary = triggerProvenanceSummary(trigger),
            perceptSummary = latestPercept?.summary.orEmpty(),
            perceptFamily = latestPercept?.family,
            cognitiveThreadId = cognitiveThread?.id,
            cognitiveThreadStatus = cognitiveThread?.status,
            opportunitySummary = opportunity?.summary.orEmpty(),
            opportunityKind = opportunity?.kind,
            allowedIntentions = opportunity?.allowedIntentions ?: setOf(IntentionKind.DEFER),
            allowedCommitModes = opportunity?.allowedCommitModes ?: setOf(CommitMode.NOT_APPLICABLE),
            availableActions = availableActions,
            dispatchableActions = dispatchableActions,
            actionDefinitions = actionDefinitions,
            conversationContext = conversationContext,
            goalWorkSummary = goalSummary,
        )
    }

    private fun triggerProvenanceSummary(trigger: EgoTrigger): String =
        when (trigger) {
            is EgoTrigger.IncomingInput ->
                Provenances.fromStimulusTrustLevel(
                    source = trigger.input.source,
                    trustLevel = when (trigger.input.conversationContext.security.instructionTrust) {
                        InstructionTrust.TRUSTED_INSTRUCTION -> StimulusTrustLevel.TRUSTED_INTERNAL
                        InstructionTrust.UNTRUSTED_INSTRUCTION -> StimulusTrustLevel.UNTRUSTED_EXTERNAL
                    },
                    sourceRef = trigger.input.rootInputId,
                ).renderSummary()

            is EgoTrigger.PendingThoughtInput ->
                Provenances.trustedSystemSignal(
                    provider = "planner_thought",
                    sourceRef = trigger.thought.rootInputId,
                ).renderSummary()

            is EgoTrigger.IncomingImpulse ->
                Provenances.trustedSystemSignal(
                    provider = "id",
                    sourceRef = trigger.impulse.rootImpulseId,
                ).renderSummary()

            is EgoTrigger.GoalWork ->
                Provenances.trustedSystemSignal(
                    provider = "goal-runtime",
                    sourceRef = trigger.workUnit.rootInputId,
                ).renderSummary()
        }

    private fun buildNumberedGoalSummary(): String {
        val goals = goalsGateway.allGoals()
        if (goals.isEmpty()) return ""
        return buildString {
            append("Active goals:")
            goals.forEachIndexed { index, g ->
                append("\n${index + 1}. \"${g.title}\" (${g.status}")
                if (!g.cronExpression.isNullOrBlank()) append(", cron=${g.cronExpression}")
                append(")")
            }
        }
    }

    private fun buildAmbientContext(trigger: EgoTrigger): AmbientContext {
        if (!shouldAttachAmbientContext(trigger)) {
            return AmbientContext()
        }
        val activeGoals = goalRegistry.activeGoals()
            .map { goal -> TextSecurity.preview(goal.instruction, AMBIENT_PROJECT_PREVIEW_CHARS) }
            .filter { it.isNotBlank() }
            .take(MAX_AMBIENT_PROJECTS)
        return AmbientContext(
            activeGoals = activeGoals,
            recentScratchpadThemes = scratchpadStore.recentResolvedGoalSignals(MAX_AMBIENT_SCRATCHPAD_SIGNALS),
            recentUsefulActionsOrUpdates = memory.recentUsefulActionsOrUpdates(),
            unresolvedOpenLoops = scratchpadStore.activeGoalSignals(MAX_AMBIENT_OPEN_LOOPS),
            recentExactLearningTopics = memory.recentExactLearningTopics(),
        )
    }

    private fun shouldAttachAmbientContext(trigger: EgoTrigger): Boolean =
        when (trigger) {
            is EgoTrigger.IncomingInput -> false
            is EgoTrigger.IncomingImpulse -> true
            is EgoTrigger.GoalWork -> true
            is EgoTrigger.PendingThoughtInput -> trigger.thought.origin.source == OriginSource.ID
        }

    private fun emitAmbientContextSnapshot(
        trigger: EgoTrigger,
        usage: String,
        ambientContext: AmbientContext,
        sessionId: String,
        rootInputId: String?,
    ) {
        if (ambientContext.isEmpty()) return
        instrumentation.emit(
            AgentEvents.ambientContextSnapshot(
                trigger = triggerLabel(trigger),
                usage = usage,
                ambientContext = ambientContext,
                sessionId = sessionId,
                rootInputId = rootInputId,
            )
        )
    }

    private fun rootInputIdForTrigger(trigger: EgoTrigger): String? =
        when (trigger) {
            is EgoTrigger.IncomingInput -> trigger.input.rootInputId
            is EgoTrigger.PendingThoughtInput -> trigger.thought.rootInputId
            is EgoTrigger.IncomingImpulse -> trigger.impulse.rootImpulseId
            is EgoTrigger.GoalWork -> trigger.workUnit.rootInputId
        }

    private fun triggerLabel(trigger: EgoTrigger): String =
        when (trigger) {
            is EgoTrigger.IncomingInput -> "input"
            is EgoTrigger.PendingThoughtInput -> "thought"
            is EgoTrigger.IncomingImpulse -> "impulse"
            is EgoTrigger.GoalWork -> "goal-work"
        }

    private fun applyIdConvergenceContextForOrigin(
        baseContext: PlannerContext,
        origin: ActionOrigin,
        triggeringTension: Double,
    ): PlannerContext {
        if (origin.source != OriginSource.ID) return baseContext
        val needId = origin.needId?.trim().orEmpty()
        if (needId.isBlank()) return baseContext
        return applyIdConvergenceContext(
            baseContext = baseContext,
            needId = needId,
            triggeringTension = triggeringTension,
        )
    }

    private fun applyIdConvergenceContext(
        baseContext: PlannerContext,
        needId: String,
        triggeringTension: Double,
    ): PlannerContext {
        val needCfg = id?.needConfig(needId)
        val convergence = needCfg?.convergence ?: ai.neopsyke.agent.id.ConvergenceMode.CONTACT_USER
        val allowEscalation = needCfg?.allowEscalation ?: false
        val allNeeds = id?.needTensions() ?: emptyMap()
        val idState = IdStateSnapshot(
            triggeringNeed = needId,
            triggeringTension = triggeringTension,
            allNeeds = allNeeds,
            convergence = convergence,
            allowEscalation = allowEscalation,
        )

        // Internalize without escalation must stay evidence-bound: no direct user
        // messaging and no trusted-data-only reflect_internal fallback.
        val blockedPlannerActions = if (convergence == ai.neopsyke.agent.id.ConvergenceMode.INTERNALIZE && !allowEscalation) {
            setOf(ActionType.CONTACT_USER, ActionType.REFLECT_INTERNAL)
        } else {
            emptySet()
        }
        val filteredAvailable = baseContext.availableActions - blockedPlannerActions
        val filteredDispatchable = baseContext.dispatchableActions - blockedPlannerActions
        val filteredDefinitions = baseContext.actionDefinitions.filterNot { it.actionType in blockedPlannerActions }
        return baseContext.copy(
            idState = idState,
            availableActions = filteredAvailable,
            dispatchableActions = filteredDispatchable,
            actionDefinitions = filteredDefinitions,
        )
    }

    private fun idNeedTension(needId: String?): Double =
        needId?.let { id?.needTensions()?.get(it) } ?: 0.0

    private fun buildEvidenceHints(rootInputId: String?, sessionId: String): String {
        val evidence = deliberation.evidenceFor(rootInputId, sessionId) ?: return ""
        val hints = mutableListOf<String>()
        if (evidence.hadSuccessfulEvidence) {
            val topSignals = evidence.successfulEvidenceSignals
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .take(MAX_EVIDENCE_HINT_SIGNALS)
            if (topSignals.isNotEmpty()) {
                hints += "successful_evidence_signals=${topSignals.joinToString(" | ")}"
            } else if (evidence.latestPlannerSignal.isNotBlank()) {
                hints += "latest_successful_signal=${evidence.latestPlannerSignal.trim()}"
            }
        }
        if (evidence.hadExternalFailures) {
            hints += "external_failures_observed=true"
        }
        if (hints.isEmpty()) {
            return ""
        }
        return TextSecurity.clamp(hints.joinToString("\n"), MAX_EVIDENCE_HINT_CHARS)
    }

    private fun superegoContext(
        sessionId: String = ConversationContext.DEFAULT_SESSION_ID,
        rootInputId: String? = null,
        origin: ActionOrigin? = null,
        conversationContext: ConversationContext = ConversationContext.default(),
    ): SuperegoContext {
        val shortTermSummary = memory.currentShortTermSummary()
        return SuperegoContext(
            recentDialogue = dialogueFor(sessionId).takeLast(12),
            shortTermContextSummary = shortTermSummary,
            origin = origin,
            conversationContext = conversationContext,
            threadSecurityContext = deliberation.threadSecurityContext(rootInputId, conversationContext),
        )
    }

    private fun maybeRunLongTermMemoryAssessment(
        trigger: String,
        force: Boolean = false,
        latestActionType: ActionType? = null,
        latestActionOutcome: String? = null,
        sessionId: String = ConversationContext.DEFAULT_SESSION_ID,
    ) {
        memory.maybeAssessLongTermMemory(
            trigger = trigger,
            force = force,
            latestActionType = latestActionType,
            latestActionOutcome = latestActionOutcome,
            deliberation = deliberation.snapshot(),
            recentDialogue = dialogueFor(sessionId).takeLast(12)
        )
    }

    private fun cleanupResolvedInputAfterAnswer(action: PendingAction) {
        cleanupResolvedInput(action, reason = "input_resolved")
    }

    private fun cleanupSatisfiedIdImpulse(action: PendingAction) {
        cleanupResolvedInput(action, reason = "id_satisfaction_resolved")
    }

    private fun cleanupResolvedInput(action: PendingAction, reason: String) {
        val rootInputId = action.rootInputId ?: return
        val sessionId = resolveSessionId(action.conversationContext)
        val scope = inputScope(rootInputId, action.conversationContext)
        planner.resetForInput(rootInputId)
        deliberation.clearForInput(rootInputId, sessionId)
        evidenceArtifactStore.clear(rootInputId, action.conversationContext)
        dispatcher.clearExternalActionSignatures(scope)
        telemetry.emitScratchpadTelemetry(
            rootInputId = rootInputId,
            rootInputReceivedAtMs = action.rootInputReceivedAtMs,
            updateType = "before_destroy_input_resolved"
        )
        val cleared = scheduler.clearPendingWorkForInput(rootInputId, sessionId)
        val digestEntry = scratchpadStore.captureDigest(rootInputId, sessionId)
        if (digestEntry != null) {
            instrumentation.emit(
                AgentEvent(
                    type = "scratchpad_digest_captured",
                    data = mapOf(
                        "root_input_id" to rootInputId,
                        "session_id" to sessionId,
                        "goal_preview" to TextSecurity.preview(digestEntry.goal, 140),
                        "section_count" to digestEntry.sectionIndex.size,
                        "evidence_count" to digestEntry.keyEvidence.size,
                    )
                )
            )
        }
        val destroyedScratchpad = scratchpadStore.destroy(rootInputId)
        if (destroyedScratchpad != null) {
            instrumentation.emit(
                AgentEvent(
                    type = "scratchpad_destroyed",
                    data = mapOf(
                        "root_input_id" to destroyedScratchpad.rootInputId,
                        "root_input_received_at_ms" to destroyedScratchpad.rootInputReceivedAtMs,
                        "section_count" to destroyedScratchpad.sectionCount,
                        "evidence_count" to destroyedScratchpad.evidenceCount,
                        "reason" to reason
                    )
                )
            )
        }
        if (cleared.thoughtsRemoved == 0 && cleared.actionsRemoved == 0) {
            return
        }
        instrumentation.emit(
            AgentEvent(
                type = "input_resolution_cleanup",
                data = mapOf(
                    "answer_action_id" to action.id,
                    "is_fallback_explanation" to action.isFallbackExplanation,
                    "root_input_id" to rootInputId,
                    "root_input_received_at_ms" to action.rootInputReceivedAtMs,
                    "session_id" to sessionId,
                    "removed_thoughts" to cleared.thoughtsRemoved,
                    "removed_actions" to cleared.actionsRemoved,
                    "reason" to reason
                )
            )
        )
        telemetry.emitQueueSnapshot("input_resolution_cleanup")
    }

    private fun cleanupAfterProjectAdvance(rootInputId: String, conversationContext: ConversationContext) {
        val sessionId = resolveSessionId(conversationContext)
        val thread = cognitiveThreads.thread(rootInputId, conversationContext)
        val goalId = thread?.goalId
        val stepId = thread?.metadata?.get("goal_step_id")
        planner.resetForInput(rootInputId)
        goalsGateway.finalizeGoalCycle(rootInputId)
        val goalState = goalId?.let { goalsGateway.goalStatus(it) }
        val stepStatus = stepId?.let { id -> goalState?.goal?.plan?.steps?.firstOrNull { it.id == id }?.status }
        val retainContinuity = when {
            goalState == null -> false
            goalState.goal.status == ai.neopsyke.agent.goal.GoalStatus.COMPLETED -> false
            goalState.goal.status == ai.neopsyke.agent.goal.GoalStatus.FAILED -> false
            stepStatus == ai.neopsyke.agent.goal.StepStatus.DONE -> false
            stepStatus == ai.neopsyke.agent.goal.StepStatus.FAILED -> false
            else -> true
        }
        if (retainContinuity) {
            when {
                stepStatus == ai.neopsyke.agent.goal.StepStatus.BLOCKED ||
                    goalState?.goal?.status == ai.neopsyke.agent.goal.GoalStatus.BLOCKED ->
                    cognitiveThreads.markBlocked(rootInputId, conversationContext, reason = "goal_blocked")
                goalState?.goal?.status == ai.neopsyke.agent.goal.GoalStatus.SUSPENDED ->
                    cognitiveThreads.markWaiting(rootInputId, conversationContext, reason = "goal_suspended")
                else ->
                    cognitiveThreads.markWaiting(rootInputId, conversationContext, reason = "goal_waiting_resume")
            }
            deliberation.clearForInput(rootInputId, sessionId, retainThreadContinuity = true)
        } else {
            if (goalState?.goal?.status == ai.neopsyke.agent.goal.GoalStatus.FAILED || stepStatus == ai.neopsyke.agent.goal.StepStatus.FAILED) {
                cognitiveThreads.markFailed(rootInputId, conversationContext, reason = "goal_failed")
            } else {
                cognitiveThreads.markResolved(rootInputId, conversationContext)
            }
            deliberation.clearForInput(rootInputId, sessionId)
            telemetry.emitScratchpadTelemetry(
                rootInputId = rootInputId,
                rootInputReceivedAtMs = System.currentTimeMillis(),
                updateType = "before_destroy_goal_cycle"
            )
            val destroyedScratchpad = scratchpadStore.destroy(rootInputId)
            if (destroyedScratchpad != null) {
                instrumentation.emit(
                    AgentEvent(
                        type = "scratchpad_destroyed",
                        data = mapOf(
                            "root_input_id" to destroyedScratchpad.rootInputId,
                            "root_input_received_at_ms" to destroyedScratchpad.rootInputReceivedAtMs,
                            "section_count" to destroyedScratchpad.sectionCount,
                            "evidence_count" to destroyedScratchpad.evidenceCount,
                            "reason" to "goal_cycle_terminal"
                        )
                    )
                )
            }
        }
        dispatcher.clearExternalActionSignatures(InputScope(rootInputId, sessionId))
        instrumentation.emit(
            AgentEvent(
                type = "goal_advance_cleanup",
                data = mapOf(
                    "goal_root_id" to rootInputId,
                    "retain_thread_continuity" to retainContinuity,
                    "goal_status" to goalState?.goal?.status?.name?.lowercase(),
                    "step_status" to stepStatus?.name?.lowercase(),
                )
            )
        )
    }

    private fun taskType(task: LoopTask): String =
        when (task) {
            is LoopTask.AttendOpportunity -> when (task.item.trigger) {
                is OpportunityTrigger.Input -> "input"
                is OpportunityTrigger.Impulse -> "impulse"
                is OpportunityTrigger.ThreadWork -> "thread_work"
            }
            is LoopTask.ProcessIntention -> "intention"
            is LoopTask.ProcessThought -> "thought"
            is LoopTask.PerformAction -> "action"
        }

    private fun taskRootInputId(task: LoopTask): String? =
        when (task) {
            is LoopTask.AttendOpportunity -> task.item.rootInputId
            is LoopTask.ProcessIntention -> task.item.rootInputId
            is LoopTask.ProcessThought -> task.item.rootInputId
            is LoopTask.PerformAction -> task.item.rootInputId
        }

    private fun taskRootInputReceivedAtMs(task: LoopTask): Long? =
        when (task) {
            is LoopTask.AttendOpportunity -> task.item.receivedAtMs ?: System.currentTimeMillis()
            is LoopTask.ProcessIntention -> task.item.rootInputReceivedAtMs
            is LoopTask.ProcessThought -> task.item.rootInputReceivedAtMs
            is LoopTask.PerformAction -> task.item.rootInputReceivedAtMs
        }

    private fun taskConversationContext(task: LoopTask): ConversationContext =
        when (task) {
            is LoopTask.AttendOpportunity -> task.item.conversationContext
            is LoopTask.ProcessIntention -> task.item.conversationContext
            is LoopTask.ProcessThought -> task.item.conversationContext
            is LoopTask.PerformAction -> task.item.conversationContext
        }

    private fun journalPlannerDecision(decision: EgoDecision) {
        val (label, actionType) = when (decision) {
            is EgoDecision.EnqueueThought -> "thought" to null
            is EgoDecision.ProposeAction -> "action: ${decision.actionType.name.lowercase()}" to decision.actionType.name.lowercase()
            is EgoDecision.EnqueuePlan -> "plan" to null
            is EgoDecision.Noop -> "noop" to null
        }
        val summary = when (decision) {
            is EgoDecision.ProposeAction ->
                "Decision: $label — ${TextSecurity.preview(decision.summary, JOURNAL_SUMMARY_PREVIEW_CHARS)}"
            is EgoDecision.EnqueueThought ->
                "Decision: $label — ${TextSecurity.preview(decision.content, JOURNAL_SUMMARY_PREVIEW_CHARS)}"
            is EgoDecision.EnqueuePlan ->
                "Decision: plan — ${TextSecurity.preview(decision.goal, JOURNAL_SUMMARY_PREVIEW_CHARS)}"
            is EgoDecision.Noop ->
                "Decision: noop — ${decision.reason}"
        }
        memory.journal(MemoryEventType.PLANNER_DECISION, summary, actionType = actionType)
    }

    private companion object {
        const val FOLLOW_UP_SIGNAL_MAX_CHARS: Int = 420
        const val MAX_AMBIENT_PROJECTS: Int = 4
        const val AMBIENT_PROJECT_PREVIEW_CHARS: Int = 180
        const val MAX_AMBIENT_SCRATCHPAD_SIGNALS: Int = 6
        const val MAX_AMBIENT_OPEN_LOOPS: Int = 4
        const val MAX_EVIDENCE_HINT_SIGNALS: Int = 3
        const val MAX_EVIDENCE_HINT_CHARS: Int = 420
        const val JOURNAL_SUMMARY_PREVIEW_CHARS: Int = 160
        const val MAX_TRACKED_SESSIONS: Int = 32
        const val HEAP_SNAPSHOT_INTERVAL: Int = 5
    }
}
