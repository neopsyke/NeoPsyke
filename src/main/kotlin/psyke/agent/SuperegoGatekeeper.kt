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
    private val directives: List<String> = DEFAULT_DIRECTIVES,
    private val instrumentation: AgentInstrumentation = NoopAgentInstrumentation,
) {
    fun review(action: PendingAction, context: SuperegoContext): GateDecision {
        val lastUserTurn = context.recentDialogue.lastOrNull { it.role == DialogueRole.USER }?.content ?: "none"
        instrumentation.emit(
            AgentEvents.superegoReviewInput(
                action = action,
                directives = directives,
                lastUserMessage = lastUserTurn
            )
        )
        val messages = buildMessages(action, context)
        var response = null as psyke.llm.ChatCompletion?
        var lastError: Exception? = null
        for (attempt in 1..2) {
            try {
                response = modelClient.chat(
                    messages = messages,
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
                break
            } catch (ex: Exception) {
                lastError = ex
                if (attempt < 2) {
                    instrumentation.emit(AgentEvents.warning("Superego call failed (attempt 1/2); retrying."))
                }
            }
        }
        if (response == null) {
            logger.warn(lastError) { "Superego review call failed for action=${action.id} type=${action.type}." }
            instrumentation.emit(AgentEvents.warning("Superego call failed; denying action by default."))
            return GateDecision(
                allow = false,
                reason = "Superego unavailable due to model error."
            )
        }
        val resolvedResponse = response
        val decision = parseResponse(resolvedResponse.content)
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

    private fun buildMessages(action: PendingAction, context: SuperegoContext): List<ChatMessage> {
        val directivesBlock = directives.joinToString(separator = "\n") { "- $it" }
        val lastUserTurn = context.recentDialogue.lastOrNull { it.role == DialogueRole.USER }?.content ?: "none"
        val memorySummary = context.memorySummary.ifBlank { "none" }
        return PromptBudgetAllocator.allocate(
            sections = listOf(
                PromptBudgetAllocator.Section(
                    role = ChatRole.SYSTEM,
                    priority = PromptBudgetAllocator.Priority.MANDATORY,
                    required = true,
                    minTokens = 28,
                    content = """
                        You are Superego, a strict gatekeeper for actions.
                        Return STRICT JSON only.
                        If action violates directives or is unsafe, deny it.
                    """.trimIndent()
                ),
                PromptBudgetAllocator.Section(
                    role = ChatRole.SYSTEM,
                    priority = PromptBudgetAllocator.Priority.IMPORTANT,
                    minTokens = 24,
                    content = """
                        Directives:
                        $directivesBlock

                        JSON schema:
                        - If allowed: {"allow": true}
                        - If denied: {"allow": false, "reason":"<=180 chars"}
                        Keep output minimal JSON only.
                    """.trimIndent()
                ),
                PromptBudgetAllocator.Section(
                    role = ChatRole.USER,
                    priority = PromptBudgetAllocator.Priority.MANDATORY,
                    required = true,
                    minTokens = 24,
                    content = """
                        Candidate action:
                        type=${action.type.name.lowercase()}
                        urgency=${action.urgency.name.lowercase()}
                        summary=${action.summary}
                        payload=${action.payload}
                    """.trimIndent()
                ),
                PromptBudgetAllocator.Section(
                    role = ChatRole.USER,
                    priority = PromptBudgetAllocator.Priority.MANDATORY,
                    required = true,
                    minTokens = 10,
                    content = """
                        Last user message:
                        $lastUserTurn
                    """.trimIndent()
                ),
                PromptBudgetAllocator.Section(
                    role = ChatRole.USER,
                    priority = PromptBudgetAllocator.Priority.IMPORTANT,
                    minTokens = 12,
                    content = """
                        Memory summary:
                        $memorySummary
                    """.trimIndent()
                )
            ),
            maxTokens = config.maxPromptTokens
        )
    }

    private data class SuperegoResponse(
        val allow: Boolean? = null,
        val reason: String? = null,
    )

    companion object {
        val DEFAULT_DIRECTIVES: List<String> = listOf(
            "Any actions should not contain words or expressions that could offend the interlocutor.",
        )

        val mapper = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }
}
