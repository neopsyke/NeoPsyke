package ai.neopsyke.agent.durablework

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.model.GroundingRequirement
import ai.neopsyke.llm.ChatMessage
import ai.neopsyke.llm.ChatModelClient
import ai.neopsyke.llm.ChatRequestOptions
import ai.neopsyke.llm.ChatResponseFormat
import ai.neopsyke.llm.ChatRole
import java.time.Instant
import mu.KotlinLogging

private val plannerLogger = KotlinLogging.logger {}

interface WorkPlanBuilder {
    fun generatePlan(workItem: WorkItem): WorkItemPlan
}

class DeterministicWorkPlanBuilder : WorkPlanBuilder {
    override fun generatePlan(workItem: WorkItem): WorkItemPlan =
        WorkItemPlan(
            steps = listOf(
                PlanStep(
                    id = "step-1",
                    description = workItem.instruction,
                    status = StepStatus.PENDING,
                    acceptanceCriteria = workItem.completionCriteria,
                )
            ),
            generatedAt = Instant.now(),
        )
}

class LlmWorkPlanBuilder(
    private val modelClient: ChatModelClient,
    private val config: AgentConfig,
) : WorkPlanBuilder {
    override fun generatePlan(workItem: WorkItem): WorkItemPlan {
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
                            Generate a goal plan.
                            title=${workItem.title}
                            instruction=${workItem.instruction}
                            completion_criteria=${workItem.completionCriteria}
                            max_steps=${config.durableWork.maxStepsPerPlan}
                            """.trimIndent()
                        )
                    ),
                    options = ChatRequestOptions(
                        maxTokens = config.planner.maxCompletionTokens,
                        responseFormat = ChatResponseFormat.JsonSchema(
                            name = "goal_plan",
                            schemaJson = PLAN_SCHEMA_JSON
                        )
                    )
                ).content
                break
            } catch (ex: Exception) {
                if (attempt < attempts) {
                    plannerLogger.warn(ex) { "Goal planner failed; retrying (attempt $attempt/$attempts)." }
                } else {
                    plannerLogger.warn(ex) { "Goal planner failed after $attempts attempts." }
                }
            }
        }
        val content = raw ?: return DeterministicWorkPlanBuilder().generatePlan(workItem)
        val payload = try {
            mapper.readValue<WorkPlanPayload>(content)
        } catch (ex: Exception) {
            plannerLogger.warn(ex) { "Goal planner response could not be parsed; using deterministic fallback." }
            return DeterministicWorkPlanBuilder().generatePlan(workItem)
        }
        val steps = payload.steps
            ?.mapIndexedNotNull { index, step ->
                val description = step.description?.trim().orEmpty()
                val acceptanceCriteria = step.acceptanceCriteria?.trim().orEmpty()
                if (description.isBlank() || acceptanceCriteria.isBlank()) {
                    null
                } else {
                    PlanStep(
                        id = step.id?.trim().takeUnless { it.isNullOrBlank() } ?: "step-${index + 1}",
                        description = description,
                        status = StepStatus.PENDING,
                        acceptanceCriteria = acceptanceCriteria,
                        requires = step.requires.orEmpty().toSet(),
                        produces = step.produces.orEmpty().toSet(),
                        maxAttempts = (step.maxAttempts ?: DEFAULT_MAX_ATTEMPTS).coerceAtLeast(1),
                        groundingRequirement = parseGroundingRequirement(step.groundingRequirement),
                    )
                }
            }
            .orEmpty()
            .take(config.durableWork.maxStepsPerPlan)
        if (steps.isEmpty()) {
            plannerLogger.warn { "Goal planner returned no valid steps; using deterministic fallback." }
            return DeterministicWorkPlanBuilder().generatePlan(workItem)
        }
        return WorkItemPlan(steps = steps, generatedAt = Instant.now())
    }

    private data class WorkPlanPayload(
        val steps: List<WorkPlanStepPayload>? = null,
    )

    private data class WorkPlanStepPayload(
        val id: String? = null,
        val description: String? = null,
        @param:JsonProperty("acceptance_criteria")
        val acceptanceCriteria: String? = null,
        @param:JsonProperty("grounding_requirement")
        val groundingRequirement: String? = null,
        val requires: List<String>? = null,
        val produces: List<String>? = null,
        @param:JsonProperty("max_attempts")
        val maxAttempts: Int? = null,
    )

    private fun parseGroundingRequirement(raw: String?): GroundingRequirement =
        when (raw?.trim()?.lowercase()) {
            "required" -> GroundingRequirement.REQUIRED
            "not_required", "", null -> GroundingRequirement.NOT_REQUIRED
            else -> {
                plannerLogger.warn { "Goal planner produced unknown grounding_requirement='$raw'; defaulting to not_required." }
                GroundingRequirement.NOT_REQUIRED
            }
        }

    private companion object {
        const val DEFAULT_MAX_ATTEMPTS: Int = 3
        val mapper = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        val PLAN_SCHEMA_JSON: String = """
            {
              "type":"object",
              "required":["steps"],
              "properties":{
                "steps":{
                  "type":"array",
                  "minItems":1,
                  "items":{
                    "type":"object",
                    "required":["description","acceptance_criteria"],
                    "properties":{
                      "id":{"type":"string"},
                      "description":{"type":"string"},
                      "acceptance_criteria":{"type":"string"},
                      "grounding_requirement":{"type":"string","enum":["required","not_required"]},
                      "requires":{"type":"array","items":{"type":"string"}},
                      "produces":{"type":"array","items":{"type":"string"}},
                      "max_attempts":{"type":"integer","minimum":1}
                    },
                    "additionalProperties":false
                  }
                }
              },
              "additionalProperties":false
            }
        """.trimIndent()
        val SYSTEM_PROMPT: String = """
            You generate persistent goal plans for a sequential agent.
            Return strict JSON only.
            Use a short flat step list.
            Each step must be independently verifiable.
            Do not exceed the provided max_steps.
            For each step set grounding_requirement:
            - "required" when the step needs fresh external evidence (weather, prices, news, schedules, current-state facts).
            - "not_required" for static/internal/procedural steps.
        """.trimIndent()
    }
}
