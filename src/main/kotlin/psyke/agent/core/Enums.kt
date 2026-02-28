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

enum class ActionType {
    WEB_SEARCH,
    ANSWER,
    MCP_TIME,
    MCP_FETCH;

    companion object {
        fun fromRaw(value: String?): ActionType? =
            when (value?.trim()?.lowercase()) {
                "web_search" -> WEB_SEARCH
                "answer" -> ANSWER
                "mcp_time" -> MCP_TIME
                "mcp_fetch" -> MCP_FETCH
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
