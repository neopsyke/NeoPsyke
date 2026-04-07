package ai.neopsyke.agent

import ai.neopsyke.agent.support.DenialReasonClassifier
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DenialReasonClassifierTest {

    @Test
    fun `TECH_ reason codes are always classified as technical`() {
        assertTrue(DenialReasonClassifier.isLikelyTechnical("TECH_MODEL_UNAVAILABLE", "some reason"))
        assertTrue(DenialReasonClassifier.isLikelyTechnical("TECH_PARSE_ERROR", null))
    }

    @Test
    fun `POLICY_ reason codes are never classified as technical even if reason text contains signals`() {
        assertFalse(
            DenialReasonClassifier.isLikelyTechnical(
                "POLICY_WEBSITE_FETCH_PAYLOAD_INVALID_JSON",
                "WEBSITE_FETCH payload must be JSON like {\"url\":\"https://example.com\",\"max_chars\":1200}."
            )
        )
        assertFalse(
            DenialReasonClassifier.isLikelyTechnical(
                "POLICY_WEBSITE_FETCH_URL_MISSING",
                "WEBSITE_FETCH payload is missing required url."
            )
        )
    }

    @Test
    fun `text heuristic applies when no reason code is present`() {
        assertTrue(DenialReasonClassifier.isLikelyTechnical(null, "request timed out"))
        assertTrue(DenialReasonClassifier.isLikelyTechnical(null, "model error during review"))
        assertFalse(DenialReasonClassifier.isLikelyTechnical(null, "action is harmful"))
    }

    @Test
    fun `text heuristic applies when reason code is blank`() {
        assertTrue(DenialReasonClassifier.isLikelyTechnical("", "transport failure"))
        assertFalse(DenialReasonClassifier.isLikelyTechnical("", "user safety concern"))
    }

    // AC 22: Grounding gate technical retry reason code is classified as technical,
    // ensuring repeated-denied-action logic does not suppress legitimate evidence retries.
    @Test
    fun `TECH_GROUNDING_EVIDENCE_FAILURE is classified as technical`() {
        assertTrue(
            DenialReasonClassifier.isLikelyTechnical("TECH_GROUNDING_EVIDENCE_FAILURE", null)
        )
    }

    @Test
    fun `GROUNDING_EVIDENCE_REQUIRED is not classified as technical`() {
        assertFalse(
            DenialReasonClassifier.isLikelyTechnical("GROUNDING_EVIDENCE_REQUIRED", null)
        )
    }
}
