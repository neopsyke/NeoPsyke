package ai.neopsyke.agent.model

import ai.neopsyke.agent.actions.ActionCapability
import ai.neopsyke.agent.actions.ActionContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SecurityModelsTest {

    @Test
    fun `owner direct contexts are trusted by default`() {
        val security = ConversationSecurityContexts.ownerDirect(
            provider = "webapp",
            channelId = "session-1",
        )

        assertEquals(PrincipalRole.OWNER, security.principal.role)
        assertEquals(ChannelSurface.DIRECT, security.channel.surface)
        assertEquals(TransportClass.CHAT, security.channel.transport)
        assertEquals(InstructionTrust.TRUSTED_INSTRUCTION, security.instructionTrust)
    }

    @Test
    fun `action contract defaults allow explicit safe data classes`() {
        val contract = ActionContract(
            actionType = ActionType.WEB_SEARCH,
            effectClass = ActionEffectClass.OBSERVE,
            capabilities = setOf(ActionCapability.GATHERS_EVIDENCE),
        )

        assertTrue(contract.allowedInstructionTrust.contains(InstructionTrust.TRUSTED_INSTRUCTION))
        assertTrue(contract.allowedInstructionTrust.contains(InstructionTrust.UNTRUSTED_INSTRUCTION))
        assertTrue(contract.allowedArgumentDataTrust.contains(DataTrust.TRUSTED_DATA))
        assertTrue(contract.allowedArgumentDataTrust.contains(DataTrust.SANITIZED_EXTERNAL_DATA))
    }

    @Test
    fun `stimulus provenance defaults track trust level`() {
        val trusted = Provenances.fromStimulusTrustLevel(
            source = "goal-runtime",
            trustLevel = StimulusTrustLevel.TRUSTED_INTERNAL,
            sourceRef = "goal-1",
        )
        val external = Provenances.fromStimulusTrustLevel(
            source = "rss",
            trustLevel = StimulusTrustLevel.UNTRUSTED_EXTERNAL,
            sourceRef = "item-1",
        )

        assertEquals(InstructionTrust.TRUSTED_INSTRUCTION, trusted.instructionTrust)
        assertEquals(DataTrust.TRUSTED_DATA, trusted.dataTrust)
        assertEquals(InstructionTrust.UNTRUSTED_INSTRUCTION, external.instructionTrust)
        assertEquals(DataTrust.EXTERNAL_DATA, external.dataTrust)
    }
}
