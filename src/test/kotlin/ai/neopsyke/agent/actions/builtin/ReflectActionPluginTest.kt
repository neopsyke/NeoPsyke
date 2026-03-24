package ai.neopsyke.agent.actions.builtin

import kotlinx.coroutines.runBlocking
import ai.neopsyke.agent.actions.ActionExecutionContext
import ai.neopsyke.agent.actions.ActionPluginFactoryContext
import ai.neopsyke.agent.actions.InMemoryEvidenceArtifactStore
import ai.neopsyke.agent.actions.ReflectionMemoryRecorder
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.ContentKind
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.model.SourceDescriptor
import ai.neopsyke.agent.model.SuperegoContext
import ai.neopsyke.agent.model.Urgency
import ai.neopsyke.agent.support.ExternalContentPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReflectActionPluginTest {
    @Test
    fun `internal reflection delegates trusted persistence`() = runBlocking {
        val recorder = RecordingReflectionMemoryRecorder()
        val plugin = ReflectInternalActionPlugin(recorder)

        val outcome = plugin.execute(
            action(
                type = ActionType.REFLECT_INTERNAL,
                payload = """{"summary":"I learned about coroutines","keywords":["kotlin","coroutines"]}""",
            ),
            ActionExecutionContext(searchResultCount = 0),
        )

        assertEquals("Reflection recorded to memory.", outcome.statusSummary)
        assertEquals(listOf("I learned about coroutines"), recorder.internalSummaries)
    }

    @Test
    fun `internal reflection rejects tainted thread context`() {
        val plugin = ReflectInternalActionPlugin(RecordingReflectionMemoryRecorder())

        val review = plugin.deterministicReview(
            action(
                type = ActionType.REFLECT_INTERNAL,
                payload = """{"summary":"trusted","keywords":[]}""",
            ),
            SuperegoContext(
                recentDialogue = emptyList(),
                threadSecurityContext = ai.neopsyke.agent.model.CognitiveThreadSecurityContext.fromConversation(
                    ai.neopsyke.agent.model.ConversationContext.default().security,
                    aggregatedDataTrust = ai.neopsyke.agent.model.DataTrust.SANITIZED_EXTERNAL_DATA,
                ),
            ),
            AgentConfig(),
        )

        assertNotNull(review)
        assertFalse(review.allow)
        assertEquals("reflect_internal_tainted_context", review.ruleId)
    }

    @Test
    fun `evidence reflection resolves same-request artifacts`() = runBlocking {
        val store = InMemoryEvidenceArtifactStore()
        val artifact = ExternalContentPipeline.ingest(
            text = "Use Gmail search operators for inbox cleanup.",
            maxChars = 300,
            source = SourceDescriptor(
                provider = "google_workspace",
                contentKind = ContentKind.RECORD,
                objectType = "gmail_observe_search",
                sourceRef = "root-1",
            ),
        )
        val action = action(
            type = ActionType.REFLECT_EVIDENCE,
            payload = """{"artifact_ids":["${artifact.id}"],"summary_hint":"Gmail search operators help triage inboxes","keywords":["gmail"]}""",
            rootInputId = "root-1",
        )
        store.record(action.rootInputId, action.conversationContext, listOf(artifact))
        val recorder = RecordingReflectionMemoryRecorder()
        val plugin = ReflectEvidenceActionPlugin(
            context = ActionPluginFactoryContext(
                config = AgentConfig(),
                webSearchActionHandler = null,
                mcpTimeTool = null,
                fetchTool = null,
                output = {},
                evidenceArtifactStore = store,
                reflectionMemoryRecorder = recorder,
            ),
            reflectionMemoryRecorder = recorder,
        )

        val outcome = plugin.execute(action, ActionExecutionContext(searchResultCount = 0))

        assertEquals("Reflection recorded to memory.", outcome.statusSummary)
        assertEquals(1, recorder.evidenceArtifactCounts.single())
    }

    private fun action(
        type: ActionType,
        payload: String,
        rootInputId: String? = null,
    ) = PendingAction(
        id = 1,
        urgency = Urgency.MEDIUM,
        type = type,
        payload = payload,
        summary = "reflect test",
        rootInputId = rootInputId,
    )

    private class RecordingReflectionMemoryRecorder : ReflectionMemoryRecorder {
        val internalSummaries = mutableListOf<String>()
        val evidenceArtifactCounts = mutableListOf<Int>()

        override fun recordInternalReflection(action: PendingAction, summary: String, keywords: List<String>): Boolean {
            internalSummaries += summary
            return true
        }

        override fun recordEvidenceReflection(
            action: PendingAction,
            summaryHint: String,
            keywords: List<String>,
            artifacts: List<ai.neopsyke.agent.model.ExternalContentArtifact>,
        ): Boolean {
            evidenceArtifactCounts += artifacts.size
            return true
        }
    }
}
