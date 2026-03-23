package ai.neopsyke.agent.actions

import ai.neopsyke.agent.actions.websearch.WebSearchActionHandler
import ai.neopsyke.agent.model.ActionOutcome
import ai.neopsyke.agent.model.ActionEffectClass
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.model.CommitAuthorization
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.DataTrust
import ai.neopsyke.agent.model.ExternalContentArtifact
import ai.neopsyke.agent.model.InstructionTrust
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.model.SuperegoContext
import ai.neopsyke.agent.goal.NoopGoalsGateway
import ai.neopsyke.agent.goal.GoalsGateway
import ai.neopsyke.agent.tools.mcp.FetchTool
import ai.neopsyke.agent.tools.mcp.McpTimeTool

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

data class ActionContract(
    val actionType: ActionType,
    val effectClass: ActionEffectClass,
    val capabilities: Set<ActionCapability>,
    val directCommitAllowed: Boolean = false,
    val supportsAutonomousCommit: Boolean = false,
    val allowedInstructionTrust: Set<InstructionTrust> = setOf(
        InstructionTrust.TRUSTED_INSTRUCTION,
        InstructionTrust.UNTRUSTED_INSTRUCTION,
    ),
    val allowedArgumentDataTrust: Set<DataTrust> = setOf(
        DataTrust.TRUSTED_DATA,
        DataTrust.SANITIZED_EXTERNAL_DATA,
    ),
)

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
    val effectClass: ActionEffectClass = ActionEffectClass.OBSERVE,
    val directCommitAllowed: Boolean = false,
    val supportsAutonomousCommit: Boolean = false,
    val allowedInstructionTrust: Set<InstructionTrust> = setOf(
        InstructionTrust.TRUSTED_INSTRUCTION,
        InstructionTrust.UNTRUSTED_INSTRUCTION,
    ),
    val allowedArgumentDataTrust: Set<DataTrust> = setOf(
        DataTrust.TRUSTED_DATA,
        DataTrust.SANITIZED_EXTERNAL_DATA,
    ),
    val connectorRuntime: ConnectorRuntimeBoundary = ConnectorRuntimeBoundary.firstPartyBuiltin(),
    val connectorBinding: ConnectorActionBinding? = null,
) {
    val contract: ActionContract
        get() = ActionContract(
            actionType = actionType,
            effectClass = effectClass,
            capabilities = capabilities,
            directCommitAllowed = directCommitAllowed,
            supportsAutonomousCommit = supportsAutonomousCommit,
            allowedInstructionTrust = allowedInstructionTrust,
            allowedArgumentDataTrust = allowedArgumentDataTrust,
        )
}

data class ActionPluginHealth(
    val available: Boolean,
    val detail: String,
)

/**
 * A plugin's conscious-level validity check on an action payload.
 *
 * This represents the plugin's own assessment of whether the payload is
 * well-formed and safe to execute ("is this a valid request?"). The superego
 * maps it into a [ai.neopsyke.agent.superego.SuperegoDeterministicDecision] which
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
    val conversationContext: ConversationContext = ConversationContext.default(),
    val requestId: String? = null,
    val dryRun: Boolean = false,
    val authorization: CommitAuthorization? = null,
)

interface ReflectionMemoryRecorder {
    fun recordInternalReflection(action: PendingAction, summary: String, keywords: List<String>): Boolean

    fun recordEvidenceReflection(
        action: PendingAction,
        summaryHint: String,
        keywords: List<String>,
        artifacts: List<ExternalContentArtifact>,
    ): Boolean
}

object NoopReflectionMemoryRecorder : ReflectionMemoryRecorder {
    override fun recordInternalReflection(action: PendingAction, summary: String, keywords: List<String>): Boolean = false

    override fun recordEvidenceReflection(
        action: PendingAction,
        summaryHint: String,
        keywords: List<String>,
        artifacts: List<ExternalContentArtifact>,
    ): Boolean = false
}

data class ActionPluginFactoryContext(
    val config: AgentConfig,
    val webSearchActionHandler: WebSearchActionHandler?,
    val mcpTimeTool: McpTimeTool?,
    val fetchTool: FetchTool?,
    val output: (String) -> Unit,
    val env: Map<String, String> = System.getenv(),
    val secretProvider: ActionSecretProvider = EnvActionSecretProvider(env),
    val conversationOutput: ConversationOutputGateway = RoutedConversationOutputGateway(fallbackOutput = output),
    val connectorRuntime: ConnectorRuntimeBoundary = ConnectorRuntimeBoundary.firstPartyBuiltin(),
    val evidenceArtifactStore: EvidenceArtifactStore = NoopEvidenceArtifactStore,
    val reflectionMemoryRecorder: ReflectionMemoryRecorder,
    val goalsGateway: GoalsGateway = NoopGoalsGateway,
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
