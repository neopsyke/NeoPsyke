package psyke.agent

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import psyke.instrumentation.AgentEvent
import psyke.instrumentation.AgentEvents
import psyke.instrumentation.AgentInstrumentation
import psyke.instrumentation.NoopAgentInstrumentation
import psyke.llm.ChatMessage
import psyke.llm.ChatCallMetadata
import psyke.llm.ChatModelClient
import psyke.llm.ChatRequestOptions
import psyke.llm.ChatRole

private val logger = KotlinLogging.logger {}

class EgoPlanner(
    private val modelClient: ChatModelClient,
    private val config: AgentConfig,
    private val instrumentation: AgentInstrumentation = NoopAgentInstrumentation,
) {
    fun decide(trigger: EgoTrigger, snapshot: AgentSnapshot): EgoDecision {
        val triggerLabel = when (trigger) {
            is EgoTrigger.IncomingInput -> "input"
            is EgoTrigger.PendingThoughtInput -> "thought"
        }
        instrumentation.emit(
            AgentEvent(
                type = "planner_start",
                data = mapOf(
                    "trigger" to triggerLabel,
                    "pending_inputs" to snapshot.pendingInputCount,
                    "pending_thoughts" to snapshot.pendingThoughtCount,
                    "pending_actions" to snapshot.pendingActionCount
                )
            )
        )

        val messages = buildMessages(trigger, snapshot)
        val boundedMessages = TextSecurity.trimMessagesToBudget(messages, config.maxPromptTokens)
        val response = modelClient.chat(
            messages = boundedMessages,
            options = ChatRequestOptions(
                temperature = 0.2,
                maxTokens = config.maxCompletionTokens,
                metadata = ChatCallMetadata(
                    actor = "ego",
                    callSite = triggerLabel
                )
            )
        )

        val decision = parseResponse(response.content)
        emitDecision(triggerLabel, decision)
        return decision
    }

    private fun parseResponse(raw: String): EgoDecision {
        return try {
            val json = TextSecurity.extractJsonObject(raw)
            val payload = mapper.readValue<EgoDecisionPayload>(json)
            when (payload.decision?.trim()?.lowercase()) {
                "thought" -> {
                    val thought = payload.thought?.trim().orEmpty()
                    if (thought.isBlank()) {
                        EgoDecision.Noop("Planner returned empty thought.")
                    } else {
                        EgoDecision.EnqueueThought(
                            urgency = Urgency.fromRaw(payload.urgency),
                            content = TextSecurity.clamp(thought, config.maxThoughtChars)
                        )
                    }
                }

                "action" -> {
                    val actionType = ActionType.fromRaw(payload.actionType)
                    val actionPayload = payload.actionPayload?.trim().orEmpty()
                    val actionSummary = payload.actionSummary?.trim().orEmpty()

                    if (actionType == null || actionPayload.isBlank() || actionSummary.isBlank()) {
                        EgoDecision.Noop("Planner returned invalid action payload.")
                    } else {
                        EgoDecision.ProposeAction(
                            urgency = Urgency.fromRaw(payload.urgency),
                            actionType = actionType,
                            payload = TextSecurity.clamp(actionPayload, config.maxActionPayloadChars),
                            summary = TextSecurity.clamp(actionSummary, config.maxActionSummaryChars)
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

    private fun buildMessages(trigger: EgoTrigger, snapshot: AgentSnapshot): List<ChatMessage> {
        val triggerText = when (trigger) {
            is EgoTrigger.IncomingInput -> "INPUT: ${trigger.input.content}"
            is EgoTrigger.PendingThoughtInput -> "THOUGHT(pass=${trigger.thought.passes}): ${trigger.thought.content}"
        }

        val dialogue = if (snapshot.recentDialogue.isEmpty()) {
            "none"
        } else {
            snapshot.recentDialogue.joinToString("\n") { turn ->
                "${turn.role.name.lowercase()}: ${turn.content}"
            }
        }

        return listOf(
            ChatMessage(
                ChatRole.SYSTEM,
                """
                You are Ego, an action planner in a loop.
                Return STRICT JSON only.
                Decisions:
                - thought: create/refine a thought for future processing.
                - action: propose one action.
                - noop: when no safe next step exists.
                Allowed actions:
                - web_search: payload is a concise search query.
                - answer: payload is the exact answer text for the interlocutor.
                """.trimIndent()
            ),
            ChatMessage(
                ChatRole.SYSTEM,
                """
                JSON schema:
                {
                  "decision":"thought|action|noop",
                  "urgency":"low|medium|high",
                  "thought":"... optional when decision=thought",
                  "action_type":"web_search|answer",
                  "action_payload":"... optional when decision=action",
                  "action_summary":"<=180 chars context summary for action review",
                  "reason":"... optional short reason"
                }
                Keep thought concise.
                Prefer concise answer payloads by default.
                Only produce a detailed answer payload when the user explicitly asks for detail.
                Action summary must be at most 180 chars.
                """.trimIndent()
            ),
            ChatMessage(
                ChatRole.USER,
                """
                Queue snapshot:
                pending_inputs=${snapshot.pendingInputCount}
                pending_thoughts=${snapshot.pendingThoughtCount}
                pending_actions=${snapshot.pendingActionCount}

                Recent dialogue:
                $dialogue

                Trigger:
                $triggerText
                """.trimIndent()
            )
        )
    }

    private data class EgoDecisionPayload(
        val decision: String? = null,
        val urgency: String? = null,
        val thought: String? = null,
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
    }
}
