package psyke.agent.model

/**
 * Represents the identity of a conversation partner.
 *
 * The [id] is format-agnostic: it may be a human-readable name, a SHA-256 hash,
 * a JWT subject, an API token, or any other identifier appropriate for the
 * authentication layer in use. The optional [label] provides a human-friendly
 * display string (falls back to [id] when absent).
 *
 * Designed for extensibility — additional fields (kind, metadata, confidence)
 * can be added in future without breaking existing code.
 */
data class Interlocutor(
    val id: String,
    val label: String? = null,
) {
    companion object {
        /** Sentinel for inputs whose interlocutor cannot be determined. */
        val UNKNOWN = Interlocutor("unknown")

        /** Convenience factory for name-based identification. */
        fun named(name: String) = Interlocutor(id = name, label = name)
    }

    fun displayName(): String = label ?: id

    override fun toString(): String = "Interlocutor($id)"
}

/**
 * Carries session identity and interlocutor through the entire processing pipeline.
 *
 * Created at the sensory boundary (SensoryCortex / ChatRuntimeBridge), propagated
 * through PendingInput → PendingThought → PendingAction, and used by every
 * stateful subsystem to scope or tag its operations.
 */
data class ConversationContext(
    val sessionId: String,
    val interlocutor: Interlocutor,
) {
    init {
        require(sessionId.isNotBlank()) { "sessionId must not be blank." }
    }

    companion object {
        const val DEFAULT_SESSION_ID: String = "default"

        fun default(interlocutor: Interlocutor = Interlocutor.UNKNOWN) =
            ConversationContext(DEFAULT_SESSION_ID, interlocutor)
    }
}
