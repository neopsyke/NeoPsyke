package psyke.agent.ego

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import psyke.agent.core.*
import psyke.agent.support.PromptBudgetAllocator
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
    private val config: AgentConfig,
    private val instrumentation: AgentInstrumentation = NoopAgentInstrumentation,
    private val onPlannerNoop: () -> Unit = {},
    private val onPlannerOutputRepaired: () -> Unit = {},
) : Ego.Planner {
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
        val retryAttempts = maxOf(1, config.llmRetryAttempts)
        for (attempt in 1..retryAttempts) {
            try {
                response = modelClient.chat(
                    messages = messages,
                    options = ChatRequestOptions(
                        temperature = 0.2,
                        maxTokens = config.maxCompletionTokens,
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
            availableActions = context.availableActions
        )
        emitDecision(triggerLabel, decision)
        return decision
    }

    private fun parseResponse(raw: String, availableActions: Set<ActionType>): EgoDecision {
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
                            content = TextSecurity.clamp(thought, config.maxThoughtChars),
                            longTermMemoryRecallQuery = payload.longTermMemoryRecallQuery?.trim()?.ifBlank { null }?.let {
                                TextSecurity.clamp(it, config.maxThoughtChars)
                            }
                        )
                    }
                }

                "action" -> {
                    val actionType = ActionType.fromRaw(payload.actionType)
                    val actionPayload = payload.actionPayload?.trim().orEmpty()
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
                            payload = TextSecurity.clamp(actionPayload, config.maxActionPayloadChars),
                            summary = TextSecurity.clamp(resolvedSummary, config.maxActionSummaryChars)
                        )
                    }
                }

                else -> EgoDecision.Noop(payload.reason?.take(120) ?: "Planner returned noop.")
            }
        } catch (ex: Exception) {
            logger.warn(ex) {
                "Failed to parse Ego decision. response_len=${raw.length} preview='${TextSecurity.preview(raw, 120)}'"
            }
            instrumentation.emit(AgentEvents.warning("Failed to parse Ego planner response."))
            EgoDecision.Noop("Planner produced non-parseable output.")
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
        val triggerText = when (trigger) {
            is EgoTrigger.IncomingInput -> "INPUT: ${trigger.input.content}"
            is EgoTrigger.PendingThoughtInput -> {
                val thought = trigger.thought
                val denialContext = if (thought.deniedActionType != null && !thought.deniedActionPayload.isNullOrBlank()) {
                    """
                    Denied action context:
                    denied_action_type=${thought.deniedActionType.name.lowercase()}
                    denied_action_payload=${thought.deniedActionPayload}
                    denied_reason=${thought.denialReason ?: "none"}
                    Do not repeat the denied action payload; prefer a materially different next step.
                    """.trimIndent()
                } else {
                    "Denied action context: none"
                }
                "THOUGHT(pass=${thought.passes}): ${thought.content}\n$denialContext"
            }
        }

        val dialogue = if (context.recentDialogue.isEmpty()) {
            "none"
        } else {
            context.recentDialogue.joinToString("\n") { turn ->
                "${turn.role.name.lowercase()}: ${turn.content}"
            }
        }
        val shortTermContextSummary = context.shortTermContextSummary.ifBlank { "none" }
        val longTermMemoryRecall = context.longTermMemoryRecall.ifBlank { "none" }
        val metaGuidance = context.metaGuidance.ifBlank { "none" }
        val deliberation = context.deliberation
        val availableActionList = context.availableActions
            .map { it.name.lowercase() }
            .sorted()
            .joinToString(", ")
            .ifBlank { "none" }
        val unavailableActionList = ActionType.entries
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
                    - noop: when no safe next step exists.
                    Allowed actions:
                    - web_search: payload is a concise search query.
                    - answer: payload is the exact answer text for the interlocutor.
                    - mcp_time: payload is JSON like {"timezone":"Europe/Berlin"} (timezone optional).
                    - mcp_fetch: payload is JSON like {"url":"https://example.com","max_chars":1200}.
                    You may receive Long-term memory recall from Hippocampus search.
                    Use long-term memory recall only when relevant to the current trigger.
                    If long-term memory recall is missing or ambiguous, do not invent details.
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
                      "decision":"thought|action|noop",
                      "urgency":"low|medium|high",
                      "thought":"... optional when decision=thought",
                      "long_term_memory_recall_query":"optional query string for explicit extra long-term recall",
                      "action_type":"web_search|answer|mcp_time|mcp_fetch",
                      "action_payload":"... optional when decision=action",
                      "action_summary":"required when decision=action; <=180 chars context summary for action review",
                      "reason":"... optional short reason"
                    }
                    Valid action example:
                    {"decision":"action","urgency":"medium","action_type":"answer","action_payload":"...","action_summary":"Deliver concise recommendation"}
                    Do not return decision=action without both action_payload and action_summary.
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
            maxTokens = config.maxPromptTokens
        )
    }

    private data class EgoDecisionPayload(
        val decision: String? = null,
        val urgency: String? = null,
        val thought: String? = null,
        @JsonProperty("long_term_memory_recall_query")
        val longTermMemoryRecallQuery: String? = null,
        @JsonProperty("action_type")
        val actionType: String? = null,
        @JsonProperty("action_payload")
        val actionPayload: String? = null,
        @JsonProperty("action_summary")
        val actionSummary: String? = null,
        val reason: String? = null,
    )

    private companion object {
        val mapper = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        val invalidJsonEscapeRegex = Regex("""\\(?!["\\/bfnrtu])""")
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
        return TextSecurity.clamp(normalized, config.maxActionSummaryChars)
    }
}
