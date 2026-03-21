package ai.neopsyke.agent.project

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

enum class ProjectStepVerdict {
    PASS,
    RETRY,
    BLOCK,
    CONTINUE,
    FAIL,
}

data class ProjectStepVerification(
    val verdict: ProjectStepVerdict,
    val reason: String = "",
    val waitCondition: WaitCondition? = null,
)

interface ProjectStepVerifier {
    fun evaluate(
        project: Project,
        step: PlanStep,
        action: PendingAction,
        outcome: ActionOutcome,
        observedEvidence: Boolean,
    ): ProjectStepVerification
}

class DeterministicProjectStepVerifier : ProjectStepVerifier {
    override fun evaluate(
        project: Project,
        step: PlanStep,
        action: PendingAction,
        outcome: ActionOutcome,
        observedEvidence: Boolean,
    ): ProjectStepVerification {
        if (!outcome.successful) {
            return ProjectStepVerification(ProjectStepVerdict.RETRY, outcome.statusSummary)
        }
        return ProjectStepVerification(ProjectStepVerdict.PASS, outcome.statusSummary)
    }
}

class LlmProjectStepVerifier(
    private val modelClient: ChatModelClient,
    private val config: AgentConfig,
) : ProjectStepVerifier {
    override fun evaluate(
        project: Project,
        step: PlanStep,
        action: PendingAction,
        outcome: ActionOutcome,
        observedEvidence: Boolean,
    ): ProjectStepVerification {
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
                            Evaluate the project step result.
                            project_title=${project.title}
                            project_instruction=${project.instruction}
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
                            name = "project_step_verdict",
                            schemaJson = VERDICT_SCHEMA_JSON
                        )
                    )
                ).content
                break
            } catch (ex: Exception) {
                if (attempt < attempts) {
                    verifierLogger.warn(ex) { "Project verifier failed; retrying (attempt $attempt/$attempts)." }
                } else {
                    verifierLogger.warn(ex) { "Project verifier failed after $attempts attempts." }
                }
            }
        }
        val content = raw ?: return safeFallback(outcome)
        val payload = try {
            mapper.readValue<ProjectVerdictPayload>(content)
        } catch (ex: Exception) {
            verifierLogger.warn(ex) { "Project verifier response could not be parsed; using safe fallback." }
            return safeFallback(outcome)
        }
        val verdict = payload.verdict?.trim()?.uppercase()
            ?.let { runCatching { ProjectStepVerdict.valueOf(it) }.getOrNull() }
            ?: return safeFallback(outcome)
        val reason = payload.reason?.trim().orEmpty()
        val waitCondition = if (verdict == ProjectStepVerdict.BLOCK) {
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
        return ProjectStepVerification(verdict = verdict, reason = reason, waitCondition = waitCondition)
    }

    private fun safeFallback(outcome: ActionOutcome): ProjectStepVerification =
        if (outcome.successful) {
            ProjectStepVerification(ProjectStepVerdict.CONTINUE, outcome.statusSummary)
        } else {
            ProjectStepVerification(ProjectStepVerdict.RETRY, outcome.statusSummary)
        }

    private data class ProjectVerdictPayload(
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
            You evaluate whether a project step has satisfied its acceptance criteria.
            Return strict JSON only.
            Use BLOCK only when the next action should wait until a later time.
            If uncertain, prefer CONTINUE over PASS.
        """.trimIndent()
    }
}
