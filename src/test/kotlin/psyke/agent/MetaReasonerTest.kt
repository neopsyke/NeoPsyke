package psyke.agent

import psyke.support.StubChatModelClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MetaReasonerTest {
    @Test
    fun `meta reasoner parses finalize verdict`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse(
                """
                {"verdict":"finalize_now","confidence":0.87,"reason":"stale loop and high pressure"}
                """.trimIndent()
            )
        }
        val reasoner = LlmMetaReasoner(
            modelClient = llm,
            config = AgentConfig(metaReasonerMaxTokens = 88)
        )

        val assessment = reasoner.assess(
            trigger = psyke.agent.core.EgoTrigger.PendingThoughtInput(
                PendingThought(
                    id = 1,
                    urgency = Urgency.MEDIUM,
                    content = "keep thinking"
                )
            ),
            context = PlannerContext(
                recentDialogue = listOf(DialogueTurn(DialogueRole.USER, "question")),
                queue = QueueSnapshot(0, 1, 0),
                deliberation = DeliberationState(
                    stepIndex = 25,
                    decisionPressure = 0.91,
                    staleStreak = 7,
                    progressScore = 0.1,
                    denialCount = 1,
                    stepsSinceNewEvidence = 9,
                    repeatSignatureHits = 3,
                    noopStreak = 4
                )
            )
        )

        assertEquals(MetaReasonerVerdict.FINALIZE_NOW, assessment.verdict)
        assertTrue(assessment.confidence > 0.8)
        assertEquals("meta_reasoner", llm.lastOptions.metadata.callSite)
        assertEquals(88, llm.lastOptions.maxTokens)
    }
}
