package ai.neopsyke.agent.goal

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.model.ActionOutcome
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.llm.ChatMessage
import ai.neopsyke.llm.ChatModelClient
import ai.neopsyke.llm.ChatRequestOptions
import ai.neopsyke.llm.ChatResponseFormat
import ai.neopsyke.llm.ChatRole
import java.time.Instant

private val verifierLogger = KotlinLogging.logger {}

enum class GoalStepVerdict {
    PASS,
    RETRY,
    BLOCK,
    CONTINUE,
    FAIL,
}

data class GoalStepVerification(
    val verdict: GoalStepVerdict,
    val reason: String = "",
    val waitCondition: WaitCondition? = null,
)

interface GoalStepVerifier {
    fun evaluate(
        goal: Goal,
        step: PlanStep,
        action: PendingAction,
        outcome: ActionOutcome,
        observedEvidence: Boolean,
    ): GoalStepVerification
}

class DeterministicGoalStepVerifier : GoalStepVerifier {
    override fun evaluate(
        goal: Goal,
        step: PlanStep,
        action: PendingAction,
        outcome: ActionOutcome,
        observedEvidence: Boolean,
    ): GoalStepVerification {
        if (!outcome.successful) {
            return GoalStepVerification(GoalStepVerdict.RETRY, outcome.statusSummary)
        }
        return GoalStepVerification(GoalStepVerdict.PASS, outcome.statusSummary)
    }
}

class LlmGoalStepVerifier(
    private val modelClient: ChatModelClient,
    private val config: AgentConfig,
) : GoalStepVerifier {
    override fun evaluate(
        goal: Goal,
        step: PlanStep,
        action: PendingAction,
        outcome: ActionOutcome,
        observedEvidence: Boolean,
    ): GoalStepVerification {
        val attempts = maxOf(1, config.llmRetryAttempts)
        var raw: String? = null
        for (attempt in 1..attempts) {
            try {
                raw = modelClient.chat(
                    messages = listOf(
                        ChatMessage(ChatRole.SYSTEM, SYSTEM_PROMPT),
                        ChatMessage(
                            ChatRole.USER,
                            """
                            Evaluate the goal step result.
                            goal_title=${goal.title}
                            goal_instruction=${goal.instruction}
                            step_id=${step.id}
                            step_description=${step.description}
                            acceptance_criteria=${step.acceptanceCriteria}
                            action_type=${action.type.id}
                            action_summary=${action.summary}
                            action_payload=${action.payload}
                            outcome_status=${outcome.statusSummary}
                            observed_evidence=$observedEvidence
                            """.trimIndent()
                        )
                    ),
                    options = ChatRequestOptions(
                        maxTokens = config.planner.maxCompletionTokens,
                        responseFormat = ChatResponseFormat.JsonSchema(
                            name = "goal_step_verdict",
                            schemaJson = VERDICT_SCHEMA_JSON
                        )
                    )
                ).content
                break
            } catch (ex: Exception) {
                if (attempt < attempts) {
                    verifierLogger.warn(ex) { "Goal verifier failed; retrying (attempt $attempt/$attempts)." }
                } else {
                    verifierLogger.warn(ex) { "Goal verifier failed after $attempts attempts." }
                }
            }
        }
        val content = raw ?: return safeFallback(outcome)
        val payload = try {
            mapper.readValue<GoalVerdictPayload>(content)
        } catch (ex: Exception) {
            verifierLogger.warn(ex) { "Goal verifier response could not be parsed; using safe fallback." }
            return safeFallback(outcome)
        }
        val verdict = payload.verdict?.trim()?.uppercase()
            ?.let { runCatching { GoalStepVerdict.valueOf(it) }.getOrNull() }
            ?: return safeFallback(outcome)
        val reason = payload.reason?.trim().orEmpty()
        val waitCondition = if (verdict == GoalStepVerdict.BLOCK) {
            val wakeAt = payload.waitWakeAt?.trim()
            WaitCondition(
                type = WaitConditionType.TIMER,
                params = wakeAt?.let { mapOf("wake_at" to it) } ?: emptyMap(),
                registeredAt = Instant.now(),
                timeoutAt = wakeAt?.let { Instant.parse(it) },
            )
        } else {
            null
        }
        return GoalStepVerification(verdict = verdict, reason = reason, waitCondition = waitCondition)
    }

    private fun safeFallback(outcome: ActionOutcome): GoalStepVerification =
        if (outcome.successful) {
            GoalStepVerification(GoalStepVerdict.CONTINUE, outcome.statusSummary)
        } else {
            GoalStepVerification(GoalStepVerdict.RETRY, outcome.statusSummary)
        }

    private data class GoalVerdictPayload(
        val verdict: String? = null,
        val reason: String? = null,
        @field:JsonProperty("wait_wake_at")
        val waitWakeAt: String? = null,
    )

    private companion object {
        val mapper = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        val VERDICT_SCHEMA_JSON: String = """
            {
              "type":"object",
              "required":["verdict","reason"],
              "properties":{
                "verdict":{"type":"string","enum":["PASS","RETRY","BLOCK","CONTINUE","FAIL"]},
                "reason":{"type":"string"},
                "wait_wake_at":{"type":"string"}
              },
              "additionalProperties":false
            }
        """.trimIndent()
        val SYSTEM_PROMPT: String = """
            You evaluate whether a goal step has satisfied its acceptance criteria.
            Return strict JSON only.
            Use BLOCK only when the next action should wait until a later time.
            If uncertain, prefer CONTINUE over PASS.
        """.trimIndent()
    }
}
