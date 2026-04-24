package ai.neopsyke.agent.cortex.motor

import ai.neopsyke.agent.cortex.motor.actions.ActionCapability
import ai.neopsyke.agent.cortex.motor.actions.ConversationOutputGateway
import ai.neopsyke.agent.cortex.motor.actions.RoutedConversationOutputGateway
import ai.neopsyke.agent.cortex.motor.actions.ActionRegistry
import ai.neopsyke.agent.cortex.motor.actions.ActionPluginFactoryContext
import ai.neopsyke.agent.cortex.motor.actions.EvidenceArtifactStore
import ai.neopsyke.agent.cortex.motor.actions.NoopEvidenceArtifactStore
import ai.neopsyke.agent.cortex.motor.actions.ReflectionMemoryRecorder
import ai.neopsyke.agent.cortex.motor.actions.websearch.WebSearchActionHandler
import ai.neopsyke.agent.model.ActionOutcome
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.cortex.motor.actions.ActionDescriptor
import ai.neopsyke.agent.model.ActionEffectClass
import ai.neopsyke.agent.model.ActionExecutionStatus
import ai.neopsyke.agent.model.CommitAuthorization
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.assignments.NoopAssignmentGateway
import ai.neopsyke.agent.assignments.AssignmentGateway
import ai.neopsyke.agent.cortex.motor.actions.fetch.FetchTool

data class ActionImplementationStatus(
    val actionType: ActionType,
    val dispatchable: Boolean,
    val available: Boolean,
    val detail: String,
)

class MotorCortex(
    private val actionRegistry: ActionRegistry,
) {
    constructor(
        webSearchActionHandler: WebSearchActionHandler,
        fetchTool: FetchTool? = null,
        output: (String) -> Unit = ::println,
        conversationOutput: ConversationOutputGateway = RoutedConversationOutputGateway(fallbackOutput = output),
        reflectionMemoryRecorder: ReflectionMemoryRecorder,
        config: AgentConfig = AgentConfig(),
        assignmentGateway: AssignmentGateway = NoopAssignmentGateway,
        evidenceArtifactStore: EvidenceArtifactStore = NoopEvidenceArtifactStore,
    ) : this(
        actionRegistry = ActionRegistry.discover(
            ActionPluginFactoryContext(
                config = config,
                webSearchActionHandler = webSearchActionHandler,
                fetchTool = fetchTool,
                output = output,
                conversationOutput = conversationOutput,
                evidenceArtifactStore = evidenceArtifactStore,
                reflectionMemoryRecorder = reflectionMemoryRecorder,
                assignmentGateway = assignmentGateway,
            )
        )
    )

    @Volatile
    private var lastStatusSnapshot: List<ActionImplementationStatus>? = null

    suspend fun startupSmokeTest(): List<ActionImplementationStatus> {
        val statuses = buildStatusSnapshot()
        lastStatusSnapshot = statuses
        return statuses
    }

    suspend fun actionImplementationStatuses(): List<ActionImplementationStatus> {
        return lastStatusSnapshot ?: buildStatusSnapshot()
    }

    suspend fun availableActionTypes(): Set<ActionType> =
        actionImplementationStatuses()
            .filter { it.dispatchable && it.available }
            .map { it.actionType }
            .toSet()

    /**
     * Non-suspend accessor for the cached set of available action types.
     * Falls back to descriptor-only filtering when no startup snapshot exists yet.
     */
    fun cachedAvailableActionTypes(): Set<ActionType> {
        val snapshot = lastStatusSnapshot
            ?: return actionRegistry.descriptors()
                .filter { it.dispatchable }
                .map { it.actionType }
                .toSet()
        return snapshot
            .filter { it.dispatchable && it.available }
            .map { it.actionType }
            .toSet()
    }

    fun plannerDescriptors(): List<ActionDescriptor> =
        actionRegistry.descriptors()
            .filter { it.dispatchable }
            .sortedBy { it.actionType.id }

    fun requiresFollowUpThought(actionType: ActionType): Boolean =
        actionRegistry.requiresFollowUpThought(actionType)

    fun followUpPrefix(actionType: ActionType): String =
        actionRegistry.followUpPrefix(actionType)

    fun repairPlannerPayload(actionType: ActionType, raw: String): String =
        actionRegistry.repairPlannerPayload(actionType, raw)

    fun hasCapability(actionType: ActionType, capability: ActionCapability): Boolean =
        actionRegistry.hasCapability(actionType, capability)

    fun actionTypesWithCapability(capability: ActionCapability): Set<ActionType> =
        actionRegistry.actionTypesWithCapability(capability)

    fun actionRegistry(): ActionRegistry = actionRegistry

    suspend fun execute(
        action: PendingAction,
        searchResultCount: Int,
        authorization: CommitAuthorization? = null,
    ): ActionOutcome {
        val contract = actionRegistry.contract(action.type)
        if (
            contract != null &&
            contract.effectClass != ActionEffectClass.OBSERVE &&
            !contract.directCommitAllowed &&
            authorization == null
        ) {
            return ActionOutcome(
                statusSummary = "Action '${action.type.id}' requires commit authorization before execution.",
                executionStatus = ActionExecutionStatus.FAILED,
                actionErrorCategory = "commit_authorization_required",
                plannerSignal = "commit authorization required for ${action.type.id}",
            )
        }
        return actionRegistry.execute(action, searchResultCount, authorization)
    }

    private suspend fun buildStatusSnapshot(): List<ActionImplementationStatus> =
        actionRegistry.descriptors()
            .sortedBy { it.actionType.id }
            .map { descriptor ->
                val health = actionRegistry.healthCheck(descriptor.actionType)
                ActionImplementationStatus(
                    actionType = descriptor.actionType,
                    dispatchable = descriptor.dispatchable,
                    available = health.available,
                    detail = health.detail
                )
            }
}
