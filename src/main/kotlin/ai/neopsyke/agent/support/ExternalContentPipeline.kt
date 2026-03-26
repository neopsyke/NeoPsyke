package ai.neopsyke.agent.support

import ai.neopsyke.agent.model.ContentKind
import ai.neopsyke.agent.model.ExternalContentArtifact
import ai.neopsyke.agent.model.Provenances
import ai.neopsyke.agent.model.SourceDescriptor

object ExternalContentPipeline {
    fun ingest(
        text: String,
        maxChars: Int,
        source: SourceDescriptor,
    ): ExternalContentArtifact {
        val original = text.trim()
        val sanitized = PromptInjectionDefense.sanitizeExternalText(original, maxChars)
        val scan = PromptInjectionDefense.scan(original)
        return ExternalContentArtifact(
            content = sanitized.ifBlank { "No content returned." },
            provenance = Provenances.sanitizedExternal(
                provider = source.provider,
                contentKind = source.contentKind,
                objectType = source.objectType,
                part = source.part,
                sourceRef = source.sourceRef,
                signalIds = scan.signalIds,
                originalChars = original.length,
            ),
        )
    }

    fun recall(
        text: String,
        maxChars: Int,
        provider: String,
        sourceRef: String? = null,
    ): ExternalContentArtifact =
        ingest(
            text = text,
            maxChars = maxChars,
            source = SourceDescriptor(
                provider = provider,
                contentKind = ContentKind.RECORD,
                objectType = "memory_recall",
                sourceRef = sourceRef,
            ),
        )
}
