package ai.neopsyke.agent.ego

import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.GroundingMetadata
import ai.neopsyke.agent.model.GroundingRequirement
import ai.neopsyke.agent.model.GroundingSource
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.model.Urgency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DecisionVerifierTest {
    private val gate = GroundingGate()
    private val evidenceActionTypes = setOf(ActionType.WEB_SEARCH, ActionType.WEBSITE_FETCH)

    // --- Non-contact_user and fallback bypass ---

    @Test
    fun `non-contact_user actions always allowed`() {
        val decision = gate.review(
            action = action(type = ActionType.WEB_SEARCH, grounding = GroundingRequirement.REQUIRED),
            context = contextWithEvidence()
        )
        assertTrue(decision.allow)
    }

    @Test
    fun `fallback explanation always allowed`() {
        val decision = gate.review(
            action = action(isFallbackExplanation = true, grounding = GroundingRequirement.REQUIRED),
            context = contextWithEvidence()
        )
        assertTrue(decision.allow)
    }

    // --- Grounding NOT_REQUIRED ---

    @Test
    fun `grounding not required allows action`() {
        val decision = gate.review(
            action = action(grounding = GroundingRequirement.NOT_REQUIRED),
            context = contextWithEvidence()
        )
        assertTrue(decision.allow)
        assertFalse(decision.groundingRequired)
    }

    // --- Grounding REQUIRED + evidence gathered ---

    @Test
    fun `grounding required with successful evidence allows action`() {
        val decision = gate.review(
            action = action(grounding = GroundingRequirement.REQUIRED),
            context = contextWithEvidence(hadSuccessfulEvidence = true)
        )
        assertTrue(decision.allow)
        assertTrue(decision.groundingRequired)
        assertTrue(decision.evidenceGathered)
    }

    // --- Grounding REQUIRED + evidence unavailable ---

    @Test
    fun `grounding required with unavailable evidence tools allows gracefully`() {
        val decision = gate.review(
            action = action(grounding = GroundingRequirement.REQUIRED),
            context = DecisionVerifierContext(
                externalEvidence = DeliberationEngine.ExternalEvidenceProgress(),
                availableActions = setOf(ActionType.CONTACT_USER),
                dispatchableActions = setOf(ActionType.CONTACT_USER),
                evidenceActionTypes = evidenceActionTypes,
            )
        )
        assertTrue(decision.allow)
        assertEquals(GroundingGate.REASON_CODE_GROUNDING_EVIDENCE_UNAVAILABLE_GRACEFUL, decision.reasonCode)
        assertTrue(decision.groundingRequired)
        assertTrue(decision.evidenceUnavailable)
    }

    // --- Grounding REQUIRED + technical evidence failure ---

    @Test
    fun `grounding required with technical evidence failure denies`() {
        val decision = gate.review(
            action = action(grounding = GroundingRequirement.REQUIRED),
            context = contextWithEvidence(hadExternalFailures = true)
        )
        assertFalse(decision.allow)
        assertEquals(GroundingGate.REASON_CODE_TECH_GROUNDING_EVIDENCE_FAILURE, decision.reasonCode)
        assertTrue(decision.groundingRequired)
        assertTrue(decision.evidenceFailedTechnically)
    }

    // --- Grounding REQUIRED + no evidence ---

    @Test
    fun `grounding required with no evidence denies`() {
        val decision = gate.review(
            action = action(grounding = GroundingRequirement.REQUIRED),
            context = contextWithEvidence()
        )
        assertFalse(decision.allow)
        assertEquals(GroundingGate.REASON_CODE_GROUNDING_EVIDENCE_REQUIRED, decision.reasonCode)
        assertTrue(decision.groundingRequired)
        assertFalse(decision.evidenceGathered)
    }

    // --- Forced terminal ---

    @Test
    fun `forced terminal with grounding not required allows`() {
        val decision = gate.review(
            action = action(grounding = GroundingRequirement.NOT_REQUIRED, isForcedTerminal = true),
            context = contextWithEvidence()
        )
        assertTrue(decision.allow)
        assertTrue(decision.forcedTerminal)
    }

    @Test
    fun `forced terminal with grounding required and successful evidence allows`() {
        val decision = gate.review(
            action = action(grounding = GroundingRequirement.REQUIRED, isForcedTerminal = true),
            context = contextWithEvidence(hadSuccessfulEvidence = true)
        )
        assertTrue(decision.allow)
    }

    @Test
    fun `forced terminal with grounding required and technical failures allows degraded`() {
        val decision = gate.review(
            action = action(grounding = GroundingRequirement.REQUIRED, isForcedTerminal = true),
            context = contextWithEvidence(
                hadExternalFailures = true,
                groundingTechnicalFailureBudgetExceeded = true,
            )
        )
        assertTrue(decision.allow)
        assertTrue(decision.forcedTerminal)
        assertEquals(GroundingGate.REASON_CODE_GROUNDING_EVIDENCE_UNAVAILABLE_GRACEFUL, decision.reasonCode)
    }

    @Test
    fun `forced terminal with grounding required and technical failures below budget denies`() {
        val decision = gate.review(
            action = action(grounding = GroundingRequirement.REQUIRED, isForcedTerminal = true),
            context = contextWithEvidence(
                hadExternalFailures = true,
                groundingTechnicalFailureBudgetExceeded = false,
            )
        )
        assertFalse(decision.allow)
        assertEquals(GroundingGate.REASON_CODE_TECH_GROUNDING_EVIDENCE_FAILURE, decision.reasonCode)
    }

    @Test
    fun `forced terminal with grounding required and no evidence denies`() {
        val decision = gate.review(
            action = action(grounding = GroundingRequirement.REQUIRED, isForcedTerminal = true),
            context = contextWithEvidence()
        )
        assertFalse(decision.allow)
        assertEquals(GroundingGate.REASON_CODE_GROUNDING_EVIDENCE_REQUIRED, decision.reasonCode)
    }

    // --- Helpers ---

    private fun action(
        type: ActionType = ActionType.CONTACT_USER,
        grounding: GroundingRequirement = GroundingRequirement.NOT_REQUIRED,
        isFallbackExplanation: Boolean = false,
        isForcedTerminal: Boolean = false,
    ): PendingAction = PendingAction(
        id = 1L,
        urgency = Urgency.MEDIUM,
        type = type,
        payload = "test payload",
        summary = "test",
        isFallbackExplanation = isFallbackExplanation,
        isForcedTerminal = isForcedTerminal,
        groundingMetadata = GroundingMetadata(grounding, GroundingSource.INPUT_CLASSIFIER),
    )

    private fun contextWithEvidence(
        hadSuccessfulEvidence: Boolean = false,
        hadExternalFailures: Boolean = false,
        groundingTechnicalFailureBudgetExceeded: Boolean = false,
    ): DecisionVerifierContext = DecisionVerifierContext(
        externalEvidence = DeliberationEngine.ExternalEvidenceProgress(
            hadSuccessfulEvidence = hadSuccessfulEvidence,
            hadExternalFailures = hadExternalFailures,
        ),
        availableActions = setOf(ActionType.WEB_SEARCH, ActionType.CONTACT_USER),
        dispatchableActions = setOf(ActionType.WEB_SEARCH, ActionType.CONTACT_USER),
        evidenceActionTypes = evidenceActionTypes,
        groundingTechnicalFailureBudgetExceeded = groundingTechnicalFailureBudgetExceeded,
    )
}
