package ai.neopsyke.agent.ego.planner.model

/**
 * Typed goal reference sealed hierarchy. Represents how the LLM resolved
 * a natural-language goal reference into a typed result.
 */
sealed interface GoalReference {

    /** Resolved to an exact internal goal ID. */
    data class ByInternalId(val id: String) : GoalReference

    /** LLM resolved a natural-language reference to a known goal. */
    data class ByResolvedEntity(
        val goalId: String,
        val resolvedFrom: String,
    ) : GoalReference

    /** Multiple candidate goals matched; clarification needed. */
    data class Ambiguous(
        val candidates: List<String>,
        val originalText: String,
    ) : GoalReference

    /** No matching goal found. */
    data class Unresolved(val originalText: String) : GoalReference
}
