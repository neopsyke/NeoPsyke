package ai.neopsyke.agent.ego

import kotlinx.coroutines.delay
import mu.KotlinLogging
import ai.neopsyke.admin.approvals.ApprovalStagingHook
import ai.neopsyke.agent.cortex.motor.actions.control.ActionControlDecisionResult
import ai.neopsyke.agent.cortex.motor.actions.control.ActionControlService
import ai.neopsyke.agent.cortex.motor.actions.control.LegacyCompatibleActionControlService
import ai.neopsyke.agent.config.*
import ai.neopsyke.agent.cortex.motor.actions.EvidenceArtifactStore
import ai.neopsyke.agent.cortex.motor.actions.InMemoryEvidenceArtifactStore
import ai.neopsyke.agent.model.*
import ai.neopsyke.agent.cortex.motor.MotorCortex
import ai.neopsyke.agent.cortex.motor.actions.ActionCapability
import ai.neopsyke.agent.cortex.sensory.CognitiveSignal
import ai.neopsyke.agent.cortex.sensory.ActionFeedbackCue
import ai.neopsyke.agent.cortex.sensory.PerceptualAppraiser
import ai.neopsyke.agent.cortex.sensory.RuntimeControlSignal
import ai.neopsyke.agent.cortex.sensory.SensoryCortex
import ai.neopsyke.agent.memory.longterm.MemoryEventType
import ai.neopsyke.agent.memory.scratchpad.ScratchpadStore
import ai.neopsyke.agent.id.EmptyWorkItemRegistry
import ai.neopsyke.agent.id.WorkItemRegistry
import ai.neopsyke.agent.durablework.NoopDurableWorkGateway
import ai.neopsyke.agent.durablework.DurableWorkActivation
import ai.neopsyke.agent.durablework.DurableWorkGateway
import ai.neopsyke.agent.ego.planner.NoopPlanRefiner
import ai.neopsyke.agent.ego.planner.PlanRefiner
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
    private val goalRegistry: WorkItemRegistry = EmptyWorkItemRegistry,
    private val durableWorkGateway: DurableWorkGateway = NoopDurableWorkGateway,
    private val evidenceArtifactStore: EvidenceArtifactStore = InMemoryEvidenceArtifactStore(),
    private val planRefiner: PlanRefiner = NoopPlanRefiner(),
    private val contactChannelSupplier: () -> Set<String> = { emptySet() },
) {
    @Volatile private var id: ai.neopsyke.agent.id.Id? = null
    @Volatile private var approvalStagingHook: ApprovalStagingHook? = null

    init {
        superego.setActionRegistry(motorCortex.actionRegistry())
    }

    fun setId(id: ai.neopsyke.agent.id.Id) {
        this.id = id
        impulseTracker.setId(id)
    }

    fun setApprovalStagingHook(hook: ApprovalStagingHook?) {
        approvalStagingHook = hook
    }

    /** Delegates to the attention scheduler's impulse enqueue. Used by the Id module. */
    fun enqueueImpulse(impulse: PendingImpulse, maxPendingImpulses: Int): Boolean {
        val percept = Percept(
            id = RootInputIds.next(),
            family = PerceptFamily.DRIVE_ACTIVATION,
            summary = impulse.prompt,
            source = "id",
            occurredAt = java.time.Instant.ofEpochMilli(impulse.receivedAtMs),
            conversationContext = impulse.conversationContext,
            rootStimulusId = impulse.rootImpulseId,
            provenance = Provenances.trustedSystemSignal("id", impulse.needId),
            metadata = mapOf("need_id" to impulse.needId),
        )
        val opportunity = shapeOpportunityContract(
            cognitiveThreads.impulseOpportunity(impulse, percept),
            impulse.rootImpulseId,
            impulse.conversationContext,
        )
        val queued = scheduler.enqueueImpulse(impulse, opportunity, maxPendingImpulses)
        if (queued) {
            emitOpportunityEnqueued(opportunity, impulse.rootImpulseId, "impulse", GroundingMetadata.NOT_REQUIRED_PREFILTER)
        }
        return queued
    }

    /** Checks whether the Ego has any pending work. Used by the Id module for idle detection. */
    fun hasPendingWork(): Boolean = scheduler.hasPendingWork()

    suspend fun processAutonomousStagedActions(limit: Int = config.actionControl.autonomousWorkerBatchSize): Int =
        actionPipeline.processAutonomousStagedActions(limit)

    interface Planner {
        fun decide(trigger: EgoTrigger, context: PlannerContext): EgoDecision
        fun resetForInput(rootInputId: String) {}
        /** Grounded root input resolved by the most recent input classification, if any. */
        val lastResolvedInput: PendingInput? get() = null
        /** Grounding metadata resolved by the most recent input classification, if any. */
        val lastResolvedGrounding: GroundingMetadata? get() = null
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
        scratchpadStore, telemetry, fallbackHandler, planRefiner,
        dialogueFor = ::dialogueFor,
        resolveSessionId = ::resolveSessionId,
        inputScope = ::inputScope,
    )
    private val taskVerifier: DecisionVerifier = GroundingGate()
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
        recordThreadIntention = ::recordThreadIntentionTransition,
        recordThreadBlocked = ::recordThreadBlocked,
        recordThreadDenied = ::recordThreadDenied,
        resolveTerminalControlPlaneDenial = ::resolveTerminalControlPlaneDenial,
        recordThreadWaiting = ::recordThreadWaiting,
        emitThreadUpdate = ::emitThreadUpdateForRoot,
        onApprovalStaged = { action, stagedAction, reason, reasonCode, conversationContext ->
            approvalStagingHook?.onApprovalStaged(
                actionSummary = action.summary,
                stagedAction = stagedAction,
                reason = reason,
                reasonCode = reasonCode,
                conversationContext = conversationContext,
            )
        },
        actionControlService = actionControlService,
        actionLifecycleObserver = durableWorkGateway,
        emitActionFeedback = sensoryCortex::offerActionFeedback,
    )
    private val stimulusIngress = StimulusIngressCoordinator(
        config = config,
        scheduler = scheduler,
        cognitiveThreads = cognitiveThreads,
        durableWorkGateway = durableWorkGateway,
        instrumentation = instrumentation,
        telemetry = telemetry,
        shapeOpportunityContract = ::shapeOpportunityContract,
        emitThreadUpdate = ::emitThreadUpdate,
        emitOpportunityEnqueued = ::emitOpportunityEnqueued,
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
                    when (val outcome = stimulusIngress.ingest(stimulus, percept)) {
                        StimulusIngressCoordinator.Outcome.NoWork -> continue
                        is StimulusIngressCoordinator.Outcome.RunLoop -> {
                            runLoop()
                            outcome.cleanupRootInputId?.let { rootInputId ->
                                val cleanupContext = outcome.cleanupConversationContext ?: stimulus.conversationContext
                                cleanupAfterProjectAdvance(rootInputId, cleanupContext)
                            }
                        }
                    }
                }

                is CognitiveSignal.FeedbackReceived -> {
                    when (val outcome = stimulusIngress.ingestFeedbackCue(signal.cue)) {
                        StimulusIngressCoordinator.Outcome.NoWork -> continue
                        is StimulusIngressCoordinator.Outcome.RunLoop -> {
                            runLoop()
                        }
                    }
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

    fun processExternalApprovalExecuted(result: ActionControlDecisionResult.Executed) {
        actionPipeline.processExternalApprovalExecuted(result)
    }

    fun processExternalApprovalDenied(result: ActionControlDecisionResult.Cancelled) {
        actionPipeline.processExternalApprovalDenied(result)
    }

    private suspend fun processActionFeedback(
        feedback: PendingFeedback,
        opportunity: Opportunity? = null,
    ) {
        val cue = feedback.cue
        val percept = feedback.percept
        val timing = PhaseTimingCollector("feedback", cue.rootInputId)
        val convCtx = cue.conversationContext
        val sessionId = resolveSessionId(convCtx)
        activateSession(convCtx)
        val resumedFromWait = feedback.resumedFromWaitingThread
        cognitiveThreads.bindPercept(
            percept = percept,
            rootInputId = cue.rootInputId,
            kind = resolveFeedbackThreadKind(feedback),
            title = cue.actionSummary.ifBlank { feedback.stimulusContent },
        )
        timing.startPhase("feedback_processing")
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
            groundingMetadata = cue.groundingMetadata,
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
        val dispatcherOriginContinuation = QueuedContinuation(
            queueId = cue.sourceActionId ?: 0L,
            urgency = Urgency.fromRaw(cue.urgency),
            continuation = ai.neopsyke.agent.model.Continuation.RetryAlternative(
                content = cue.feedbackContent,
                allowFallbackExplanation = cue.origin.source != OriginSource.ID,
                originActionType = cue.actionType,
                originActionObservedEvidence = observed,
            ),
            passes = cue.attempts,
            rootInputId = cue.rootInputId,
            rootInputReceivedAtMs = cue.rootInputReceivedAtMs ?: feedback.receivedAtMs,
            conversationContext = convCtx,
            origin = cue.origin,
            groundingMetadata = cue.groundingMetadata,
        )
        cue.groundingMetadata.let { metadata ->
            instrumentation.emit(
                AgentEvents.groundingMetadataPropagated(
                    rootInputId = cue.rootInputId,
                    fromEnvelopeType = "action_feedback_cue",
                    toEnvelopeType = "pending_action",
                    groundingRequired = metadata.requirement == GroundingRequirement.REQUIRED,
                    source = metadata.source.name.lowercase(),
                )
            )
            instrumentation.emit(
                AgentEvents.groundingMetadataPropagated(
                    rootInputId = cue.rootInputId,
                    fromEnvelopeType = "action_feedback_cue",
                    toEnvelopeType = "queued_continuation",
                    groundingRequired = metadata.requirement == GroundingRequirement.REQUIRED,
                    source = metadata.source.name.lowercase(),
                )
            )
        }
        if (cue.executionStatus == ActionExecutionStatus.FAILED) {
            deliberation.markEvidenceFailure(feedbackAction)
        }
        deliberation.recordEvidenceProgress(feedbackAction, outcome, observed)
        deliberation.onActionExecuted(feedbackAction, observed)
        deliberation.recordActionOutcome(feedbackAction, outcome, observed)
        val continuationRequired = shouldContinueAfterFeedback(
            cue = cue,
            feedbackAction = feedbackAction,
            resumedFromWait = resumedFromWait,
        )
        val threadUpdateReason = when {
            cue.executionStatus == ActionExecutionStatus.WAITING -> {
                cognitiveThreads.markWaiting(
                    rootInputId = cue.rootInputId,
                    conversationContext = convCtx,
                    reason = cue.statusSummary.ifBlank { cue.actionSummary },
                    resumeHint = cue.actionType.id,
                )
                "feedback_waiting"
            }

            !continuationRequired && cue.executionStatus == ActionExecutionStatus.FAILED -> {
                cognitiveThreads.markFailed(
                    rootInputId = cue.rootInputId,
                    conversationContext = convCtx,
                    reason = cue.actionErrorCategory ?: cue.fetchErrorCategory ?: cue.statusSummary,
                    summary = cue.statusSummary.ifBlank { cue.actionSummary },
                )
                "feedback_terminal_failed"
            }

            !continuationRequired -> {
                cognitiveThreads.markResolved(
                    rootInputId = cue.rootInputId,
                    conversationContext = convCtx,
                    reason = cue.statusSummary.ifBlank { cue.actionSummary },
                    summary = cue.statusSummary.ifBlank { cue.actionSummary },
                )
                "feedback_terminal_resolved"
            }

            else -> "feedback_bound"
        }
        cognitiveThreads.thread(cue.rootInputId, convCtx)
            ?.let { updated -> emitThreadUpdate(updated, cue.rootInputId, threadUpdateReason) }
        instrumentation.emit(
            AgentEvent(
                type = "action_feedback_integrated",
                data = mapOf(
                    "action_type" to cue.actionType.id,
                    "root_input_id" to cue.rootInputId,
                    "execution_status" to cue.executionStatus.name.lowercase(),
                    "continuation_required" to continuationRequired,
                )
            )
        )
        if (!continuationRequired) {
            instrumentation.emit(AgentEvents.phaseTimings(timing.build()))
            return
        }

        timing.startPhase("planner_context")
        val trigger = EgoTrigger.ActionFeedback(feedback)
        val baseContext = plannerContext(
            trigger = trigger,
            rootInputId = cue.rootInputId,
            sessionId = sessionId,
            conversationContext = convCtx,
            opportunity = opportunity,
        )
        val context = applyIdConvergenceContextForOrigin(
            baseContext = baseContext,
            origin = cue.origin,
            triggeringTension = idNeedTension(cue.origin.needId),
        )
        timing.startPhase("meta_assessment")
        val assessment = deliberation.maybeAssessAndUpdateGuidance(trigger, context)

        timing.startPhase("planner_decide")
        val decision = planner.decide(
            trigger = trigger,
            context = context.copy(metaGuidance = deliberation.guidance())
        )
        val finalDecision = deliberation.maybeApplyPressureOverride(
            decision = decision,
            assessment = assessment,
            scheduler = scheduler,
            rootInputId = cue.rootInputId,
            rootInputReceivedAtMs = cue.rootInputReceivedAtMs ?: feedback.receivedAtMs,
            conversationContext = convCtx,
            groundingMetadata = context.groundingMetadata,
        )
        deliberation.onPlannerDecision(finalDecision)
        journalPlannerDecision(finalDecision)

        timing.startPhase("apply_decision")
        dispatcher.dispatch(
            decision = finalDecision,
            nextPassCount = cue.attempts,
            originContinuation = dispatcherOriginContinuation,
            rootInputId = cue.rootInputId,
            rootInputReceivedAtMs = cue.rootInputReceivedAtMs ?: feedback.receivedAtMs,
            conversationContext = convCtx,
            plannerContext = context,
            origin = cue.origin,
        )

        instrumentation.emit(AgentEvents.phaseTimings(timing.build()))
    }

    private fun shouldContinueAfterFeedback(
        cue: ActionFeedbackCue,
        feedbackAction: PendingAction,
        resumedFromWait: Boolean,
    ): Boolean {
        if (!durableWorkGateway.allowFollowUp(feedbackAction)) return false
        if (cue.executionStatus == ActionExecutionStatus.WAITING) return false
        return resumedFromWait || cue.requiresFollowUpThought || cue.executionStatus != ActionExecutionStatus.SUCCESS
    }

    private suspend fun runLoop() {
        var steps = 0
        while (steps < config.planner.maxLoopStepsPerInput) {
            val task = scheduler.nextTask(cognitiveThreads::isBlocked) ?: break
            steps += 1
            instrumentation.emit(AgentEvents.loopStep(step = steps, taskType = taskType(task)))
            val taskConversationContext = taskConversationContext(task)
            activateSession(taskConversationContext)
            val state = deliberation.startStep()
            telemetry.emitDeliberationState(taskType(task), state)
            try {
                when (task) {
                    is LoopTask.AttendOpportunity -> processOpportunity(task.item)
                    is LoopTask.ProcessContinuation -> processContinuation(task.item)
                    is LoopTask.ProcessIntention -> processIntention(task.item)
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
                conversationContext = taskConversationContext,
                groundingMetadata = taskGroundingMetadata(task),
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
        val finalDecision = deliberation.maybeApplyPressureOverride(
            decision = decision,
            assessment = assessment,
            scheduler = scheduler,
            rootInputId = input.rootInputId,
            rootInputReceivedAtMs = input.receivedAtMs,
            conversationContext = convCtx,
            groundingMetadata = context.groundingMetadata,
        )
        deliberation.onPlannerDecision(finalDecision)
        journalPlannerDecision(finalDecision)

        // Incorporate resolved grounding metadata from InputPlanner classification.
        val resolvedInput = planner.lastResolvedInput ?: input
        val resolvedGrounding = resolvedInput.groundingMetadata
        instrumentation.emit(
            AgentEvents.groundingMetadataPropagated(
                rootInputId = resolvedInput.rootInputId,
                fromEnvelopeType = "pending_input",
                toEnvelopeType = "planner_context",
                groundingRequired = resolvedGrounding.requirement == GroundingRequirement.REQUIRED,
                source = resolvedGrounding.source.name.lowercase(),
            )
        )
        val groundedContext = context.copy(groundingMetadata = resolvedGrounding)

        timing.startPhase("apply_decision")
        dispatcher.dispatch(
            finalDecision,
            nextPassCount = 0,
            originContinuation = null,
            rootInputId = resolvedInput.rootInputId,
            rootInputReceivedAtMs = resolvedInput.receivedAtMs,
            conversationContext = convCtx,
            plannerContext = groundedContext,
        )

        instrumentation.emit(AgentEvents.phaseTimings(timing.build()))
    }

    private suspend fun processOpportunity(opportunity: ScheduledOpportunity) {
        when (val trigger = opportunity.trigger) {
            is OpportunityTrigger.Input -> processInput(trigger.input, opportunity.opportunity)
            is OpportunityTrigger.Impulse -> processImpulse(trigger.impulse, opportunity.opportunity)
            is OpportunityTrigger.Feedback -> processActionFeedback(trigger.feedback, opportunity.opportunity)
            is OpportunityTrigger.DurableWork -> processGoalWork(trigger.work, opportunity.opportunity)
        }
    }

    private suspend fun processContinuation(continuation: QueuedContinuation) {
        val timing = PhaseTimingCollector("continuation", continuation.rootInputId)
        val convCtx = continuation.conversationContext
        val sessionId = resolveSessionId(convCtx)
        activateSession(convCtx)

        if (deliberation.hasForcedTerminalForInput(continuation.rootInputId, sessionId)) {
            logger.info { "Dropping continuation ${continuation.queueId}: forced terminal answer already queued for this input." }
            instrumentation.emit(AgentEvents.continuationDropped(continuation = continuation, reason = "forced_terminal_queued"))
            return
        }

        if (continuation.passes >= config.planner.maxContinuationPasses) {
            logger.info { "Dropping continuation ${continuation.queueId} due to max continuation passes." }
            instrumentation.emit(AgentEvents.continuationDropped(continuation = continuation, reason = "max_passes_reached"))
            if (continuation.allowFallbackExplanation) {
                fallbackHandler.enqueueFallbackExplanation(continuation)
            }
            return
        }

        timing.startPhase("continuation_processing")
        instrumentation.emit(AgentEvents.continuationProcessing(continuation))
        continuation.planContext?.let { planContext ->
            instrumentation.emit(
                AgentEvents.planStepStarted(
                    planId = planContext.planId,
                    stepIndex = planContext.stepIndex,
                    totalSteps = planContext.totalSteps,
                    stepDescription = planContext.stepDescription,
                    rootInputId = continuation.rootInputId,
                )
            )
        }

        timing.startPhase("planner_context")
        val trigger = EgoTrigger.Continuation(continuation)
        val baseContext = plannerContext(
            trigger,
            rootInputId = continuation.rootInputId,
            sessionId = sessionId,
            conversationContext = convCtx
        )
        val context = applyIdConvergenceContextForOrigin(
            baseContext = baseContext,
            origin = continuation.origin,
            triggeringTension = idNeedTension(continuation.origin.needId),
        )

        timing.startPhase("meta_assessment")
        val assessment = deliberation.maybeAssessAndUpdateGuidance(trigger, context)

        timing.startPhase("planner_decide")
        val decision = planner.decide(
            trigger = trigger,
            context = context.copy(metaGuidance = deliberation.guidance())
        )
        val finalDecision = deliberation.maybeApplyPressureOverride(
            decision = decision,
            assessment = assessment,
            scheduler = scheduler,
            rootInputId = continuation.rootInputId,
            rootInputReceivedAtMs = continuation.rootInputReceivedAtMs,
            conversationContext = convCtx,
            groundingMetadata = context.groundingMetadata,
        )
        deliberation.onPlannerDecision(finalDecision)
        journalPlannerDecision(finalDecision)

        timing.startPhase("apply_decision")
        dispatcher.dispatch(
            finalDecision,
            nextPassCount = continuation.passes + 1,
            originContinuation = continuation,
            rootInputId = continuation.rootInputId,
            rootInputReceivedAtMs = continuation.rootInputReceivedAtMs,
            conversationContext = convCtx,
            plannerContext = context,
            origin = continuation.origin,
        )

        instrumentation.emit(AgentEvents.phaseTimings(timing.build()))
    }

    private suspend fun processIntention(intention: QueuedIntention) {
        instrumentation.emit(
            AgentEvent(
                type = "intention_processing",
                data = mapOf(
                    "intention_id" to intention.intention.id,
                    "thread_id" to intention.intention.cognitiveThreadId,
                    "intention_kind" to intention.intention.kind.name.lowercase(),
                    "summary" to intention.intention.summary,
                    "action_type" to intention.proposedActionType?.id,
                    "root_input_id" to intention.rootInputId,
                    "grounding_required" to intention.groundingMetadata.requirement.name.lowercase(),
                    "grounding_source" to intention.groundingMetadata.source.name.lowercase(),
                )
            )
        )
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
            groundingMetadata = intention.groundingMetadata,
        )
        intention.groundingMetadata.let { metadata ->
            instrumentation.emit(
                AgentEvents.groundingMetadataPropagated(
                    rootInputId = intention.rootInputId,
                    fromEnvelopeType = "queued_intention",
                    toEnvelopeType = "pending_action",
                    groundingRequired = metadata.requirement == GroundingRequirement.REQUIRED,
                    source = metadata.source.name.lowercase(),
                )
            )
        }
        processAction(pendingAction)
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
        val projectSummary = durableWorkGateway.pendingWorkSummary()
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
                    originContinuation = null,
                    rootInputId = impulse.rootImpulseId,
                    rootInputReceivedAtMs = impulse.receivedAtMs,
                    conversationContext = convCtx,
                    plannerContext = context,
                    origin = origin,
                )
            }
        }

        instrumentation.emit(AgentEvents.phaseTimings(timing.build()))
    }

    private suspend fun processAction(action: PendingAction) {
        actionPipeline.reviewAndExecute(action)
    }

    private suspend fun processGoalWork(
        work: DurableWorkActivation,
        opportunity: Opportunity? = null,
    ) {
        val timing = PhaseTimingCollector("durable_work", "work:${work.workItemId}")
        val convCtx = work.conversationContext
        val sessionId = resolveSessionId(convCtx)
        activateSession(convCtx)
        cognitiveThreads.bindGoalWork(work)
        maybeCreateGoalScratchpad(work)

        timing.startPhase("goal_work_processing")
        instrumentation.emit(
            AgentEvent(
                type = "goal_work_processing",
                data = mapOf(
                    "goal_id" to work.workItemId,
                    "step_id" to work.stepId,
                    "step_description" to work.stepDescription,
                ),
            )
        )

        timing.startPhase("planner_context")
        val trigger = EgoTrigger.DurableWork(work)
        val rawContext = plannerContext(
            trigger = trigger,
            rootInputId = work.rootInputId,
            sessionId = sessionId,
            conversationContext = convCtx,
            opportunity = opportunity,
        )
        // Goal step execution is conversation-independent: a cron-backed goal
        // may fire minutes or months after the user set it up, so session-level
        // context (recent dialogue, short-term summary, scratchpad digest,
        // ambient context) is noise at best and a contamination source at worst.
        // The step has its own focused scratchpad + goal context. Long-term
        // recall, lessons, and episodic recall are kept — they carry durable
        // user preferences and prior execution history.
        val context = rawContext.copy(
            shortTermContextSummary = "",
            recentDialogue = emptyList(),
            sessionScratchpadDigest = "",
            ambientContext = AmbientContext(),
        )

        timing.startPhase("meta_assessment")
        val assessment = deliberation.maybeAssessAndUpdateGuidance(trigger, context)

        timing.startPhase("planner_decide")
        val decision = planner.decide(
            trigger = trigger,
            context = context.copy(metaGuidance = deliberation.guidance())
        )
        val finalDecision = deliberation.maybeApplyPressureOverride(
            decision = decision,
            assessment = assessment,
            scheduler = scheduler,
            rootInputId = work.rootInputId,
            rootInputReceivedAtMs = System.currentTimeMillis(),
            conversationContext = convCtx,
            groundingMetadata = context.groundingMetadata,
        )
        deliberation.onPlannerDecision(finalDecision)
        journalPlannerDecision(finalDecision)

        timing.startPhase("apply_decision")
        val origin = ActionOrigin(OriginSource.DURABLE_WORK)
        if (finalDecision is EgoDecision.Noop) {
            durableWorkGateway.notifyStepPlannerNoop(work.rootInputId, finalDecision.reason)
        }
        dispatcher.dispatch(
            decision = finalDecision,
            nextPassCount = 0,
            originContinuation = null,
            rootInputId = work.rootInputId,
            rootInputReceivedAtMs = System.currentTimeMillis(),
            conversationContext = convCtx,
            plannerContext = context,
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

    private fun maybeCreateGoalScratchpad(work: DurableWorkActivation) {
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
        val shapedActionSurface = CognitivePolicyShaper.shapePlannerActions(
            conversationContext = conversationContext,
            threadSecurityContext = threadSecurityContext,
            descriptors = plannerDescriptors,
            disabledActions = disabled,
        )
        val availableActions = if (opportunity?.availableActions?.isNotEmpty() == true) {
            shapedActionSurface.availableActions
                .intersect(implementedAvailableActions)
                .intersect(opportunity.availableActions)
        } else {
            shapedActionSurface.availableActions intersect implementedAvailableActions
        }
        val actionDefinitions = if (opportunity?.actionDefinitions?.isNotEmpty() == true) {
            shapedActionSurface.actionDefinitions
                .filter { definition ->
                    definition.actionType in availableActions &&
                        opportunity.actionDefinitions.any { candidate -> candidate.actionType == definition.actionType }
                }
        } else {
            shapedActionSurface.actionDefinitions
                .filter { definition -> definition.actionType in availableActions }
        }
        val evidenceHints = buildEvidenceHints(rootInputId, sessionId)
        val goalSummaryResult = buildNumberedGoalSummary()
        val reviewableResponsibilities = buildReviewableResponsibilitySummary()
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
            allowedIntentions = opportunity?.allowedIntentions ?: setOf(
                IntentionKind.OBSERVE,
                IntentionKind.PREPARE,
            ),
            allowedCommitModes = opportunity?.allowedCommitModes ?: CommitMode.entries.toSet(),
            availableActions = availableActions,
            availableContactChannels = contactChannelSupplier(),
            actionDefinitions = actionDefinitions,
            conversationContext = conversationContext,
            goalWorkSummary = goalSummaryResult.text,
            goalIndex = goalSummaryResult.index,
            goalSnapshots = goalSummaryResult.snapshots,
            reviewableResponsibilitySummary = reviewableResponsibilities.text,
            reviewableResponsibilityIndex = reviewableResponsibilities.index,
            groundingMetadata = groundingMetadataForTrigger(trigger),
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

            is EgoTrigger.Continuation ->
                Provenances.trustedSystemSignal(
                    provider = "planner-continuation",
                    sourceRef = trigger.continuation.rootInputId,
                ).renderSummary()

            is EgoTrigger.ActionFeedback ->
                Provenances.trustedSystemSignal(
                    provider = "action-feedback",
                    sourceRef = trigger.feedback.cue.rootInputId,
                ).renderSummary()

            is EgoTrigger.IncomingImpulse ->
                Provenances.trustedSystemSignal(
                    provider = "id",
                    sourceRef = trigger.impulse.rootImpulseId,
                ).renderSummary()

            is EgoTrigger.DurableWork ->
                Provenances.trustedSystemSignal(
                    provider = "goal-runtime",
                    sourceRef = trigger.workUnit.rootInputId,
                ).renderSummary()
        }

    private fun groundingMetadataForTrigger(trigger: EgoTrigger): GroundingMetadata =
        when (trigger) {
            is EgoTrigger.IncomingInput -> trigger.input.groundingMetadata
            is EgoTrigger.Continuation -> trigger.continuation.groundingMetadata
            is EgoTrigger.ActionFeedback -> trigger.feedback.cue.groundingMetadata
            is EgoTrigger.IncomingImpulse -> GroundingMetadata.NOT_REQUIRED_PREFILTER
            is EgoTrigger.DurableWork -> trigger.workUnit.groundingMetadata
        }

    private data class GoalSummaryResult(
        val text: String,
        val index: Map<Int, String>,
        val snapshots: Map<String, DurableWorkItemSnapshot>,
    )

    private fun buildNumberedGoalSummary(): GoalSummaryResult {
        val goals = durableWorkGateway.allWorkItems()
        if (goals.isEmpty()) return GoalSummaryResult("", emptyMap(), emptyMap())
        val index = mutableMapOf<Int, String>()
        val snapshots = mutableMapOf<String, DurableWorkItemSnapshot>()
        val text = buildString {
            append("Active goals:")
            goals.forEachIndexed { i, g ->
                val position = i + 1
                index[position] = g.workItemId
                val state = durableWorkGateway.workItemStatus(g.workItemId)
                val projection = durableWorkGateway.workItemProjection(g.workItemId)
                state?.let { workState ->
                    snapshots[g.workItemId] = DurableWorkItemSnapshot(
                        workItemId = workState.id,
                        kind = workState.workItem.kind,
                        title = workState.workItem.title,
                        instruction = workState.workItem.instruction,
                        completionCriteria = workState.workItem.completionCriteria,
                        status = workState.workItem.status,
                        planRevision = workState.workItem.planRevision,
                        failureCountInWindow = workState.workItem.failureWindow.failureCount,
                        latestArtifactSummary = projection?.latestArtifactSummary ?: workState.durableState.artifacts.lastSummary,
                        planSteps = workState.workItem.plan.steps.map { step ->
                            DurableWorkPlanStepSnapshot(
                                id = step.id,
                                description = step.description,
                                status = step.status,
                                acceptanceCriteria = step.acceptanceCriteria,
                                requires = step.requires,
                                produces = step.produces,
                                attempts = step.attempts,
                                maxAttempts = step.maxAttempts,
                            )
                        },
                    )
                }
                val snapshot = snapshots[g.workItemId]
                append("\n$position. \"${g.title}\" (${snapshot?.status ?: g.status}")
                if (!g.cronExpression.isNullOrBlank()) append(", cron=${g.cronExpression}")
                snapshot?.let {
                    val doneCount = it.planSteps.count { step -> step.status == ai.neopsyke.agent.durablework.StepStatus.DONE }
                    append(", rev=${it.planRevision}, steps=$doneCount/${it.planSteps.size}")
                }
                append(")")
                val currentStep = snapshot?.planSteps?.firstOrNull { step ->
                    step.status in setOf(
                        ai.neopsyke.agent.durablework.StepStatus.IN_PROGRESS,
                        ai.neopsyke.agent.durablework.StepStatus.READY,
                        ai.neopsyke.agent.durablework.StepStatus.BLOCKED,
                    )
                }
                if (currentStep != null) {
                    append("\n   current_step: ${currentStep.id} (${currentStep.status.name.lowercase()}) ${currentStep.description}")
                }
                snapshot?.latestArtifactSummary?.takeIf { it.isNotBlank() }?.let { summary ->
                    append("\n   latest_artifact: ${TextSecurity.preview(summary, GOAL_ARTIFACT_SUMMARY_PREVIEW_CHARS)}")
                }
                snapshot?.let {
                    if (it.failureCountInWindow > 0) {
                        append("\n   failure_count_in_window: ${it.failureCountInWindow}")
                    }
                }
            }
        }
        return GoalSummaryResult(text, index, snapshots)
    }

    private fun buildReviewableResponsibilitySummary(): GoalSummaryResult {
        val reviewable = durableWorkGateway.reviewableResponsibilities(MAX_REVIEWABLE_RESPONSIBILITIES)
        if (reviewable.isEmpty()) return GoalSummaryResult("", emptyMap(), emptyMap())
        val index = linkedMapOf<Int, String>()
        val text = buildString {
            append("Reviewable responsibilities:")
            reviewable.forEachIndexed { i, item ->
                val position = i + 1
                index[position] = item.workItemId
                append("\n$position. \"${item.title}\" (${item.priority.name.lowercase()}")
                item.nextReviewAt?.let { append(", next_review_at=$it") }
                item.lastReviewAt?.let { append(", last_review_at=$it") }
                append(")")
                append("\n   summary: ${TextSecurity.preview(item.operatorSummary, GOAL_ARTIFACT_SUMMARY_PREVIEW_CHARS)}")
            }
        }
        return GoalSummaryResult(text, index, emptyMap())
    }

    private fun buildAmbientContext(trigger: EgoTrigger): AmbientContext {
        if (!shouldAttachAmbientContext(trigger)) {
            return AmbientContext()
        }
        val activeWorkItems = goalRegistry.activeWorkItems()
            .map { item -> TextSecurity.preview(item.instruction, AMBIENT_PROJECT_PREVIEW_CHARS) }
            .filter { it.isNotBlank() }
            .take(MAX_AMBIENT_PROJECTS)
        return AmbientContext(
            activeWorkItems = activeWorkItems,
            recentScratchpadThemes = scratchpadStore.recentResolvedGoalSignals(MAX_AMBIENT_SCRATCHPAD_SIGNALS),
            recentUsefulActionsOrUpdates = memory.recentUsefulActionsOrUpdates(),
            unresolvedOpenLoops = scratchpadStore.activeGoalSignals(MAX_AMBIENT_OPEN_LOOPS),
            recentExactLearningTopics = memory.recentExactLearningTopics(),
        )
    }

    private fun shouldAttachAmbientContext(trigger: EgoTrigger): Boolean =
        when (trigger) {
            is EgoTrigger.IncomingInput -> false
            is EgoTrigger.ActionFeedback -> trigger.feedback.cue.origin.source == OriginSource.ID
            is EgoTrigger.IncomingImpulse -> true
            is EgoTrigger.DurableWork -> true
            is EgoTrigger.Continuation -> trigger.continuation.origin.source == OriginSource.ID
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
            is EgoTrigger.Continuation -> trigger.continuation.rootInputId
            is EgoTrigger.ActionFeedback -> trigger.feedback.cue.rootInputId
            is EgoTrigger.IncomingImpulse -> trigger.impulse.rootImpulseId
            is EgoTrigger.DurableWork -> trigger.workUnit.rootInputId
        }

    private fun triggerLabel(trigger: EgoTrigger): String =
        when (trigger) {
            is EgoTrigger.IncomingInput -> "input"
            is EgoTrigger.Continuation -> "continuation"
            is EgoTrigger.ActionFeedback -> "feedback"
            is EgoTrigger.IncomingImpulse -> "impulse"
            is EgoTrigger.DurableWork -> "durable-work"
        }

    private fun resolveFeedbackThreadKind(feedback: PendingFeedback): CognitiveThreadKind =
        cognitiveThreads.thread(feedback.cue.rootInputId, feedback.cue.conversationContext)?.kind
            ?: when (feedback.cue.origin.source) {
                OriginSource.DURABLE_WORK -> CognitiveThreadKind.DURABLE_WORK_DIRECTED
                OriginSource.ID -> CognitiveThreadKind.DRIVE
                OriginSource.SYSTEM,
                OriginSource.USER,
                -> CognitiveThreadKind.CONVERSATION
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
        val filteredDefinitions = baseContext.actionDefinitions.filterNot { it.actionType in blockedPlannerActions }
        return baseContext.copy(
            idState = idState,
            availableActions = filteredAvailable,
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
        cognitiveThreads.markResolved(
            rootInputId = rootInputId,
            conversationContext = action.conversationContext,
            reason = reason,
            summary = action.summary,
        )
        cognitiveThreads.thread(rootInputId, action.conversationContext)
            ?.let { updated -> emitThreadUpdate(updated, rootInputId, "input_terminal") }
        deliberation.clearForInput(rootInputId, sessionId, retainThreadContinuity = true)
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
        if (cleared.continuationsRemoved == 0 && cleared.actionsRemoved == 0) {
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
                    "removed_continuations" to cleared.continuationsRemoved,
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
        val workItemId = thread?.workItemId
        val stepId = thread?.metadata?.get("goal_step_id")
        planner.resetForInput(rootInputId)
        durableWorkGateway.finalizeDurableWorkCycle(rootInputId)
        val goalState = workItemId?.let { durableWorkGateway.workItemStatus(it) }
        val stepStatus = stepId?.let { id -> goalState?.workItem?.plan?.steps?.firstOrNull { it.id == id }?.status }
        val retainContinuity = when {
            goalState == null -> false
            goalState.workItem.status == ai.neopsyke.agent.durablework.WorkItemStatus.COMPLETED -> false
            goalState.workItem.status == ai.neopsyke.agent.durablework.WorkItemStatus.FAILED -> false
            stepStatus == ai.neopsyke.agent.durablework.StepStatus.DONE -> false
            stepStatus == ai.neopsyke.agent.durablework.StepStatus.FAILED -> false
            else -> true
        }
        if (retainContinuity) {
            when {
                stepStatus == ai.neopsyke.agent.durablework.StepStatus.BLOCKED ||
                    goalState?.workItem?.status == ai.neopsyke.agent.durablework.WorkItemStatus.BLOCKED ->
                    cognitiveThreads.markBlocked(rootInputId, conversationContext, reason = "goal_blocked")
                goalState?.workItem?.status == ai.neopsyke.agent.durablework.WorkItemStatus.SUSPENDED ->
                    cognitiveThreads.markWaiting(rootInputId, conversationContext, reason = "goal_suspended")
                else ->
                    cognitiveThreads.markWaiting(rootInputId, conversationContext, reason = "goal_waiting_resume")
            }
            cognitiveThreads.thread(rootInputId, conversationContext)
                ?.let { updated -> emitThreadUpdate(updated, rootInputId, "goal_cycle_retained") }
            deliberation.clearForInput(rootInputId, sessionId, retainThreadContinuity = true)
        } else {
            if (goalState?.workItem?.status == ai.neopsyke.agent.durablework.WorkItemStatus.FAILED || stepStatus == ai.neopsyke.agent.durablework.StepStatus.FAILED) {
                cognitiveThreads.markFailed(rootInputId, conversationContext, reason = "goal_failed")
            } else {
                cognitiveThreads.markResolved(rootInputId, conversationContext)
            }
            cognitiveThreads.thread(rootInputId, conversationContext)
                ?.let { updated -> emitThreadUpdate(updated, rootInputId, "goal_cycle_terminal") }
            deliberation.clearForInput(rootInputId, sessionId)
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
                    "goal_status" to goalState?.workItem?.status?.name?.lowercase(),
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
                is OpportunityTrigger.Feedback -> "feedback"
                is OpportunityTrigger.DurableWork -> "durable_work"
            }
            is LoopTask.ProcessContinuation -> "continuation"
            is LoopTask.ProcessIntention -> "intention"
            is LoopTask.PerformAction -> "action"
        }

    private fun taskRootInputId(task: LoopTask): String? =
        when (task) {
            is LoopTask.AttendOpportunity -> task.item.rootInputId
            is LoopTask.ProcessContinuation -> task.item.rootInputId
            is LoopTask.ProcessIntention -> task.item.rootInputId
            is LoopTask.PerformAction -> task.item.rootInputId
        }

    private fun taskRootInputReceivedAtMs(task: LoopTask): Long? =
        when (task) {
            is LoopTask.AttendOpportunity -> task.item.receivedAtMs ?: System.currentTimeMillis()
            is LoopTask.ProcessContinuation -> task.item.rootInputReceivedAtMs
            is LoopTask.ProcessIntention -> task.item.rootInputReceivedAtMs
            is LoopTask.PerformAction -> task.item.rootInputReceivedAtMs
        }

    private fun taskConversationContext(task: LoopTask): ConversationContext =
        when (task) {
            is LoopTask.AttendOpportunity -> task.item.conversationContext
            is LoopTask.ProcessContinuation -> task.item.conversationContext
            is LoopTask.ProcessIntention -> task.item.conversationContext
            is LoopTask.PerformAction -> task.item.conversationContext
        }

    private fun taskGroundingMetadata(task: LoopTask): GroundingMetadata =
        when (task) {
            is LoopTask.AttendOpportunity -> task.item.trigger.groundingMetadata
            is LoopTask.ProcessContinuation -> task.item.groundingMetadata
            is LoopTask.ProcessIntention -> task.item.groundingMetadata
            is LoopTask.PerformAction -> task.item.groundingMetadata
        }

    private fun journalPlannerDecision(decision: EgoDecision) {
        val (label, actionType) = when (decision) {
            is EgoDecision.EnqueueContinuation -> "continuation" to null
            is EgoDecision.FormIntention ->
                "intention: ${decision.intentionKind.name.lowercase()} ${decision.actionType.name.lowercase()}" to
                    decision.actionType.name.lowercase()
            is EgoDecision.EnqueuePlan -> "plan" to null
            is EgoDecision.Noop -> "noop" to null
        }
        val summary = when (decision) {
            is EgoDecision.FormIntention ->
                "Decision: $label — ${TextSecurity.preview(decision.summary, JOURNAL_SUMMARY_PREVIEW_CHARS)}"
            is EgoDecision.EnqueueContinuation ->
                "Decision: $label — ${TextSecurity.preview(decision.continuation.content, JOURNAL_SUMMARY_PREVIEW_CHARS)}"
            is EgoDecision.EnqueuePlan ->
                "Decision: plan — ${TextSecurity.preview(decision.goal, JOURNAL_SUMMARY_PREVIEW_CHARS)}"
            is EgoDecision.Noop ->
                "Decision: noop — ${decision.reason}"
        }
        memory.journal(MemoryEventType.PLANNER_DECISION, summary, actionType = actionType)
    }

    private companion object {
        const val GOAL_ARTIFACT_SUMMARY_PREVIEW_CHARS: Int = 160
        const val MAX_AMBIENT_PROJECTS: Int = 4
        const val MAX_REVIEWABLE_RESPONSIBILITIES: Int = 8
        const val AMBIENT_PROJECT_PREVIEW_CHARS: Int = 180
        const val MAX_AMBIENT_SCRATCHPAD_SIGNALS: Int = 6
        const val MAX_AMBIENT_OPEN_LOOPS: Int = 4
        const val MAX_EVIDENCE_HINT_SIGNALS: Int = 3
        const val MAX_EVIDENCE_HINT_CHARS: Int = 420
        const val JOURNAL_SUMMARY_PREVIEW_CHARS: Int = 160
        const val MAX_TRACKED_SESSIONS: Int = 32
        const val HEAP_SNAPSHOT_INTERVAL: Int = 5
    }

    private fun emitThreadUpdate(
        thread: CognitiveThread,
        rootInputId: String?,
        reason: String,
    ) {
        val snapshot = cognitiveThreads.snapshot(rootInputId, thread.conversationContext)
        instrumentation.emit(
            AgentEvent(
                type = "cognitive_thread_updated",
                data = mapOf(
                    "thread_id" to thread.id,
                    "thread_kind" to thread.kind.name.lowercase(),
                    "thread_status" to thread.status.name.lowercase(),
                    "root_input_id" to rootInputId,
                    "goal_id" to thread.workItemId,
                    "policy_scope_id" to thread.securityContext.policyScope.id,
                    "reason" to reason,
                    "thread_snapshot" to snapshot,
                )
            )
        )
    }

    private fun emitOpportunityEnqueued(
        opportunity: Opportunity,
        rootInputId: String?,
        source: String,
        groundingMetadata: GroundingMetadata? = null,
    ) {
        instrumentation.emit(
            AgentEvent(
                type = "opportunity_enqueued",
                data = mapOf(
                    "opportunity_id" to opportunity.id,
                    "thread_id" to opportunity.cognitiveThreadId,
                    "opportunity_kind" to opportunity.kind.name.lowercase(),
                    "summary" to opportunity.summary,
                    "root_input_id" to rootInputId,
                    "source" to source,
                    "allowed_intentions" to opportunity.allowedIntentions.map { it.name.lowercase() },
                    "allowed_commit_modes" to opportunity.allowedCommitModes.map { it.name.lowercase() },
                    "available_actions" to opportunity.availableActions.map { it.id }.sorted(),
                    "opportunity_metadata" to opportunity.metadata,
                    "thread_snapshot" to cognitiveThreads.snapshot(rootInputId, opportunity.conversationContext),
                    "grounding_required" to groundingMetadata?.requirement?.name?.lowercase(),
                    "grounding_source" to groundingMetadata?.source?.name?.lowercase(),
                )
            )
        )
    }

    private fun shapeOpportunityContract(
        opportunity: Opportunity,
        rootInputId: String?,
        conversationContext: ConversationContext,
    ): Opportunity {
        val sessionId = resolveSessionId(conversationContext)
        val disabled = deliberation.disabledActionTypes(rootInputId, sessionId)
        val threadSecurityContext = deliberation.threadSecurityContext(rootInputId, conversationContext)
        val plannerDescriptors = motorCortex.plannerDescriptors()
            .filter { descriptor ->
                descriptor.allowedInstructionTrust.contains(conversationContext.security.instructionTrust) &&
                    descriptor.allowedArgumentDataTrust.contains(threadSecurityContext.aggregatedDataTrust)
            }
        val shapedActionSurface = CognitivePolicyShaper.shapePlannerActions(
            conversationContext = conversationContext,
            threadSecurityContext = threadSecurityContext,
            descriptors = plannerDescriptors,
            disabledActions = disabled,
        )
        val shaped = CognitivePolicyShaper.shapeOpportunityContract(
            opportunity = opportunity,
            plannerActionSurface = shapedActionSurface,
            implementedAvailableActions = motorCortex.cachedAvailableActionTypes(),
        )
        cognitiveThreads.recordOpportunity(rootInputId, conversationContext, shaped)
        return shaped
    }

    private fun emitThreadUpdateForRoot(
        rootInputId: String?,
        conversationContext: ConversationContext,
        reason: String,
    ) {
        cognitiveThreads.thread(rootInputId, conversationContext)
            ?.let { emitThreadUpdate(it, rootInputId, reason) }
    }

    private fun recordThreadIntentionTransition(
        rootInputId: String?,
        conversationContext: ConversationContext,
        intentionId: String?,
        kind: IntentionKind,
        summary: String,
        commitMode: CommitMode,
        metadata: Map<String, String>,
    ) {
        val thread = cognitiveThreads.thread(rootInputId, conversationContext) ?: return
        val intention = Intention(
            id = intentionId ?: RootInputIds.next(),
            cognitiveThreadId = thread.id,
            kind = kind,
            summary = summary,
            createdAt = java.time.Instant.now(),
            conversationContext = conversationContext,
            commitMode = commitMode,
            rootStimulusId = rootInputId,
            workItemId = thread.workItemId,
            goalRunId = thread.goalRunId,
            metadata = metadata,
        )
        cognitiveThreads.recordIntention(rootInputId, conversationContext, intention)
    }

    private fun recordThreadBlocked(
        rootInputId: String?,
        conversationContext: ConversationContext,
        reason: String?,
        reasonCode: String?,
    ) {
        cognitiveThreads.markBlocked(rootInputId, conversationContext, reason, reasonCode)
    }

    private fun recordThreadDenied(
        rootInputId: String?,
        conversationContext: ConversationContext,
        reason: String?,
        reasonCode: String?,
    ) {
        cognitiveThreads.recordDenied(rootInputId, conversationContext, reason, reasonCode)
    }

    private fun recordThreadWaiting(
        rootInputId: String?,
        conversationContext: ConversationContext,
        reason: String?,
        resumeHint: String?,
    ) {
        cognitiveThreads.markWaiting(rootInputId, conversationContext, reason, resumeHint)
    }

    private fun resolveTerminalControlPlaneDenial(
        rootInputId: String?,
        conversationContext: ConversationContext,
        reason: String?,
        reasonCode: String?,
    ) {
        cognitiveThreads.markResolved(
            rootInputId = rootInputId,
            conversationContext = conversationContext,
            reason = reasonCode ?: "approval_terminal_denied",
            summary = reason,
        )
    }
}
