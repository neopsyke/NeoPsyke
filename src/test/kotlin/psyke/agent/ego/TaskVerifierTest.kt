package psyke.agent.ego

import psyke.agent.core.ActionType
import psyke.agent.core.PendingAction
import psyke.agent.core.Urgency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TaskVerifierTest {
    private val verifier = DeterministicTaskVerifier()

    @Test
    fun `volatile factual answer requires evidence when tools are available`() {
        val decision = verifier.review(
            action = answerAction(payload = "The current price is 20 USD."),
            context = TaskVerifierContext(
                latestUserTurn = "What is the latest price today?",
                availableActions = setOf(ActionType.WEB_SEARCH, ActionType.ANSWER),
                dispatchableActions = setOf(ActionType.WEB_SEARCH, ActionType.ANSWER),
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
            context = TaskVerifierContext(
                latestUserTurn = "What is the current score right now?",
                availableActions = setOf(ActionType.ANSWER),
                dispatchableActions = setOf(ActionType.ANSWER),
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
            context = TaskVerifierContext(
                latestUserTurn = "Rewrite this sentence in formal tone: current policy is strict.",
                availableActions = setOf(ActionType.WEB_SEARCH, ActionType.ANSWER),
                dispatchableActions = setOf(ActionType.WEB_SEARCH, ActionType.ANSWER),
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
            context = TaskVerifierContext(
                latestUserTurn = "xqzv",
                availableActions = setOf(ActionType.WEB_SEARCH, ActionType.ANSWER),
                dispatchableActions = setOf(ActionType.WEB_SEARCH, ActionType.ANSWER),
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
            context = TaskVerifierContext(
                latestUserTurn = "What is the latest release version?",
                availableActions = setOf(ActionType.WEB_SEARCH, ActionType.ANSWER),
                dispatchableActions = setOf(ActionType.WEB_SEARCH, ActionType.ANSWER),
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
            type = ActionType.ANSWER,
            payload = payload,
            summary = "respond"
        )
}
