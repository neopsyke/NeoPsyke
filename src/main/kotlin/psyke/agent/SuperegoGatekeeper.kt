package psyke.agent

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import psyke.instrumentation.AgentEvents
import psyke.instrumentation.AgentInstrumentation
import psyke.instrumentation.NoopAgentInstrumentation
import psyke.llm.ChatCallMetadata
import psyke.llm.ChatMessage
import psyke.llm.ChatModelClient
import psyke.llm.ChatRequestOptions
import psyke.llm.ChatRole

private val logger = KotlinLogging.logger {}

class SuperegoGatekeeper(
    private val modelClient: ChatModelClient,
    private val config: AgentConfig,
    private val directives: List<String> = listOf(
        "Any actions should not contain words or expressions that could offend the interlocutor.",
    ),
    private val instrumentation: AgentInstrumentation = NoopAgentInstrumentation,
) {
    fun review(action: PendingAction, snapshot: AgentSnapshot): GateDecision {
        val lastUserTurn = snapshot.recentDialogue.lastOrNull { it.role == DialogueRole.USER }?.content ?: "none"
        instrumentation.emit(
            AgentEvents.superegoReviewInput(
                action = action,
                directives = directives,
                lastUserMessage = lastUserTurn
            )
        )
        val messages = buildMessages(action, snapshot)
        val boundedMessages = TextSecurity.trimMessagesToBudget(messages, config.maxPromptTokens)
        val response = modelClient.chat(
            messages = boundedMessages,
            options = ChatRequestOptions(
                temperature = 0.0,
                maxTokens = 80,
                metadata = ChatCallMetadata(
                    actor = "superego",
                    callSite = "action_review",
                    actionType = action.type.name.lowercase()
                )
            )
        )
        val decision = parseResponse(response.content)
        instrumentation.emit(
            AgentEvents.superegoReviewOutput(
                actionId = action.id,
                allow = decision.allow,
                reason = decision.reason
            )
        )
        return decision
    }

    private fun parseResponse(raw: String): GateDecision {
        return try {
            val json = TextSecurity.extractJsonObject(raw)
            val payload = mapper.readValue<SuperegoResponse>(json)
            val allow = payload.allow == true
            val reason = TextSecurity.clamp(payload.reason?.trim().orEmpty(), 180)
            GateDecision(
                allow = allow,
                reason = if (allow) "" else reason.ifBlank { "No reason supplied." }
            )
        } catch (ex: Exception) {
            logger.warn(ex) {
                "Failed to parse Superego response. response_len=${raw.length} preview='${TextSecurity.preview(raw, 120)}'"
            }
            instrumentation.emit(AgentEvents.warning("Failed to parse Superego response."))
            GateDecision(
                allow = false,
                reason = "Superego response could not be parsed."
            )
        }
    }

    private fun buildMessages(action: PendingAction, snapshot: AgentSnapshot): List<ChatMessage> {
        val directivesBlock = directives.joinToString(separator = "\n") { "- $it" }
        val lastUserTurn = snapshot.recentDialogue.lastOrNull { it.role == DialogueRole.USER }?.content ?: "none"
        return listOf(
            ChatMessage(
                role = ChatRole.SYSTEM,
                content = """
                    You are Superego, a strict gatekeeper for actions.
                    Return STRICT JSON only.
                    If action violates directives or is unsafe, deny it.
                """.trimIndent()
            ),
            ChatMessage(
                role = ChatRole.SYSTEM,
                content = """
                    Directives:
                    $directivesBlock

                    JSON schema:
                    - If allowed: {"allow": true}
                    - If denied: {"allow": false, "reason":"<=180 chars"}
                    Keep output minimal JSON only.
                """.trimIndent()
            ),
            ChatMessage(
                role = ChatRole.USER,
                content = """
                    Candidate action:
                    type=${action.type.name.lowercase()}
                    urgency=${action.urgency.name.lowercase()}
                    summary=${action.summary}
                    payload=${action.payload}

                    Last user message:
                    $lastUserTurn
                """.trimIndent()
            )
        )
    }

    private data class SuperegoResponse(
        val allow: Boolean? = null,
        val reason: String? = null,
    )

    private companion object {
        val mapper = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }
}
