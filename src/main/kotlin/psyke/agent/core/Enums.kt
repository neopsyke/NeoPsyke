package psyke.agent.core

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
 * Types of actions recognized by the agent.
 *
 * [dispatchable] indicates whether the LLM planner can propose this action.
 * Non-dispatchable types (e.g. [MEMORY]) represent internal subsystem capabilities
 * that are tracked for status/dashboard purposes but never appear in the
 * planner's available/unavailable action lists.
 */
enum class ActionType(val dispatchable: Boolean = true) {
    WEB_SEARCH,
    ANSWER,
    MCP_TIME,
    MCP_FETCH,
    MEMORY(dispatchable = false);

    companion object {
        /** Subset of entries the planner is allowed to propose. */
        val DISPATCHABLE: Set<ActionType> = entries.filter { it.dispatchable }.toSet()

        fun fromRaw(value: String?): ActionType? =
            when (value?.trim()?.lowercase()) {
                "web_search" -> WEB_SEARCH
                "answer" -> ANSWER
                "mcp_time" -> MCP_TIME
                "mcp_fetch" -> MCP_FETCH
                "memory" -> MEMORY
                else -> null
            }
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
