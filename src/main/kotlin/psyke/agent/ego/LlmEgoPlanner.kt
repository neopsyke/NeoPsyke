package psyke.agent.ego

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import psyke.agent.core.*
import psyke.agent.support.DenialReasonClassifier
import psyke.agent.support.PromptBudgetAllocator
import psyke.agent.support.RetryPolicy
import psyke.agent.support.TextSecurity
import psyke.instrumentation.AgentEvent
import psyke.instrumentation.AgentEvents
import psyke.instrumentation.AgentInstrumentation
import psyke.instrumentation.NoopAgentInstrumentation
import psyke.llm.ChatMessage
import psyke.llm.ChatCallMetadata
import psyke.llm.ChatModelClient
import psyke.llm.ChatRequestOptions
import psyke.llm.ChatRole
import java.util.Locale

private val logger = KotlinLogging.logger {}

class LlmEgoPlanner(
    private val modelClient: ChatModelClient,
    private val actionVerifierModelClient: ChatModelClient = modelClient,
    private val config: AgentConfig,
    private val instrumentation: AgentInstrumentation = NoopAgentInstrumentation,
    private val onPlannerNoop: () -> Unit = {},
    private val onPlannerOutputRepaired: () -> Unit = {},
) : Ego.Planner {
    private val actionVerifierParseFailureStreakByKey = mutableMapOf<ActionVerifierCircuitKey, Int>()
    private val actionVerifierBypassRemainingByKey = mutableMapOf<ActionVerifierCircuitKey, Int>()

    override fun decide(trigger: EgoTrigger, context: PlannerContext): EgoDecision {
        val triggerLabel = when (trigger) {
            is EgoTrigger.IncomingInput -> "input"
            is EgoTrigger.PendingThoughtInput -> "thought"
        }
        instrumentation.emit(
            AgentEvent(
                type = "planner_start",
                data = mapOf(
                    "trigger" to triggerLabel,
                    "pending_inputs" to context.queue.pendingInputCount,
                    "pending_thoughts" to context.queue.pendingThoughtCount,
                    "pending_actions" to context.queue.pendingActionCount
                )
            )
        )

        val messages = buildMessages(trigger, context)
        var response = null as psyke.llm.ChatCompletion?
        var lastError: Exception? = null
        val retryAttempts = RetryPolicy.boundedLlmRetryAttempts(config.planner.llmRetryAttempts)
        for (attempt in 1..retryAttempts) {
            try {
                response = modelClient.chat(
                    messages = messages,
                    options = ChatRequestOptions(
                        temperature = 0.2,
                        maxTokens = config.planner.maxCompletionTokens,
                        metadata = ChatCallMetadata(
                            actor = "ego",
                            callSite = triggerLabel
                        )
                    )
                )
                break
            } catch (ex: Exception) {
                lastError = ex
                if (attempt < retryAttempts) {
                    instrumentation.emit(
                        AgentEvents.warning(
                            "Planner call failed (attempt $attempt/$retryAttempts); retrying."
                        )
                    )
                }
            }
        }
        if (response == null) {
            logger.warn(lastError) { "Planner call failed for trigger=$triggerLabel." }
            instrumentation.emit(AgentEvents.warning("Planner call failed; falling back to noop."))
            val fallback = EgoDecision.Noop("Planner unavailable due to model error.")
            emitDecision(triggerLabel, fallback)
            return fallback
        }
        val resolvedResponse = response

        val decision = parseResponse(
            raw = resolvedResponse.content,
            availableActions = context.availableActions,
            emitParseWarning = false
        ) ?: run {
            instrumentation.emit(AgentEvents.warning("Planner response was non-parseable; requesting strict JSON retry."))
            val recovered = requestStrictJsonRetry(
                baseMessages = messages,
                callSite = triggerLabel
            )
            val repairedDecision = recovered?.let {
                parseResponse(
                    raw = it.content,
                    availableActions = context.availableActions,
                    emitParseWarning = true
                )
            }
            repairedDecision ?: run {
                instrumentation.emit(AgentEvents.warning("Planner response remained non-parseable after strict JSON retry."))
                EgoDecision.Noop("Planner produced non-parseable output.")
            }
        }
        val verifiedDecision = verifyActionDecision(
            trigger = trigger,
            context = context,
            decision = decision
        )
        emitDecision(triggerLabel, verifiedDecision)
        return verifiedDecision
    }

    private fun parseResponse(
        raw: String,
        availableActions: Set<ActionType>,
        emitParseWarning: Boolean,
    ): EgoDecision? {
        return try {
            val payload = parsePayloadWithRepair(raw)
            when (payload.decision?.trim()?.lowercase()) {
                "thought" -> {
                    val thought = payload.thought?.trim().orEmpty()
                    if (thought.isBlank()) {
                        EgoDecision.Noop("Planner returned empty thought.")
                    } else {
                        EgoDecision.EnqueueThought(
                            urgency = Urgency.fromRaw(payload.urgency),
                            content = TextSecurity.clamp(thought, config.planner.maxThoughtChars),
                            longTermMemoryRecallQuery = payload.longTermMemoryRecallQuery?.trim()?.ifBlank { null }?.let {
                                TextSecurity.clamp(it, config.planner.maxThoughtChars)
                            }
                        )
                    }
                }

                "action" -> {
                    val actionType = ActionType.fromRaw(payload.actionType)
                    val actionPayload = normalizeActionPayload(payload.actionPayload)?.trim().orEmpty()
                    val actionSummary = payload.actionSummary?.trim().orEmpty()
                    val resolvedSummary = if (actionSummary.isBlank() && actionType != null && actionPayload.isNotBlank()) {
                        onPlannerOutputRepaired()
                        instrumentation.emit(AgentEvents.plannerOutputRepaired(actionType = actionType.name.lowercase()))
                        synthesizeActionSummary(actionPayload)
                    } else {
                        actionSummary
                    }

                    if (actionType == null || actionPayload.isBlank() || resolvedSummary.isBlank()) {
                        EgoDecision.Noop("Planner returned invalid action payload.")
                    } else if (!availableActions.contains(actionType)) {
                        EgoDecision.Noop(
                            "Planner proposed unavailable action type: ${actionType.name.lowercase()}."
                        )
                    } else {
                        EgoDecision.ProposeAction(
                            urgency = Urgency.fromRaw(payload.urgency),
                            actionType = actionType,
                            payload = TextSecurity.clamp(actionPayload, config.planner.maxActionPayloadChars),
                            summary = TextSecurity.clamp(resolvedSummary, config.planner.maxActionSummaryChars)
                        )
                    }
                }

                "plan" -> {
                    val goal = payload.planGoal?.trim().orEmpty()
                    val steps = payload.planSteps
                        ?.map { it.trim() }
                        ?.filter { it.isNotBlank() }
                        ?.take(config.planner.maxPlanSteps)
                        ?.map { TextSecurity.clamp(it, config.planner.maxPlanStepDescriptionChars) }
                        .orEmpty()
                    if (goal.isBlank() || steps.isEmpty()) {
                        EgoDecision.Noop("Planner returned plan with missing goal or empty steps.")
                    } else {
                        EgoDecision.EnqueuePlan(
                            urgency = Urgency.fromRaw(payload.urgency),
                            goal = TextSecurity.clamp(goal, config.planner.maxThoughtChars),
                            steps = steps,
                        )
                    }
                }

                else -> EgoDecision.Noop(payload.reason?.take(120) ?: "Planner returned noop.")
            }
        } catch (ex: Exception) {
            logger.warn(ex) {
                "Failed to parse Ego decision. response_len=${raw.length} preview='${TextSecurity.preview(raw, 120)}'"
            }
            if (emitParseWarning) {
                instrumentation.emit(AgentEvents.warning("Failed to parse Ego planner response."))
            }
            null
        }
    }

    private fun parsePayloadWithRepair(raw: String): EgoDecisionPayload {
        val json = TextSecurity.extractJsonObject(raw)
        return try {
            mapper.readValue<EgoDecisionPayload>(json)
        } catch (initial: Exception) {
            val repaired = repairInvalidJsonEscapes(json)
            if (repaired == json) {
                throw initial
            }
            try {
                val payload = mapper.readValue<EgoDecisionPayload>(repaired)
                onPlannerOutputRepaired()
                instrumentation.emit(
                    AgentEvents.plannerOutputRepaired(
                        actionType = "planner",
                        repair = "invalid_json_escape"
                    )
                )
                payload
            } catch (_: Exception) {
                throw initial
            }
        }
    }

    private fun repairInvalidJsonEscapes(json: String): String =
        json.replace(invalidJsonEscapeRegex, "")

    private fun verifyActionDecision(
        trigger: EgoTrigger,
        context: PlannerContext,
        decision: EgoDecision,
    ): EgoDecision {
        if (decision !is EgoDecision.ProposeAction) {
            return decision
        }
        val circuitKey = resolveActionVerifierCircuitKey(trigger, decision)
        val bypassRemaining = actionVerifierBypassRemainingByKey[circuitKey] ?: 0
        if (bypassRemaining > 0) {
            val next = bypassRemaining - 1
            if (next > 0) {
                actionVerifierBypassRemainingByKey[circuitKey] = next
            } else {
                actionVerifierBypassRemainingByKey.remove(circuitKey)
            }
            instrumentation.emit(
                AgentEvents.warning(
                    "Action verifier temporarily bypassed after repeated parse failures; keeping original action proposal."
                )
            )
            emitActionVerifierCircuitBreakerEvent(
                phase = "bypassed",
                circuitKey = circuitKey,
                parseFailureStreak = actionVerifierParseFailureStreakByKey[circuitKey] ?: 0,
                bypassRemaining = next
            )
            return decision
        }
        val messages = buildActionVerifierMessages(
            trigger = trigger,
            context = context,
            decision = decision
        )
        val verifierOutcome = callActionVerifier(
            messages = messages,
            actionType = decision.actionType.name.lowercase(),
            circuitKey = circuitKey
        )
        val verifierPayload = verifierOutcome.payload ?: return decision
        return resolveVerifierDecision(
            original = decision,
            payload = verifierPayload,
            availableActions = context.availableActions
        )
    }

    private fun callActionVerifier(
        messages: List<ChatMessage>,
        actionType: String,
        circuitKey: ActionVerifierCircuitKey,
    ): ActionVerifierOutcome {
        var response = null as psyke.llm.ChatCompletion?
        var lastError: Exception? = null
        val retryAttempts = RetryPolicy.boundedLlmRetryAttempts(config.planner.llmRetryAttempts)
        for (attempt in 1..retryAttempts) {
            try {
                response = actionVerifierModelClient.chat(
                    messages = messages,
                    options = ChatRequestOptions(
                        temperature = 0.1,
                        maxTokens = minOf(config.planner.maxCompletionTokens, ACTION_VERIFIER_MAX_TOKENS),
                        metadata = ChatCallMetadata(
                            actor = "ego",
                            callSite = "action_verifier",
                            actionType = actionType
                        )
                    )
                )
                break
            } catch (ex: Exception) {
                lastError = ex
                if (attempt < retryAttempts) {
                    instrumentation.emit(
                        AgentEvents.warning(
                            "Action verifier call failed (attempt $attempt/$retryAttempts); retrying."
                        )
                    )
                }
            }
        }
        if (response == null) {
            logger.warn(lastError) { "Action verifier call failed for action_type=$actionType." }
            instrumentation.emit(AgentEvents.warning("Action verifier unavailable; keeping original action proposal."))
            return ActionVerifierOutcome(payload = null, parseFailed = false)
        }
        val initialParse = tryParseActionVerifierPayload(response.content, actionType, emitParseWarning = false)
        if (!initialParse.parseFailed) {
            actionVerifierParseFailureStreakByKey.remove(circuitKey)
            return initialParse
        }
        instrumentation.emit(
            AgentEvents.warning("Action verifier response was non-parseable; requesting strict JSON retry.")
        )
        val retryResponse = requestActionVerifierStrictJsonRetry(messages = messages, actionType = actionType)
        val parseResult = if (retryResponse == null) {
            ActionVerifierOutcome(payload = null, parseFailed = true)
        } else {
            tryParseActionVerifierPayload(retryResponse.content, actionType, emitParseWarning = true)
        }
        if (parseResult.parseFailed) {
            val parseFailureStreak = (actionVerifierParseFailureStreakByKey[circuitKey] ?: 0) + 1
            actionVerifierParseFailureStreakByKey[circuitKey] = parseFailureStreak
            instrumentation.emit(
                AgentEvents.warning("Action verifier response remained non-parseable after strict JSON retry.")
            )
            if (parseFailureStreak >= ACTION_VERIFIER_PARSE_FAILURE_TRIP_THRESHOLD) {
                actionVerifierBypassRemainingByKey[circuitKey] = ACTION_VERIFIER_BYPASS_TURNS
                actionVerifierParseFailureStreakByKey.remove(circuitKey)
                instrumentation.emit(
                    AgentEvents.warning(
                        "Action verifier parse-failure circuit breaker tripped for action_type=${circuitKey.actionType.name.lowercase()} root_input=${circuitKey.rootInputEnqueuedAtMs}; bypassing verifier for $ACTION_VERIFIER_BYPASS_TURNS decision."
                    )
                )
                emitActionVerifierCircuitBreakerEvent(
                    phase = "tripped",
                    circuitKey = circuitKey,
                    parseFailureStreak = parseFailureStreak,
                    bypassRemaining = ACTION_VERIFIER_BYPASS_TURNS
                )
            }
        } else {
            actionVerifierParseFailureStreakByKey.remove(circuitKey)
        }
        return parseResult
    }

    private fun resolveActionVerifierCircuitKey(
        trigger: EgoTrigger,
        decision: EgoDecision.ProposeAction,
    ): ActionVerifierCircuitKey {
        val rootInputEnqueuedAtMs = when (trigger) {
            is EgoTrigger.IncomingInput -> trigger.input.enqueuedAtMs
            is EgoTrigger.PendingThoughtInput -> trigger.thought.rootInputEnqueuedAtMs
        }
        return ActionVerifierCircuitKey(
            rootInputEnqueuedAtMs = rootInputEnqueuedAtMs,
            actionType = decision.actionType
        )
    }

    private fun emitActionVerifierCircuitBreakerEvent(
        phase: String,
        circuitKey: ActionVerifierCircuitKey,
        parseFailureStreak: Int,
        bypassRemaining: Int,
    ) {
        instrumentation.emit(
            AgentEvent(
                type = "action_verifier_circuit_breaker",
                data = mapOf(
                    "phase" to phase,
                    "action_type" to circuitKey.actionType.name.lowercase(),
                    "root_input_enqueued_at_ms" to circuitKey.rootInputEnqueuedAtMs,
                    "parse_failure_streak" to parseFailureStreak,
                    "bypass_remaining" to bypassRemaining
                )
            )
        )
    }

    private fun tryParseActionVerifierPayload(
        raw: String,
        actionType: String,
        emitParseWarning: Boolean,
    ): ActionVerifierOutcome {
        return try {
            ActionVerifierOutcome(
                payload = parseActionVerifierPayloadWithRepair(raw),
                parseFailed = false
            )
        } catch (ex: Exception) {
            logger.warn(ex) {
                "Failed to parse action verifier response. action_type=$actionType preview='${TextSecurity.preview(raw, 120)}'"
            }
            if (emitParseWarning) {
                instrumentation.emit(AgentEvents.warning("Failed to parse action verifier response."))
            }
            ActionVerifierOutcome(payload = null, parseFailed = true)
        }
    }

    private fun requestStrictJsonRetry(
        baseMessages: List<ChatMessage>,
        callSite: String,
    ): psyke.llm.ChatCompletion? {
        val retryMessages = baseMessages + ChatMessage(
            role = ChatRole.USER,
            content = """
                Your previous output was not valid JSON.
                Reply with STRICT JSON only and no markdown/code fences.
                Use this exact schema:
                {
                  "decision":"thought|action|plan|noop",
                  "urgency":"low|medium|high",
                  "thought":"optional when decision=thought",
                  "long_term_memory_recall_query":"optional query string",
                  "action_type":"web_search|answer|mcp_time|mcp_fetch",
                  "action_payload":"optional when decision=action",
                  "action_summary":"required when decision=action",
                  "plan_goal":"required when decision=plan",
                  "plan_steps":["step 1","step 2"],
                  "reason":"optional short reason"
                }
            """.trimIndent()
        )
        return try {
            modelClient.chat(
                messages = retryMessages,
                options = ChatRequestOptions(
                    temperature = 0.0,
                    maxTokens = config.planner.maxCompletionTokens,
                    metadata = ChatCallMetadata(
                        actor = "ego",
                        callSite = "${callSite}_json_retry"
                    )
                )
            )
        } catch (ex: Exception) {
            logger.warn(ex) { "Planner strict JSON retry call failed for call_site=$callSite." }
            null
        }
    }

    private fun requestActionVerifierStrictJsonRetry(
        messages: List<ChatMessage>,
        actionType: String,
    ): psyke.llm.ChatCompletion? {
        val retryMessages = messages + ChatMessage(
            role = ChatRole.USER,
            content = """
                Your previous output was not valid JSON.
                Reply with STRICT JSON only and no markdown/code fences.
                Use exactly this schema:
                {
                  "verdict":"approve|repair|reject",
                  "action_type":"required when verdict=repair",
                  "action_payload":"required when verdict=repair",
                  "action_summary":"required when verdict=repair",
                  "reason":"optional short reason"
                }
            """.trimIndent()
        )
        return try {
            actionVerifierModelClient.chat(
                messages = retryMessages,
                options = ChatRequestOptions(
                    temperature = 0.0,
                    maxTokens = minOf(config.planner.maxCompletionTokens, ACTION_VERIFIER_MAX_TOKENS),
                    metadata = ChatCallMetadata(
                        actor = "ego",
                        callSite = "action_verifier_json_retry",
                        actionType = actionType
                    )
                )
            )
        } catch (ex: Exception) {
            logger.warn(ex) { "Action verifier strict JSON retry call failed for action_type=$actionType." }
            null
        }
    }

    private fun parseActionVerifierPayloadWithRepair(raw: String): ActionVerifierPayload {
        val json = TextSecurity.extractJsonObject(raw)
        return try {
            mapper.readValue<ActionVerifierPayload>(json)
        } catch (initial: Exception) {
            val repaired = repairInvalidJsonEscapes(json)
            if (repaired == json) {
                throw initial
            }
            try {
                val payload = mapper.readValue<ActionVerifierPayload>(repaired)
                onPlannerOutputRepaired()
                instrumentation.emit(
                    AgentEvents.plannerOutputRepaired(
                        actionType = "action_verifier",
                        repair = "invalid_json_escape"
                    )
                )
                payload
            } catch (_: Exception) {
                throw initial
            }
        }
    }

    private fun resolveVerifierDecision(
        original: EgoDecision.ProposeAction,
        payload: ActionVerifierPayload,
        availableActions: Set<ActionType>,
    ): EgoDecision {
        return when (payload.verdict?.trim()?.lowercase()) {
            "approve" -> {
                emitVerifierResult(
                    verdict = "approve",
                    originalActionType = original.actionType.name.lowercase(),
                    resultingActionType = original.actionType.name.lowercase(),
                    repaired = false,
                    reason = payload.reason
                )
                original
            }

            "reject" -> {
                val reason = payload.reason?.trim().orEmpty().ifBlank {
                    "Action verifier rejected candidate action."
                }
                emitVerifierResult(
                    verdict = "reject",
                    originalActionType = original.actionType.name.lowercase(),
                    resultingActionType = null,
                    repaired = false,
                    reason = reason
                )
                EgoDecision.Noop(TextSecurity.clamp(reason, 160))
            }

            "repair" -> {
                val repairedActionType = ActionType.fromRaw(payload.actionType) ?: original.actionType
                if (!availableActions.contains(repairedActionType)) {
                    val reason = "Action verifier proposed unavailable action type: ${repairedActionType.name.lowercase()}."
                    emitVerifierResult(
                        verdict = "reject",
                        originalActionType = original.actionType.name.lowercase(),
                        resultingActionType = repairedActionType.name.lowercase(),
                        repaired = false,
                        reason = reason
                    )
                    return EgoDecision.Noop(reason)
                }

                val repairedPayload = normalizeActionPayload(payload.actionPayload)?.trim().orEmpty()
                if (repairedPayload.isBlank()) {
                    instrumentation.emit(
                        AgentEvents.warning(
                            "Action verifier repair missing payload; keeping original action."
                        )
                    )
                    return original
                }
                val repairedSummary = payload.actionSummary?.trim().orEmpty()
                val resolvedSummary = if (repairedSummary.isBlank()) {
                    synthesizeActionSummary(repairedPayload)
                } else {
                    repairedSummary
                }
                if (resolvedSummary.isBlank()) {
                    instrumentation.emit(
                        AgentEvents.warning(
                            "Action verifier repair missing summary; keeping original action."
                        )
                    )
                    return original
                }
                onPlannerOutputRepaired()
                instrumentation.emit(
                    AgentEvents.plannerOutputRepaired(
                        actionType = repairedActionType.name.lowercase(),
                        repair = "action_verifier_repair"
                    )
                )
                emitVerifierResult(
                    verdict = "repair",
                    originalActionType = original.actionType.name.lowercase(),
                    resultingActionType = repairedActionType.name.lowercase(),
                    repaired = true,
                    reason = payload.reason
                )
                EgoDecision.ProposeAction(
                    urgency = original.urgency,
                    actionType = repairedActionType,
                    payload = TextSecurity.clamp(repairedPayload, config.planner.maxActionPayloadChars),
                    summary = TextSecurity.clamp(resolvedSummary, config.planner.maxActionSummaryChars)
                )
            }

            else -> {
                instrumentation.emit(
                    AgentEvents.warning("Action verifier returned unknown verdict; keeping original action.")
                )
                original
            }
        }
    }

    private fun emitVerifierResult(
        verdict: String,
        originalActionType: String,
        resultingActionType: String?,
        repaired: Boolean,
        reason: String?,
    ) {
        instrumentation.emit(
            AgentEvent(
                type = "action_verifier_result",
                data = mapOf(
                    "verdict" to verdict,
                    "original_action_type" to originalActionType,
                    "resulting_action_type" to resultingActionType,
                    "repaired" to repaired,
                    "reason" to reason
                )
            )
        )
    }

    private fun emitDecision(triggerLabel: String, decision: EgoDecision) {
        when (decision) {
            is EgoDecision.EnqueueThought -> {
                instrumentation.emit(
                    AgentEvents.plannerDecision(
                        trigger = triggerLabel,
                        decisionType = "thought",
                        urgency = decision.urgency.name.lowercase(),
                        thought = decision.content
                    )
                )
            }

            is EgoDecision.ProposeAction -> {
                instrumentation.emit(
                    AgentEvents.plannerDecision(
                        trigger = triggerLabel,
                        decisionType = "action",
                        urgency = decision.urgency.name.lowercase(),
                        actionType = decision.actionType.name.lowercase(),
                        payload = decision.payload,
                        summary = decision.summary
                    )
                )
            }

            is EgoDecision.EnqueuePlan -> {
                instrumentation.emit(
                    AgentEvents.plannerDecision(
                        trigger = triggerLabel,
                        decisionType = "plan",
                        urgency = decision.urgency.name.lowercase(),
                        thought = decision.goal,
                        reason = "steps=${decision.steps.size}"
                    )
                )
            }

            is EgoDecision.Noop -> {
                onPlannerNoop()
                instrumentation.emit(
                    AgentEvents.plannerDecision(
                        trigger = triggerLabel,
                        decisionType = "noop",
                        reason = decision.reason
                    )
                )
            }
        }
    }

    private fun buildMessages(trigger: EgoTrigger, context: PlannerContext): List<ChatMessage> {
        val triggerText = formatTriggerText(trigger)

        val dialogue = if (context.recentDialogue.isEmpty()) {
            "none"
        } else {
            context.recentDialogue.joinToString("\n") { turn ->
                "${turn.role.name.lowercase()}: ${turn.content}"
            }
        }
        val shortTermContextSummary = context.shortTermContextSummary.ifBlank { "none" }
        val longTermMemoryRecall = context.longTermMemoryRecall.ifBlank { "none" }
        val taskWorkspaceSummary = context.taskWorkspaceSummary.ifBlank { "none" }
        val evidenceHints = context.evidenceHints.ifBlank { "none" }
        val metaGuidance = context.metaGuidance.ifBlank { "none" }
        val deliberation = context.deliberation
        val availableActionList = context.availableActions
            .map { it.name.lowercase() }
            .sorted()
            .joinToString(", ")
            .ifBlank { "none" }
        val unavailableActionList = ActionType.DISPATCHABLE
            .filterNot { context.availableActions.contains(it) }
            .map { it.name.lowercase() }
            .sorted()
            .joinToString(", ")
            .ifBlank { "none" }

        return PromptBudgetAllocator.allocate(
            sections = listOf(
                PromptBudgetAllocator.Section(
                    role = ChatRole.SYSTEM,
                    priority = PromptBudgetAllocator.Priority.MANDATORY,
                    required = true,
                    minTokens = 48,
                    content = """
                    You are Ego, an action planner in a loop.
                    Return STRICT JSON only.
                    Decisions:
                    - thought: create/refine a thought for future processing.
                    - action: propose one action.
                    - plan: decompose into ordered steps when the task needs multiple stages.
                    - noop: when no safe next step exists.
                    Use plan when the task requires multiple sequential stages (e.g. search, then verify, then answer).
                    Each plan_step is a concise directive (<=120 chars). The planner re-evaluates each step.
                    Do not use plan for simple tasks solvable in one or two steps.
                    Allowed actions:
                    - web_search: payload is a concise search query.
                    - answer: payload is the exact answer text for the interlocutor.
                    - mcp_time: payload is JSON like {"timezone":"Europe/Berlin"} (timezone optional).
                    - mcp_fetch: payload is JSON like {"url":"https://example.com","max_chars":1200}.
                    You may receive Long-term memory recall from Hippocampus search.
                    Use long-term memory recall only when relevant to the current trigger.
                    If long-term memory recall is missing or ambiguous, do not invent details.
                    You may receive a Task workspace summary scoped to the current request.
                    Treat Task workspace as ephemeral working notes, not durable long-term memory.
                    You may also receive Decision pressure metadata.
                    As pressure rises, reduce exploratory loops and converge on a final answer.
                    """.trimIndent()
                ),
                PromptBudgetAllocator.Section(
                    role = ChatRole.SYSTEM,
                    priority = PromptBudgetAllocator.Priority.IMPORTANT,
                    minTokens = 36,
                    content = """
                    JSON schema:
                    {
                      "decision":"thought|action|plan|noop",
                      "urgency":"low|medium|high",
                      "thought":"... optional when decision=thought",
                      "long_term_memory_recall_query":"optional query string for explicit extra long-term recall",
                      "action_type":"web_search|answer|mcp_time|mcp_fetch",
                      "action_payload":"... optional when decision=action",
                      "action_summary":"required when decision=action; <=180 chars context summary for action review",
                      "plan_goal":"required when decision=plan; overall objective",
                      "plan_steps":["step 1 directive","step 2 directive","..."],
                      "reason":"... optional short reason"
                    }
                    Valid action example:
                    {"decision":"action","urgency":"medium","action_type":"answer","action_payload":"...","action_summary":"Deliver concise recommendation"}
                    Valid plan example:
                    {"decision":"plan","urgency":"medium","plan_goal":"Find and verify current pricing","plan_steps":["Search for official pricing page","Fetch the pricing page content","Synthesize and answer with verified pricing"]}
                    Do not return decision=action without both action_payload and action_summary.
                    action_payload must always be a JSON string value; never return object/array directly.
                    Do not return decision=plan without both plan_goal and plan_steps.
                    Keep thought concise.
                    Prefer concise answer payloads by default.
                    Only produce a detailed answer payload when the user explicitly asks for detail.
                    Action summary must be at most 180 chars.
                    """.trimIndent()
                ),
                PromptBudgetAllocator.Section(
                    role = ChatRole.USER,
                    priority = PromptBudgetAllocator.Priority.OPTIONAL,
                    content = """
                    Queue snapshot:
                    pending_inputs=${context.queue.pendingInputCount}
                    pending_thoughts=${context.queue.pendingThoughtCount}
                    pending_actions=${context.queue.pendingActionCount}
                    """.trimIndent()
                ),
                PromptBudgetAllocator.Section(
                    role = ChatRole.USER,
                    priority = PromptBudgetAllocator.Priority.IMPORTANT,
                    minTokens = 20,
                    content = """
                    Runtime action availability:
                    available_action_types=$availableActionList
                    unavailable_action_types=$unavailableActionList
                    Never propose unavailable_action_types.
                    """.trimIndent()
                ),
                PromptBudgetAllocator.Section(
                    role = ChatRole.USER,
                    priority = PromptBudgetAllocator.Priority.OPTIONAL,
                    content = "Recent dialogue:\n$dialogue"
                ),
                PromptBudgetAllocator.Section(
                    role = ChatRole.USER,
                    priority = PromptBudgetAllocator.Priority.IMPORTANT,
                    minTokens = 24,
                    content = "Short-term context summary:\n$shortTermContextSummary"
                ),
                PromptBudgetAllocator.Section(
                    role = ChatRole.USER,
                    priority = PromptBudgetAllocator.Priority.IMPORTANT,
                    minTokens = 24,
                    content = "Long-term memory recall:\n$longTermMemoryRecall"
                ),
                PromptBudgetAllocator.Section(
                    role = ChatRole.USER,
                    priority = PromptBudgetAllocator.Priority.IMPORTANT,
                    minTokens = 20,
                    content = "Task workspace summary:\n$taskWorkspaceSummary"
                ),
                PromptBudgetAllocator.Section(
                    role = ChatRole.USER,
                    priority = PromptBudgetAllocator.Priority.IMPORTANT,
                    minTokens = 18,
                    content = "External evidence hints:\n$evidenceHints"
                ),
                PromptBudgetAllocator.Section(
                    role = ChatRole.USER,
                    priority = PromptBudgetAllocator.Priority.IMPORTANT,
                    minTokens = 24,
                    content = """
                    Deliberation pressure:
                    step_index=${deliberation.stepIndex}
                    decision_pressure=${String.format(Locale.ROOT, "%.3f", deliberation.decisionPressure)}
                    stale_streak=${deliberation.staleStreak}
                    progress_score=${String.format(Locale.ROOT, "%.3f", deliberation.progressScore)}
                    denial_count=${deliberation.denialCount}
                    steps_since_new_evidence=${deliberation.stepsSinceNewEvidence}
                    repeat_signature_hits=${deliberation.repeatSignatureHits}
                    noop_streak=${deliberation.noopStreak}
                    Guidance:
                    - if decision_pressure >= 0.75, prefer a concrete action or final answer.
                    - if decision_pressure >= 0.90, avoid new thought loops unless strictly necessary.
                    """.trimIndent()
                ),
                PromptBudgetAllocator.Section(
                    role = ChatRole.USER,
                    priority = PromptBudgetAllocator.Priority.IMPORTANT,
                    minTokens = 16,
                    content = "Meta reasoning guidance:\n$metaGuidance"
                ),
                PromptBudgetAllocator.Section(
                    role = ChatRole.USER,
                    priority = PromptBudgetAllocator.Priority.MANDATORY,
                    required = true,
                    minTokens = 30,
                    content = "Trigger:\n$triggerText"
                )
            ),
            maxTokens = config.planner.maxPromptTokens
        )
    }

    private fun buildActionVerifierMessages(
        trigger: EgoTrigger,
        context: PlannerContext,
        decision: EgoDecision.ProposeAction,
    ): List<ChatMessage> {
        val triggerText = formatTriggerText(trigger)
        val dialogue = if (context.recentDialogue.isEmpty()) {
            "none"
        } else {
            context.recentDialogue.joinToString("\n") { turn ->
                "${turn.role.name.lowercase()}: ${turn.content}"
            }
        }
        val availableActionList = context.availableActions
            .map { it.name.lowercase() }
            .sorted()
            .joinToString(", ")
            .ifBlank { "none" }
        val shortTermContextSummary = context.shortTermContextSummary.ifBlank { "none" }
        val longTermMemoryRecall = context.longTermMemoryRecall.ifBlank { "none" }
        val taskWorkspaceSummary = context.taskWorkspaceSummary.ifBlank { "none" }

        return PromptBudgetAllocator.allocate(
            sections = listOf(
                PromptBudgetAllocator.Section(
                    role = ChatRole.SYSTEM,
                    priority = PromptBudgetAllocator.Priority.MANDATORY,
                    required = true,
                    minTokens = 36,
                    content = """
                    You are an action verifier.
                    Return STRICT JSON only.
                    Evaluate whether the candidate action is logically consistent with trigger and context.
                    Output schema:
                    {
                      "verdict":"approve|repair|reject",
                      "action_type":"required when verdict=repair",
                      "action_payload":"required when verdict=repair",
                      "action_summary":"required when verdict=repair",
                      "reason":"optional short reason"
                    }
                    Rules:
                    - approve: action is coherent and ready for policy review.
                    - repair: one-shot correction to make action coherent.
                    - reject: action cannot be repaired safely/coherently.
                    - Never use action types outside available_action_types.
                    """.trimIndent()
                ),
                PromptBudgetAllocator.Section(
                    role = ChatRole.USER,
                    priority = PromptBudgetAllocator.Priority.IMPORTANT,
                    minTokens = 18,
                    content = "available_action_types=$availableActionList"
                ),
                PromptBudgetAllocator.Section(
                    role = ChatRole.USER,
                    priority = PromptBudgetAllocator.Priority.OPTIONAL,
                    content = "Recent dialogue:\n$dialogue"
                ),
                PromptBudgetAllocator.Section(
                    role = ChatRole.USER,
                    priority = PromptBudgetAllocator.Priority.IMPORTANT,
                    minTokens = 24,
                    content = "Short-term context summary:\n$shortTermContextSummary"
                ),
                PromptBudgetAllocator.Section(
                    role = ChatRole.USER,
                    priority = PromptBudgetAllocator.Priority.OPTIONAL,
                    content = "Long-term memory recall:\n$longTermMemoryRecall"
                ),
                PromptBudgetAllocator.Section(
                    role = ChatRole.USER,
                    priority = PromptBudgetAllocator.Priority.OPTIONAL,
                    content = "Task workspace summary:\n$taskWorkspaceSummary"
                ),
                PromptBudgetAllocator.Section(
                    role = ChatRole.USER,
                    priority = PromptBudgetAllocator.Priority.MANDATORY,
                    required = true,
                    minTokens = 30,
                    content = """
                    Trigger:
                    $triggerText
                    
                    Candidate action:
                    urgency=${decision.urgency.name.lowercase()}
                    action_type=${decision.actionType.name.lowercase()}
                    action_payload=${decision.payload}
                    action_summary=${decision.summary}
                    """.trimIndent()
                )
            ),
            maxTokens = minOf(config.planner.maxPromptTokens, 1_200)
        )
    }

    private fun formatTriggerText(trigger: EgoTrigger): String =
        when (trigger) {
            is EgoTrigger.IncomingInput -> "INPUT: ${trigger.input.content}"
            is EgoTrigger.PendingThoughtInput -> {
                val thought = trigger.thought
                val planInfo = thought.planContext?.let { ctx ->
                    """
                    Active plan context:
                    plan_goal=${ctx.planGoal}
                    step=${ctx.stepIndex + 1}/${ctx.totalSteps}
                    step_description=${ctx.stepDescription}
                    Re-evaluate this step in light of current context. You may refine, skip, or act.
                    """.trimIndent()
                } ?: ""
                val denialContext = if (thought.deniedActionType != null && !thought.deniedActionPayload.isNullOrBlank()) {
                    val technicalDenial = DenialReasonClassifier.isLikelyTechnical(
                        reasonCode = thought.denialReasonCode,
                        reason = thought.denialReason
                    )
                    val retryGuidance = if (technicalDenial) {
                        "Denied reason appears technical/transient; retrying the same payload once is allowed if still best."
                    } else {
                        "Do not repeat the denied action payload; prefer a materially different next step."
                    }
                    """
                    Denied action context:
                    denied_action_type=${thought.deniedActionType.name.lowercase()}
                    denied_action_payload=${thought.deniedActionPayload}
                    denied_reason=${thought.denialReason ?: "none"}
                    denied_reason_code=${thought.denialReasonCode ?: "none"}
                    $retryGuidance
                    """.trimIndent()
                } else {
                    "Denied action context: none"
                }
                val parts = listOf("THOUGHT(pass=${thought.passes}): ${thought.content}", planInfo, denialContext)
                    .filter { it.isNotBlank() }
                parts.joinToString("\n")
            }
        }

    private data class EgoDecisionPayload(
        val decision: String? = null,
        val urgency: String? = null,
        val thought: String? = null,
        @field:JsonProperty("long_term_memory_recall_query")
        val longTermMemoryRecallQuery: String? = null,
        @field:JsonProperty("action_type")
        val actionType: String? = null,
        @field:JsonProperty("action_payload")
        val actionPayload: JsonNode? = null,
        @field:JsonProperty("action_summary")
        val actionSummary: String? = null,
        val reason: String? = null,
        @field:JsonProperty("plan_goal")
        val planGoal: String? = null,
        @field:JsonProperty("plan_steps")
        val planSteps: List<String>? = null,
    )

    private data class ActionVerifierPayload(
        val verdict: String? = null,
        @field:JsonProperty("action_type")
        val actionType: String? = null,
        @field:JsonProperty("action_payload")
        val actionPayload: JsonNode? = null,
        @field:JsonProperty("action_summary")
        val actionSummary: String? = null,
        val reason: String? = null,
    )

    private data class ActionVerifierOutcome(
        val payload: ActionVerifierPayload?,
        val parseFailed: Boolean,
    )

    private data class ActionVerifierCircuitKey(
        val rootInputEnqueuedAtMs: Long?,
        val actionType: ActionType,
    )

    private companion object {
        const val ACTION_VERIFIER_MAX_TOKENS: Int = 220
        const val ACTION_VERIFIER_PARSE_FAILURE_TRIP_THRESHOLD: Int = 2
        const val ACTION_VERIFIER_BYPASS_TURNS: Int = 1
        val mapper = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        val invalidJsonEscapeRegex = Regex("""\\(?!["\\/bfnrtu])""")
    }

    private fun normalizeActionPayload(node: JsonNode?): String? {
        if (node == null || node.isNull) {
            return null
        }
        return when {
            node.isTextual -> node.asText()
            node.isObject || node.isArray -> mapper.writeValueAsString(node)
            else -> node.asText()
        }
    }

    private fun synthesizeActionSummary(actionPayload: String): String {
        val firstLine = actionPayload
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
        val normalized = firstLine
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.isBlank()) {
            return "Generated action summary."
        }
        return TextSecurity.clamp(normalized, config.planner.maxActionSummaryChars)
    }
}
