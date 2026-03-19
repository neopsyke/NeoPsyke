package psyke.agent.actions.builtin

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import psyke.agent.actions.ActionDescriptor
import psyke.agent.actions.ActionDeterministicReview
import psyke.agent.actions.ActionExecutionContext
import psyke.agent.actions.AgentActionPlugin
import psyke.agent.actions.AgentActionPluginFactory
import psyke.agent.actions.ActionPluginFactoryContext
import psyke.agent.config.AgentConfig
import psyke.agent.model.ActionExecutionStatus
import psyke.agent.model.ActionOutcome
import psyke.agent.model.ActionType
import psyke.agent.model.PendingAction
import psyke.agent.model.SuperegoContext
import psyke.agent.project.ProjectOperation
import psyke.agent.project.ProjectOperationRequest

class ProjectOperationActionPlugin(
    private val context: ActionPluginFactoryContext,
) : AgentActionPlugin {
    override val descriptor: ActionDescriptor = ActionDescriptor(
        actionType = ActionType.PROJECT_OPERATION,
        dispatchable = context.config.projects.enabled,
        plannerDescription = "project_operation: create, inspect, pause, resume, reprioritize, complete, list, or revise persistent projects.",
        payloadGuidance = "Strict JSON with an operation field and the required project arguments.",
        payloadSchemaExample = """
            {"operation":"create","title":"Inbox cleanup","instruction":"Keep my inbox triaged daily","priority":"HIGH","completion_criteria":"Inbox is triaged and rules are documented"}
        """.trimIndent(),
        requiresFollowUpThought = false,
        followUpPrefix = "Project operation completed.",
    )

    override fun deterministicReview(
        action: PendingAction,
        context: SuperegoContext,
        config: AgentConfig,
    ): ActionDeterministicReview {
        val payload = parsePayload(action.payload)
            ?: return ActionDeterministicReview(
                allow = false,
                ruleId = "project_operation_invalid_payload",
                reason = "PROJECT_OPERATION payload must be valid JSON."
            )
        val operation = payload.operation?.trim().orEmpty()
        if (operation.isBlank()) {
            return ActionDeterministicReview(
                allow = false,
                ruleId = "project_operation_missing_operation",
                reason = "PROJECT_OPERATION payload requires an operation field."
            )
        }
        return ActionDeterministicReview(allow = true)
    }

    override suspend fun execute(action: PendingAction, context: ActionExecutionContext): ActionOutcome {
        val payload = parsePayload(action.payload)
            ?: return ActionOutcome(
                statusSummary = "Invalid project_operation payload.",
                executionStatus = ActionExecutionStatus.FAILED,
            )
        val operation = payload.operation
            ?.trim()
            ?.uppercase()
            ?.let { runCatching { ProjectOperation.valueOf(it) }.getOrNull() }
            ?: return ActionOutcome(
                statusSummary = "Unknown project operation '${payload.operation}'.",
                executionStatus = ActionExecutionStatus.FAILED,
            )
        val result = this.context.projectsGateway.executeOperation(
            ProjectOperationRequest(
                operation = operation,
                projectId = payload.projectId,
                title = payload.title,
                instruction = payload.instruction,
                priority = payload.priority
                    ?.trim()
                    ?.uppercase()
                    ?.let { runCatching { psyke.agent.project.ProjectPriority.valueOf(it) }.getOrNull() },
                completionCriteria = payload.completionCriteria,
                reason = payload.reason,
            )
        )
        return ActionOutcome(
            statusSummary = result.message,
            assistantOutput = result.message,
            executionStatus = if (result.success) ActionExecutionStatus.SUCCESS else ActionExecutionStatus.FAILED,
        )
    }

    private fun parsePayload(raw: String): ProjectOperationPayload? =
        runCatching { mapper.readValue<ProjectOperationPayload>(raw) }.getOrNull()

    private data class ProjectOperationPayload(
        val operation: String? = null,
        val projectId: String? = null,
        val title: String? = null,
        val instruction: String? = null,
        val priority: String? = null,
        val completionCriteria: String? = null,
        val reason: String? = null,
    )

    private companion object {
        val mapper = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }
}

class ProjectOperationActionPluginFactory : AgentActionPluginFactory {
    override fun create(context: ActionPluginFactoryContext): AgentActionPlugin =
        ProjectOperationActionPlugin(context)
}
