package psyke.agent.config

import psyke.agent.model.Interlocutor

/**
 * Resolves the [Interlocutor] for an incoming input.
 *
 * Implementations are injected at the sensory cortex boundary.
 * The default implementation returns a fixed identity; future implementations
 * may inspect HTTP headers, JWT tokens, API keys, or external identity services.
 */
interface InterlocutorResolver {
    fun resolve(source: String, metadata: Map<String, Any>? = null): Interlocutor
}

/**
 * Default resolver that maps all sources to a single interlocutor.
 */
class DefaultInterlocutorResolver(
    private val defaultInterlocutor: Interlocutor = Interlocutor.named("Victor"),
) : InterlocutorResolver {
    override fun resolve(source: String, metadata: Map<String, Any>?): Interlocutor =
        defaultInterlocutor
}
