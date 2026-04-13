package ai.neopsyke.agent.model

/**
 * Binary grounding requirement: does the final user-facing answer need to be
 * backed by fresh external evidence?
 *
 * There is deliberately no UNKNOWN state. Ambiguous-route classifier failures
 * are coerced to [NOT_REQUIRED] as a conscious graceful-degradation choice.
 * This fail-open behavior is provisional and may be tightened if production
 * telemetry shows it is too permissive.
 */
enum class GroundingRequirement {
    REQUIRED,
    NOT_REQUIRED,
}

/**
 * Where the grounding decision originated.
 */
enum class GroundingSource {
    /** Deterministic pre-filter on typed [InputRoute] variant. */
    INPUT_PREFILTER,

    /** LLM-based classifier for ambiguous routes. */
    INPUT_CLASSIFIER,

    /** Typed policy on a goal step / activation. */
    DURABLE_WORK_STEP_POLICY,

    /** Inherited from an upstream envelope (continuation, feedback, etc.). */
    INHERITED,
}

/**
 * Typed grounding metadata carried on runtime envelopes.
 *
 * Set exactly once at classification/policy time and then copied forward
 * unchanged. Downstream components may read it but must not reinterpret it
 * from natural-language content or ask any model to return it.
 */
data class GroundingMetadata(
    val requirement: GroundingRequirement,
    val source: GroundingSource,
) {
    companion object {
        /** Default for triggers that do not require grounding. */
        val NOT_REQUIRED_PREFILTER = GroundingMetadata(
            requirement = GroundingRequirement.NOT_REQUIRED,
            source = GroundingSource.INPUT_PREFILTER,
        )
    }
}
