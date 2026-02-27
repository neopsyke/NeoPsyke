package psyke.agent

import mu.KotlinLogging
import psyke.instrumentation.AgentEvent
import psyke.instrumentation.AgentEvents
import psyke.instrumentation.AgentInstrumentation
import psyke.instrumentation.NoopAgentInstrumentation

private val logger = KotlinLogging.logger {}

class EgoAgent(
    private val planner: EgoPlanner,
    private val superego: SuperegoGatekeeper,
    private val motorCortex: MotorCortex,
    private val config: AgentConfig,
    private val sensoryCortex: SensoryCortex = SensoryCortex.stdin(config),
    private val memoryStore: MemoryStore = MemoryStore(config.maxMemoryChars),
    private val onActionDenied: () -> Unit = {},
    private val onQueueSaturation: (queueType: String, pending: Int, capacity: Int) -> Unit = { _, _, _ -> },
    private val instrumentation: AgentInstrumentation = NoopAgentInstrumentation,
) {
    private val scheduler = AttentionScheduler(config)
    private val dialogue = ArrayDeque<DialogueTurn>()

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
            try {
                when (task) {
                    is LoopTask.ProcessInput -> processInput(task.item)
                    is LoopTask.ProcessThought -> processThought(task.item)
                    is LoopTask.PerformAction -> processAction(task.item)
                }
            } catch (ex: Exception) {
                logger.warn(ex) { "Task processing failed for task_type=${taskType(task)}." }
                instrumentation.emit(
                    AgentEvents.warning(
                        "Task processing failed for ${taskType(task)}; continuing loop."
                    )
                )
            }
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
        val decision = planner.decide(
            trigger = EgoTrigger.IncomingInput(input),
            context = plannerContext()
        )
        applyDecision(decision, nextPassCount = 0, originThought = null)
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
        val decision = planner.decide(
            trigger = EgoTrigger.PendingThoughtInput(thought),
            context = plannerContext()
        )
        applyDecision(decision, nextPassCount = thought.passes + 1, originThought = thought)
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
        if (outcome.assistantOutput != null) {
            val assistantTurn = DialogueTurn(DialogueRole.ASSISTANT, outcome.assistantOutput)
            dialogue.addLast(assistantTurn)
            memoryStore.remember(assistantTurn)
            trimDialogue()
        }

        if (action.type == ActionType.WEB_SEARCH) {
            val followUpThought = TextSecurity.clamp(
                "Web search completed. ${outcome.statusSummary}. Decide if an answer should be sent.",
                config.maxThoughtChars
            )
            val queued = scheduler.enqueueThought(
                content = followUpThought,
                urgency = action.urgency,
                passes = action.attempts
            )
            if (!queued) {
                instrumentation.emit(AgentEvents.warning("Failed to enqueue follow-up thought after web search."))
                recordQueueSaturation(
                    queueType = "thought",
                    capacity = config.maxPendingThoughts,
                    reason = "enqueue_followup_thought_failed_full"
                )
            }
            emitQueueSnapshot("web_search_followup")
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

    private fun plannerContext(): PlannerContext {
        val memorySummary = currentMemorySummary()
        return PlannerContext(
            recentDialogue = dialogue.takeLast(12),
            queue = scheduler.queueSnapshot(),
            memorySummary = memorySummary
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
}
