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
    private val onActionDenied: () -> Unit = {},
    private val instrumentation: AgentInstrumentation = NoopAgentInstrumentation,
) {
    private val scheduler = AttentionScheduler(config)
    private val dialogue = ArrayDeque<DialogueTurn>()

    fun runInteractive() {
        logger.info { "Ego loop started. Type 'exit' to quit." }
        instrumentation.emit(AgentEvents.loopStatus(status = "running", message = "Interactive loop started"))
        emitQueueSnapshot("loop_start")
        while (true) {
            print("you> ")
            val rawInput = readLine()
            if (rawInput == null) {
                instrumentation.emit(AgentEvents.loopStatus(status = "stopped", message = "stdin_closed"))
                break
            }
            if (rawInput.equals("exit", ignoreCase = true)) {
                logger.info { "Stopping Ego loop." }
                instrumentation.emit(AgentEvents.loopStatus(status = "stopped", message = "exit_command"))
                break
            }
            if (rawInput.isBlank()) {
                continue
            }

            val sanitized = TextSecurity.clamp(rawInput.trim(), config.maxInputChars)
            if (!scheduler.enqueueInput(sanitized)) {
                logger.warn { "Input queue full; dropping input." }
                instrumentation.emit(AgentEvents.warning("Input queue full; dropping input."))
                continue
            }
            val queuedInput = scheduler.queueState().inputs.lastOrNull()
            if (queuedInput != null) {
                instrumentation.emit(AgentEvents.inputQueued(queuedInput))
            }
            emitQueueSnapshot("input_enqueued")
            runLoop()
        }
    }

    private fun runLoop() {
        var steps = 0
        while (steps < config.maxLoopStepsPerInput) {
            val task = scheduler.nextTask() ?: break
            steps += 1
            instrumentation.emit(AgentEvents.loopStep(step = steps, taskType = taskType(task)))
            emitQueueSnapshot("task_dequeued")
            when (task) {
                is LoopTask.ProcessInput -> processInput(task.item)
                is LoopTask.ProcessThought -> processThought(task.item)
                is LoopTask.PerformAction -> processAction(task.item)
            }
        }

        if (steps >= config.maxLoopStepsPerInput && scheduler.hasPendingWork()) {
            logger.warn { "Reached loop step limit with pending work still in queues." }
            instrumentation.emit(AgentEvents.warning("Loop step limit reached with pending work."))
            emitQueueSnapshot("step_limit_reached")
        }

        // For debugging purpose, so we can see the flow in real time
        try {
            Thread.sleep(1_000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun processInput(input: PendingInput) {
        instrumentation.emit(AgentEvents.inputProcessing(input))
        dialogue.addLast(DialogueTurn(DialogueRole.USER, input.content))
        trimDialogue()
        val decision = planner.decide(
            trigger = EgoTrigger.IncomingInput(input),
            snapshot = scheduler.snapshot(dialogue.toList())
        )
        applyDecision(decision, nextPassCount = 0)
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
            return
        }
        instrumentation.emit(AgentEvents.thoughtProcessing(thought))
        val decision = planner.decide(
            trigger = EgoTrigger.PendingThoughtInput(thought),
            snapshot = scheduler.snapshot(dialogue.toList())
        )
        applyDecision(decision, nextPassCount = thought.passes + 1)
    }

    private fun processAction(action: PendingAction) {
        instrumentation.emit(AgentEvents.actionReviewRequested(action))
        val gateDecision = superego.review(action, scheduler.snapshot(dialogue.toList()))
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
                "Action denied by superego: ${gateDecision.reason}",
                config.maxThoughtChars
            )
            val queued = scheduler.enqueueThought(
                content = denialThought,
                urgency = action.urgency,
                passes = action.attempts + 1
            )
            instrumentation.emit(AgentEvents.actionDenied(action, gateDecision.reason))
            if (!queued) {
                instrumentation.emit(AgentEvents.warning("Failed to enqueue denial thought."))
            }
            emitQueueSnapshot("action_denied")
            return
        }

        val outcome = motorCortex.execute(action, config.searchResultCount)
        instrumentation.emit(AgentEvents.actionExecuted(action, outcome.statusSummary))
        if (outcome.assistantOutput != null) {
            dialogue.addLast(DialogueTurn(DialogueRole.ASSISTANT, outcome.assistantOutput))
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
            }
            emitQueueSnapshot("web_search_followup")
        }
    }

    private fun applyDecision(decision: EgoDecision, nextPassCount: Int) {
        when (decision) {
            is EgoDecision.EnqueueThought -> {
                val queued = scheduler.enqueueThought(
                    content = decision.content,
                    urgency = decision.urgency,
                    passes = nextPassCount
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
                emitQueueSnapshot("decision_thought")
            }

            is EgoDecision.ProposeAction -> {
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
                    passes = nextPassCount
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
                emitQueueSnapshot("decision_noop")
            }
        }
    }

    private fun trimDialogue() {
        while (dialogue.size > 20) {
            dialogue.removeFirst()
        }
    }

    private fun emitQueueSnapshot(source: String) {
        instrumentation.emit(
            AgentEvents.queueSnapshot(
                source = source,
                queues = scheduler.queueState()
            )
        )
    }

    private fun taskType(task: LoopTask): String =
        when (task) {
            is LoopTask.ProcessInput -> "input"
            is LoopTask.ProcessThought -> "thought"
            is LoopTask.PerformAction -> "action"
        }
}
