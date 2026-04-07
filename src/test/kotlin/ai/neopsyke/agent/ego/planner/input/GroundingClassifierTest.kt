package ai.neopsyke.agent.ego.planner.input

import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.ego.planner.model.InputRoute
import ai.neopsyke.agent.ego.planner.runtime.PlannerRuntime
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.EgoTrigger
import ai.neopsyke.agent.model.GroundingMetadata
import ai.neopsyke.agent.model.GroundingRequirement
import ai.neopsyke.agent.model.GroundingSource
import ai.neopsyke.agent.model.PendingInput
import ai.neopsyke.agent.model.PlannerContext
import ai.neopsyke.agent.model.QueueSnapshot
import ai.neopsyke.instrumentation.NoopAgentInstrumentation
import ai.neopsyke.support.RecordingInstrumentation
import ai.neopsyke.support.StubChatModelClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroundingClassifierTest {

    // --- AC 17: Pre-filter deterministic routes ---

    @Test
    fun `prefilter returns NOT_REQUIRED for GoalCreation`() {
        val classifier = buildClassifier()
        val result = classifier.prefilter(InputRoute.GoalCreation("test"))
        assertNotNull(result)
        assertEquals(GroundingRequirement.NOT_REQUIRED, result.requirement)
        assertEquals(GroundingSource.INPUT_PREFILTER, result.source)
    }

    @Test
    fun `prefilter returns NOT_REQUIRED for GoalManagement`() {
        val classifier = buildClassifier()
        val result = classifier.prefilter(InputRoute.GoalManagement("test"))
        assertNotNull(result)
        assertEquals(GroundingRequirement.NOT_REQUIRED, result.requirement)
        assertEquals(GroundingSource.INPUT_PREFILTER, result.source)
    }

    @Test
    fun `prefilter returns NOT_REQUIRED for ClarificationNeeded`() {
        val classifier = buildClassifier()
        val result = classifier.prefilter(InputRoute.ClarificationNeeded("test"))
        assertNotNull(result)
        assertEquals(GroundingRequirement.NOT_REQUIRED, result.requirement)
        assertEquals(GroundingSource.INPUT_PREFILTER, result.source)
    }

    @Test
    fun `prefilter returns NOT_REQUIRED for Noop`() {
        val classifier = buildClassifier()
        val result = classifier.prefilter(InputRoute.Noop("test"))
        assertNotNull(result)
        assertEquals(GroundingRequirement.NOT_REQUIRED, result.requirement)
        assertEquals(GroundingSource.INPUT_PREFILTER, result.source)
    }

    @Test
    fun `prefilter returns null for DirectResponse`() {
        val classifier = buildClassifier()
        assertNull(classifier.prefilter(InputRoute.DirectResponse("test")))
    }

    @Test
    fun `prefilter returns null for GeneralAction`() {
        val classifier = buildClassifier()
        assertNull(classifier.prefilter(InputRoute.GeneralAction("test")))
    }

    @Test
    fun `prefilter returns null for MultiStepTask`() {
        val classifier = buildClassifier()
        assertNull(classifier.prefilter(InputRoute.MultiStepTask("test")))
    }

    @Test
    fun `deterministic route emits no grounding_classifier LLM call`() {
        val llm = StubChatModelClient()
        val classifier = buildClassifier(llm = llm)
        val result = classifier.classify(
            InputRoute.GoalCreation("create weather goal"),
            inputTrigger(),
            defaultContext(),
        )
        assertEquals(GroundingRequirement.NOT_REQUIRED, result.requirement)
        assertEquals(GroundingSource.INPUT_PREFILTER, result.source)
        assertTrue(llm.calls.none { it.options.metadata.callSite == "grounding_classifier" })
    }

    @Test
    fun `ambiguous route invokes grounding_classifier LLM call`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponseForCallSite("grounding_classifier", """{"grounding_required":true}""")
        }
        val classifier = buildClassifier(llm = llm)
        val result = classifier.classify(
            InputRoute.DirectResponse("what is weather in Hamburg"),
            inputTrigger("what is weather in Hamburg"),
            defaultContext(),
        )
        assertEquals(GroundingRequirement.REQUIRED, result.requirement)
        assertEquals(GroundingSource.INPUT_CLASSIFIER, result.source)
        assertTrue(llm.calls.any { it.options.metadata.callSite == "grounding_classifier" })
    }

    @Test
    fun `ambiguous route with grounding_required false returns NOT_REQUIRED`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponseForCallSite("grounding_classifier", """{"grounding_required":false}""")
        }
        val classifier = buildClassifier(llm = llm)
        val result = classifier.classify(
            InputRoute.DirectResponse("explain how gravity works"),
            inputTrigger("explain how gravity works"),
            defaultContext(),
        )
        assertEquals(GroundingRequirement.NOT_REQUIRED, result.requirement)
        assertEquals(GroundingSource.INPUT_CLASSIFIER, result.source)
    }

    // --- AC 18: Classifier fail-open fallback ---

    @Test
    fun `classifier coerces invalid JSON to NOT_REQUIRED`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponseForCallSite("grounding_classifier", """not valid json at all""")
        }
        val instrumentation = RecordingInstrumentation()
        val classifier = buildClassifier(llm = llm, instrumentation = instrumentation, config = AgentConfig(llmRetryAttempts = 1))
        val result = classifier.classify(
            InputRoute.DirectResponse("what is the price of bitcoin"),
            inputTrigger("what is the price of bitcoin"),
            defaultContext(),
        )
        assertEquals(GroundingRequirement.NOT_REQUIRED, result.requirement)
        assertEquals(GroundingSource.INPUT_CLASSIFIER, result.source)
        assertTrue(
            instrumentation.events.any { it.type == "grounding_classification_fallback_not_required" },
            "Expected grounding_classification_fallback_not_required event"
        )
    }

    @Test
    fun `classifier coerces missing grounding_required field to NOT_REQUIRED`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponseForCallSite("grounding_classifier", """{"some_other_field": true}""")
        }
        val instrumentation = RecordingInstrumentation()
        val classifier = buildClassifier(llm = llm, instrumentation = instrumentation, config = AgentConfig(llmRetryAttempts = 1))
        val result = classifier.classify(
            InputRoute.GeneralAction("what is the weather"),
            inputTrigger("what is the weather"),
            defaultContext(),
        )
        assertEquals(GroundingRequirement.NOT_REQUIRED, result.requirement)
        assertEquals(GroundingSource.INPUT_CLASSIFIER, result.source)
        assertTrue(
            instrumentation.events.any { it.type == "grounding_classification_fallback_not_required" },
            "Expected grounding_classification_fallback_not_required event"
        )
    }

    @Test
    fun `fallback result is visible to planner unchanged`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponseForCallSite("grounding_classifier", """garbage""")
        }
        val classifier = buildClassifier(llm = llm)
        val result = classifier.classify(
            InputRoute.DirectResponse("test"),
            inputTrigger(),
            defaultContext(),
        )
        // The fallback result should be usable by the planner as-is.
        assertEquals(GroundingRequirement.NOT_REQUIRED, result.requirement)
        assertEquals(GroundingSource.INPUT_CLASSIFIER, result.source)
    }

    // --- Helpers ---

    private fun buildClassifier(
        llm: StubChatModelClient = StubChatModelClient(),
        config: AgentConfig = AgentConfig(),
        instrumentation: ai.neopsyke.instrumentation.AgentInstrumentation = NoopAgentInstrumentation,
    ): GroundingClassifier {
        val runtime = PlannerRuntime(
            defaultModelClient = llm,
            config = config,
            instrumentation = instrumentation,
        )
        return GroundingClassifier(runtime, config, instrumentation)
    }

    private fun inputTrigger(content: String = "hello"): EgoTrigger.IncomingInput =
        EgoTrigger.IncomingInput(
            input = PendingInput(id = 1L, content = content)
        )

    private fun defaultContext(): PlannerContext =
        PlannerContext(
            recentDialogue = emptyList(),
            queue = QueueSnapshot(0, 0, 0),
        )
}
