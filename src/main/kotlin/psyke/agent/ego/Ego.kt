package psyke.agent.ego

import kotlinx.coroutines.delay
import mu.KotlinLogging
import psyke.agent.config.*
import psyke.agent.model.*
import psyke.agent.cortex.motor.MotorCortex
import psyke.agent.cortex.sensory.ProjectSignal
import psyke.agent.cortex.sensory.SensoryCortex
import psyke.agent.cortex.sensory.SensorySignal
import psyke.agent.cortex.sensory.Signal
import psyke.agent.cortex.sensory.SystemSignal
import psyke.agent.memory.episodic.EpisodicEventType
import psyke.agent.memory.workspace.TaskWorkspaceStore
import psyke.agent.id.EmptyProjectRegistry
import psyke.agent.id.ProjectRegistry
import psyke.agent.project.ProjectEvent
import psyke.agent.project.ProjectManager
import psyke.agent.project.ProjectWorkUnit
import psyke.agent.support.TextSecurity
import psyke.agent.superego.Superego
import psyke.instrumentation.AgentEvent
import psyke.instrumentation.AgentEvents
import psyke.instrumentation.AgentInstrumentation
import psyke.instrumentation.NoopAgentInstrumentation
import psyke.instrumentation.PhaseTimingCollector

private val logger = KotlinLogging.logger {}

class Ego(
    private val planner: Planner,
    private val superego: Superego,
    private val motorCortex: MotorCortex,
    private val config: AgentConfig,
    private val memory: MemoryCoordinator,
    private val metaReasoner: MetaReasoner = NoopMetaReasoner,
    private val sensoryCortex: SensoryCortex = SensoryCortex.stdin(config),
    private val taskWorkspaceStore: TaskWorkspaceStore = TaskWorkspaceStore(config.memory.taskWorkspace),
    private val taskWorkspaceFinalizer: TaskWorkspaceFinalizer = NoopTaskWorkspaceFinalizer,
    private val instrumentation: AgentInstrumentation = NoopAgentInstrumentation,
    private val projectRegistry: ProjectRegistry = EmptyProjectRegistry,
    private val projectManager: ProjectManager? = null,
) {
    @Volatile private var id: psyke.agent.id.Id? = null

    fun setId(id: psyke.agent.id.Id) {
        this.id = id
        impulseTracker.setId(id)
    }

    /** Delegates to the attention scheduler's impulse enqueue. Used by the Id module. */
    fun enqueueImpulse(impulse: PendingImpulse, maxPendingImpulses: Int): Boolean =
        scheduler.enqueueImpulse(impulse, maxPendingImpulses)

    /** Checks whether the Ego has any pending work. Used by the Id module for idle detection. */
    fun hasPendingWork(): Boolean = scheduler.hasPendingWork()

    interface Planner {
        fun decide(trigger: EgoTrigger, context: PlannerContext): EgoDecision
        fun resetForInput(rootInputId: String) {}
    }

    private val scheduler = AttentionScheduler(config)
    private val impulseTracker = ImpulseLifecycleTracker(instrumentation, scheduler)
    private val dialogueBySession: MutableMap<String, ArrayDeque<DialogueTurn>> =
        object : LinkedHashMap<String, ArrayDeque<DialogueTurn>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArrayDeque<DialogueTurn>>): Boolean =
                size > MAX_TRACKED_SESSIONS
        }
    private val deliberation = DeliberationEngine(
        config, instrumentation, metaReasoner,
        isEvidenceActionType = { motorCortex.hasCapability(it, psyke.agent.actions.ActionCapability.GATHERS_EVIDENCE) }
    )
    private val telemetry = EgoTelemetry(instrumentation, scheduler, memory, taskWorkspaceStore, config)
    private val fallbackHandler = FallbackHandler(
        scheduler, config, instrumentation, deliberation, memory, telemetry,
        dialogueFor = ::dialogueFor,
        resolveSessionId = ::resolveSessionId,
        processActionFallback = ::processAction,
    )
    private val dispatcher = DecisionDispatcher(
        scheduler, config, instrumentation, deliberation, memory, motorCortex,
        taskWorkspaceStore, telemetry, fallbackHandler,
        dialogueFor = ::dialogueFor,
        resolveSessionId = ::resolveSessionId,
        inputScope = ::inputScope,
    )
    private val taskVerifier: TaskVerifier = DeterministicTaskVerifier()
    private val actionPipeline = ActionReviewPipeline(
        superego, motorCortex, config, instrumentation, scheduler,
        taskVerifier, taskWorkspaceStore, taskWorkspaceFinalizer,
        deliberation, memory, telemetry, fallbackHandler, impulseTracker,
        dialogueFor = ::dialogueFor,
        resolveSessionId = ::resolveSessionId,
        superegoContext = ::superegoContext,
        cleanupResolvedInputAfterAnswer = ::cleanupResolvedInputAfterAnswer,
        getId = { id },
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
        memory.setActiveSession(sessionId, interlocutor)
        deliberation.setActiveSession(sessionId)
    }

    suspend fun runInteractive() {
        logger.info { "Ego loop started. Web dashboard chat is the active input path." }
        instrumentation.emit(AgentEvents.loopStatus(status = "running", message = "Interactive loop started"))
        telemetry.emitQueueSnapshot("loop_start")
        while (true) {
            when (val signal = sensoryCortex.nextSignal()) {
                // ── Sensory signals (external perception) ────────────
                SensorySignal.NoInput -> continue

                is SensorySignal.SourceClosed -> {
                    val message = if (signal.source == "stdin") "stdin_closed" else "${signal.source}_closed"
                    instrumentation.emit(AgentEvents.loopStatus(status = "stopped", message = message))
                    break
                }

                is SensorySignal.ExitRequested -> {
                    logger.info { "Stopping Ego loop." }
                    val message = if (signal.source == "stdin") "exit_command" else "${signal.source}_exit_command"
                    instrumentation.emit(AgentEvents.loopStatus(status = "stopped", message = message))
                    break
                }

                is SensorySignal.InputReceived -> {
                    if (!scheduler.enqueueInput(
                            content = signal.input.content,
                            priority = signal.input.priority,
                            source = signal.input.source,
                            conversationContext = signal.input.conversationContext
                        )
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

                // ── System signals (internal events) ─────────────────
                SystemSignal.ImpulseReady -> runLoop()

                SystemSignal.ShutdownRequested -> {
                    logger.info { "Graceful shutdown requested." }
                    instrumentation.emit(AgentEvents.loopStatus(status = "stopped", message = "shutdown_requested"))
                    break
                }

                is SystemSignal.ConfigReloaded -> {
                    logger.info { "Config reloaded: key=${signal.key}" }
                }

                // ── Project signals (project subsystem) ──────────────
                is ProjectSignal -> {
                    val work = projectManager?.pickWork(signal)
                    if (work != null) {
                        logger.info { "Project work picked: ${work.projectId}/${work.stepId}" }
                        scheduler.enqueueProjectWork(work)
                        runLoop()
                        // R6: Clear Ego transient state after the project work cycle
                        // completes so there is no context bleed into subsequent tasks.
                        // Also finalizes context.md (R1) and updates the budget counter (R3).
                        cleanupAfterProjectAdvance(work.projectId, work.stepId)
                    }
                }
            }
        }
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
                    is LoopTask.ProcessInput -> processInput(task.item)
                    is LoopTask.ProcessThought -> processThought(task.item)
                    is LoopTask.PerformAction -> processAction(task.item)
                    is LoopTask.ProcessImpulse -> processImpulse(task.item)
                    is LoopTask.ProcessProjectWork -> processProjectWork(task.item)
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

        if (!scheduler.hasPendingWork()) {
            impulseTracker.finalizeAllIdle()
            deliberation.reset()
            memory.resetForNewInput()
            dispatcher.resetForNewInput()
            val clearedWorkspaces = taskWorkspaceStore.clearActiveWorkspaces()
            if (clearedWorkspaces > 0) {
                instrumentation.emit(
                    AgentEvent(
                        type = "task_workspace_cleared",
                        data = mapOf(
                            "cleared_count" to clearedWorkspaces,
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

    private suspend fun processInput(input: PendingInput) {
        val timing = PhaseTimingCollector("input", input.rootInputId)
        val convCtx = input.conversationContext
        val sessionId = resolveSessionId(convCtx)
        activateSession(convCtx)
        id?.onActivity("input_received")

        timing.startPhase("input_processing")
        instrumentation.emit(AgentEvents.inputProcessing(input))
        val userTurn = DialogueTurn(
            role = DialogueRole.USER,
            content = input.content,
            sessionId = sessionId,
            interlocutor = convCtx.interlocutor,
            timestamp = java.time.Instant.now()
        )
        dialogueFor(sessionId).addLast(userTurn)
        memory.remember(userTurn)
        maybeCreateTaskWorkspace(input)
        trimDialogue(sessionId)

        timing.startPhase("planner_context")
        val trigger = EgoTrigger.IncomingInput(input)
        val context = plannerContext(
            trigger = trigger,
            rootInputId = input.rootInputId,
            sessionId = sessionId,
            conversationContext = convCtx
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
            triggeringUrgency = idNeedUrgency(thought.origin.needId),
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

    private suspend fun processImpulse(impulse: PendingImpulse) {
        val timing = PhaseTimingCollector("impulse", impulse.rootImpulseId)
        val convCtx = impulse.conversationContext
        val sessionId = resolveSessionId(convCtx)
        activateSession(convCtx)
        impulseTracker.registerLifecycle(impulse.rootImpulseId, impulse.needId)

        timing.startPhase("impulse_processing")
        instrumentation.emit(
            AgentEvent(
                type = "impulse_processing",
                data = mapOf(
                    "need_id" to impulse.needId,
                    "urgency" to impulse.urgency,
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
        )
        val idConstrainedContext = applyIdConvergenceContext(
            baseContext = baseContext,
            needId = impulse.needId,
            triggeringUrgency = impulse.urgency,
        )
        val projectSummary = projectManager?.pendingWorkSummary().orEmpty()
        val context = idConstrainedContext.copy(
            // Override: no short-term memory from other sessions
            shortTermContextSummary = "",
            projectWorkSummary = projectSummary,
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

    /**
     * Process a project work unit through the full Ego pipeline.
     *
     * This is the R2 fix: the ProjectWorkUnit is fed into the planner as an
     * [EgoTrigger.ProjectWork] so the Planner decides actions in the context
     * of the project step (description, acceptance criteria, working context).
     */
    private suspend fun processProjectWork(work: ProjectWorkUnit) {
        val timing = PhaseTimingCollector("project_work", "project:${work.projectId}")
        val convCtx = ConversationContext.default()
        val sessionId = resolveSessionId(convCtx)
        activateSession(convCtx)

        timing.startPhase("project_work_processing")
        instrumentation.emit(
            AgentEvent(
                type = "project_work_processing",
                data = mapOf(
                    "project_id" to work.projectId,
                    "step_id" to work.stepId,
                    "step_description" to work.stepDescription,
                ),
            )
        )

        // Mark step as started in the state machine
        projectManager?.applyEventExternal(
            work.projectId,
            ProjectEvent.StepStarted(work.projectId, work.stepId)
        )

        timing.startPhase("planner_context")
        val trigger = EgoTrigger.ProjectWork(work)
        val context = plannerContext(
            trigger = trigger,
            rootInputId = "project:${work.projectId}",
            sessionId = sessionId,
            conversationContext = convCtx,
        )

        timing.startPhase("planner_decide")
        val decision = planner.decide(trigger = trigger, context = context)
        journalPlannerDecision(decision)

        timing.startPhase("apply_decision")
        val origin = ActionOrigin(OriginSource.PROJECT)
        dispatcher.dispatch(
            decision = decision,
            nextPassCount = 0,
            originThought = null,
            rootInputId = "project:${work.projectId}",
            rootInputReceivedAtMs = System.currentTimeMillis(),
            conversationContext = convCtx,
            origin = origin,
        )

        instrumentation.emit(AgentEvents.phaseTimings(timing.build()))
    }

    private suspend fun processAction(action: PendingAction) {
        actionPipeline.reviewAndExecute(action)
    }

    private fun trimDialogue(sessionId: String) {
        val deque = dialogueFor(sessionId)
        while (deque.size > 20) {
            deque.removeFirst()
        }
    }

    private fun maybeCreateTaskWorkspace(input: PendingInput) {
        val created = taskWorkspaceStore.ensureForInput(input)
        if (!created) return
        instrumentation.emit(
            AgentEvent(
                type = "task_workspace_created",
                data = mapOf(
                    "root_input_id" to input.rootInputId,
                    "root_input_received_at_ms" to input.receivedAtMs,
                    "input_id" to input.id,
                    "goal_preview" to TextSecurity.preview(input.content, 140),
                    "active_tasks" to taskWorkspaceStore.activeTaskCount()
                )
            )
        )
        telemetry.emitTaskWorkspaceTelemetry(
            rootInputId = input.rootInputId,
            rootInputReceivedAtMs = input.receivedAtMs,
            updateType = "workspace_created"
        )
    }

    private suspend fun plannerContext(
        trigger: EgoTrigger,
        rootInputId: String? = null,
        sessionId: String = ConversationContext.DEFAULT_SESSION_ID,
        conversationContext: ConversationContext = ConversationContext.default(),
    ): PlannerContext {
        val recentDialogue = dialogueFor(sessionId).takeLast(12)
        val shortTermSummary = memory.currentShortTermSummary()
        val episodicCues = memory.recallEpisodicAsVectorCues(recentDialogue)
        val ambientContext = buildAmbientContext(trigger)
        val longTermRecall = memory.recall(
            trigger = trigger,
            shortTermSummary = shortTermSummary,
            recentDialogue = recentDialogue,
            episodicCues = episodicCues,
            ambientContext = ambientContext,
        )
        val lessons = memory.recallLessons(trigger, recentDialogue)
        val episodicRecall = memory.recallEpisodic(trigger, recentDialogue)
        val taskWorkspaceSummary = taskWorkspaceStore.promptSummary(
            rootInputId = rootInputId,
            maxTokens = config.memory.taskWorkspace.maxPromptTokens
        )
        val sessionWorkspaceDigest = taskWorkspaceStore.digestPromptSummary(
            sessionId = sessionId,
            maxTokens = config.memory.taskWorkspace.digestMaxPromptTokens
        )
        val disabled = deliberation.disabledActionTypes(rootInputId, sessionId)
        val availableActions = motorCortex.availableActionTypes() - disabled
        val dispatchableActions = motorCortex.dispatchableActionTypes() - disabled
        val actionDefinitions = motorCortex.plannerDescriptors().map { descriptor ->
            ActionPlanningDefinition(
                actionType = descriptor.actionType,
                description = descriptor.plannerDescription,
                payloadGuidance = descriptor.payloadGuidance,
                payloadSchemaExample = descriptor.payloadSchemaExample
            )
        }
        val evidenceHints = buildEvidenceHints(rootInputId, sessionId)
        return PlannerContext(
            recentDialogue = recentDialogue,
            queue = scheduler.queueSnapshot(),
            shortTermContextSummary = shortTermSummary,
            longTermMemoryRecall = longTermRecall,
            lessons = lessons,
            episodicRecall = episodicRecall,
            taskWorkspaceSummary = taskWorkspaceSummary,
            sessionWorkspaceDigest = sessionWorkspaceDigest,
            ambientContext = ambientContext,
            evidenceHints = evidenceHints,
            deliberation = deliberation.snapshot(),
            metaGuidance = deliberation.guidance(),
            availableActions = availableActions,
            dispatchableActions = dispatchableActions,
            actionDefinitions = actionDefinitions,
            conversationContext = conversationContext
        )
    }

    private fun buildAmbientContext(trigger: EgoTrigger): AmbientContext {
        if (!shouldAttachAmbientContext(trigger)) {
            return AmbientContext()
        }
        val activeProjects = projectRegistry.activeProjects()
            .map { project -> TextSecurity.preview(project.instruction, AMBIENT_PROJECT_PREVIEW_CHARS) }
            .filter { it.isNotBlank() }
            .take(MAX_AMBIENT_PROJECTS)
        return AmbientContext(
            activeProjects = activeProjects,
            recentWorkspaceThemes = taskWorkspaceStore.recentResolvedGoalSignals(MAX_AMBIENT_WORKSPACE_SIGNALS),
            recentUsefulActionsOrUpdates = memory.recentUsefulActionsOrUpdates(),
            unresolvedOpenLoops = taskWorkspaceStore.activeGoalSignals(MAX_AMBIENT_OPEN_LOOPS),
            recentExactLearningTopics = memory.recentExactLearningTopics(),
        )
    }

    private fun shouldAttachAmbientContext(trigger: EgoTrigger): Boolean =
        when (trigger) {
            is EgoTrigger.IncomingInput -> false
            is EgoTrigger.IncomingImpulse -> true
            is EgoTrigger.ProjectWork -> true
            is EgoTrigger.PendingThoughtInput -> trigger.thought.origin.source == OriginSource.ID
        }

    private fun applyIdConvergenceContextForOrigin(
        baseContext: PlannerContext,
        origin: ActionOrigin,
        triggeringUrgency: Double,
    ): PlannerContext {
        if (origin.source != OriginSource.ID) return baseContext
        val needId = origin.needId?.trim().orEmpty()
        if (needId.isBlank()) return baseContext
        return applyIdConvergenceContext(
            baseContext = baseContext,
            needId = needId,
            triggeringUrgency = triggeringUrgency,
        )
    }

    private fun applyIdConvergenceContext(
        baseContext: PlannerContext,
        needId: String,
        triggeringUrgency: Double,
    ): PlannerContext {
        val needCfg = id?.needConfig(needId)
        val convergence = needCfg?.convergence ?: psyke.agent.id.ConvergenceMode.CONTACT_USER
        val allowEscalation = needCfg?.allowEscalation ?: false
        val allNeeds = id?.needUrgencies() ?: emptyMap()
        val idState = IdStateSnapshot(
            triggeringNeed = needId,
            triggeringUrgency = triggeringUrgency,
            allNeeds = allNeeds,
            convergence = convergence,
            allowEscalation = allowEscalation,
        )

        // Internalize without escalation must not offer direct user messaging.
        val filteredDispatchable = if (convergence == psyke.agent.id.ConvergenceMode.INTERNALIZE && !allowEscalation) {
            baseContext.dispatchableActions - setOf(ActionType.CONTACT_USER)
        } else {
            baseContext.dispatchableActions
        }
        val filteredDefinitions = if (convergence == psyke.agent.id.ConvergenceMode.INTERNALIZE && !allowEscalation) {
            baseContext.actionDefinitions.filter { it.actionType != ActionType.CONTACT_USER }
        } else {
            baseContext.actionDefinitions
        }
        return baseContext.copy(
            idState = idState,
            dispatchableActions = filteredDispatchable,
            actionDefinitions = filteredDefinitions,
        )
    }

    private fun idNeedUrgency(needId: String?): Double =
        needId?.let { id?.needUrgencies()?.get(it) } ?: 0.0

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
        origin: ActionOrigin? = null,
    ): SuperegoContext {
        val shortTermSummary = memory.currentShortTermSummary()
        return SuperegoContext(
            recentDialogue = dialogueFor(sessionId).takeLast(12),
            shortTermContextSummary = shortTermSummary,
            origin = origin,
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
        val rootInputId = action.rootInputId ?: return
        val sessionId = resolveSessionId(action.conversationContext)
        val scope = inputScope(rootInputId, action.conversationContext)
        planner.resetForInput(rootInputId)
        deliberation.clearForInput(rootInputId, sessionId)
        dispatcher.clearExternalActionSignatures(scope)
        telemetry.emitTaskWorkspaceTelemetry(
            rootInputId = rootInputId,
            rootInputReceivedAtMs = action.rootInputReceivedAtMs,
            updateType = "before_destroy_input_resolved"
        )
        val cleared = scheduler.clearPendingWorkForInput(rootInputId, sessionId)
        val digestEntry = taskWorkspaceStore.captureDigest(rootInputId, sessionId)
        if (digestEntry != null) {
            instrumentation.emit(
                AgentEvent(
                    type = "task_workspace_digest_captured",
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
        val destroyedWorkspace = taskWorkspaceStore.destroy(rootInputId)
        if (destroyedWorkspace != null) {
            instrumentation.emit(
                AgentEvent(
                    type = "task_workspace_destroyed",
                    data = mapOf(
                        "root_input_id" to destroyedWorkspace.rootInputId,
                        "root_input_received_at_ms" to destroyedWorkspace.rootInputReceivedAtMs,
                        "section_count" to destroyedWorkspace.sectionCount,
                        "evidence_count" to destroyedWorkspace.evidenceCount,
                        "reason" to "input_resolved"
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
                    "removed_actions" to cleared.actionsRemoved
                )
            )
        )
        telemetry.emitQueueSnapshot("input_resolution_cleanup")
    }

    /**
     * R6: Clear Ego transient state after a project work cycle completes.
     *
     * Unlike [cleanupResolvedInputAfterAnswer], this does NOT destroy the task workspace
     * (project workspaces persist across sessions). It only clears the Ego's in-flight
     * state that is scoped to this project's "root input ID" convention.
     *
     * Also triggers R1 (context.md write) and R3 (budget increment) via
     * [ProjectManager.finalizeWorkCycle].
     */
    private fun cleanupAfterProjectAdvance(projectId: String, stepId: String) {
        val projectRootId = "project:$projectId"
        val sessionId = ConversationContext.DEFAULT_SESSION_ID
        planner.resetForInput(projectRootId)
        deliberation.clearForInput(projectRootId, sessionId)
        dispatcher.clearExternalActionSignatures(InputScope(projectRootId, sessionId))
        projectManager?.finalizeWorkCycle(projectId, stepId)
        instrumentation.emit(
            AgentEvent(
                type = "project_advance_cleanup",
                data = mapOf(
                    "project_id" to projectId,
                    "step_id" to stepId,
                    "project_root_id" to projectRootId,
                )
            )
        )
    }

    private fun taskType(task: LoopTask): String =
        when (task) {
            is LoopTask.ProcessInput -> "input"
            is LoopTask.ProcessThought -> "thought"
            is LoopTask.PerformAction -> "action"
            is LoopTask.ProcessImpulse -> "impulse"
            is LoopTask.ProcessProjectWork -> "project_work"
        }

    private fun taskRootInputId(task: LoopTask): String? =
        when (task) {
            is LoopTask.ProcessInput -> task.item.rootInputId
            is LoopTask.ProcessThought -> task.item.rootInputId
            is LoopTask.PerformAction -> task.item.rootInputId
            is LoopTask.ProcessImpulse -> task.item.rootImpulseId
            is LoopTask.ProcessProjectWork -> "project:${task.item.projectId}"
        }

    private fun taskRootInputReceivedAtMs(task: LoopTask): Long? =
        when (task) {
            is LoopTask.ProcessInput -> task.item.receivedAtMs
            is LoopTask.ProcessThought -> task.item.rootInputReceivedAtMs
            is LoopTask.PerformAction -> task.item.rootInputReceivedAtMs
            is LoopTask.ProcessImpulse -> task.item.receivedAtMs
            is LoopTask.ProcessProjectWork -> System.currentTimeMillis()
        }

    private fun taskConversationContext(task: LoopTask): ConversationContext =
        when (task) {
            is LoopTask.ProcessInput -> task.item.conversationContext
            is LoopTask.ProcessThought -> task.item.conversationContext
            is LoopTask.PerformAction -> task.item.conversationContext
            is LoopTask.ProcessImpulse -> task.item.conversationContext
            is LoopTask.ProcessProjectWork -> ConversationContext.default()
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
        memory.journal(EpisodicEventType.PLANNER_DECISION, summary, actionType = actionType)
    }

    private companion object {
        const val MAX_AMBIENT_PROJECTS: Int = 4
        const val AMBIENT_PROJECT_PREVIEW_CHARS: Int = 180
        const val MAX_AMBIENT_WORKSPACE_SIGNALS: Int = 6
        const val MAX_AMBIENT_OPEN_LOOPS: Int = 4
        const val MAX_EVIDENCE_HINT_SIGNALS: Int = 3
        const val MAX_EVIDENCE_HINT_CHARS: Int = 420
        const val JOURNAL_SUMMARY_PREVIEW_CHARS: Int = 160
        const val MAX_TRACKED_SESSIONS: Int = 32
        const val HEAP_SNAPSHOT_INTERVAL: Int = 5
    }
}
