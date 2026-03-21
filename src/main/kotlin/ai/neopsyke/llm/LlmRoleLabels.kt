package ai.neopsyke.llm

object LlmRoleLabels {
    const val PLANNER: String = "planner"
    const val ACTION_VERIFIER: String = "action_verifier"
    const val SUPEREGO: String = "superego"
    const val META_REASONER: String = "meta_reasoner"
    const val MEMORY_ADVISOR: String = "memory_advisor"
    const val WEB_SEARCH: String = "web_search"
    const val UNKNOWN: String = "unknown"

    fun classify(
        actor: String?,
        callSite: String?,
        actionType: String?,
    ): String {
        val normalizedActor = actor.orEmpty().trim().lowercase()
        val normalizedCallSite = callSite.orEmpty().trim().lowercase()
        val normalizedActionType = actionType.orEmpty().trim().lowercase()

        return when {
            normalizedActor == "superego" -> SUPEREGO
            normalizedCallSite.startsWith("action_verifier") -> ACTION_VERIFIER
            normalizedCallSite.startsWith("meta_reasoner") -> META_REASONER
            normalizedCallSite.startsWith("long_term_memory_assessment") -> MEMORY_ADVISOR
            normalizedCallSite.startsWith("web_search") || normalizedActionType == WEB_SEARCH -> WEB_SEARCH
            normalizedActor == "ego" -> PLANNER
            else -> UNKNOWN
        }
    }
}
