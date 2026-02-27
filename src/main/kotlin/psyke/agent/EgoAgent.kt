package psyke.agent

import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class EgoAgent(
    private val planner: EgoPlanner,
    private val superego: SuperegoGatekeeper,
    private val motorCortex: MotorCortex,
    private val config: AgentConfig,
    private val onActionDenied: () -> Unit = {},
) {
    private val scheduler = AttentionScheduler(config)
    private val dialogue = ArrayDeque<DialogueTurn>()

    fun runInteractive() {
        logger.info { "Ego loop started. Type 'exit' to quit." }
        while (true) {
            print("you> ")
            val rawInput = readLine() ?: break
            if (rawInput.equals("exit", ignoreCase = true)) {
                logger.info { "Stopping Ego loop." }
                break
            }
            if (rawInput.isBlank()) {
                continue
            }

            val sanitized = TextSecurity.clamp(rawInput.trim(), config.maxInputChars)
            if (!scheduler.enqueueInput(sanitized)) {
                logger.warn { "Input queue full; dropping input." }
                continue
            }
            logger.trace { "ego.input.queued len=${sanitized.length}" }
            runLoop()
        }
    }

    private fun runLoop() {
        var steps = 0
        while (steps < config.maxLoopStepsPerInput) {
            val task = scheduler.nextTask() ?: break
            steps += 1
            logger.trace {
                "ego.loop.step index=$steps task=${task.javaClass.simpleName}"
            }
            when (task) {
                is LoopTask.ProcessInput -> processInput(task.item)
                is LoopTask.ProcessThought -> processThought(task.item)
                is LoopTask.PerformAction -> processAction(task.item)
            }
        }

        if (steps >= config.maxLoopStepsPerInput && scheduler.hasPendingWork()) {
            logger.warn { "Reached loop step limit with pending work still in queues." }
        }
    }

    private fun processInput(input: PendingInput) {
        logger.trace { "ego.input.process id=${input.id} len=${input.content.length}" }
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
            return
        }
        logger.trace {
            "ego.thought.process id=${thought.id} urgency=${thought.urgency.name.lowercase()} pass=${thought.passes} len=${thought.content.length}"
        }
        val decision = planner.decide(
            trigger = EgoTrigger.PendingThoughtInput(thought),
            snapshot = scheduler.snapshot(dialogue.toList())
        )
        applyDecision(decision, nextPassCount = thought.passes + 1)
    }

    private fun processAction(action: PendingAction) {
        logger.trace {
            "ego.action.review id=${action.id} type=${action.type.name.lowercase()} urgency=${action.urgency.name.lowercase()} attempts=${action.attempts} summary='${TextSecurity.preview(action.summary, 80)}'"
        }
        val gateDecision = superego.review(action, scheduler.snapshot(dialogue.toList()))
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
            logger.trace {
                "ego.action.denied id=${action.id} queued_thought=$queued reason='${TextSecurity.preview(gateDecision.reason, 80)}'"
            }
            return
        }

        val outcome = motorCortex.execute(action, config.searchResultCount)
        logger.trace {
            "ego.action.executed id=${action.id} type=${action.type.name.lowercase()} outcome_len=${outcome.statusSummary.length}"
        }
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
            logger.trace { "ego.action.followup_thought id=${action.id} queued=$queued" }
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
                logger.trace {
                    "ego.decision.enqueue_thought queued=$queued urgency=${decision.urgency.name.lowercase()} len=${decision.content.length}"
                }
            }

            is EgoDecision.ProposeAction -> {
                val queued = scheduler.enqueueAction(
                    type = decision.actionType,
                    payload = decision.payload,
                    summary = decision.summary,
                    urgency = decision.urgency,
                    attempts = nextPassCount
                )
                logger.trace {
                    "ego.decision.proposed_action queued=$queued type=${decision.actionType.name.lowercase()} urgency=${decision.urgency.name.lowercase()} payload_len=${decision.payload.length} summary='${TextSecurity.preview(decision.summary, 80)}'"
                }
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
                logger.trace {
                    "ego.decision.noop queued_thought=$queued reason='${TextSecurity.preview(decision.reason, 80)}'"
                }
            }
        }
    }

    private fun trimDialogue() {
        while (dialogue.size > 20) {
            dialogue.removeFirst()
        }
    }
}
