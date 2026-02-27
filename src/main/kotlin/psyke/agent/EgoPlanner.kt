package psyke.agent

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import psyke.llm.ChatMessage
import psyke.llm.ChatModelClient
import psyke.llm.ChatRequestOptions
import psyke.llm.ChatRole

private val logger = KotlinLogging.logger {}

class EgoPlanner(
    private val modelClient: ChatModelClient,
    private val config: AgentConfig,
) {
    fun decide(trigger: EgoTrigger, snapshot: AgentSnapshot): EgoDecision {
        val triggerLabel = when (trigger) {
            is EgoTrigger.IncomingInput -> "input"
            is EgoTrigger.PendingThoughtInput -> "thought"
        }
        logger.trace {
            "ego.plan.start trigger=$triggerLabel pending(in=${snapshot.pendingInputCount},th=${snapshot.pendingThoughtCount},ac=${snapshot.pendingActionCount})"
        }

        val messages = buildMessages(trigger, snapshot)
        val boundedMessages = TextSecurity.trimMessagesToBudget(messages, config.maxPromptTokens)
        val response = modelClient.chat(
            messages = boundedMessages,
            options = ChatRequestOptions(
                temperature = 0.2,
                maxTokens = config.maxCompletionTokens
            )
        )

        val decision = parseResponse(response.content)
        logDecision(decision)
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
            EgoDecision.Noop("Planner produced non-parseable output.")
        }
    }

    private fun logDecision(decision: EgoDecision) {
        when (decision) {
            is EgoDecision.EnqueueThought -> {
                logger.trace {
                    "ego.plan.decision type=thought urgency=${decision.urgency.name.lowercase()} thought_len=${decision.content.length}"
                }
            }

            is EgoDecision.ProposeAction -> {
                logger.trace {
                    "ego.plan.decision type=action action=${decision.actionType.name.lowercase()} urgency=${decision.urgency.name.lowercase()} payload_len=${decision.payload.length} summary='${TextSecurity.preview(decision.summary, 80)}'"
                }
            }

            is EgoDecision.Noop -> {
                logger.trace {
                    "ego.plan.decision type=noop reason='${TextSecurity.preview(decision.reason, 80)}'"
                }
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
