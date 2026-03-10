package psyke.agent.ego

import kotlinx.coroutines.delay
import mu.KotlinLogging
import psyke.agent.core.*
import psyke.agent.cortex.motor.MotorCortex
import psyke.agent.cortex.sensory.SensoryCortex
import psyke.agent.cortex.sensory.SensorySignal
import psyke.agent.memory.episodic.EpisodicEventType
import psyke.agent.memory.episodic.Logbook
import psyke.agent.memory.episodic.LogbookSummarizer
import psyke.agent.memory.longterm.Hippocampus
import psyke.agent.memory.longterm.LongTermMemoryAdvisor
import psyke.agent.memory.longterm.NoopHippocampus
import psyke.agent.memory.longterm.NoopLongTermMemoryAdvisor
import psyke.agent.memory.shortterm.MemoryStore
import psyke.agent.memory.workspace.TaskWorkspaceStore
import psyke.agent.support.DenialReasonClassifier
import psyke.agent.support.PromptInjectionDefense
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
    private val hippocampus: Hippocampus = NoopHippocampus,
    private val metaReasoner: MetaReasoner = NoopMetaReasoner,
    private val longTermMemoryAdvisor: LongTermMemoryAdvisor = NoopLongTermMemoryAdvisor,
    private val sensoryCortex: SensoryCortex = SensoryCortex.stdin(config),
    private val memoryStore: MemoryStore = MemoryStore(config.memory.maxShortTermContextChars),
    private val taskWorkspaceStore: TaskWorkspaceStore = TaskWorkspaceStore(config.memory.taskWorkspace),
    private val taskWorkspaceFinalizer: TaskWorkspaceFinalizer = NoopTaskWorkspaceFinalizer,
    private val instrumentation: AgentInstrumentation = NoopAgentInstrumentation,
    private val logbook: Logbook? = null,
    private val logbookSummarizer: LogbookSummarizer? = null,
    private val runId: String? = null,
) {
    interface Planner {
        fun decide(trigger: EgoTrigger, context: PlannerContext): EgoDecision
        fun resetForInput(rootInputId: String) {}
    }

    private val scheduler = AttentionScheduler(config)
    private val dialogueBySession: MutableMap<String, ArrayDeque<DialogueTurn>> =
        object : LinkedHashMap<String, ArrayDeque<DialogueTurn>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArrayDeque<DialogueTurn>>): Boolean =
                size > MAX_TRACKED_SESSIONS
        }
    private val deliberation = DeliberationEngine(config, instrumentation, metaReasoner)
    private val memory = MemoryCoordinator(
        hippocampus, longTermMemoryAdvisor, config, instrumentation,
        initialMemoryStore = memoryStore,
        logbook = logbook,
        logbookSummarizer = logbookSummarizer ?: psyke.agent.memory.episodic.DeterministicLogbookSummarizer(config.logbook),
        runId = runId,
    )
    private val planCountByInput = mutableMapOf<InputScope, Int>()
    private val emittedPlanHashes = mutableMapOf<InputScope, MutableSet<String>>()
    private val externalActionSignatureHitsByInput = mutableMapOf<InputScope, MutableMap<String, Int>>()
    private val taskVerifier: TaskVerifier = DeterministicTaskVerifier()

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
        emitQueueSnapshot("loop_start")
        while (true) {
            when (val signal = sensoryCortex.nextSignal()) {
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
                        recordQueueSaturation(
                            queueType = "input",
                            capacity = config.maxPendingInputs,
                            reason = "enqueue_input_failed_full"
                        )
                        continue
                    }
                    scheduler.latestQueuedInput()?.let { queuedInput ->
                        instrumentation.emit(AgentEvents.inputQueued(queuedInput))
                    }
                    emitQueueSnapshot("input_enqueued")
                    runLoop()
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
            emitDeliberationState(taskType(task), state)
            try {
                when (task) {
                    is LoopTask.ProcessInput -> processInput(task.item)
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
            emitQueueSnapshot("task_processed")
            if (steps == 1 || steps % HEAP_SNAPSHOT_INTERVAL == 0) {
                emitHeapSnapshot()
            }
        }

        if (steps >= config.planner.maxLoopStepsPerInput && scheduler.hasPendingWork()) {
            logger.warn { "Reached loop step limit with pending work still in queues." }
            instrumentation.emit(AgentEvents.warning("Loop step limit reached with pending work."))
            emitQueueSnapshot("step_limit_reached")
            val fallbackAction = scheduler.dequeueFallbackExplanationAction()
            if (fallbackAction != null) {
                steps += 1
                instrumentation.emit(AgentEvents.loopStep(step = steps, taskType = "action_fallback"))
                processAction(fallbackAction)
                emitQueueSnapshot("fallback_explanation_step")
            }
        }

        if (!scheduler.hasPendingWork()) {
            deliberation.reset()
            memory.resetForNewInput()
            planCountByInput.clear()
            emittedPlanHashes.clear()
            externalActionSignatureHitsByInput.clear()
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
        applyDecision(
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
                enqueueFallbackExplanation(thought)
            }
            return
        }

        timing.startPhase("thought_processing")
        instrumentation.emit(AgentEvents.thoughtProcessing(thought))

        timing.startPhase("planner_context")
        val trigger = EgoTrigger.PendingThoughtInput(thought)
        val context = plannerContext(trigger, rootInputId = thought.rootInputId, sessionId = sessionId, conversationContext = convCtx)

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
        applyDecision(
            finalDecision,
            nextPassCount = thought.passes + 1,
            originThought = thought,
            rootInputId = thought.rootInputId,
            rootInputReceivedAtMs = thought.rootInputReceivedAtMs,
            conversationContext = convCtx
        )

        instrumentation.emit(AgentEvents.phaseTimings(timing.build()))
    }

    private suspend fun processAction(action: PendingAction) {
        val timing = PhaseTimingCollector("action", action.rootInputId)
        val convCtx = action.conversationContext
        val sessionId = resolveSessionId(convCtx)
        activateSession(convCtx)

        timing.startPhase("workspace_final_pass")
        val resolvedAction = applyTaskWorkspaceFinalPass(action)
        instrumentation.emit(AgentEvents.actionReviewRequested(resolvedAction))
        if (resolvedAction.isFallbackExplanation) {
            instrumentation.emit(
                AgentEvents.actionReviewResult(
                    actionId = resolvedAction.id,
                    allow = true,
                    reason = "fallback_explanation_bypass",
                    reasonCode = "SYSTEM_FALLBACK_BYPASS"
                )
            )
            val outcome = executeActionSafely(resolvedAction) ?: return
            instrumentation.emit(AgentEvents.actionExecuted(resolvedAction, outcome.statusSummary))
            if (resolvedAction.type == ActionType.ANSWER) {
                memory.journal(
                    EpisodicEventType.ANSWER_DELIVERED,
                    "Answered (fallback): ${TextSecurity.preview(resolvedAction.summary, JOURNAL_SUMMARY_PREVIEW_CHARS)}",
                    actionType = "answer",
                )
                val receivedAtMs = resolvedAction.rootInputReceivedAtMs
                if (receivedAtMs != null) {
                    val latencyMs = (System.currentTimeMillis() - receivedAtMs).coerceAtLeast(0L)
                    instrumentation.emit(AgentEvents.responseLatencyRecorded(latencyMs = latencyMs, actionId = resolvedAction.id))
                }
                deliberation.clearEvidenceForInput(resolvedAction.rootInputId, sessionId)
                cleanupResolvedInputAfterAnswer(resolvedAction)
            }
            if (outcome.assistantOutput != null) {
                val assistantTurn = DialogueTurn(
                    role = DialogueRole.ASSISTANT,
                    content = outcome.assistantOutput,
                    sessionId = sessionId,
                    interlocutor = convCtx.interlocutor,
                    timestamp = java.time.Instant.now()
                )
                dialogueFor(sessionId).addLast(assistantTurn)
                memory.remember(assistantTurn)
                trimDialogue(sessionId)
            }
            val observed = deliberation.observedEvidence(resolvedAction, outcome)
            deliberation.recordEvidenceProgress(resolvedAction, outcome, observed)
            deliberation.onActionExecuted(resolvedAction, observed)
            maybeRecordTaskWorkspaceOutcome(resolvedAction, outcome, observed)
            maybeRunTerminalAnswerMemoryAssessment(resolvedAction, outcome, sessionId)
            instrumentation.emit(AgentEvents.phaseTimings(timing.build()))
            return
        }
        timing.startPhase("task_verifier")
        val recentDialogue = dialogueFor(sessionId).takeLast(12)
        val latestUserTurn = recentDialogue
            .asReversed()
            .firstOrNull { it.role == DialogueRole.USER }
            ?.content
            .orEmpty()
        val disabledForScope = deliberation.disabledActionTypes(resolvedAction.rootInputId, sessionId)
        val availableActionsForScope = motorCortex.availableActionTypes() - disabledForScope
        val dispatchableActionsForScope = motorCortex.dispatchableActionTypes() - disabledForScope
        val taskVerificationDecision = taskVerifier.review(
            action = resolvedAction,
            context = TaskVerifierContext(
                recentDialogue = recentDialogue,
                externalEvidence = deliberation.evidenceFor(resolvedAction.rootInputId, sessionId),
                availableActions = availableActionsForScope,
                dispatchableActions = dispatchableActionsForScope,
                latestUserTurn = latestUserTurn
            )
        )
        val assessment = taskVerificationDecision.assessment
        instrumentation.emit(
            AgentEvent(
                type = "task_verifier_review",
                data = mapOf(
                    "action_id" to resolvedAction.id,
                    "root_input_id" to resolvedAction.rootInputId,
                    "root_input_received_at_ms" to resolvedAction.rootInputReceivedAtMs,
                    "session_id" to sessionId,
                    "action_type" to resolvedAction.type.id,
                    "allow" to taskVerificationDecision.allow,
                    "reason" to taskVerificationDecision.reason,
                    "reason_code" to taskVerificationDecision.reasonCode,
                    "intent_category" to assessment?.intentCategory?.name?.lowercase(),
                    "volatility_level" to assessment?.volatilityLevel?.name?.lowercase(),
                    "volatility_score" to assessment?.volatilityScore,
                    "requires_external_evidence" to assessment?.requiresExternalEvidence,
                    "evidence_actions_available" to assessment?.evidenceActionsAvailable,
                    "evidence_actions_dispatchable" to assessment?.evidenceActionsDispatchable,
                    "had_successful_evidence" to assessment?.hadSuccessfulEvidence,
                    "had_external_failures" to assessment?.hadExternalFailures,
                    "latest_user_turn_preview" to TextSecurity.preview(latestUserTurn, 140)
                )
            )
        )
        if (!taskVerificationDecision.allow) {
            instrumentation.emit(
                AgentEvents.actionReviewResult(
                    actionId = resolvedAction.id,
                    allow = false,
                    reason = taskVerificationDecision.reason,
                    reasonCode = taskVerificationDecision.reasonCode
                )
            )
            handleDeniedAction(
                action = resolvedAction,
                reason = taskVerificationDecision.reason,
                reasonCode = taskVerificationDecision.reasonCode,
                conversationContext = convCtx,
                sessionId = sessionId,
                source = "task_verifier"
            )
            instrumentation.emit(AgentEvents.phaseTimings(timing.build()))
            return
        }
        timing.startPhase("superego_review")
        val gateDecision = superego.review(resolvedAction, superegoContext(sessionId))
        instrumentation.emit(
            AgentEvents.actionReviewResult(
                actionId = resolvedAction.id,
                allow = gateDecision.allow,
                reason = gateDecision.reason,
                reasonCode = gateDecision.reasonCode
            )
        )
        if (!gateDecision.allow) {
            handleDeniedAction(
                action = resolvedAction,
                reason = gateDecision.reason,
                reasonCode = gateDecision.reasonCode,
                conversationContext = convCtx,
                sessionId = sessionId,
                source = "superego"
            )
            instrumentation.emit(AgentEvents.phaseTimings(timing.build()))
            return
        }

        timing.startPhase("action_execute")
        val outcome = executeActionSafely(resolvedAction) ?: return
        instrumentation.emit(AgentEvents.actionExecuted(resolvedAction, outcome.statusSummary))
        if (resolvedAction.type == ActionType.ANSWER) {
            memory.journal(
                EpisodicEventType.ANSWER_DELIVERED,
                "Answered: ${TextSecurity.preview(resolvedAction.summary, JOURNAL_SUMMARY_PREVIEW_CHARS)}",
                actionType = "answer",
            )
        } else {
            memory.journal(
                EpisodicEventType.ACTION_EXECUTED,
                "Executed ${resolvedAction.type.name.lowercase()}: ${TextSecurity.preview(outcome.statusSummary, JOURNAL_SUMMARY_PREVIEW_CHARS)}",
                actionType = resolvedAction.type.name.lowercase(),
            )
        }
        timing.startPhase("post_execute")
        val observed = deliberation.observedEvidence(resolvedAction, outcome)
        deliberation.recordEvidenceProgress(resolvedAction, outcome, observed)
        deliberation.onActionExecuted(resolvedAction, observed)
        maybeRecordTaskWorkspaceOutcome(resolvedAction, outcome, observed)
        deliberation.recordActionOutcome(resolvedAction, outcome, observed)
        if (resolvedAction.type == ActionType.ANSWER) {
            val receivedAtMs = resolvedAction.rootInputReceivedAtMs
            if (receivedAtMs != null) {
                val latencyMs = (System.currentTimeMillis() - receivedAtMs).coerceAtLeast(0L)
                instrumentation.emit(AgentEvents.responseLatencyRecorded(latencyMs = latencyMs, actionId = resolvedAction.id))
            }
            deliberation.clearEvidenceForInput(resolvedAction.rootInputId, sessionId)
            cleanupResolvedInputAfterAnswer(resolvedAction)
        }
        if (outcome.assistantOutput != null) {
            val assistantTurn = DialogueTurn(
                role = DialogueRole.ASSISTANT,
                content = outcome.assistantOutput,
                sessionId = sessionId,
                interlocutor = convCtx.interlocutor,
                timestamp = java.time.Instant.now()
            )
            dialogueFor(sessionId).addLast(assistantTurn)
            memory.remember(assistantTurn)
            trimDialogue(sessionId)
        }
        maybeRunTerminalAnswerMemoryAssessment(resolvedAction, outcome, sessionId)
        maybeRunLongTermMemoryAssessment(
            trigger = "post_allowed_action",
            force = config.memory.longTermMemoryForceAssessOnAllowedAction,
            latestActionType = resolvedAction.type,
            latestActionOutcome = outcome.plannerSignal,
            sessionId = sessionId
        )

        timing.startPhase("follow_up")
        if (resolvedAction.requiresFollowUpThought) {
            val safePlannerSignal = PromptInjectionDefense.asUntrustedDataBlock(
                text = outcome.plannerSignal,
                maxChars = FOLLOW_UP_SIGNAL_MAX_CHARS
            )
            val followUpThought = TextSecurity.clamp(
                "${resolvedAction.followUpPrefix}\n$safePlannerSignal\nDecide if an answer should be sent.",
                config.planner.maxThoughtChars
            )
            val queued = scheduler.enqueueThought(
                content = followUpThought,
                urgency = resolvedAction.urgency,
                passes = resolvedAction.attempts,
                rootInputId = resolvedAction.rootInputId,
                rootInputReceivedAtMs = resolvedAction.rootInputReceivedAtMs,
                allowFallbackExplanation = true,
                originActionType = resolvedAction.type,
                originActionObservedEvidence = observed,
                conversationContext = convCtx
            )
            if (!queued) {
                instrumentation.emit(AgentEvents.warning("Failed to enqueue follow-up thought after action."))
                recordQueueSaturation(
                    queueType = "thought",
                    capacity = config.maxPendingThoughts,
                    reason = "enqueue_followup_thought_failed_full"
                )
            }
            emitQueueSnapshot("follow_up_thought_enqueued")
        }

        instrumentation.emit(AgentEvents.phaseTimings(timing.build()))
    }

    private fun handleDeniedAction(
        action: PendingAction,
        reason: String,
        reasonCode: String?,
        conversationContext: ConversationContext,
        sessionId: String,
        source: String,
    ) {
        deliberation.onActionDenied()
        val denialThought = TextSecurity.clamp(
            "Action denied by $source ($reason). " +
                "Try a different safe action than the denied one. " +
                "If no safe alternative exists, prepare a concise explanation for the interlocutor.",
            config.planner.maxThoughtChars
        )
        val queued = scheduler.enqueueThought(
            content = denialThought,
            urgency = action.urgency,
            passes = action.attempts + 1,
            rootInputId = action.rootInputId,
            rootInputReceivedAtMs = action.rootInputReceivedAtMs,
            deniedActionType = action.type,
            deniedActionPayload = TextSecurity.clamp(action.payload, 240),
            denialReason = reason,
            denialReasonCode = reasonCode,
            allowFallbackExplanation = true,
            conversationContext = conversationContext
        )
        instrumentation.emit(AgentEvents.actionDenied(action, reason, reasonCode))
        memory.journal(
            EpisodicEventType.ACTION_DENIED,
            "Denied ${action.type.name.lowercase()} by $source: $reason",
            actionType = action.type.name.lowercase(),
            metadata = mapOf(
                "source" to source,
                "reason_code" to reasonCode
            )
        )
        memory.maybeRecordReflectionLesson(
            trigger = "action_denied_$source",
            actionType = action.type,
            reasonCode = reasonCode,
            reason = reason,
            deniedPayload = action.payload,
            recentDialogue = dialogueFor(sessionId).takeLast(12),
            stepIndex = deliberation.snapshot().stepIndex
        )
        if (!queued) {
            instrumentation.emit(AgentEvents.warning("Failed to enqueue denial thought."))
            recordQueueSaturation(
                queueType = "thought",
                capacity = config.maxPendingThoughts,
                reason = "enqueue_denial_thought_failed_full"
            )
        }
        emitQueueSnapshot("action_denied")
    }

    private suspend fun applyDecision(
        decision: EgoDecision,
        nextPassCount: Int,
        originThought: PendingThought?,
        rootInputId: String?,
        rootInputReceivedAtMs: Long?,
        conversationContext: ConversationContext,
    ) {
        when (decision) {
            is EgoDecision.EnqueueThought -> {
                val queued = scheduler.enqueueThought(
                    content = decision.content,
                    urgency = decision.urgency,
                    passes = nextPassCount,
                    longTermMemoryRecallQuery = decision.longTermMemoryRecallQuery,
                    rootInputId = rootInputId,
                    rootInputReceivedAtMs = rootInputReceivedAtMs,
                    deniedActionType = originThought?.deniedActionType,
                    deniedActionPayload = originThought?.deniedActionPayload,
                    denialReason = originThought?.denialReason,
                    denialReasonCode = originThought?.denialReasonCode,
                    allowFallbackExplanation = originThought?.allowFallbackExplanation ?: false,
                    originActionType = originThought?.originActionType,
                    originActionObservedEvidence = originThought?.originActionObservedEvidence,
                    conversationContext = conversationContext
                )
                if (!decision.longTermMemoryRecallQuery.isNullOrBlank()) {
                    instrumentation.emit(
                        AgentEvents.longTermMemoryRecallRequested(
                            trigger = "thought",
                            source = "planner",
                            queryPreview = TextSecurity.preview(decision.longTermMemoryRecallQuery, 180)
                        )
                    )
                }
                instrumentation.emit(
                    AgentEvent(
                        type = "thought_enqueued",
                        data = mapOf(
                            "queued" to queued,
                            "urgency" to decision.urgency.name.lowercase(),
                            "content" to decision.content
                        )
                    )
                )
                if (!queued) {
                    instrumentation.emit(AgentEvents.warning("Failed to enqueue planner thought."))
                    recordQueueSaturation(
                        queueType = "thought",
                        capacity = config.maxPendingThoughts,
                        reason = "enqueue_planner_thought_failed_full"
                    )
                }
                emitQueueSnapshot("decision_thought")
            }

            is EgoDecision.ProposeAction -> {
                val repeatedDeniedAction = originThought != null && isRepeatOfDeniedAction(originThought, decision)
                val technicalDenial = DenialReasonClassifier.isLikelyTechnical(
                    reasonCode = originThought?.denialReasonCode,
                    reason = originThought?.denialReason
                )
                if (repeatedDeniedAction && !technicalDenial) {
                    instrumentation.emit(AgentEvents.warning("Planner repeated a denied action; requesting an alternative."))
                    deliberation.onRepeatedDeniedAction()
                    memory.maybeRecordReflectionLesson(
                        trigger = "repeated_denied_action",
                        actionType = decision.actionType,
                        reasonCode = originThought.denialReasonCode,
                        reason = originThought.denialReason,
                        deniedPayload = decision.payload,
                        recentDialogue = dialogueFor(resolveSessionId(conversationContext)).takeLast(12),
                        stepIndex = deliberation.snapshot().stepIndex
                    )
                    val retryThought = TextSecurity.clamp(
                        "Previous proposed action repeats a denied action. Pick a materially different safe action.",
                        config.planner.maxThoughtChars
                    )
                    val queuedRetry = scheduler.enqueueThought(
                        content = retryThought,
                        urgency = originThought.urgency,
                        passes = nextPassCount,
                        rootInputId = rootInputId,
                        rootInputReceivedAtMs = rootInputReceivedAtMs,
                        deniedActionType = originThought.deniedActionType,
                        deniedActionPayload = originThought.deniedActionPayload,
                        denialReason = originThought.denialReason,
                        denialReasonCode = originThought.denialReasonCode,
                        allowFallbackExplanation = originThought.allowFallbackExplanation,
                        originActionType = originThought.originActionType,
                        originActionObservedEvidence = originThought.originActionObservedEvidence,
                        conversationContext = conversationContext
                    )
                    if (!queuedRetry) {
                        instrumentation.emit(AgentEvents.warning("Failed to enqueue retry thought after repeated denied action."))
                        recordQueueSaturation(
                            queueType = "thought",
                            capacity = config.maxPendingThoughts,
                            reason = "enqueue_retry_thought_failed_full"
                        )
                    }
                    emitQueueSnapshot("repeated_denied_action_blocked")
                    return
                }
                emitExternalActionRedundancySignal(
                    decision = decision,
                    rootInputId = rootInputId,
                    rootInputReceivedAtMs = rootInputReceivedAtMs,
                    conversationContext = conversationContext
                )
                val queued = scheduler.enqueueAction(
                    type = decision.actionType,
                    payload = decision.payload,
                    summary = decision.summary,
                    urgency = decision.urgency,
                    requiresFollowUpThought = motorCortex.requiresFollowUpThought(decision.actionType),
                    followUpPrefix = motorCortex.followUpPrefix(decision.actionType),
                    attempts = nextPassCount,
                    rootInputId = rootInputId,
                    rootInputReceivedAtMs = rootInputReceivedAtMs,
                    conversationContext = conversationContext
                )
                instrumentation.emit(
                    AgentEvents.actionProposed(
                        actionType = decision.actionType.id,
                        urgency = decision.urgency.name.lowercase(),
                        payload = decision.payload,
                        summary = decision.summary,
                        queued = queued
                    )
                )
                if (!queued) {
                    instrumentation.emit(AgentEvents.warning("Failed to enqueue proposed action."))
                    recordQueueSaturation(
                        queueType = "action",
                        capacity = config.maxPendingActions,
                        reason = "enqueue_action_failed_full"
                    )
                }
                emitQueueSnapshot("decision_action")
            }

            is EgoDecision.EnqueuePlan -> {
                val inputScope = inputScope(rootInputId, conversationContext)
                // ── Gate 1: plan budget per input ──
                val currentPlanCount = planCountByInput.getOrDefault(inputScope, 0)
                if (currentPlanCount >= config.planner.maxPlansPerInput) {
                    instrumentation.emit(
                        AgentEvents.warning(
                            "Duplicate plan suppressed; plan budget exhausted " +
                                "($currentPlanCount/${config.planner.maxPlansPerInput}) for this input."
                        )
                    )
                    instrumentation.emit(
                        AgentEvents.duplicatePlanSuppressed(
                            reason = "budget_exhausted",
                            rootInputId = rootInputId,
                            rootInputReceivedAtMs = rootInputReceivedAtMs
                        )
                    )
                    recoverFromSuppressedPlan(
                        suppressionReason = "budget_exhausted",
                        decision = decision,
                        nextPassCount = nextPassCount,
                        originThought = originThought,
                        rootInputId = rootInputId,
                        rootInputReceivedAtMs = rootInputReceivedAtMs,
                        conversationContext = conversationContext
                    )
                    emitQueueSnapshot("decision_plan_suppressed_budget")
                    return
                }

                // ── Gate 2: exact plan hash dedup ──
                val planHash = normalizePlanHash(decision.goal, decision.steps)
                val inputHashes = emittedPlanHashes.getOrPut(inputScope) { mutableSetOf() }
                if (!inputHashes.add(planHash)) {
                    instrumentation.emit(
                        AgentEvents.warning(
                            "Duplicate plan suppressed; identical plan hash already emitted for this input."
                        )
                    )
                    instrumentation.emit(
                        AgentEvents.duplicatePlanSuppressed(
                            reason = "hash_dedup",
                            rootInputId = rootInputId,
                            rootInputReceivedAtMs = rootInputReceivedAtMs
                        )
                    )
                    recoverFromSuppressedPlan(
                        suppressionReason = "hash_dedup",
                        decision = decision,
                        nextPassCount = nextPassCount,
                        originThought = originThought,
                        rootInputId = rootInputId,
                        rootInputReceivedAtMs = rootInputReceivedAtMs,
                        conversationContext = conversationContext
                    )
                    emitQueueSnapshot("decision_plan_suppressed_hash")
                    return
                }

                // ── All gates passed: emit plan ──
                planCountByInput[inputScope] = currentPlanCount + 1
                val workspaceActivated = taskWorkspaceStore.recordPlan(
                    rootInputId = rootInputId,
                    goal = decision.goal,
                    steps = decision.steps
                )
                if (workspaceActivated) {
                    instrumentation.emit(
                        AgentEvent(
                            type = "task_workspace_created",
                            data = mapOf(
                                "root_input_id" to rootInputId,
                                "root_input_received_at_ms" to rootInputReceivedAtMs,
                                "goal_preview" to TextSecurity.preview(decision.goal, 140),
                                "active_tasks" to taskWorkspaceStore.activeTaskCount(),
                                "activation_trigger" to "plan_complexity",
                                "plan_step_count" to decision.steps.size
                            )
                        )
                    )
                    emitTaskWorkspaceTelemetry(
                        rootInputId = rootInputId,
                        rootInputReceivedAtMs = rootInputReceivedAtMs,
                        updateType = "workspace_activated"
                    )
                }
                instrumentation.emit(
                    AgentEvent(
                        type = "task_workspace_updated",
                        data = mapOf(
                            "root_input_id" to rootInputId,
                            "root_input_received_at_ms" to rootInputReceivedAtMs,
                            "update_type" to "plan_recorded",
                            "goal_preview" to TextSecurity.preview(decision.goal, 140),
                            "step_count" to decision.steps.size,
                            "active_tasks" to taskWorkspaceStore.activeTaskCount()
                        )
                    )
                )
                emitTaskWorkspaceTelemetry(
                    rootInputId = rootInputId,
                    rootInputReceivedAtMs = rootInputReceivedAtMs,
                    updateType = "plan_recorded"
                )

                val planId = java.util.UUID.randomUUID().toString().take(PLAN_ID_LENGTH)
                instrumentation.emit(
                    AgentEvents.planCreated(
                        planId = planId,
                        goal = decision.goal,
                        stepCount = decision.steps.size,
                        urgency = decision.urgency.name.lowercase()
                    )
                )
                var allQueued = true
                decision.steps.forEachIndexed { index, stepDescription ->
                    val stepContent = TextSecurity.clamp(
                        "Plan step ${index + 1}/${decision.steps.size}: $stepDescription",
                        config.planner.maxThoughtChars
                    )
                    val queued = scheduler.enqueueThought(
                        content = stepContent,
                        urgency = decision.urgency,
                        passes = nextPassCount,
                        rootInputId = rootInputId,
                        rootInputReceivedAtMs = rootInputReceivedAtMs,
                        planContext = PlanContext(
                            planId = planId,
                            planGoal = decision.goal,
                            stepIndex = index,
                            totalSteps = decision.steps.size,
                            stepDescription = stepDescription,
                        ),
                        originActionType = originThought?.originActionType,
                        originActionObservedEvidence = originThought?.originActionObservedEvidence,
                        conversationContext = conversationContext
                    )
                    if (!queued) {
                        allQueued = false
                        instrumentation.emit(
                            AgentEvents.warning("Failed to enqueue plan step ${index + 1}/${decision.steps.size}.")
                        )
                        recordQueueSaturation(
                            queueType = "thought",
                            capacity = config.maxPendingThoughts,
                            reason = "enqueue_plan_step_failed_full"
                        )
                    }
                }
                instrumentation.emit(
                    AgentEvents.planStepsEnqueued(
                        planId = planId,
                        totalSteps = decision.steps.size,
                        allQueued = allQueued
                    )
                )
                emitQueueSnapshot("decision_plan")
            }

            is EgoDecision.Noop -> {
                if (decision.parseFailureShortCircuit) {
                    instrumentation.emit(
                        AgentEvents.warning("Parse-failure circuit breaker tripped; skipping noop re-enqueue and going to fallback.")
                    )
                    enqueueFallbackExplanation(
                        rootInputId = rootInputId,
                        rootInputReceivedAtMs = rootInputReceivedAtMs,
                        reason = decision.reason,
                        conversationContext = conversationContext
                    )
                    emitQueueSnapshot("decision_noop_short_circuit")
                } else {
                    val noopThought = TextSecurity.clamp("Noop decision: ${decision.reason}", config.planner.maxThoughtChars)
                    val queued = scheduler.enqueueThought(
                        content = noopThought,
                        urgency = Urgency.LOW,
                        passes = nextPassCount,
                        rootInputId = rootInputId,
                        rootInputReceivedAtMs = rootInputReceivedAtMs,
                        deniedActionType = originThought?.deniedActionType,
                        deniedActionPayload = originThought?.deniedActionPayload,
                        denialReason = originThought?.denialReason,
                        denialReasonCode = originThought?.denialReasonCode,
                        allowFallbackExplanation = originThought?.allowFallbackExplanation ?: false,
                        originActionType = originThought?.originActionType,
                        originActionObservedEvidence = originThought?.originActionObservedEvidence,
                        conversationContext = conversationContext
                    )
                    instrumentation.emit(
                        AgentEvent(
                            type = "noop_recorded",
                            data = mapOf("queued_thought" to queued, "reason" to decision.reason)
                        )
                    )
                    if (!queued) {
                        instrumentation.emit(AgentEvents.warning("Failed to enqueue noop thought."))
                        recordQueueSaturation(
                            queueType = "thought",
                            capacity = config.maxPendingThoughts,
                            reason = "enqueue_noop_thought_failed_full"
                        )
                    }
                    emitQueueSnapshot("decision_noop")
                }
            }
        }
    }

    private suspend fun recoverFromSuppressedPlan(
        suppressionReason: String,
        decision: EgoDecision.EnqueuePlan,
        nextPassCount: Int,
        originThought: PendingThought?,
        rootInputId: String?,
        rootInputReceivedAtMs: Long?,
        conversationContext: ConversationContext,
    ) {
        val sessionId = resolveSessionId(conversationContext)
        if (scheduler.hasPendingPlanThoughtsForInput(rootInputId, sessionId)) {
            return
        }
        if (scheduler.hasPendingConvergenceThoughtForInput(rootInputId, sessionId)) {
            return
        }
        val convergenceThought = TextSecurity.clamp(
            "${AttentionScheduler.CONVERGENCE_THOUGHT_PREFIX}" +
                "Plan emission was suppressed ($suppressionReason). " +
                "Converge now: use gathered evidence and produce a final answer, " +
                "or provide a concise fallback explanation if completion is not possible.",
            config.planner.maxThoughtChars
        )
        val queued = scheduler.enqueueThought(
            content = convergenceThought,
            urgency = decision.urgency,
            passes = nextPassCount,
            rootInputId = rootInputId,
            rootInputReceivedAtMs = rootInputReceivedAtMs,
            allowFallbackExplanation = originThought?.allowFallbackExplanation ?: true,
            originActionType = originThought?.originActionType,
            originActionObservedEvidence = originThought?.originActionObservedEvidence,
            conversationContext = conversationContext
        )
        if (queued) {
            instrumentation.emit(
                AgentEvents.convergenceThoughtEnqueued(
                    rootInputId = rootInputId,
                    rootInputReceivedAtMs = rootInputReceivedAtMs
                )
            )
            return
        }
        instrumentation.emit(
            AgentEvents.warning("Failed to enqueue convergence thought after plan suppression recovery.")
        )
        recordQueueSaturation(
            queueType = "thought",
            capacity = config.maxPendingThoughts,
            reason = "enqueue_plan_suppression_recovery_thought_failed_full"
        )
        if (originThought?.allowFallbackExplanation == true) {
            enqueueFallbackExplanation(originThought)
        }
    }

    private suspend fun enqueueFallbackExplanation(thought: PendingThought) {
        val sessionId = resolveSessionId(thought.conversationContext)
        if (scheduler.hasPendingFallbackExplanationAction(thought.rootInputId, sessionId)) {
            instrumentation.emit(
                AgentEvents.warning("Fallback explanation already queued for this input; skipping duplicate enqueue.")
            )
            return
        }
        val evidence = deliberation.evidenceFor(thought.rootInputId, sessionId)
        val parseFailureLikely = thought.content.contains("non-parseable", ignoreCase = true)
        val (payload, summary) = when {
            !thought.denialReason.isNullOrBlank() -> {
                val message = "I cannot complete the previous action because it was blocked by policy " +
                    "(${thought.denialReason ?: "no reason provided"}). " +
                    "I could not find a safe alternative."
                message to "Explain inability to comply after policy denial."
            }
            evidence?.hadSuccessfulEvidence == true -> {
                val signals = evidence.successfulEvidenceSignals
                val aggregatedEvidence = if (signals.isNotEmpty()) {
                    signals.joinToString(" | ")
                } else {
                    evidence.latestPlannerSignal.ifBlank {
                        "I gathered external evidence, but final synthesis failed."
                    }
                }
                val message = "I completed external verification, but repeated internal planner formatting/parsing failures " +
                    "prevented a clean final synthesis. Best-effort result from gathered evidence: $aggregatedEvidence"
                message to "Provide best-effort answer using gathered evidence after planner parse failures."
            }
            evidence?.hadExternalFailures == true -> {
                val message = "I could not complete external verification after multiple attempts due to transient tool/provider " +
                    "failures (for example, timeouts). I can still provide a best-effort answer based on currently available " +
                    "context, but it may be stale."
                message to "Explain inability to verify externally after repeated tool failures."
            }
            parseFailureLikely -> {
                val message = "I encountered repeated internal parsing/formatting failures while preparing the final response. " +
                    "I can still provide a concise best-effort answer, but confidence is reduced."
                message to "Explain inability to finalize due to internal parse failures."
            }
            else -> {
                val message = "I could not complete this request reliably after multiple attempts. " +
                    "I can still provide a concise best-effort answer from available context."
                message to "Explain inability to complete reliably after repeated failures."
            }
        }
        val queued = scheduler.enqueueAction(
            type = ActionType.ANSWER,
            payload = TextSecurity.clamp(payload, config.planner.maxActionPayloadChars),
            summary = summary,
            urgency = thought.urgency,
            attempts = thought.passes,
            isFallbackExplanation = true,
            rootInputId = thought.rootInputId,
            rootInputReceivedAtMs = thought.rootInputReceivedAtMs,
            conversationContext = thought.conversationContext
        )
        if (!queued) {
            logger.warn { "Fallback explanation enqueue failed; executing immediate fallback action." }
            instrumentation.emit(AgentEvents.warning("Failed to enqueue fallback explanation action. Executing immediately."))
            recordQueueSaturation(
                queueType = "action",
                capacity = config.maxPendingActions,
                reason = "enqueue_fallback_action_failed_full"
            )
            processAction(
                PendingAction(
                    id = -1,
                    urgency = thought.urgency,
                    type = ActionType.ANSWER,
                    payload = payload,
                    summary = summary,
                    attempts = thought.passes,
                    isFallbackExplanation = true,
                    rootInputId = thought.rootInputId,
                    rootInputReceivedAtMs = thought.rootInputReceivedAtMs,
                    conversationContext = thought.conversationContext
                )
            )
            emitQueueSnapshot("fallback_explanation_executed_immediate")
            return
        }
        emitQueueSnapshot("fallback_explanation_enqueued")
    }

    private fun enqueueFallbackExplanation(
        rootInputId: String?,
        rootInputReceivedAtMs: Long?,
        reason: String,
        conversationContext: ConversationContext,
    ) {
        val sessionId = resolveSessionId(conversationContext)
        if (scheduler.hasPendingFallbackExplanationAction(rootInputId, sessionId)) {
            return
        }
        val payload = "I encountered repeated internal parsing/formatting failures while preparing the response. " +
            "Reason: $reason"
        val summary = "Fallback answer after planner circuit breaker trip."
        val queued = scheduler.enqueueAction(
            type = ActionType.ANSWER,
            payload = TextSecurity.clamp(payload, config.planner.maxActionPayloadChars),
            summary = summary,
            urgency = Urgency.HIGH,
            isFallbackExplanation = true,
            rootInputId = rootInputId,
            rootInputReceivedAtMs = rootInputReceivedAtMs,
            conversationContext = conversationContext
        )
        if (!queued) {
            instrumentation.emit(AgentEvents.warning("Failed to enqueue circuit-breaker fallback."))
        }
        emitQueueSnapshot("fallback_explanation_circuit_breaker")
    }

    private fun isRepeatOfDeniedAction(thought: PendingThought, decision: EgoDecision.ProposeAction): Boolean {
        val deniedType = thought.deniedActionType ?: return false
        val deniedPayload = thought.deniedActionPayload ?: return false
        if (decision.actionType != deniedType) return false
        return normalizeActionPayload(decision.payload) == normalizeActionPayload(deniedPayload)
    }

    private fun emitExternalActionRedundancySignal(
        decision: EgoDecision.ProposeAction,
        rootInputId: String?,
        rootInputReceivedAtMs: Long?,
        conversationContext: ConversationContext,
    ) {
        if (!motorCortex.requiresFollowUpThought(decision.actionType)) return
        val scope = inputScope(rootInputId, conversationContext)
        val signature = "${decision.actionType.id}:${normalizeActionPayload(decision.payload)}"
        val hitsBySignature = externalActionSignatureHitsByInput.getOrPut(scope) { mutableMapOf() }
        val signatureHits = (hitsBySignature[signature] ?: 0) + 1
        hitsBySignature[signature] = signatureHits

        val evidence = deliberation.evidenceFor(rootInputId, conversationContext.sessionId)
        val hadSuccessfulEvidence = evidence?.hadSuccessfulEvidence == true
        val hadExternalFailures = evidence?.hadExternalFailures == true
        val redundantRisk = hadSuccessfulEvidence && signatureHits > 1
        if (signatureHits < REDUNDANCY_SIGNAL_MIN_HITS && !redundantRisk) return

        instrumentation.emit(
            AgentEvents.externalActionRedundancySignal(
                actionType = decision.actionType.id,
                signatureHits = signatureHits,
                hadSuccessfulEvidence = hadSuccessfulEvidence,
                hadExternalFailures = hadExternalFailures,
                redundantRisk = redundantRisk,
                rootInputId = rootInputId,
                rootInputReceivedAtMs = rootInputReceivedAtMs
            )
        )
    }

    private fun normalizeActionPayload(payload: String): String =
        payload.lowercase().replace(Regex("\\s+"), " ").trim()

    private fun normalizePlanHash(goal: String, steps: List<String>): String {
        val normalized = (listOf(goal) + steps)
            .joinToString("|") { it.lowercase().replace(Regex("\\s+"), " ").trim() }
        return normalized.hashCode().toString(16)
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
        emitTaskWorkspaceTelemetry(
            rootInputId = input.rootInputId,
            rootInputReceivedAtMs = input.receivedAtMs,
            updateType = "workspace_created"
        )
    }

    private fun maybeRecordTaskWorkspaceOutcome(action: PendingAction, outcome: ActionOutcome, observedEvidence: Boolean) {
        taskWorkspaceStore.recordActionOutcome(
            rootInputId = action.rootInputId,
            action = action,
            outcome = outcome,
            observedEvidence = observedEvidence
        )
        if (action.type == ActionType.ANSWER) return
        instrumentation.emit(
            AgentEvent(
                type = "task_workspace_updated",
                data = mapOf(
                    "root_input_id" to action.rootInputId,
                    "root_input_received_at_ms" to action.rootInputReceivedAtMs,
                    "update_type" to "action_outcome_recorded",
                    "action_type" to action.type.name.lowercase(),
                    "observed_evidence" to observedEvidence,
                    "status_preview" to TextSecurity.preview(outcome.statusSummary, 140),
                    "active_tasks" to taskWorkspaceStore.activeTaskCount()
                )
            )
        )
        emitTaskWorkspaceTelemetry(
            rootInputId = action.rootInputId,
            rootInputReceivedAtMs = action.rootInputReceivedAtMs,
            updateType = "action_outcome_recorded"
        )
    }

    private fun applyTaskWorkspaceFinalPass(action: PendingAction): PendingAction {
        val sessionId = resolveSessionId(action.conversationContext)
        if (action.type != ActionType.ANSWER) {
            return action
        }
        if (!config.memory.taskWorkspace.enabled || !config.memory.taskWorkspace.finalPassRewriteEnabled) {
            return action
        }
        val preFinalSnapshot = taskWorkspaceStore.debugSnapshot(action.rootInputId)
        if (preFinalSnapshot != null) {
            instrumentation.emit(
                AgentEvent(
                    type = "task_workspace_pre_final_dump",
                    data = mapOf(
                        "session_id" to sessionId,
                        "root_input_id" to action.rootInputId,
                        "candidate_answer" to action.payload,
                        "snapshot" to preFinalSnapshot
                    )
                )
            )
        }
        taskWorkspaceStore.recordAnswerDraft(
            rootInputId = action.rootInputId,
            payload = action.payload
        )
        emitTaskWorkspaceTelemetry(
            rootInputId = action.rootInputId,
            rootInputReceivedAtMs = action.rootInputReceivedAtMs,
            updateType = "answer_draft_recorded"
        )
        val finalPassInput = taskWorkspaceStore.buildFinalPassInput(
            rootInputId = action.rootInputId,
            candidateAnswer = action.payload,
            maxChars = config.memory.taskWorkspace.finalCompilationMaxChars
        ) ?: return action
        if (finalPassInput.evidenceCount == 0) {
            instrumentation.emit(
                AgentEvent(
                    type = "task_workspace_final_pass_skipped",
                    data = mapOf(
                        "root_input_id" to action.rootInputId,
                        "action_id" to action.id,
                        "reason" to "no_evidence",
                    )
                )
            )
            return action
        }
        instrumentation.emit(
            AgentEvent(
                type = "task_workspace_final_pass",
                data = mapOf(
                    "root_input_id" to action.rootInputId,
                    "root_input_received_at_ms" to action.rootInputReceivedAtMs,
                    "action_id" to action.id,
                    "workspace_confidence" to finalPassInput.workspaceConfidence,
                    "section_count" to finalPassInput.sectionCount,
                    "evidence_count" to finalPassInput.evidenceCount,
                    "compilation_preview" to TextSecurity.preview(finalPassInput.compilation, 220)
                )
            )
        )
        if (finalPassInput.workspaceConfidence < config.memory.taskWorkspace.finalPassMinWorkspaceConfidence) {
            instrumentation.emit(
                AgentEvent(
                    type = "task_workspace_final_pass_skipped",
                    data = mapOf(
                        "root_input_id" to action.rootInputId,
                        "root_input_received_at_ms" to action.rootInputReceivedAtMs,
                        "action_id" to action.id,
                        "reason" to "workspace_confidence_gate",
                        "workspace_confidence" to finalPassInput.workspaceConfidence,
                        "min_workspace_confidence" to config.memory.taskWorkspace.finalPassMinWorkspaceConfidence
                    )
                )
            )
            return action
        }
        val finalizerResult = taskWorkspaceFinalizer.finalize(
            TaskWorkspaceFinalizerRequest(
                action = action,
                workspaceCompilation = finalPassInput.compilation,
                workspaceConfidence = finalPassInput.workspaceConfidence,
                recentDialogue = dialogueFor(sessionId).takeLast(12)
            )
        ) ?: run {
            instrumentation.emit(
                AgentEvent(
                    type = "task_workspace_final_pass_skipped",
                    data = mapOf(
                        "root_input_id" to action.rootInputId,
                        "root_input_received_at_ms" to action.rootInputReceivedAtMs,
                        "action_id" to action.id,
                        "reason" to "finalizer_unavailable_or_parse"
                    )
                )
            )
            return action
        }
        if (finalizerResult.confidence < config.memory.taskWorkspace.finalPassMinModelConfidence) {
            instrumentation.emit(
                AgentEvent(
                    type = "task_workspace_final_pass_skipped",
                    data = mapOf(
                        "root_input_id" to action.rootInputId,
                        "root_input_received_at_ms" to action.rootInputReceivedAtMs,
                        "action_id" to action.id,
                        "reason" to "model_confidence_gate",
                        "model_confidence" to finalizerResult.confidence,
                        "min_model_confidence" to config.memory.taskWorkspace.finalPassMinModelConfidence
                    )
                )
            )
            return action
        }
        val rewrittenPayload = TextSecurity.clamp(finalizerResult.rewrittenPayload, config.planner.maxActionPayloadChars)
        if (rewrittenPayload.isBlank() || rewrittenPayload == action.payload) {
            instrumentation.emit(
                AgentEvent(
                    type = "task_workspace_final_pass_skipped",
                    data = mapOf(
                        "root_input_id" to action.rootInputId,
                        "root_input_received_at_ms" to action.rootInputReceivedAtMs,
                        "action_id" to action.id,
                        "reason" to "rewrite_empty_or_unchanged"
                    )
                )
            )
            return action
        }
        instrumentation.emit(
            AgentEvent(
                type = "task_workspace_final_pass_applied",
                data = mapOf(
                    "root_input_id" to action.rootInputId,
                    "root_input_received_at_ms" to action.rootInputReceivedAtMs,
                    "action_id" to action.id,
                    "workspace_confidence" to finalPassInput.workspaceConfidence,
                    "model_confidence" to finalizerResult.confidence,
                    "rewrite_reason" to finalizerResult.reason,
                    "payload_before_preview" to TextSecurity.preview(action.payload, 180),
                    "payload_after_preview" to TextSecurity.preview(rewrittenPayload, 180)
                )
            )
        )
        return action.copy(payload = rewrittenPayload)
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
        val longTermRecall = memory.recall(trigger, shortTermSummary, recentDialogue, episodicCues)
        val reflectionLessons = memory.recallReflectionLessons(trigger, recentDialogue)
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
            reflectionLessons = reflectionLessons,
            episodicRecall = episodicRecall,
            taskWorkspaceSummary = taskWorkspaceSummary,
            sessionWorkspaceDigest = sessionWorkspaceDigest,
            evidenceHints = evidenceHints,
            deliberation = deliberation.snapshot(),
            metaGuidance = deliberation.guidance(),
            availableActions = availableActions,
            dispatchableActions = dispatchableActions,
            actionDefinitions = actionDefinitions,
            conversationContext = conversationContext
        )
    }

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

    private fun superegoContext(sessionId: String = ConversationContext.DEFAULT_SESSION_ID): SuperegoContext {
        val shortTermSummary = memory.currentShortTermSummary()
        return SuperegoContext(
            recentDialogue = dialogueFor(sessionId).takeLast(12),
            shortTermContextSummary = shortTermSummary
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

    private fun maybeRunTerminalAnswerMemoryAssessment(
        action: PendingAction,
        outcome: ActionOutcome,
        sessionId: String = ConversationContext.DEFAULT_SESSION_ID,
    ) {
        if (action.type != ActionType.ANSWER) return
        maybeRunLongTermMemoryAssessment(
            trigger = "post_terminal_answer",
            force = config.memory.longTermMemoryForceAssessOnTerminalAnswer,
            latestActionType = action.type,
            latestActionOutcome = outcome.plannerSignal,
            sessionId = sessionId
        )
    }

    private fun emitDeliberationState(taskType: String, state: DeliberationState) {
        instrumentation.emit(
            AgentEvent(
                type = "deliberation_state",
                data = mapOf(
                    "task_type" to taskType,
                    "step_index" to state.stepIndex,
                    "decision_pressure" to state.decisionPressure,
                    "stale_streak" to state.staleStreak,
                    "progress_score" to state.progressScore,
                    "denial_count" to state.denialCount,
                    "steps_since_new_evidence" to state.stepsSinceNewEvidence,
                    "repeat_signature_hits" to state.repeatSignatureHits,
                    "noop_streak" to state.noopStreak,
                    "model_error_streak" to state.modelErrorStreak
                )
            )
        )
    }

    private fun emitQueueSnapshot(source: String) {
        instrumentation.emit(
            AgentEvents.queueSnapshot(source = source, queues = scheduler.queueState())
        )
    }

    private fun emitHeapSnapshot() {
        val rt = Runtime.getRuntime()
        val usedBytes = rt.totalMemory() - rt.freeMemory()
        val maxBytes = rt.maxMemory()
        val memStats = memoryStore.stats()
        val queueSnap = scheduler.queueSnapshot()
        val modules = buildMap<String, Map<String, Any?>> {
            put("memory_store", mapOf(
                "label" to "Short-term memory",
                "item_count" to memStats.recentTurns,
                "chars_or_bytes" to memStats.totalChars.toLong(),
                "unit" to "chars",
            ))
            put("task_workspaces", mapOf(
                "label" to "Task workspaces",
                "item_count" to taskWorkspaceStore.activeTaskCount(),
                "chars_or_bytes" to 0L,
                "unit" to "items",
            ))
            put("attention_queues", mapOf(
                "label" to "Attention queues",
                "item_count" to (queueSnap.pendingInputCount + queueSnap.pendingThoughtCount + queueSnap.pendingActionCount),
                "chars_or_bytes" to 0L,
                "unit" to "items",
            ))
        }
        instrumentation.emit(
            AgentEvents.heapSnapshot(
                jvmTotalBytes = rt.totalMemory(),
                jvmFreeBytes = rt.freeMemory(),
                jvmMaxBytes = maxBytes,
                jvmUsedBytes = usedBytes,
                jvmUsedPercent = if (maxBytes > 0) (usedBytes.toDouble() / maxBytes) * 100.0 else 0.0,
                moduleEstimates = modules,
            )
        )
    }

    private fun cleanupResolvedInputAfterAnswer(action: PendingAction) {
        val rootInputId = action.rootInputId ?: return
        val sessionId = resolveSessionId(action.conversationContext)
        val scope = inputScope(rootInputId, action.conversationContext)
        planner.resetForInput(rootInputId)
        deliberation.clearForInput(rootInputId, sessionId)
        externalActionSignatureHitsByInput.remove(scope)
        emitTaskWorkspaceTelemetry(
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
        emitQueueSnapshot("input_resolution_cleanup")
    }

    private fun emitTaskWorkspaceTelemetry(
        rootInputId: String?,
        rootInputReceivedAtMs: Long?,
        updateType: String,
    ) {
        val head = taskWorkspaceStore.debugHead(rootInputId) ?: return
        instrumentation.emit(
            AgentEvent(
                type = "task_workspace_head",
                data = mapOf(
                    "root_input_id" to head.rootInputId,
                    "root_input_received_at_ms" to head.rootInputReceivedAtMs,
                    "update_type" to updateType,
                    "version" to head.version,
                    "updated_at_ms" to head.updatedAtMs,
                    "goal_preview" to TextSecurity.preview(head.goal, 140),
                    "section_count" to head.sectionCount,
                    "evidence_count" to head.evidenceCount,
                    "workspace_confidence" to head.workspaceConfidence,
                    "bytes_estimate" to head.bytesEstimate
                )
            )
        )
        if (!config.memory.taskWorkspace.debugCaptureEnabled) return
        val snapshot = taskWorkspaceStore.debugSnapshot(rootInputId) ?: return
        instrumentation.emit(
            AgentEvent(
                type = "task_workspace_debug_snapshot",
                data = mapOf(
                    "root_input_id" to snapshot.head.rootInputId,
                    "root_input_received_at_ms" to snapshot.head.rootInputReceivedAtMs,
                    "update_type" to updateType,
                    "version" to snapshot.head.version,
                    "updated_at_ms" to snapshot.head.updatedAtMs,
                    "goal" to snapshot.head.goal,
                    "section_count" to snapshot.head.sectionCount,
                    "evidence_count" to snapshot.head.evidenceCount,
                    "workspace_confidence" to snapshot.head.workspaceConfidence,
                    "bytes_estimate" to snapshot.head.bytesEstimate,
                    "sections" to snapshot.sections.map { section ->
                        mapOf(
                            "title" to section.title,
                            "summary" to section.summary,
                            "content" to section.content,
                            "source" to section.source
                        )
                    },
                    "evidence" to snapshot.evidence
                )
            )
        )
    }

    private fun recordQueueSaturation(queueType: String, capacity: Int, reason: String) {
        val pending = scheduler.queueSnapshot().let { snapshot ->
            when (queueType) {
                "input" -> snapshot.pendingInputCount
                "thought" -> snapshot.pendingThoughtCount
                "action" -> snapshot.pendingActionCount
                else -> 0
            }
        }
        instrumentation.emit(
            AgentEvents.queueSaturation(
                queueType = queueType,
                pending = pending,
                capacity = capacity,
                reason = reason
            )
        )
    }

    private suspend fun executeActionSafely(action: PendingAction): ActionOutcome? {
        return try {
            motorCortex.execute(action, config.searchResultCount)
        } catch (ex: Exception) {
            deliberation.markEvidenceFailure(action)
            logger.warn(ex) { "Action execution failed for action_id=${action.id} type=${action.type}." }
            instrumentation.emit(AgentEvents.warning("Action execution failed; action dropped."))
            null
        }
    }

    private fun taskType(task: LoopTask): String =
        when (task) {
            is LoopTask.ProcessInput -> "input"
            is LoopTask.ProcessThought -> "thought"
            is LoopTask.PerformAction -> "action"
        }

    private fun taskRootInputId(task: LoopTask): String? =
        when (task) {
            is LoopTask.ProcessInput -> task.item.rootInputId
            is LoopTask.ProcessThought -> task.item.rootInputId
            is LoopTask.PerformAction -> task.item.rootInputId
        }

    private fun taskRootInputReceivedAtMs(task: LoopTask): Long? =
        when (task) {
            is LoopTask.ProcessInput -> task.item.receivedAtMs
            is LoopTask.ProcessThought -> task.item.rootInputReceivedAtMs
            is LoopTask.PerformAction -> task.item.rootInputReceivedAtMs
        }

    private fun taskConversationContext(task: LoopTask): ConversationContext =
        when (task) {
            is LoopTask.ProcessInput -> task.item.conversationContext
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
        memory.journal(EpisodicEventType.PLANNER_DECISION, summary, actionType = actionType)
    }

    private data class InputScope(
        val rootInputId: String?,
        val sessionId: String,
    )

    private companion object {
        const val PLAN_ID_LENGTH: Int = 8
        const val FOLLOW_UP_SIGNAL_MAX_CHARS: Int = 420
        const val MAX_EVIDENCE_HINT_SIGNALS: Int = 3
        const val MAX_EVIDENCE_HINT_CHARS: Int = 420
        const val JOURNAL_SUMMARY_PREVIEW_CHARS: Int = 160
        const val MAX_TRACKED_SESSIONS: Int = 32
        const val REDUNDANCY_SIGNAL_MIN_HITS: Int = 2
        const val HEAP_SNAPSHOT_INTERVAL: Int = 5
    }
}
