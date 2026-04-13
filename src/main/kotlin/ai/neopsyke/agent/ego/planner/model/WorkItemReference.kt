package ai.neopsyke.agent.ego.planner.model

/**
 * Typed goal reference sealed hierarchy. Represents how the LLM resolved
 * a natural-language goal reference into a typed result.
 */
sealed interface WorkItemReference {

    /** Resolved to an exact internal goal ID. */
    data class ByInternalId(val id: String) : WorkItemReference

    /** LLM resolved a natural-language reference to a known goal. */
    data class ByResolvedEntity(
        val workItemId: String,
        val resolvedFrom: String,
    ) : WorkItemReference

    /** Multiple candidate goals matched; clarification needed. */
    data class Ambiguous(
        val candidates: List<String>,
        val originalText: String,
    ) : WorkItemReference

    /** No matching goal found. */
    data class Unresolved(val originalText: String) : WorkItemReference
}
