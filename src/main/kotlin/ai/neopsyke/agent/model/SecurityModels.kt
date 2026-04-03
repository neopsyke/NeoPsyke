package ai.neopsyke.agent.model

enum class PrincipalRole {
    OWNER,
    APPROVED_AUTOMATION,
    EXTERNAL_PARTICIPANT,
    SYSTEM_INTERNAL,
    ADMIN_CONTROL,
    UNAUTHENTICATED_EXTERNAL,
}

data class PrincipalRef(
    val id: String,
    val role: PrincipalRole,
    val label: String? = null,
    val attributes: Map<String, String> = emptyMap(),
)

enum class ChannelSurface {
    DIRECT,
    GROUP,
    SHARED_WORKSPACE,
    AUTOMATION,
    ADMIN,
}

enum class TransportClass {
    CHAT,
    WEBHOOK,
    API,
    INTERNAL,
}

data class ChannelRef(
    val provider: String,
    val surface: ChannelSurface,
    val transport: TransportClass,
    val channelId: String,
    val accountId: String? = null,
    val label: String? = null,
    val attributes: Map<String, String> = emptyMap(),
)

enum class InstructionTrust {
    TRUSTED_INSTRUCTION,
    UNTRUSTED_INSTRUCTION,
}

enum class DataTrust {
    TRUSTED_DATA,
    EXTERNAL_DATA,
    SANITIZED_EXTERNAL_DATA,
}

/**
 * Identifies the policy scope for a conversation or cognitive thread.
 *
 * | Scope | Behavior |
 * |---|---|
 * | [DEFAULT] | Normal local operation. Standard channel/principal/action rules apply. |
 * | [DEPLOYMENT_RESTRICTED] | Future use: forces all commit modes to approval-backed only. Intended for non-local deployments where autonomous execution must be gated. |
 * | [FULL_AUTONOMY] | Widens the autonomous action surface. May execute side-effecting actions without human approval. Use at your own risk — recommended to run sandboxed or containerised. |
 *
 * Configurable via `agent.policy_scope_id` in `agent-runtime.yaml` or the
 * `NEOPSYKE_POLICY_SCOPE_ID` env var. Per-channel overrides (e.g.
 * `NEOPSYKE_TELEGRAM_POLICY_SCOPE_ID`) take precedence for that channel.
 */
enum class PolicyScope {
    DEFAULT,
    DEPLOYMENT_RESTRICTED,
    FULL_AUTONOMY;

    /** Kebab-case identifier used in YAML, JSON, and env vars. */
    val id: String get() = name.lowercase().replace('_', '-')

    companion object {
        fun fromId(value: String): PolicyScope =
            entries.firstOrNull { it.id == value }
                ?: throw IllegalArgumentException(
                    "Unknown policy scope: '$value'. Valid values: ${entries.joinToString { it.id }}"
                )
    }
}

enum class ContentKind {
    MESSAGE,
    DOCUMENT,
    EVENT,
    RECORD,
    RESPONSE,
    SYSTEM_SIGNAL,
}

data class SourceDescriptor(
    val provider: String,
    val contentKind: ContentKind,
    val objectType: String,
    val part: String? = null,
    val sourceRef: String? = null,
    val attributes: Map<String, String> = emptyMap(),
)

data class SanitizationRecord(
    val method: String,
    val signalIds: Set<String> = emptySet(),
    val originalChars: Int = 0,
)

data class Provenance(
    val instructionTrust: InstructionTrust,
    val dataTrust: DataTrust,
    val source: SourceDescriptor,
    val sanitization: SanitizationRecord? = null,
)

data class ConversationSecurityContext(
    val principal: PrincipalRef,
    val channel: ChannelRef,
    val instructionTrust: InstructionTrust,
    val policyScope: PolicyScope = PolicyScope.DEFAULT,
)

data class CognitiveThreadSecurityContext(
    val policyScope: PolicyScope,
    val principalRole: PrincipalRole,
    val instructionTrust: InstructionTrust,
    val channelSurface: ChannelSurface,
    val aggregatedDataTrust: DataTrust,
    val taintSourceSummaries: List<String> = emptyList(),
) {
    companion object {
        private const val MAX_TAINT_SOURCE_SUMMARIES: Int = 8

        fun fromConversation(
            security: ConversationSecurityContext,
            aggregatedDataTrust: DataTrust = DataTrust.TRUSTED_DATA,
        ): CognitiveThreadSecurityContext =
            CognitiveThreadSecurityContext(
                policyScope = security.policyScope,
                principalRole = security.principal.role,
                instructionTrust = security.instructionTrust,
                channelSurface = security.channel.surface,
                aggregatedDataTrust = aggregatedDataTrust,
            )
    }

    fun withObservedArtifact(sourceSummary: String, dataTrust: DataTrust): CognitiveThreadSecurityContext {
        val normalizedSummary = sourceSummary.trim()
        val nextSources = if (normalizedSummary.isBlank()) {
            taintSourceSummaries
        } else {
            (taintSourceSummaries + normalizedSummary).distinct().takeLast(MAX_TAINT_SOURCE_SUMMARIES)
        }
        return copy(
            aggregatedDataTrust = minDataTrust(aggregatedDataTrust, dataTrust),
            taintSourceSummaries = nextSources,
        )
    }

    private fun minDataTrust(left: DataTrust, right: DataTrust): DataTrust {
        val worstRank = maxOf(left.rank(), right.rank())
        return when (worstRank) {
            2 -> DataTrust.EXTERNAL_DATA
            1 -> DataTrust.SANITIZED_EXTERNAL_DATA
            else -> DataTrust.TRUSTED_DATA
        }
    }

    private fun DataTrust.rank(): Int =
        when (this) {
            DataTrust.TRUSTED_DATA -> 0
            DataTrust.SANITIZED_EXTERNAL_DATA -> 1
            DataTrust.EXTERNAL_DATA -> 2
        }

}

object ConversationSecurityContexts {
    fun default(): ConversationSecurityContext =
        externalParticipant(
            provider = "unknown",
            channelId = ConversationContext.DEFAULT_SESSION_ID,
        )

    fun ownerDirect(
        provider: String,
        channelId: String,
        accountId: String? = null,
        principalId: String = "owner",
        principalLabel: String? = "Owner",
        policyScope: PolicyScope = PolicyScope.DEFAULT,
    ): ConversationSecurityContext =
        ConversationSecurityContext(
            principal = PrincipalRef(
                id = principalId,
                role = PrincipalRole.OWNER,
                label = principalLabel,
            ),
            channel = ChannelRef(
                provider = provider,
                surface = ChannelSurface.DIRECT,
                transport = TransportClass.CHAT,
                channelId = channelId,
                accountId = accountId,
            ),
            instructionTrust = InstructionTrust.TRUSTED_INSTRUCTION,
            policyScope = policyScope,
        )

    fun externalParticipant(
        provider: String,
        channelId: String,
        surface: ChannelSurface = ChannelSurface.GROUP,
        transport: TransportClass = TransportClass.CHAT,
        policyScope: PolicyScope = PolicyScope.DEFAULT,
    ): ConversationSecurityContext =
        ConversationSecurityContext(
            principal = PrincipalRef(
                id = "external",
                role = PrincipalRole.EXTERNAL_PARTICIPANT,
                label = "External participant",
            ),
            channel = ChannelRef(
                provider = provider,
                surface = surface,
                transport = transport,
                channelId = channelId,
            ),
            instructionTrust = InstructionTrust.UNTRUSTED_INSTRUCTION,
            policyScope = policyScope,
        )

    fun internalAutomation(
        provider: String,
        channelId: String,
        principalId: String = provider,
        policyScope: PolicyScope = PolicyScope.DEFAULT,
    ): ConversationSecurityContext =
        ConversationSecurityContext(
            principal = PrincipalRef(
                id = principalId,
                role = PrincipalRole.SYSTEM_INTERNAL,
                label = provider,
            ),
            channel = ChannelRef(
                provider = provider,
                surface = ChannelSurface.AUTOMATION,
                transport = TransportClass.INTERNAL,
                channelId = channelId,
            ),
            instructionTrust = InstructionTrust.TRUSTED_INSTRUCTION,
            policyScope = policyScope,
        )

    fun adminControl(
        provider: String,
        channelId: String,
        principalId: String = "admin",
        policyScope: PolicyScope = PolicyScope.DEFAULT,
    ): ConversationSecurityContext =
        ConversationSecurityContext(
            principal = PrincipalRef(
                id = principalId,
                role = PrincipalRole.ADMIN_CONTROL,
                label = "Admin control",
            ),
            channel = ChannelRef(
                provider = provider,
                surface = ChannelSurface.ADMIN,
                transport = TransportClass.INTERNAL,
                channelId = channelId,
            ),
            instructionTrust = InstructionTrust.TRUSTED_INSTRUCTION,
            policyScope = policyScope,
        )
}

object Provenances {
    private const val DEFAULT_PROVIDER: String = "unknown"

    fun defaultExternal(sourceRef: String? = null): Provenance =
        Provenance(
            instructionTrust = InstructionTrust.UNTRUSTED_INSTRUCTION,
            dataTrust = DataTrust.EXTERNAL_DATA,
            source = SourceDescriptor(
                provider = DEFAULT_PROVIDER,
                contentKind = ContentKind.MESSAGE,
                objectType = "external_input",
                sourceRef = sourceRef,
            ),
        )

    fun trustedMessage(provider: String, sourceRef: String? = null): Provenance =
        Provenance(
            instructionTrust = InstructionTrust.TRUSTED_INSTRUCTION,
            dataTrust = DataTrust.TRUSTED_DATA,
            source = SourceDescriptor(
                provider = provider,
                contentKind = ContentKind.MESSAGE,
                objectType = "message",
                sourceRef = sourceRef,
            ),
        )

    fun trustedSystemSignal(provider: String, sourceRef: String? = null): Provenance =
        Provenance(
            instructionTrust = InstructionTrust.TRUSTED_INSTRUCTION,
            dataTrust = DataTrust.TRUSTED_DATA,
            source = SourceDescriptor(
                provider = provider,
                contentKind = ContentKind.SYSTEM_SIGNAL,
                objectType = "system_signal",
                sourceRef = sourceRef,
            ),
        )

    fun sanitizedExternal(
        provider: String,
        contentKind: ContentKind,
        objectType: String,
        part: String? = null,
        sourceRef: String? = null,
        signalIds: Set<String> = emptySet(),
        originalChars: Int = 0,
    ): Provenance =
        Provenance(
            instructionTrust = InstructionTrust.UNTRUSTED_INSTRUCTION,
            dataTrust = DataTrust.SANITIZED_EXTERNAL_DATA,
            source = SourceDescriptor(
                provider = provider,
                contentKind = contentKind,
                objectType = objectType,
                part = part,
                sourceRef = sourceRef,
            ),
            sanitization = SanitizationRecord(
                method = "prompt_injection_defense_v1",
                signalIds = signalIds,
                originalChars = originalChars,
            ),
        )

    fun fromStimulusTrustLevel(
        source: String,
        trustLevel: StimulusTrustLevel,
        sourceRef: String? = null,
    ): Provenance =
        when (trustLevel) {
            StimulusTrustLevel.TRUSTED_INTERNAL -> trustedSystemSignal(provider = source, sourceRef = sourceRef)
            StimulusTrustLevel.DEFAULT -> defaultExternal(sourceRef = sourceRef).copy(
                source = SourceDescriptor(
                    provider = source,
                    contentKind = ContentKind.MESSAGE,
                    objectType = "stimulus",
                    sourceRef = sourceRef,
                ),
                dataTrust = DataTrust.SANITIZED_EXTERNAL_DATA,
            )
            StimulusTrustLevel.UNTRUSTED_EXTERNAL -> defaultExternal(sourceRef = sourceRef).copy(
                source = SourceDescriptor(
                    provider = source,
                    contentKind = ContentKind.MESSAGE,
                    objectType = "stimulus",
                    sourceRef = sourceRef,
                ),
            )
        }
}

fun ConversationSecurityContext.renderSummary(): String =
    buildString {
        append("principal_role=")
        append(principal.role.name.lowercase())
        append("\nchannel_provider=")
        append(channel.provider)
        append("\nchannel_surface=")
        append(channel.surface.name.lowercase())
        append("\ntransport_class=")
        append(channel.transport.name.lowercase())
        append("\ninstruction_trust=")
        append(instructionTrust.name.lowercase())
        append("\npolicy_scope_id=")
        append(policyScope.id)
    }

fun CognitiveThreadSecurityContext.renderSummary(): String =
    buildString {
        append("policy_scope_id=")
        append(policyScope.id)
        append("\nprincipal_role=")
        append(principalRole.name.lowercase())
        append("\ninstruction_trust=")
        append(instructionTrust.name.lowercase())
        append("\nchannel_surface=")
        append(channelSurface.name.lowercase())
        append("\naggregated_data_trust=")
        append(aggregatedDataTrust.name.lowercase())
        if (taintSourceSummaries.isNotEmpty()) {
            append("\ntaint_sources=")
            append(taintSourceSummaries.joinToString(" | "))
        }
    }

fun Provenance.renderSummary(): String =
    buildString {
        append("instruction_trust=")
        append(instructionTrust.name.lowercase())
        append("\ndata_trust=")
        append(dataTrust.name.lowercase())
        append("\nsource_provider=")
        append(source.provider)
        append("\ncontent_kind=")
        append(source.contentKind.name.lowercase())
        append("\nobject_type=")
        append(source.objectType)
        if (!source.part.isNullOrBlank()) {
            append("\nsource_part=")
            append(source.part)
        }
        // sourceRef excluded: contains volatile rootInputId/rootImpulseId
        // that the LLM does not need for decision-making. These IDs are
        // available in ChatCallMetadata for logging/tracing.
        sanitization?.let {
            append("\nsanitization_method=")
            append(it.method)
            append("\nsanitization_original_chars=")
            append(it.originalChars)
        }
    }
