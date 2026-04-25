package ai.neopsyke.agent.assignments

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.model.ActionOutcome
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.llm.ChatCallMetadata
import ai.neopsyke.llm.ChatMessage
import ai.neopsyke.llm.ChatModelClient
import ai.neopsyke.llm.ChatRequestOptions
import ai.neopsyke.prompt.PromptCatalog
import java.time.Instant

private val verifierLogger = KotlinLogging.logger {}

enum class WorkStepVerdict {
    PASS,
    RETRY,
    BLOCK,
    CONTINUE,
    FAIL,
}

data class WorkStepVerification(
    val verdict: WorkStepVerdict,
    val reason: String = "",
    val waitCondition: WaitCondition? = null,
)

interface WorkStepVerifier {
    fun evaluate(
        workItem: WorkItem,
        step: PlanStep,
        action: PendingAction,
        outcome: ActionOutcome,
        observedEvidence: Boolean,
    ): WorkStepVerification
}

class DeterministicWorkStepVerifier : WorkStepVerifier {
    override fun evaluate(
        workItem: WorkItem,
        step: PlanStep,
        action: PendingAction,
        outcome: ActionOutcome,
        observedEvidence: Boolean,
    ): WorkStepVerification {
        if (!outcome.successful) {
            return WorkStepVerification(WorkStepVerdict.RETRY, outcome.statusSummary)
        }
        return WorkStepVerification(WorkStepVerdict.PASS, outcome.statusSummary)
    }
}

class LlmWorkStepVerifier(
    private val modelClient: ChatModelClient,
    private val config: AgentConfig,
    private val promptCatalog: PromptCatalog = PromptCatalog.shared,
) : WorkStepVerifier {
    override fun evaluate(
        workItem: WorkItem,
        step: PlanStep,
        action: PendingAction,
        outcome: ActionOutcome,
        observedEvidence: Boolean,
    ): WorkStepVerification {
        val attempts = maxOf(1, config.llmRetryAttempts)
        var raw: String? = null
        for (attempt in 1..attempts) {
            try {
                val prompt = promptCatalog.renderSections(
                    "assignments/work-step-verifier",
                    mapOf(
                        "assignment_title" to workItem.title,
                        "assignment_instruction" to workItem.instruction,
                        "step_id" to step.id,
                        "step_description" to step.description,
                        "acceptance_criteria" to step.acceptanceCriteria,
                        "action_type" to action.type.id,
                        "action_summary" to action.summary,
                        "action_payload" to action.payload,
                        "outcome_status" to outcome.statusSummary,
                        "observed_evidence" to observedEvidence.toString(),
                    )
                )
                val schema = promptCatalog.responseFormat("assignment-step-verdict")
                raw = modelClient.chat(
                    messages = prompt.sections.map { ChatMessage(role = it.role, content = it.content) },
                    options = ChatRequestOptions(
                        maxTokens = config.planner.maxCompletionTokens,
                        responseFormat = schema.format,
                        metadata = promptCatalog.metadata(
                            ChatCallMetadata(
                                actor = "assignment_verifier",
                                callSite = "assignment_work_step_verifier",
                                actionType = action.type.id,
                            ),
                            prompt,
                            schema,
                        ),
                    )
                ).content
                break
            } catch (ex: Exception) {
                if (attempt < attempts) {
                    verifierLogger.warn(ex) { "Assignment verifier failed; retrying (attempt $attempt/$attempts)." }
                } else {
                    verifierLogger.warn(ex) { "Assignment verifier failed after $attempts attempts." }
                }
            }
        }
        val content = raw ?: return safeFallback(outcome)
        val payload = try {
            mapper.readValue<WorkVerdictPayload>(content)
        } catch (ex: Exception) {
            verifierLogger.warn(ex) { "Assignment verifier response could not be parsed; using safe fallback." }
            return safeFallback(outcome)
        }
        val verdict = payload.verdict?.trim()?.uppercase()
            ?.let { runCatching { WorkStepVerdict.valueOf(it) }.getOrNull() }
            ?: return safeFallback(outcome)
        val reason = payload.reason?.trim().orEmpty()
        val waitCondition = if (verdict == WorkStepVerdict.BLOCK) {
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
        return WorkStepVerification(verdict = verdict, reason = reason, waitCondition = waitCondition)
    }

    private fun safeFallback(outcome: ActionOutcome): WorkStepVerification =
        if (outcome.successful) {
            WorkStepVerification(WorkStepVerdict.CONTINUE, outcome.statusSummary)
        } else {
            WorkStepVerification(WorkStepVerdict.RETRY, outcome.statusSummary)
        }

    private data class WorkVerdictPayload(
        val verdict: String? = null,
        val reason: String? = null,
        @field:JsonProperty("wait_wake_at")
        val waitWakeAt: String? = null,
    )

    private companion object {
        val mapper = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }
}
