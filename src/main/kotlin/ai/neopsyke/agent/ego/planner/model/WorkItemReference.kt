package ai.neopsyke.agent.ego.planner.model

/**
 * Typed assignment reference sealed hierarchy. Represents how the LLM resolved
 * a natural-language assignment reference into a typed result.
 */
sealed interface WorkItemReference {

    /** Resolved to an exact internal assignment ID. */
    data class ByInternalId(val id: String) : WorkItemReference

    /** LLM resolved a natural-language reference to a known assignment. */
    data class ByResolvedEntity(
        val workItemId: String,
        val resolvedFrom: String,
    ) : WorkItemReference

    /** Multiple candidate assignments matched; clarification needed. */
    data class Ambiguous(
        val candidates: List<String>,
        val originalText: String,
    ) : WorkItemReference

    /** No matching assignment found. */
    data class Unresolved(val originalText: String) : WorkItemReference
}
