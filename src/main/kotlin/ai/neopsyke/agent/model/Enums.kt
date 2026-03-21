package ai.neopsyke.agent.model

import com.fasterxml.jackson.annotation.JsonIgnore
import java.util.Locale

enum class Urgency(val priority: Int) {
    LOW(1),
    MEDIUM(2),
    HIGH(3);

    companion object {
        fun fromRaw(value: String?): Urgency =
            when (value?.trim()?.lowercase()) {
                "high" -> HIGH
                "low" -> LOW
                else -> MEDIUM
            }
    }
}

/**
 * Extensible action type identifier.
 *
 * Built-in action constants are exposed in [companion object], but any valid action
 * id can be instantiated to support plugin-discovered actions.
 *
 * Whether an action is dispatchable (i.e. the planner may propose it) is determined
 * by [ai.neopsyke.agent.actions.ActionDescriptor.dispatchable], not by the type itself.
 */
data class ActionType(
    val id: String,
) {
    @get:JsonIgnore
    val name: String
        get() = id.uppercase(Locale.ROOT)

    companion object {
        val WEB_SEARCH: ActionType = ActionType("web_search")
        val CONTACT_USER: ActionType = ActionType("contact_user")
        val RESOLUTION_DRAFT: ActionType = ActionType("resolution_draft")
        val MCP_TIME: ActionType = ActionType("mcp_time")
        val WEBSITE_FETCH: ActionType = ActionType("website_fetch")
        val REFLECT: ActionType = ActionType("reflect")
        val PROJECT_OPERATION: ActionType = ActionType("project_operation")

        /** Built-in action set for compatibility with existing loops/tests. */
        val entries: Set<ActionType> = setOf(
            WEB_SEARCH,
            CONTACT_USER,
            RESOLUTION_DRAFT,
            MCP_TIME,
            WEBSITE_FETCH,
            REFLECT,
            PROJECT_OPERATION,
        )

        fun fromRaw(value: String?): ActionType? =
            value
                ?.trim()
                ?.lowercase(Locale.ROOT)
                ?.takeIf { it.matches(Regex("^[a-z][a-z0-9_]{1,63}$")) }
                ?.let { ActionType(it) }
    }
}

enum class ActionEffect {
    TASK_PROGRESS,
    EVIDENCE_GATHERED,
    DURABLE_MEMORY_SAVED,
    USER_MESSAGE_DELIVERED;

    companion object {
        fun fromRaw(value: String?): ActionEffect? =
            value
                ?.trim()
                ?.uppercase(Locale.ROOT)
                ?.replace('-', '_')
                ?.let { normalized -> entries.firstOrNull { it.name == normalized } }
    }
}

enum class InputPriority(val level: Int) {
    LOW(1),
    MEDIUM(2),
    HIGH(3);

    companion object {
        fun fromRaw(value: String?): InputPriority =
            when (value?.trim()?.lowercase()) {
                "high", "3" -> HIGH
                "low", "1" -> LOW
                else -> MEDIUM
            }
    }
}
