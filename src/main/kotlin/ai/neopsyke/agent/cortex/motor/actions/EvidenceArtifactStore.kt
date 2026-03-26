package ai.neopsyke.agent.cortex.motor.actions

import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.ExternalContentArtifact

interface EvidenceArtifactStore {
    fun record(rootInputId: String?, conversationContext: ConversationContext, artifacts: List<ExternalContentArtifact>)

    fun resolveAll(
        rootInputId: String?,
        conversationContext: ConversationContext,
    ): List<ExternalContentArtifact>

    fun clear(rootInputId: String?, conversationContext: ConversationContext)
}

object NoopEvidenceArtifactStore : EvidenceArtifactStore {
    override fun record(rootInputId: String?, conversationContext: ConversationContext, artifacts: List<ExternalContentArtifact>) = Unit

    override fun resolveAll(
        rootInputId: String?,
        conversationContext: ConversationContext,
    ): List<ExternalContentArtifact> = emptyList()

    override fun clear(rootInputId: String?, conversationContext: ConversationContext) = Unit
}

class InMemoryEvidenceArtifactStore : EvidenceArtifactStore {
    private data class Scope(
        val rootInputId: String,
        val sessionId: String,
    )

    private val artifactsByScope = linkedMapOf<Scope, LinkedHashMap<String, ExternalContentArtifact>>()

    @Synchronized
    override fun record(rootInputId: String?, conversationContext: ConversationContext, artifacts: List<ExternalContentArtifact>) {
        val scope = scope(rootInputId, conversationContext) ?: return
        if (artifacts.isEmpty()) return
        val bucket = artifactsByScope.getOrPut(scope) { linkedMapOf() }
        artifacts.forEach { artifact ->
            bucket[artifact.id] = artifact
        }
    }

    @Synchronized
    override fun resolveAll(
        rootInputId: String?,
        conversationContext: ConversationContext,
    ): List<ExternalContentArtifact> {
        val scope = scope(rootInputId, conversationContext) ?: return emptyList()
        val bucket = artifactsByScope[scope] ?: return emptyList()
        return bucket.values.toList()
    }

    @Synchronized
    override fun clear(rootInputId: String?, conversationContext: ConversationContext) {
        val scope = scope(rootInputId, conversationContext) ?: return
        artifactsByScope.remove(scope)
    }

    private fun scope(rootInputId: String?, conversationContext: ConversationContext): Scope? {
        val normalizedRoot = rootInputId?.trim().orEmpty()
        if (normalizedRoot.isEmpty()) return null
        return Scope(rootInputId = normalizedRoot, sessionId = conversationContext.sessionId)
    }
}
