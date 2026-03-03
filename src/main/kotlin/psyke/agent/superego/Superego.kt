package psyke.agent.superego

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import psyke.agent.core.AgentConfig
import psyke.agent.core.DialogueRole
import psyke.agent.core.GateDecision
import psyke.agent.core.PendingAction
import psyke.agent.core.SuperegoContext
import psyke.agent.support.PromptBudgetAllocator
import psyke.agent.support.TextSecurity
import psyke.instrumentation.AgentEvent
import psyke.instrumentation.AgentEvents
import psyke.instrumentation.AgentInstrumentation
import psyke.instrumentation.NoopAgentInstrumentation
import psyke.llm.ChatCallMetadata
import psyke.llm.ChatMessage
import psyke.llm.ChatModelClient
import psyke.llm.ChatRequestOptions
import psyke.llm.ChatRole

private val logger = KotlinLogging.logger {}

class Superego(
    private val modelClient: ChatModelClient,
    private val config: AgentConfig,
    private val policy: SuperegoPolicy = SuperegoPolicy,
    private val instrumentation: AgentInstrumentation = NoopAgentInstrumentation,
) {
    private val deterministicConscience = SuperegoDeterministicConscience(config)

    fun review(action: PendingAction, context: SuperegoContext): GateDecision {
        val resolvedDirectives = policy.forAction(action.type).all
        val lastUserTurn = context.recentDialogue.lastOrNull { it.role == DialogueRole.USER }?.content ?: "none"
        instrumentation.emit(
            AgentEvents.superegoReviewInput(
                action = action,
                directives = resolvedDirectives,
                lastUserMessage = lastUserTurn
            )
        )
        val deterministicDecision = deterministicConscience.review(action, context)
        if (!deterministicDecision.allow) {
            val reason = TextSecurity.clamp(
                deterministicDecision.reason.ifBlank { "Deterministic policy denied action." },
                MAX_DENY_REASON_CHARS
            )
            instrumentation.emit(
                AgentEvent(
                    type = "superego_deterministic_review",
                    data = mapOf(
                        "action_id" to action.id,
                        "allow" to false,
                        "rule_id" to deterministicDecision.ruleId,
                        "reason" to reason
                    )
                )
            )
            instrumentation.emit(AgentEvents.superegoReviewOutput(actionId = action.id, allow = false, reason = reason))
            return GateDecision(allow = false, reason = reason)
        }
        instrumentation.emit(
            AgentEvent(
                type = "superego_deterministic_review",
                data = mapOf(
                    "action_id" to action.id,
                    "allow" to true
                )
            )
        )
        val messages = buildMessages(action, context, resolvedDirectives)
        var response = null as psyke.llm.ChatCompletion?
        var lastError: Exception? = null
        val retryAttempts = maxOf(1, config.planner.llmRetryAttempts)
        for (attempt in 1..retryAttempts) {
            try {
                response = modelClient.chat(
                    messages = messages,
                    options = ChatRequestOptions(
                        temperature = 0.0,
                        // Keep response budget independent from prompt/directive growth.
                        maxTokens = config.superego.maxCompletionTokens,
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
                if (attempt < retryAttempts) {
                    instrumentation.emit(
                        AgentEvents.warning(
                            "Superego call failed (attempt $attempt/$retryAttempts); retrying."
                        )
                    )
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
            if (payload.allow == null) {
                logger.warn {
                    "Superego response missing required 'allow' field. response_len=${raw.length} preview='${TextSecurity.preview(raw, 120)}'"
                }
                return GateDecision(allow = false, reason = "Superego response missing required field.")
            }
            val allow = payload.allow == true
            val reason = TextSecurity.clamp(payload.reason?.trim().orEmpty(), MAX_DENY_REASON_CHARS)
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

    private fun buildMessages(
        action: PendingAction,
        context: SuperegoContext,
        directives: List<String>,
    ): List<ChatMessage> {
        val directivesBlock = directives.joinToString(separator = "\n") { "- $it" }
        val lastUserTurn = context.recentDialogue.lastOrNull { it.role == DialogueRole.USER }?.content ?: "none"
        val shortTermContextSummary = context.shortTermContextSummary.ifBlank { "none" }
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
                        - If denied: {"allow": false, "reason":"<=${MAX_DENY_REASON_CHARS} chars"}
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
                        Short-term context summary:
                        $shortTermContextSummary
                    """.trimIndent()
                )
            ),
            maxTokens = config.planner.maxPromptTokens
        )
    }

    private data class SuperegoResponse(
        val allow: Boolean? = null,
        val reason: String? = null,
    )

    companion object {
        private const val MAX_DENY_REASON_CHARS: Int = 180

        val mapper = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }
}
