package ai.neopsyke.agent.ego

import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.model.Urgency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DecisionVerifierTest {
    private val verifier = DeterministicDecisionVerifier()
    private val evidenceActionTypes = setOf(ActionType.WEB_SEARCH, ActionType.MCP_TIME, ActionType.WEBSITE_FETCH)

    @Test
    fun `volatile factual answer requires evidence when tools are available`() {
        val decision = verifier.review(
            action = answerAction(payload = "The current price is 20 USD."),
            context = DecisionVerifierContext(
                latestUserTurn = "What is the latest price today?",
                availableActions = setOf(ActionType.WEB_SEARCH, ActionType.CONTACT_USER),
                dispatchableActions = setOf(ActionType.WEB_SEARCH, ActionType.CONTACT_USER),
                evidenceActionTypes = evidenceActionTypes,
                externalEvidence = DeliberationEngine.ExternalEvidenceProgress()
            )
        )

        assertFalse(decision.allow)
        assertEquals("TASK_EVIDENCE_REQUIRED", decision.reasonCode)
        assertEquals(TaskIntentCategory.VOLATILE_FACT, decision.assessment?.intentCategory)
        assertTrue(decision.assessment?.requiresExternalEvidence == true)
    }

    @Test
    fun `volatile factual answer degrades gracefully when evidence actions are unavailable`() {
        val decision = verifier.review(
            action = answerAction(payload = "The current score is 3-1."),
            context = DecisionVerifierContext(
                latestUserTurn = "What is the current score right now?",
                availableActions = setOf(ActionType.CONTACT_USER),
                dispatchableActions = setOf(ActionType.CONTACT_USER),
                evidenceActionTypes = evidenceActionTypes,
                externalEvidence = DeliberationEngine.ExternalEvidenceProgress()
            )
        )

        assertTrue(decision.allow)
        assertEquals("TASK_EVIDENCE_UNAVAILABLE_GRACEFUL", decision.reasonCode)
        assertTrue(decision.assessment?.requiresExternalEvidence == true)
        assertFalse(decision.assessment?.evidenceActionsAvailable == true)
    }

    @Test
    fun `transformation intent bypasses evidence requirement even with volatile words`() {
        val decision = verifier.review(
            action = answerAction(payload = "Rewritten sentence."),
            context = DecisionVerifierContext(
                latestUserTurn = "Rewrite this sentence in formal tone: current policy is strict.",
                availableActions = setOf(ActionType.WEB_SEARCH, ActionType.CONTACT_USER),
                dispatchableActions = setOf(ActionType.WEB_SEARCH, ActionType.CONTACT_USER),
                evidenceActionTypes = evidenceActionTypes,
                externalEvidence = DeliberationEngine.ExternalEvidenceProgress()
            )
        )

        assertTrue(decision.allow)
        assertEquals(TaskIntentCategory.TRANSFORMATION, decision.assessment?.intentCategory)
        assertTrue(decision.assessment?.requiresExternalEvidence == false)
    }

    @Test
    fun `unknown intent with low volatility does not require evidence`() {
        val decision = verifier.review(
            action = answerAction(payload = "maybe"),
            context = DecisionVerifierContext(
                latestUserTurn = "xqzv",
                availableActions = setOf(ActionType.WEB_SEARCH, ActionType.CONTACT_USER),
                dispatchableActions = setOf(ActionType.WEB_SEARCH, ActionType.CONTACT_USER),
                evidenceActionTypes = evidenceActionTypes,
                externalEvidence = DeliberationEngine.ExternalEvidenceProgress()
            )
        )

        assertTrue(decision.allow)
        assertEquals(TaskIntentCategory.UNKNOWN, decision.assessment?.intentCategory)
        assertEquals(null, decision.reasonCode)
    }

    @Test
    fun `successful evidence allows volatile factual answer`() {
        val decision = verifier.review(
            action = answerAction(payload = "Latest verified release is 2.1.0."),
            context = DecisionVerifierContext(
                latestUserTurn = "What is the latest release version?",
                availableActions = setOf(ActionType.WEB_SEARCH, ActionType.CONTACT_USER),
                dispatchableActions = setOf(ActionType.WEB_SEARCH, ActionType.CONTACT_USER),
                evidenceActionTypes = evidenceActionTypes,
                externalEvidence = DeliberationEngine.ExternalEvidenceProgress(
                    hadSuccessfulEvidence = true,
                    latestPlannerSignal = "official changelog"
                )
            )
        )

        assertTrue(decision.allow)
        val assessment = decision.assessment
        assertNotNull(assessment)
        assertEquals(TaskIntentCategory.VOLATILE_FACT, assessment.intentCategory)
        assertTrue(assessment.requiresExternalEvidence)
        assertTrue(assessment.hadSuccessfulEvidence)
    }

    private fun answerAction(payload: String): PendingAction =
        PendingAction(
            id = 1L,
            urgency = Urgency.MEDIUM,
            type = ActionType.CONTACT_USER,
            payload = payload,
            summary = "respond"
        )
}
