package psyke.agent.ego

import mu.KotlinLogging
import psyke.agent.core.*
import psyke.agent.cortex.motor.MotorCortex
import psyke.agent.cortex.sensory.SensoryCortex
import psyke.agent.cortex.sensory.SensorySignal
import psyke.agent.memory.longterm.Hippocampus
import psyke.agent.memory.longterm.LongTermMemoryAdvisor
import psyke.agent.memory.longterm.NoopHippocampus
import psyke.agent.memory.longterm.NoopLongTermMemoryAdvisor
import psyke.agent.memory.shortterm.MemoryStore
import psyke.agent.support.TextSecurity
import psyke.agent.superego.Superego
import psyke.instrumentation.AgentEvent
import psyke.instrumentation.AgentEvents
import psyke.instrumentation.AgentInstrumentation
import psyke.instrumentation.NoopAgentInstrumentation

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
    private val instrumentation: AgentInstrumentation = NoopAgentInstrumentation,
) {
    interface Planner {
        fun decide(trigger: EgoTrigger, context: PlannerContext): EgoDecision
    }

    private val scheduler = AttentionScheduler(config)
    private val dialogue = ArrayDeque<DialogueTurn>()
    private val deliberation = DeliberationEngine(config, instrumentation, metaReasoner)
    private val memory = MemoryCoordinator(hippocampus, longTermMemoryAdvisor, config, instrumentation, memoryStore)

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
        while (steps < config.planner.maxLoopStepsPerInput) {
            val task = scheduler.nextTask() ?: break
            steps += 1
            instrumentation.emit(AgentEvents.loopStep(step = steps, taskType = taskType(task)))
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
            deliberation.maybeForceTerminalAnswer(scheduler)
            maybeRunLongTermMemoryAssessment(trigger = "interval")
            emitQueueSnapshot("task_processed")
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
        memory.remember(userTurn)
        trimDialogue()
        val trigger = EgoTrigger.IncomingInput(input)
        val context = plannerContext(trigger)
        val assessment = deliberation.maybeAssessAndUpdateGuidance(trigger, context)
        val decision = planner.decide(
            trigger = trigger,
            context = context.copy(metaGuidance = deliberation.guidance())
        )
        val finalDecision = deliberation.maybeApplyPressureOverride(decision, assessment)
        deliberation.onPlannerDecision(finalDecision)
        applyDecision(
            finalDecision,
            nextPassCount = 0,
            originThought = null,
            rootInputEnqueuedAtMs = input.enqueuedAtMs
        )
    }

    private fun processThought(thought: PendingThought) {
        if (thought.passes >= config.planner.maxThoughtPasses) {
            logger.info { "Dropping thought ${thought.id} due to max thought passes." }
            instrumentation.emit(AgentEvents.thoughtDropped(thought = thought, reason = "max_passes_reached"))
            if (thought.allowFallbackExplanation) {
                enqueueFallbackExplanation(thought)
            }
            return
        }
        instrumentation.emit(AgentEvents.thoughtProcessing(thought))
        val trigger = EgoTrigger.PendingThoughtInput(thought)
        val context = plannerContext(trigger)
        val assessment = deliberation.maybeAssessAndUpdateGuidance(trigger, context)
        val decision = planner.decide(
            trigger = trigger,
            context = context.copy(metaGuidance = deliberation.guidance())
        )
        val finalDecision = deliberation.maybeApplyPressureOverride(decision, assessment)
        deliberation.onPlannerDecision(finalDecision)
        applyDecision(
            finalDecision,
            nextPassCount = thought.passes + 1,
            originThought = thought,
            rootInputEnqueuedAtMs = thought.rootInputEnqueuedAtMs
        )
    }

    private fun processAction(action: PendingAction) {
        instrumentation.emit(AgentEvents.actionReviewRequested(action))
        if (action.isFallbackExplanation) {
            instrumentation.emit(
                AgentEvents.actionReviewResult(actionId = action.id, allow = true, reason = "fallback_explanation_bypass")
            )
            val outcome = executeActionSafely(action) ?: return
            instrumentation.emit(AgentEvents.actionExecuted(action, outcome.statusSummary))
            if (action.type == ActionType.ANSWER) {
                val enqueuedAtMs = action.rootInputEnqueuedAtMs
                if (enqueuedAtMs != null) {
                    val latencyMs = (System.currentTimeMillis() - enqueuedAtMs).coerceAtLeast(0L)
                    instrumentation.emit(AgentEvents.responseLatencyRecorded(latencyMs = latencyMs, actionId = action.id))
                }
                deliberation.clearEvidenceForInput(action.rootInputEnqueuedAtMs)
            }
            if (outcome.assistantOutput != null) {
                val assistantTurn = DialogueTurn(DialogueRole.ASSISTANT, outcome.assistantOutput)
                dialogue.addLast(assistantTurn)
                memory.remember(assistantTurn)
                trimDialogue()
            }
            val observed = deliberation.observedEvidence(action, outcome)
            deliberation.recordEvidenceProgress(action, outcome, observed)
            deliberation.onActionExecuted(action, observed)
            return
        }
        val gateDecision = superego.review(action, superegoContext())
        instrumentation.emit(
            AgentEvents.actionReviewResult(actionId = action.id, allow = gateDecision.allow, reason = gateDecision.reason)
        )
        if (!gateDecision.allow) {
            deliberation.onActionDenied()
            val denialThought = TextSecurity.clamp(
                "Action denied by superego (${gateDecision.reason}). " +
                    "Try a different safe action than the denied one. " +
                    "If no safe alternative exists, prepare a concise explanation for the interlocutor.",
                config.planner.maxThoughtChars
            )
            val queued = scheduler.enqueueThought(
                content = denialThought,
                urgency = action.urgency,
                passes = action.attempts + 1,
                rootInputEnqueuedAtMs = action.rootInputEnqueuedAtMs,
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
        val observed = deliberation.observedEvidence(action, outcome)
        deliberation.recordEvidenceProgress(action, outcome, observed)
        deliberation.onActionExecuted(action, observed)
        if (action.type == ActionType.ANSWER) {
            val enqueuedAtMs = action.rootInputEnqueuedAtMs
            if (enqueuedAtMs != null) {
                val latencyMs = (System.currentTimeMillis() - enqueuedAtMs).coerceAtLeast(0L)
                instrumentation.emit(AgentEvents.responseLatencyRecorded(latencyMs = latencyMs, actionId = action.id))
            }
            deliberation.clearEvidenceForInput(action.rootInputEnqueuedAtMs)
        }
        if (outcome.assistantOutput != null) {
            val assistantTurn = DialogueTurn(DialogueRole.ASSISTANT, outcome.assistantOutput)
            dialogue.addLast(assistantTurn)
            memory.remember(assistantTurn)
            trimDialogue()
        }
        maybeRunLongTermMemoryAssessment(
            trigger = "post_allowed_action",
            force = config.memory.longTermMemoryForceAssessOnAllowedAction,
            latestActionType = action.type,
            latestActionOutcome = outcome.plannerSignal
        )

        if (action.type.requiresFollowUpThought()) {
            val followUpThought = TextSecurity.clamp(
                "${action.type.followUpPrefix()} ${outcome.plannerSignal}. Decide if an answer should be sent.",
                config.planner.maxThoughtChars
            )
            val queued = scheduler.enqueueThought(
                content = followUpThought,
                urgency = action.urgency,
                passes = action.attempts,
                rootInputEnqueuedAtMs = action.rootInputEnqueuedAtMs,
                allowFallbackExplanation = true
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
    }

    private fun applyDecision(
        decision: EgoDecision,
        nextPassCount: Int,
        originThought: PendingThought?,
        rootInputEnqueuedAtMs: Long?,
    ) {
        when (decision) {
            is EgoDecision.EnqueueThought -> {
                val queued = scheduler.enqueueThought(
                    content = decision.content,
                    urgency = decision.urgency,
                    passes = nextPassCount,
                    longTermMemoryRecallQuery = decision.longTermMemoryRecallQuery,
                    rootInputEnqueuedAtMs = rootInputEnqueuedAtMs,
                    deniedActionType = originThought?.deniedActionType,
                    deniedActionPayload = originThought?.deniedActionPayload,
                    denialReason = originThought?.denialReason,
                    allowFallbackExplanation = originThought?.allowFallbackExplanation ?: false
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
                if (originThought != null && isRepeatOfDeniedAction(originThought, decision)) {
                    instrumentation.emit(AgentEvents.warning("Planner repeated a denied action; requesting an alternative."))
                    deliberation.onRepeatedDeniedAction()
                    val retryThought = TextSecurity.clamp(
                        "Previous proposed action repeats a denied action. Pick a materially different safe action.",
                        config.planner.maxThoughtChars
                    )
                    val queuedRetry = scheduler.enqueueThought(
                        content = retryThought,
                        urgency = originThought.urgency,
                        passes = nextPassCount,
                        rootInputEnqueuedAtMs = rootInputEnqueuedAtMs,
                        deniedActionType = originThought.deniedActionType,
                        deniedActionPayload = originThought.deniedActionPayload,
                        denialReason = originThought.denialReason,
                        allowFallbackExplanation = originThought.allowFallbackExplanation
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
                val queued = scheduler.enqueueAction(
                    type = decision.actionType,
                    payload = decision.payload,
                    summary = decision.summary,
                    urgency = decision.urgency,
                    attempts = nextPassCount,
                    rootInputEnqueuedAtMs = rootInputEnqueuedAtMs
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

            is EgoDecision.EnqueuePlan -> {
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
                        rootInputEnqueuedAtMs = rootInputEnqueuedAtMs,
                        planContext = PlanContext(
                            planId = planId,
                            planGoal = decision.goal,
                            stepIndex = index,
                            totalSteps = decision.steps.size,
                            stepDescription = stepDescription,
                        ),
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
                val noopThought = TextSecurity.clamp("Noop decision: ${decision.reason}", config.planner.maxThoughtChars)
                val queued = scheduler.enqueueThought(
                    content = noopThought,
                    urgency = Urgency.LOW,
                    passes = nextPassCount,
                    rootInputEnqueuedAtMs = rootInputEnqueuedAtMs,
                    deniedActionType = originThought?.deniedActionType,
                    deniedActionPayload = originThought?.deniedActionPayload,
                    denialReason = originThought?.denialReason,
                    allowFallbackExplanation = originThought?.allowFallbackExplanation ?: false
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

    private fun enqueueFallbackExplanation(thought: PendingThought) {
        val evidence = deliberation.evidenceFor(thought.rootInputEnqueuedAtMs)
        val parseFailureLikely = thought.content.contains("non-parseable", ignoreCase = true)
        val (payload, summary) = when {
            !thought.denialReason.isNullOrBlank() -> {
                val message = "I cannot complete the previous action because it was blocked by policy " +
                    "(${thought.denialReason ?: "no reason provided"}). " +
                    "I could not find a safe alternative."
                message to "Explain inability to comply after policy denial."
            }
            evidence?.hadSuccessfulEvidence == true -> {
                val evidenceSignal = evidence.latestPlannerSignal.ifBlank {
                    "I gathered external evidence, but final synthesis failed."
                }
                val message = "I completed external verification, but repeated internal planner formatting/parsing failures " +
                    "prevented a clean final synthesis. Best-effort result from gathered evidence: $evidenceSignal"
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
            rootInputEnqueuedAtMs = thought.rootInputEnqueuedAtMs
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
                    rootInputEnqueuedAtMs = thought.rootInputEnqueuedAtMs
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
        if (decision.actionType != deniedType) return false
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
        val shortTermSummary = memory.currentShortTermSummary()
        val longTermRecall = memory.recall(trigger, shortTermSummary, dialogue.takeLast(12))
        return PlannerContext(
            recentDialogue = dialogue.takeLast(12),
            queue = scheduler.queueSnapshot(),
            shortTermContextSummary = shortTermSummary,
            longTermMemoryRecall = longTermRecall,
            deliberation = deliberation.snapshot(),
            metaGuidance = deliberation.guidance(),
            availableActions = motorCortex.availableActionTypes()
        )
    }

    private fun superegoContext(): SuperegoContext {
        val shortTermSummary = memory.currentShortTermSummary()
        return SuperegoContext(
            recentDialogue = dialogue.takeLast(12),
            shortTermContextSummary = shortTermSummary
        )
    }

    private fun maybeRunLongTermMemoryAssessment(
        trigger: String,
        force: Boolean = false,
        latestActionType: ActionType? = null,
        latestActionOutcome: String? = null,
    ) {
        memory.maybeAssessLongTermMemory(
            trigger = trigger,
            force = force,
            latestActionType = latestActionType,
            latestActionOutcome = latestActionOutcome,
            deliberation = deliberation.snapshot(),
            recentDialogue = dialogue.takeLast(12)
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

    private fun executeActionSafely(action: PendingAction): ActionOutcome? {
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

    private fun ActionType.requiresFollowUpThought(): Boolean =
        this == ActionType.WEB_SEARCH || this == ActionType.MCP_TIME || this == ActionType.MCP_FETCH

    private fun ActionType.followUpPrefix(): String =
        when (this) {
            ActionType.WEB_SEARCH -> "Web search completed."
            ActionType.MCP_TIME -> "MCP time lookup completed."
            ActionType.MCP_FETCH -> "MCP fetch completed."
            ActionType.ANSWER -> "Action completed."
        }

    private companion object {
        const val PLAN_ID_LENGTH: Int = 8
    }
}
