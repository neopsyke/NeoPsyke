package psyke.agent

import mu.KotlinLogging
import psyke.instrumentation.AgentEvent
import psyke.instrumentation.AgentEvents
import psyke.instrumentation.AgentInstrumentation
import psyke.instrumentation.NoopAgentInstrumentation
import java.util.Locale

private val logger = KotlinLogging.logger {}

class EgoAgent(
    private val planner: EgoPlanner,
    private val superego: SuperegoGatekeeper,
    private val motorCortex: MotorCortex,
    private val config: AgentConfig,
    private val hippocampus: Hippocampus = NoopHippocampus,
    private val metaReasoner: MetaReasoner = NoopMetaReasoner,
    private val memoryConsolidationAdvisor: MemoryConsolidationAdvisor = NoopMemoryConsolidationAdvisor,
    private val sensoryCortex: SensoryCortex = SensoryCortex.stdin(config),
    private val memoryStore: MemoryStore = MemoryStore(config.maxMemoryChars),
    private val onActionDenied: () -> Unit = {},
    private val onQueueSaturation: (queueType: String, pending: Int, capacity: Int) -> Unit = { _, _, _ -> },
    private val onMemoryRecall: (hitCount: Int, latencyMs: Long, recallChars: Int, truncated: Boolean) -> Unit = { _, _, _, _ -> },
    private val onMemoryRecallFailure: (latencyMs: Long) -> Unit = {},
    private val onMemoryConsolidationAssessment: (saveRecommended: Boolean) -> Unit = {},
    private val onMemoryImprintResult: (saved: Boolean, summaryChars: Int, latencyMs: Long) -> Unit = { _, _, _ -> },
    private val instrumentation: AgentInstrumentation = NoopAgentInstrumentation,
) {
    private val scheduler = AttentionScheduler(config)
    private val dialogue = ArrayDeque<DialogueTurn>()
    private val deliberationMonitor = DeliberationProgressMonitor()
    private var latestMetaGuidance: String = ""
    private var lastMetaAssessmentStep: Int = 0
    private var forcedTerminalAnswerQueued: Boolean = false
    private var lastConsolidationStep: Int = 0
    private var latestPlannerMemorySummary: String = ""
    private var latestPlannerMemoryRecall: String = ""
    private val recentImprintFingerprints = ArrayDeque<String>()

    fun runInteractive() {
        logger.info { "Ego loop started. Type 'exit' to quit." }
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
                    if (!scheduler.enqueueInput(signal.input.content, signal.input.priority)) {
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

    private fun runLoop() {
        var steps = 0
        while (steps < config.maxLoopStepsPerInput) {
            val task = scheduler.nextTask() ?: break
            steps += 1
            instrumentation.emit(AgentEvents.loopStep(step = steps, taskType = taskType(task)))
            val state = deliberationMonitor.startStep()
            emitDeliberationState(taskType(task), state)
            try {
                when (task) {
                    is LoopTask.ProcessInput -> processInput(task.item)
                    is LoopTask.ProcessThought -> processThought(task.item)
                    is LoopTask.PerformAction -> processAction(task.item)
                }
            } catch (ex: Exception) {
                logger.warn(ex) { "Task processing failed for task_type=${taskType(task)}." }
                deliberationMonitor.onTaskFailure()
                instrumentation.emit(
                    AgentEvents.warning(
                        "Task processing failed for ${taskType(task)}; continuing loop."
                    )
                )
            }
            maybeForceTerminalAnswer()
            maybeRunMemoryConsolidation(trigger = "interval")
            emitQueueSnapshot("task_processed")
        }

        if (steps >= config.maxLoopStepsPerInput && scheduler.hasPendingWork()) {
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
            latestMetaGuidance = ""
            lastMetaAssessmentStep = 0
            forcedTerminalAnswerQueued = false
            lastConsolidationStep = 0
            deliberationMonitor.reset()
        }

        if (config.loopDelayMs > 0) {
            try {
                Thread.sleep(config.loopDelayMs.toLong())
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun processInput(input: PendingInput) {
        instrumentation.emit(AgentEvents.inputProcessing(input))
        val userTurn = DialogueTurn(DialogueRole.USER, input.content)
        dialogue.addLast(userTurn)
        memoryStore.remember(userTurn)
        trimDialogue()
        val trigger = EgoTrigger.IncomingInput(input)
        val context = plannerContext(trigger)
        val assessment = maybeAssessDeliberation(trigger, context)
        if (assessment != null) {
            latestMetaGuidance = buildMetaGuidance(assessment)
        }
        val decision = planner.decide(
            trigger = trigger,
            context = context.copy(metaGuidance = latestMetaGuidance)
        )
        val finalDecision = maybeApplyMetaPressureOverride(decision, assessment)
        deliberationMonitor.onPlannerDecision(finalDecision)
        applyDecision(finalDecision, nextPassCount = 0, originThought = null)
    }

    private fun processThought(thought: PendingThought) {
        if (thought.passes >= config.maxThoughtPasses) {
            logger.info { "Dropping thought ${thought.id} due to max thought passes." }
            instrumentation.emit(
                AgentEvents.thoughtDropped(
                    thought = thought,
                    reason = "max_passes_reached"
                )
            )
            if (thought.allowFallbackExplanation) {
                enqueueFallbackExplanation(thought)
            }
            return
        }
        instrumentation.emit(AgentEvents.thoughtProcessing(thought))
        val trigger = EgoTrigger.PendingThoughtInput(thought)
        val context = plannerContext(trigger)
        val assessment = maybeAssessDeliberation(trigger, context)
        if (assessment != null) {
            latestMetaGuidance = buildMetaGuidance(assessment)
        }
        val decision = planner.decide(
            trigger = trigger,
            context = context.copy(metaGuidance = latestMetaGuidance)
        )
        val finalDecision = maybeApplyMetaPressureOverride(decision, assessment)
        deliberationMonitor.onPlannerDecision(finalDecision)
        applyDecision(finalDecision, nextPassCount = thought.passes + 1, originThought = thought)
    }

    private fun processAction(action: PendingAction) {
        instrumentation.emit(AgentEvents.actionReviewRequested(action))
        if (action.isFallbackExplanation) {
            instrumentation.emit(
                AgentEvents.actionReviewResult(
                    actionId = action.id,
                    allow = true,
                    reason = "fallback_explanation_bypass"
                )
            )
            val outcome = executeActionSafely(action) ?: return
            instrumentation.emit(AgentEvents.actionExecuted(action, outcome.statusSummary))
            if (outcome.assistantOutput != null) {
                val assistantTurn = DialogueTurn(DialogueRole.ASSISTANT, outcome.assistantOutput)
                dialogue.addLast(assistantTurn)
                memoryStore.remember(assistantTurn)
                trimDialogue()
            }
            deliberationMonitor.onActionExecuted(action)
            return
        }
        val gateDecision = superego.review(action, superegoContext())
        instrumentation.emit(
            AgentEvents.actionReviewResult(
                actionId = action.id,
                allow = gateDecision.allow,
                reason = gateDecision.reason
            )
        )
        if (!gateDecision.allow) {
            onActionDenied()
            deliberationMonitor.onActionDenied()
            val denialThought = TextSecurity.clamp(
                "Action denied by superego (${gateDecision.reason}). " +
                    "Try a different safe action than the denied one. " +
                    "If no safe alternative exists, prepare a concise explanation for the interlocutor.",
                config.maxThoughtChars
            )
            val queued = scheduler.enqueueThought(
                content = denialThought,
                urgency = action.urgency,
                passes = action.attempts + 1,
                deniedActionType = action.type,
                deniedActionPayload = TextSecurity.clamp(action.payload, 240),
                denialReason = gateDecision.reason,
                allowFallbackExplanation = true
            )
            instrumentation.emit(AgentEvents.actionDenied(action, gateDecision.reason))
            if (!queued) {
                instrumentation.emit(AgentEvents.warning("Failed to enqueue denial thought."))
                recordQueueSaturation(
                    queueType = "thought",
                    capacity = config.maxPendingThoughts,
                    reason = "enqueue_denial_thought_failed_full"
                )
            }
            emitQueueSnapshot("action_denied")
            return
        }

        val outcome = executeActionSafely(action) ?: return
        instrumentation.emit(AgentEvents.actionExecuted(action, outcome.statusSummary))
        deliberationMonitor.onActionExecuted(action)
        if (outcome.assistantOutput != null) {
            val assistantTurn = DialogueTurn(DialogueRole.ASSISTANT, outcome.assistantOutput)
            dialogue.addLast(assistantTurn)
            memoryStore.remember(assistantTurn)
            trimDialogue()
        }
        maybeRunMemoryConsolidation(
            trigger = "post_allowed_action",
            force = true,
            latestActionType = action.type,
            latestActionOutcome = outcome.statusSummary
        )

        if (action.type.requiresFollowUpThought()) {
            val followUpThought = TextSecurity.clamp(
                "${action.type.followUpPrefix()} ${outcome.statusSummary}. Decide if an answer should be sent.",
                config.maxThoughtChars
            )
            val queued = scheduler.enqueueThought(
                content = followUpThought,
                urgency = action.urgency,
                passes = action.attempts
            )
            if (!queued) {
                instrumentation.emit(AgentEvents.warning("Failed to enqueue follow-up thought after action."))
                recordQueueSaturation(
                    queueType = "thought",
                    capacity = config.maxPendingThoughts,
                    reason = "enqueue_followup_thought_failed_full"
                )
            }
            emitQueueSnapshot("action_followup")
        }
    }

    private fun applyDecision(decision: EgoDecision, nextPassCount: Int, originThought: PendingThought?) {
        when (decision) {
            is EgoDecision.EnqueueThought -> {
                val queued = scheduler.enqueueThought(
                    content = decision.content,
                    urgency = decision.urgency,
                    passes = nextPassCount,
                    deniedActionType = originThought?.deniedActionType,
                    deniedActionPayload = originThought?.deniedActionPayload,
                    denialReason = originThought?.denialReason,
                    allowFallbackExplanation = originThought?.allowFallbackExplanation ?: false
                )
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
                if (originThought != null && isRepeatOfDeniedAction(originThought, decision)) {
                    instrumentation.emit(
                        AgentEvents.warning("Planner repeated a denied action; requesting an alternative.")
                    )
                    deliberationMonitor.onRepeatedDeniedAction()
                    val retryThought = TextSecurity.clamp(
                        "Previous proposed action repeats a denied action. " +
                            "Pick a materially different safe action.",
                        config.maxThoughtChars
                    )
                    val queuedRetry = scheduler.enqueueThought(
                        content = retryThought,
                        urgency = originThought.urgency,
                        passes = nextPassCount,
                        deniedActionType = originThought.deniedActionType,
                        deniedActionPayload = originThought.deniedActionPayload,
                        denialReason = originThought.denialReason,
                        allowFallbackExplanation = originThought.allowFallbackExplanation
                    )
                    if (!queuedRetry) {
                        instrumentation.emit(
                            AgentEvents.warning("Failed to enqueue retry thought after repeated denied action.")
                        )
                        recordQueueSaturation(
                            queueType = "thought",
                            capacity = config.maxPendingThoughts,
                            reason = "enqueue_retry_thought_failed_full"
                        )
                    }
                    emitQueueSnapshot("repeated_denied_action_blocked")
                    return
                }
                val queued = scheduler.enqueueAction(
                    type = decision.actionType,
                    payload = decision.payload,
                    summary = decision.summary,
                    urgency = decision.urgency,
                    attempts = nextPassCount
                )
                instrumentation.emit(
                    AgentEvents.actionProposed(
                        actionType = decision.actionType.name.lowercase(),
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

            is EgoDecision.Noop -> {
                val noopThought = TextSecurity.clamp(
                    "Noop decision: ${decision.reason}",
                    config.maxThoughtChars
                )
                val queued = scheduler.enqueueThought(
                    content = noopThought,
                    urgency = Urgency.LOW,
                    passes = nextPassCount,
                    deniedActionType = originThought?.deniedActionType,
                    deniedActionPayload = originThought?.deniedActionPayload,
                    denialReason = originThought?.denialReason,
                    allowFallbackExplanation = originThought?.allowFallbackExplanation ?: false
                )
                instrumentation.emit(
                    AgentEvent(
                        type = "noop_recorded",
                        data = mapOf(
                            "queued_thought" to queued,
                            "reason" to decision.reason
                        )
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

    private fun enqueueFallbackExplanation(thought: PendingThought) {
        val payload = TextSecurity.clamp(
            "I cannot complete the previous action because it was blocked by policy " +
                "(${thought.denialReason ?: "no reason provided"}). " +
                "I could not find a safe alternative.",
            config.maxActionPayloadChars
        )
        val summary = "Explain inability to comply after policy denial."
        val queued = scheduler.enqueueAction(
            type = ActionType.ANSWER,
            payload = payload,
            summary = summary,
            urgency = thought.urgency,
            attempts = thought.passes,
            isFallbackExplanation = true
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
                    isFallbackExplanation = true
                )
            )
            emitQueueSnapshot("fallback_explanation_executed_immediate")
            return
        }
        emitQueueSnapshot("fallback_explanation_enqueued")
    }

    private fun isRepeatOfDeniedAction(thought: PendingThought, decision: EgoDecision.ProposeAction): Boolean {
        val deniedType = thought.deniedActionType ?: return false
        val deniedPayload = thought.deniedActionPayload ?: return false
        if (decision.actionType != deniedType) {
            return false
        }
        return normalizeActionPayload(decision.payload) == normalizeActionPayload(deniedPayload)
    }

    private fun normalizeActionPayload(payload: String): String =
        payload.lowercase().replace(Regex("\\s+"), " ").trim()

    private fun trimDialogue() {
        while (dialogue.size > 20) {
            dialogue.removeFirst()
        }
    }

    private fun plannerContext(trigger: EgoTrigger): PlannerContext {
        val memorySummary = currentMemorySummary()
        val memoryRecall = recallMemory(trigger, memorySummary)
        latestPlannerMemorySummary = memorySummary
        latestPlannerMemoryRecall = memoryRecall
        return PlannerContext(
            recentDialogue = dialogue.takeLast(12),
            queue = scheduler.queueSnapshot(),
            memorySummary = memorySummary,
            memoryRecall = memoryRecall,
            deliberation = deliberationMonitor.snapshot(),
            metaGuidance = latestMetaGuidance,
            availableActions = motorCortex.availableActionTypes()
        )
    }

    private fun superegoContext(): SuperegoContext {
        val memorySummary = currentMemorySummary()
        return SuperegoContext(
            recentDialogue = dialogue.takeLast(12),
            memorySummary = memorySummary
        )
    }

    private fun currentMemorySummary(): String {
        val memoryTokenBudget = minOf(
            config.maxMemoryPromptTokens,
            maxOf(64, config.maxPromptTokens / 3)
        )
        return memoryStore.summaryForPrompt(memoryTokenBudget)
    }

    private fun recallMemory(trigger: EgoTrigger, memorySummary: String): String {
        if (!hippocampus.enabled) {
            return ""
        }

        val cue = buildRecallCue(trigger).trim()
        if (cue.isBlank()) {
            return ""
        }

        val triggerLabel = when (trigger) {
            is EgoTrigger.IncomingInput -> "input"
            is EgoTrigger.PendingThoughtInput -> "thought"
        }
        instrumentation.emit(
            AgentEvents.memoryRecallStart(
                trigger = triggerLabel,
                provider = hippocampus.providerName,
                cuePreview = TextSecurity.preview(cue, 180)
            )
        )

        val startedAt = System.nanoTime()
        return try {
            val recall = hippocampus.recall(
                MemoryRecallQuery(
                    cue = cue,
                    recentDialogue = dialogue.takeLast(12),
                    memorySummary = memorySummary,
                    maxItems = config.memoryRecallMaxItems,
                    maxChars = config.memoryRecallMaxChars
                )
            )
            val latencyMs = (System.nanoTime() - startedAt) / 1_000_000L
            instrumentation.emit(
                AgentEvents.memoryRecallResult(
                    trigger = triggerLabel,
                    provider = recall.provider.ifBlank { hippocampus.providerName },
                    hitCount = recall.hitCount,
                    latencyMs = latencyMs,
                    recallChars = recall.text.length,
                    truncated = recall.truncated
                )
            )
            onMemoryRecall(
                recall.hitCount,
                latencyMs,
                recall.text.length,
                recall.truncated
            )
            recall.text
        } catch (ex: Exception) {
            val latencyMs = (System.nanoTime() - startedAt) / 1_000_000L
            logger.warn(ex) {
                "Memory recall failed for trigger=$triggerLabel cue='${TextSecurity.preview(cue, 120)}'."
            }
            instrumentation.emit(
                AgentEvents.memoryRecallFailure(
                    trigger = triggerLabel,
                    provider = hippocampus.providerName,
                    latencyMs = latencyMs,
                    reason = ex.message ?: "memory recall failed"
                )
            )
            onMemoryRecallFailure(latencyMs)
            ""
        }
    }

    private fun buildRecallCue(trigger: EgoTrigger): String {
        val triggerCue = when (trigger) {
            is EgoTrigger.IncomingInput -> trigger.input.content.trim()
            is EgoTrigger.PendingThoughtInput -> {
                val thought = trigger.thought
                buildString {
                    append(thought.content.trim())
                    if (thought.deniedActionType != null && !thought.deniedActionPayload.isNullOrBlank()) {
                        append("\ndenied_action_type=")
                        append(thought.deniedActionType.name.lowercase())
                        append("\ndenied_action_payload=")
                        append(thought.deniedActionPayload)
                    }
                }.trim()
            }
        }
        val recentUserTurn = dialogue
            .asReversed()
            .firstOrNull { it.role == DialogueRole.USER }
            ?.content
            ?.trim()
            .orEmpty()
        return listOfNotNull(
            triggerCue.ifBlank { null },
            recentUserTurn.takeIf { it.isNotBlank() && it != triggerCue }?.let { "latest_user_message: $it" }
        ).joinToString(separator = "\n")
    }

    private fun maybeAssessDeliberation(
        trigger: EgoTrigger,
        context: PlannerContext,
    ): MetaReasonerAssessment? {
        if (!metaReasoner.enabled) {
            return null
        }
        val state = deliberationMonitor.snapshot()
        val minStepReached = state.stepIndex >= config.deliberationPressureAssessmentMinStep
        if (!minStepReached) {
            return null
        }
        val stepsSinceLast = state.stepIndex - lastMetaAssessmentStep
        val dueByInterval = stepsSinceLast >= config.deliberationPressureAssessmentEverySteps
        val dueByPressure = state.decisionPressure >= config.deliberationPressureAssessmentThreshold &&
            stepsSinceLast >= config.metaReasonerCooldownSteps
        if (!dueByInterval && !dueByPressure) {
            return null
        }

        val assessment = try {
            metaReasoner.assess(trigger, context)
        } catch (ex: Exception) {
            logger.warn(ex) { "MetaReasoner assessment failed; continuing without override." }
            instrumentation.emit(
                AgentEvents.warning("MetaReasoner call failed; continuing default deliberation.")
            )
            return null
        }

        lastMetaAssessmentStep = state.stepIndex
        instrumentation.emit(
            AgentEvent(
                type = "meta_reasoner_assessment",
                data = mapOf(
                    "step_index" to state.stepIndex,
                    "decision_pressure" to state.decisionPressure,
                    "verdict" to assessment.verdict.name.lowercase(),
                    "confidence" to assessment.confidence,
                    "reason" to assessment.reason
                )
            )
        )
        return assessment
    }

    private fun buildMetaGuidance(assessment: MetaReasonerAssessment): String =
        when (assessment.verdict) {
            MetaReasonerVerdict.CONTINUE -> {
                "Continue current reasoning. Reason: ${assessment.reason}"
            }

            MetaReasonerVerdict.CONTINUE_WITH_CONSTRAINTS -> {
                "Reasoning is degrading. Avoid repeated thoughts/actions. Converge to one concrete next step in <=2 iterations. Reason: ${assessment.reason}"
            }

            MetaReasonerVerdict.FINALIZE_NOW -> {
                "Finalize now. Prefer action=answer with a concise best-effort response and explicit uncertainty if needed. Reason: ${assessment.reason}"
            }

            MetaReasonerVerdict.REQUEST_TOOL_THEN_FINALIZE -> {
                "At most one decisive external action is allowed, then finalize with action=answer immediately. Reason: ${assessment.reason}"
            }
        }

    private fun maybeApplyMetaPressureOverride(
        decision: EgoDecision,
        assessment: MetaReasonerAssessment?,
    ): EgoDecision {
        if (assessment == null) {
            return decision
        }
        val needsFinalizationPressure = assessment.verdict == MetaReasonerVerdict.FINALIZE_NOW ||
            assessment.verdict == MetaReasonerVerdict.REQUEST_TOOL_THEN_FINALIZE
        if (!needsFinalizationPressure) {
            return decision
        }
        if (decision is EgoDecision.ProposeAction) {
            return decision
        }
        val pressuredThought = TextSecurity.clamp(
            "Decision pressure is high. Stop looping and provide a concise best-effort final answer now. " +
                "If one decisive tool action is strictly necessary, do only one then answer.",
            config.maxThoughtChars
        )
        instrumentation.emit(
            AgentEvents.warning("MetaReasoner requested faster convergence; overriding non-action decision.")
        )
        return EgoDecision.EnqueueThought(
            urgency = Urgency.HIGH,
            content = pressuredThought
        )
    }

    private fun maybeForceTerminalAnswer() {
        if (forcedTerminalAnswerQueued) {
            return
        }
        val state = deliberationMonitor.snapshot()
        if (state.stepIndex < config.deliberationPressureAssessmentMinStep) {
            return
        }
        if (state.decisionPressure < 0.98 || state.staleStreak < 8) {
            return
        }
        val queued = scheduler.enqueueAction(
            type = ActionType.ANSWER,
            payload = TextSecurity.clamp(
                "I have reached diminishing returns in internal reasoning. " +
                    "Here is the best concise answer I can provide now with current evidence.",
                config.maxActionPayloadChars
            ),
            summary = "Forced terminal answer due to high decision pressure.",
            urgency = Urgency.HIGH
        )
        if (queued) {
            forcedTerminalAnswerQueued = true
            instrumentation.emit(
                AgentEvents.warning("Forced terminal answer queued due to persistent circular deliberation pressure.")
            )
            emitQueueSnapshot("forced_terminal_answer")
            latestMetaGuidance = "Finalize immediately due to persistent circular reasoning pressure."
        }
    }

    private fun maybeRunMemoryConsolidation(
        trigger: String,
        force: Boolean = false,
        latestActionType: ActionType? = null,
        latestActionOutcome: String? = null,
    ) {
        if (!hippocampus.enabled || !memoryConsolidationAdvisor.enabled) {
            return
        }
        val state = deliberationMonitor.snapshot()
        val shouldByInterval = !force &&
            state.stepIndex > 0 &&
            state.stepIndex % config.memoryConsolidationEverySteps == 0
        if (!force && !shouldByInterval) {
            return
        }
        if (!force) {
            val stepsSinceLast = state.stepIndex - lastConsolidationStep
            if (stepsSinceLast in 0 until config.memoryConsolidationCooldownSteps) {
                return
            }
        }
        if (state.stepIndex == lastConsolidationStep && lastConsolidationStep > 0) {
            return
        }

        val context = MemoryConsolidationContext(
            trigger = trigger,
            deliberation = state,
            recentDialogue = dialogue.takeLast(12),
            memorySummary = latestPlannerMemorySummary.ifBlank { currentMemorySummary() },
            memoryRecall = latestPlannerMemoryRecall,
            metaGuidance = latestMetaGuidance,
            latestActionType = latestActionType,
            latestActionOutcome = latestActionOutcome
        )

        val decision = try {
            memoryConsolidationAdvisor.assess(context)
        } catch (ex: Exception) {
            logger.warn(ex) { "Memory consolidation assessment failed." }
            instrumentation.emit(
                AgentEvents.warning("Memory consolidation advisor failed; skipping this cycle.")
            )
            return
        } finally {
            lastConsolidationStep = state.stepIndex
        }

        instrumentation.emit(
            AgentEvent(
                type = "memory_consolidation_assessment",
                data = mapOf(
                    "trigger" to trigger,
                    "step_index" to state.stepIndex,
                    "save" to decision.shouldSave,
                    "confidence" to decision.confidence,
                    "reason" to decision.reason,
                    "summary_preview" to TextSecurity.preview(decision.summary, 180)
                )
            )
        )
        onMemoryConsolidationAssessment(decision.shouldSave)

        if (!decision.shouldSave) {
            return
        }
        if (decision.confidence < config.memoryConsolidationMinConfidence) {
            instrumentation.emit(
                AgentEvents.warning(
                    "Memory consolidation skipped: confidence ${String.format(Locale.ROOT, "%.2f", decision.confidence)} below threshold."
                )
            )
            return
        }

        val fingerprint = normalizeActionPayload(decision.summary)
        if (recentImprintFingerprints.contains(fingerprint)) {
            instrumentation.emit(
                AgentEvents.warning("Memory consolidation skipped: duplicate imprint summary.")
            )
            return
        }

        val imprintStartedAt = System.nanoTime()
        val saved = try {
            hippocampus.imprint(
                MemoryImprint(
                    summary = decision.summary,
                    source = trigger,
                    confidence = decision.confidence,
                    tags = decision.tags
                )
            )
        } catch (ex: Exception) {
            logger.warn(ex) { "Hippocampus imprint failed." }
            false
        }
        val imprintLatencyMs = (System.nanoTime() - imprintStartedAt) / 1_000_000L

        instrumentation.emit(
            AgentEvent(
                type = "memory_imprint_result",
                data = mapOf(
                    "trigger" to trigger,
                    "saved" to saved,
                    "provider" to hippocampus.providerName,
                    "summary_chars" to decision.summary.length,
                    "latency_ms" to imprintLatencyMs,
                    "confidence" to decision.confidence,
                    "tags" to decision.tags
                )
            )
        )
        onMemoryImprintResult(saved, decision.summary.length, imprintLatencyMs)
        if (saved) {
            recentImprintFingerprints.addLast(fingerprint)
            while (recentImprintFingerprints.size > 24) {
                recentImprintFingerprints.removeFirst()
            }
        }
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
                    "noop_streak" to state.noopStreak
                )
            )
        )
    }

    private fun emitQueueSnapshot(source: String) {
        instrumentation.emit(
            AgentEvents.queueSnapshot(
                source = source,
                queues = scheduler.queueState()
            )
        )
    }

    private fun recordQueueSaturation(
        queueType: String,
        capacity: Int,
        reason: String,
    ) {
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
        onQueueSaturation(queueType, pending, capacity)
    }

    private fun executeActionSafely(action: PendingAction): ActionOutcome? {
        return try {
            motorCortex.execute(action, config.searchResultCount)
        } catch (ex: Exception) {
            logger.warn(ex) {
                "Action execution failed for action_id=${action.id} type=${action.type}."
            }
            instrumentation.emit(
                AgentEvents.warning("Action execution failed; action dropped.")
            )
            null
        }
    }

    private fun taskType(task: LoopTask): String =
        when (task) {
            is LoopTask.ProcessInput -> "input"
            is LoopTask.ProcessThought -> "thought"
            is LoopTask.PerformAction -> "action"
        }

    private fun ActionType.requiresFollowUpThought(): Boolean =
        this == ActionType.WEB_SEARCH || this == ActionType.MCP_TIME || this == ActionType.MCP_FETCH

    private fun ActionType.followUpPrefix(): String =
        when (this) {
            ActionType.WEB_SEARCH -> "Web search completed."
            ActionType.MCP_TIME -> "MCP time lookup completed."
            ActionType.MCP_FETCH -> "MCP fetch completed."
            ActionType.ANSWER -> "Action completed."
        }
}
