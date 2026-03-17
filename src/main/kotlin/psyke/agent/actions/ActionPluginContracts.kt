package psyke.agent.actions

import psyke.agent.actions.websearch.WebSearchActionHandler
import psyke.agent.model.ActionOutcome
import psyke.agent.model.ActionType
import psyke.agent.config.AgentConfig
import psyke.agent.model.PendingAction
import psyke.agent.model.SuperegoContext
import psyke.agent.tools.mcp.FetchTool
import psyke.agent.tools.mcp.McpTimeTool

/**
 * Declares a broad behavioural trait of an action plugin.
 *
 * Capabilities are queried by cross-cutting subsystems (evidence tracking,
 * task verification, workspace management) instead of hard-coding
 * [ActionType] checks.
 */
enum class ActionCapability {
    /** Action produces output visible to the end user (e.g. ANSWER). */
    PRODUCES_USER_OUTPUT,

    /** Action gathers external evidence for workspace synthesis (e.g. WEB_SEARCH, WEBSITE_FETCH, MCP_TIME). */
    GATHERS_EVIDENCE,
}

data class ActionDescriptor(
    val actionType: ActionType,
    val dispatchable: Boolean = true,
    val plannerDescription: String,
    val payloadGuidance: String,
    val payloadSchemaExample: String? = null,
    val requiresFollowUpThought: Boolean = false,
    val followUpPrefix: String = "Action completed.",
    val superegoDirectives: List<String> = emptyList(),
    val capabilities: Set<ActionCapability> = emptySet(),
)

data class ActionPluginHealth(
    val available: Boolean,
    val detail: String,
)

/**
 * A plugin's conscious-level validity check on an action payload.
 *
 * This represents the plugin's own assessment of whether the payload is
 * well-formed and safe to execute ("is this a valid request?"). The superego
 * maps it into a [psyke.agent.superego.SuperegoDeterministicDecision] which
 * frames the same verdict in ethical/moral terms ("should the agent be
 * *allowed* to do this?"). The two types are intentionally distinct to
 * preserve the psychoanalytic metaphor: the plugin validates, the superego
 * judges.
 */
data class ActionDeterministicReview(
    val allow: Boolean,
    val reason: String = "",
    val ruleId: String? = null,
    val reasonCode: String? = null,
)

data class ActionExecutionContext(
    val searchResultCount: Int,
)

interface ReflectionMemoryRecorder {
    fun recordReflection(action: PendingAction, summary: String, keywords: List<String>)
}

object NoopReflectionMemoryRecorder : ReflectionMemoryRecorder {
    override fun recordReflection(action: PendingAction, summary: String, keywords: List<String>) = Unit
}

data class ActionPluginFactoryContext(
    val config: AgentConfig,
    val webSearchActionHandler: WebSearchActionHandler?,
    val mcpTimeTool: McpTimeTool?,
    val fetchTool: FetchTool?,
    val output: (String) -> Unit,
    val env: Map<String, String> = System.getenv(),
    val reflectionMemoryRecorder: ReflectionMemoryRecorder,
)

interface AgentActionPlugin : AutoCloseable {
    val descriptor: ActionDescriptor

    suspend fun execute(action: PendingAction, context: ActionExecutionContext): ActionOutcome

    suspend fun healthCheck(): ActionPluginHealth =
        ActionPluginHealth(available = true, detail = "Action plugin configured.")

    fun deterministicReview(
        @Suppress("UNUSED_PARAMETER") action: PendingAction,
        @Suppress("UNUSED_PARAMETER") context: SuperegoContext,
        @Suppress("UNUSED_PARAMETER") config: AgentConfig,
    ): ActionDeterministicReview? = null

    fun repairPlannerPayload(raw: String): String = raw

    override fun close() {}
}

interface AgentActionPluginFactory {
    fun create(context: ActionPluginFactoryContext): AgentActionPlugin
}
