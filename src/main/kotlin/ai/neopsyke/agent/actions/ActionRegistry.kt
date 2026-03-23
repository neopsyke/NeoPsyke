package ai.neopsyke.agent.actions

import mu.KotlinLogging
import ai.neopsyke.agent.model.ActionOutcome
import ai.neopsyke.agent.model.ActionExecutionStatus
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.model.CommitAuthorization
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.model.SuperegoContext
import java.util.ServiceLoader

private val logger = KotlinLogging.logger {}

class ActionRegistry private constructor(
    plugins: List<AgentActionPlugin>,
    val loadWarnings: List<String> = emptyList(),
) : AutoCloseable {
    private val pluginByType: Map<ActionType, AgentActionPlugin> = plugins.associateBy { it.descriptor.actionType }

    fun actionTypes(): Set<ActionType> = pluginByType.keys

    fun descriptor(actionType: ActionType): ActionDescriptor? = pluginByType[actionType]?.descriptor

    fun descriptors(): List<ActionDescriptor> =
        pluginByType.values
            .map { it.descriptor }
            .sortedBy { it.actionType.id }

    fun dispatchableActionTypes(): Set<ActionType> =
        pluginByType.values
            .map { it.descriptor }
            .filter { it.dispatchable }
            .map { it.actionType }
            .toSet()

    suspend fun healthCheck(actionType: ActionType): ActionPluginHealth =
        pluginByType[actionType]?.healthCheck()
            ?: ActionPluginHealth(
                available = false,
                detail = "Action plugin is not registered."
            )

    suspend fun execute(
        action: PendingAction,
        searchResultCount: Int,
        authorization: CommitAuthorization? = null,
    ): ActionOutcome {
        val plugin = pluginByType[action.type]
            ?: return ActionOutcome(
                statusSummary = "Action type '${action.type.id}' is not registered.",
                executionStatus = ActionExecutionStatus.FAILED,
            )
        return plugin.execute(
            action = action,
            context = ActionExecutionContext(
                searchResultCount = searchResultCount,
                conversationContext = action.conversationContext,
                requestId = action.rootInputId,
                authorization = authorization,
            )
        )
    }

    fun deterministicReview(
        action: PendingAction,
        context: SuperegoContext,
        config: AgentConfig,
    ): ActionDeterministicReview? =
        pluginByType[action.type]?.deterministicReview(action = action, context = context, config = config)

    fun repairPlannerPayload(actionType: ActionType, raw: String): String =
        pluginByType[actionType]?.repairPlannerPayload(raw) ?: raw

    fun superegoDirectives(actionType: ActionType): List<String> =
        pluginByType[actionType]?.descriptor?.superegoDirectives.orEmpty()

    fun requiresFollowUpThought(actionType: ActionType): Boolean =
        pluginByType[actionType]?.descriptor?.requiresFollowUpThought == true

    fun followUpPrefix(actionType: ActionType): String =
        pluginByType[actionType]?.descriptor?.followUpPrefix ?: "Action completed."

    fun hasCapability(actionType: ActionType, capability: ActionCapability): Boolean =
        pluginByType[actionType]?.descriptor?.capabilities?.contains(capability) == true

    fun contract(actionType: ActionType): ActionContract? =
        pluginByType[actionType]?.descriptor?.contract

    fun actionTypesWithCapability(capability: ActionCapability): Set<ActionType> =
        pluginByType.values
            .filter { it.descriptor.capabilities.contains(capability) }
            .map { it.descriptor.actionType }
            .toSet()

    override fun close() {
        pluginByType.values.forEach { plugin ->
            try {
                plugin.close()
            } catch (ex: Exception) {
                logger.debug(ex) { "Action plugin close failed for action_type=${plugin.descriptor.actionType.id}." }
            }
        }
    }

    companion object {
        fun empty(): ActionRegistry = ActionRegistry(plugins = emptyList())

        fun fromPlugins(plugins: List<AgentActionPlugin>): ActionRegistry =
            ActionRegistry(plugins = plugins)

        fun discover(context: ActionPluginFactoryContext): ActionRegistry {
            val loader = ServiceLoader.load(AgentActionPluginFactory::class.java)
            val plugins = mutableListOf<AgentActionPlugin>()
            val warnings = mutableListOf<String>()
            loader.forEach { factory ->
                try {
                    plugins += factory.create(context)
                } catch (ex: Exception) {
                    warnings += "Failed to initialize action plugin factory ${factory::class.qualifiedName}: ${ex.message}"
                    logger.warn(ex) { "Failed to initialize action plugin factory ${factory::class.qualifiedName}." }
                }
            }
            val byType = linkedMapOf<ActionType, AgentActionPlugin>()
            plugins.forEach { plugin ->
                val type = plugin.descriptor.actionType
                if (byType.containsKey(type)) {
                    warnings += "Duplicate action plugin detected for action_type=${type.id}; keeping first implementation."
                    return@forEach
                }
                byType[type] = plugin
            }
            return ActionRegistry(
                plugins = byType.values.toList(),
                loadWarnings = warnings.toList()
            )
        }
    }
}
