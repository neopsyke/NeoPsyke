package psyke.agent.project

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import psyke.agent.config.AgentConfig
import psyke.llm.ChatMessage
import psyke.llm.ChatModelClient
import psyke.llm.ChatRequestOptions
import psyke.llm.ChatResponseFormat
import psyke.llm.ChatRole
import java.time.Instant

private val plannerLogger = KotlinLogging.logger {}

interface ProjectPlanner {
    fun generatePlan(project: Project): ProjectPlan
}

class DeterministicProjectPlanner : ProjectPlanner {
    override fun generatePlan(project: Project): ProjectPlan =
        ProjectPlan(
            steps = listOf(
                PlanStep(
                    id = "step-1",
                    description = project.instruction,
                    status = StepStatus.PENDING,
                    acceptanceCriteria = project.completionCriteria,
                )
            ),
            generatedAt = Instant.now(),
        )
}

class LlmProjectPlanner(
    private val modelClient: ChatModelClient,
    private val config: AgentConfig,
) : ProjectPlanner {
    override fun generatePlan(project: Project): ProjectPlan {
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
                            Generate a project plan.
                            title=${project.title}
                            instruction=${project.instruction}
                            completion_criteria=${project.completionCriteria}
                            max_steps=${config.projects.maxStepsPerPlan}
                            """.trimIndent()
                        )
                    ),
                    options = ChatRequestOptions(
                        maxTokens = config.planner.maxCompletionTokens,
                        responseFormat = ChatResponseFormat.JsonSchema(
                            name = "project_plan",
                            schemaJson = PLAN_SCHEMA_JSON
                        )
                    )
                ).content
                break
            } catch (ex: Exception) {
                if (attempt < attempts) {
                    plannerLogger.warn(ex) { "Project planner failed; retrying (attempt $attempt/$attempts)." }
                } else {
                    plannerLogger.warn(ex) { "Project planner failed after $attempts attempts." }
                }
            }
        }
        val content = raw ?: return DeterministicProjectPlanner().generatePlan(project)
        val payload = try {
            mapper.readValue<ProjectPlanPayload>(content)
        } catch (ex: Exception) {
            plannerLogger.warn(ex) { "Project planner response could not be parsed; using deterministic fallback." }
            return DeterministicProjectPlanner().generatePlan(project)
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
            .take(config.projects.maxStepsPerPlan)
        if (steps.isEmpty()) {
            plannerLogger.warn { "Project planner returned no valid steps; using deterministic fallback." }
            return DeterministicProjectPlanner().generatePlan(project)
        }
        return ProjectPlan(steps = steps, generatedAt = Instant.now())
    }

    private data class ProjectPlanPayload(
        val steps: List<ProjectPlanStepPayload>? = null,
    )

    private data class ProjectPlanStepPayload(
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
            You generate persistent project plans for a sequential agent.
            Return strict JSON only.
            Use a short flat step list.
            Each step must be independently verifiable.
            Do not exceed the provided max_steps.
        """.trimIndent()
    }
}
