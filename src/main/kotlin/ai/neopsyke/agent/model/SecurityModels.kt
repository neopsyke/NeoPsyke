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
    val policyScopeId: String = DEFAULT_POLICY_SCOPE_ID,
) {
    companion object {
        const val DEFAULT_POLICY_SCOPE_ID: String = "default"
    }
}

data class CognitiveThreadSecurityContext(
    val policyScopeId: String,
    val principalRole: PrincipalRole,
    val instructionTrust: InstructionTrust,
    val channelSurface: ChannelSurface,
    val aggregatedDataTrust: DataTrust,
) {
    companion object {
        fun fromConversation(
            security: ConversationSecurityContext,
            aggregatedDataTrust: DataTrust = DataTrust.SANITIZED_EXTERNAL_DATA,
        ): CognitiveThreadSecurityContext =
            CognitiveThreadSecurityContext(
                policyScopeId = security.policyScopeId,
                principalRole = security.principal.role,
                instructionTrust = security.instructionTrust,
                channelSurface = security.channel.surface,
                aggregatedDataTrust = aggregatedDataTrust,
            )
    }
}

object ConversationSecurityContexts {
    private const val DEFAULT_POLICY_SCOPE: String = ConversationSecurityContext.DEFAULT_POLICY_SCOPE_ID

    fun default(): ConversationSecurityContext =
        externalParticipant(
            provider = "unknown",
            channelId = ConversationContext.DEFAULT_SESSION_ID,
            policyScopeId = DEFAULT_POLICY_SCOPE,
        )

    fun ownerDirect(
        provider: String,
        channelId: String,
        accountId: String? = null,
        principalId: String = "owner",
        principalLabel: String? = "Owner",
        policyScopeId: String = DEFAULT_POLICY_SCOPE,
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
            policyScopeId = policyScopeId,
        )

    fun externalParticipant(
        provider: String,
        channelId: String,
        surface: ChannelSurface = ChannelSurface.GROUP,
        transport: TransportClass = TransportClass.CHAT,
        policyScopeId: String = DEFAULT_POLICY_SCOPE,
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
            policyScopeId = policyScopeId,
        )

    fun internalAutomation(
        provider: String,
        channelId: String,
        principalId: String = provider,
        policyScopeId: String = DEFAULT_POLICY_SCOPE,
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
            policyScopeId = policyScopeId,
        )

    fun adminControl(
        provider: String,
        channelId: String,
        principalId: String = "admin",
        policyScopeId: String = DEFAULT_POLICY_SCOPE,
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
            policyScopeId = policyScopeId,
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
