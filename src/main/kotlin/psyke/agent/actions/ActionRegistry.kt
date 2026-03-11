package psyke.agent.actions

import mu.KotlinLogging
import psyke.agent.core.ActionOutcome
import psyke.agent.core.ActionType
import psyke.agent.core.AgentConfig
import psyke.agent.core.PendingAction
import psyke.agent.core.SuperegoContext
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

    suspend fun execute(action: PendingAction, searchResultCount: Int): ActionOutcome {
        val plugin = pluginByType[action.type]
            ?: return ActionOutcome(statusSummary = "Action type '${action.type.id}' is not registered.")
        return plugin.execute(
            action = action,
            context = ActionExecutionContext(searchResultCount = searchResultCount)
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
