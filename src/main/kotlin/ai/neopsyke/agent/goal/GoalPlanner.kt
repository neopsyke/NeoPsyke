package ai.neopsyke.agent.goal

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.llm.ChatMessage
import ai.neopsyke.llm.ChatModelClient
import ai.neopsyke.llm.ChatRequestOptions
import ai.neopsyke.llm.ChatResponseFormat
import ai.neopsyke.llm.ChatRole
import java.time.Instant

private val plannerLogger = KotlinLogging.logger {}

interface GoalPlanner {
    fun generatePlan(goal: Goal): GoalPlan
}

class DeterministicGoalPlanner : GoalPlanner {
    override fun generatePlan(goal: Goal): GoalPlan =
        GoalPlan(
            steps = listOf(
                PlanStep(
                    id = "step-1",
                    description = goal.instruction,
                    status = StepStatus.PENDING,
                    acceptanceCriteria = goal.completionCriteria,
                )
            ),
            generatedAt = Instant.now(),
        )
}

class LlmGoalPlanner(
    private val modelClient: ChatModelClient,
    private val config: AgentConfig,
) : GoalPlanner {
    override fun generatePlan(goal: Goal): GoalPlan {
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
                            title=${goal.title}
                            instruction=${goal.instruction}
                            completion_criteria=${goal.completionCriteria}
                            max_steps=${config.goals.maxStepsPerPlan}
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
        val content = raw ?: return DeterministicGoalPlanner().generatePlan(goal)
        val payload = try {
            mapper.readValue<GoalPlanPayload>(content)
        } catch (ex: Exception) {
            plannerLogger.warn(ex) { "Goal planner response could not be parsed; using deterministic fallback." }
            return DeterministicGoalPlanner().generatePlan(goal)
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
                    )
                }
            }
            .orEmpty()
            .take(config.goals.maxStepsPerPlan)
        if (steps.isEmpty()) {
            plannerLogger.warn { "Goal planner returned no valid steps; using deterministic fallback." }
            return DeterministicGoalPlanner().generatePlan(goal)
        }
        return GoalPlan(steps = steps, generatedAt = Instant.now())
    }

    private data class GoalPlanPayload(
        val steps: List<GoalPlanStepPayload>? = null,
    )

    private data class GoalPlanStepPayload(
        val id: String? = null,
        val description: String? = null,
        @field:JsonProperty("acceptance_criteria")
        val acceptanceCriteria: String? = null,
        val requires: List<String>? = null,
        val produces: List<String>? = null,
        @field:JsonProperty("max_attempts")
        val maxAttempts: Int? = null,
    )

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
        """.trimIndent()
    }
}
