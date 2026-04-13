package ai.neopsyke.agent.ego.planner

import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.ego.planner.prompt.SharedPromptSections
import ai.neopsyke.agent.model.ActionExecutionStatus
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.EgoTrigger
import ai.neopsyke.agent.model.GroundingMetadata
import ai.neopsyke.agent.model.GroundingRequirement
import ai.neopsyke.agent.model.GroundingSource
import ai.neopsyke.agent.model.PendingInput
import ai.neopsyke.agent.model.PlannerContext
import ai.neopsyke.agent.model.QueueSnapshot
import ai.neopsyke.agent.model.QueuedIntention
import ai.neopsyke.agent.model.Urgency
import ai.neopsyke.agent.model.Intention
import ai.neopsyke.agent.model.IntentionKind
import ai.neopsyke.agent.model.PendingFeedback
import ai.neopsyke.agent.model.ActionOrigin
import ai.neopsyke.agent.cortex.sensory.ActionFeedbackCue
import ai.neopsyke.agent.durablework.DurableWorkActivation
import ai.neopsyke.agent.model.Percept
import ai.neopsyke.agent.model.PerceptFamily
import ai.neopsyke.support.RecordingInstrumentation
import ai.neopsyke.support.StubChatModelClient
import ai.neopsyke.support.buildTestHierarchicalPlanner
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AC 20: Every answer-producing lane sees groundingMetadata in PlannerContext.
 * The shared prompt section must appear only when grounding is REQUIRED.
 */
class GroundingPromptVisibilityTest {

    // --- SharedPromptSections direct tests ---

    @Test
    fun `groundingRequirementSection returns null when NOT_REQUIRED`() {
        val context = contextWith(GroundingRequirement.NOT_REQUIRED)
        assertNull(SharedPromptSections.groundingRequirementSection(context))
    }

    @Test
    fun `groundingRequirementSection returns section when REQUIRED`() {
        val context = contextWith(GroundingRequirement.REQUIRED)
        val section = SharedPromptSections.groundingRequirementSection(context)
        assertNotNull(section)
        assertTrue(section.content.contains("GROUNDING REQUIREMENT"))
    }

    // --- Per-lane prompt injection via HierarchicalPlanner ---

    @Test
    fun `DirectResponse lane includes grounding section when REQUIRED`() {
        assertLaneIncludesGroundingPrompt(
            route = "direct_response",
            classifierRequired = true,
            context = contextWith(GroundingRequirement.REQUIRED),
            trigger = inputTrigger(),
        )
    }

    @Test
    fun `DirectResponse lane omits grounding section when NOT_REQUIRED`() {
        assertLaneOmitsGroundingPrompt(
            route = "direct_response",
            classifierRequired = false,
            context = contextWith(GroundingRequirement.NOT_REQUIRED),
            trigger = inputTrigger(),
        )
    }

    @Test
    fun `GeneralAction lane includes grounding section when REQUIRED`() {
        assertLaneIncludesGroundingPrompt(
            route = "general_action",
            classifierRequired = true,
            context = contextWith(GroundingRequirement.REQUIRED),
            trigger = inputTrigger(),
        )
    }

    @Test
    fun `GeneralAction lane omits grounding section when NOT_REQUIRED`() {
        assertLaneOmitsGroundingPrompt(
            route = "general_action",
            classifierRequired = false,
            context = contextWith(GroundingRequirement.NOT_REQUIRED),
            trigger = inputTrigger(),
        )
    }

    @Test
    fun `TaskDecomposition lane includes grounding section when REQUIRED`() {
        assertLaneIncludesGroundingPrompt(
            route = "multi_step_task",
            classifierRequired = true,
            context = contextWith(GroundingRequirement.REQUIRED),
            trigger = inputTrigger(),
        )
    }

    @Test
    fun `TaskDecomposition lane omits grounding section when NOT_REQUIRED`() {
        assertLaneOmitsGroundingPrompt(
            route = "multi_step_task",
            classifierRequired = false,
            context = contextWith(GroundingRequirement.NOT_REQUIRED),
            trigger = inputTrigger(),
        )
    }

    @Test
    fun `DeferredStep lane includes grounding section when REQUIRED`() {
        assertLaneIncludesGroundingPrompt(
            route = null,
            context = contextWith(GroundingRequirement.REQUIRED),
            trigger = continuationTrigger(),
        )
    }

    @Test
    fun `DeferredStep lane omits grounding section when NOT_REQUIRED`() {
        assertLaneOmitsGroundingPrompt(
            route = null,
            context = contextWith(GroundingRequirement.NOT_REQUIRED),
            trigger = continuationTrigger(),
        )
    }

    @Test
    fun `Feedback lane includes grounding section when REQUIRED`() {
        assertLaneIncludesGroundingPrompt(
            route = null,
            context = contextWith(GroundingRequirement.REQUIRED),
            trigger = feedbackTrigger(),
        )
    }

    @Test
    fun `Feedback lane omits grounding section when NOT_REQUIRED`() {
        assertLaneOmitsGroundingPrompt(
            route = null,
            context = contextWith(GroundingRequirement.NOT_REQUIRED),
            trigger = feedbackTrigger(),
        )
    }

    @Test
    fun `GoalWork lane includes grounding section when REQUIRED`() {
        assertLaneIncludesGroundingPrompt(
            route = null,
            context = contextWith(GroundingRequirement.REQUIRED),
            trigger = goalWorkTrigger(),
        )
    }

    @Test
    fun `GoalWork lane omits grounding section when NOT_REQUIRED`() {
        assertLaneOmitsGroundingPrompt(
            route = null,
            context = contextWith(GroundingRequirement.NOT_REQUIRED),
            trigger = goalWorkTrigger(),
        )
    }

    // --- Helpers ---

    private fun assertLaneIncludesGroundingPrompt(
        route: String?,
        context: PlannerContext,
        trigger: EgoTrigger,
        classifierRequired: Boolean = false,
    ) {
        val llm = stubLlm(route, classifierRequired)
        val planner = buildTestHierarchicalPlanner(modelClient = llm)
        planner.decide(trigger, context)
        // Find the L2 sub-planner call (last call excluding router and classifier)
        val plannerCalls = llm.calls.filter {
            it.options.metadata.callSite != "input_intent_router" &&
                it.options.metadata.callSite != "grounding_classifier"
        }
        val lastCall = plannerCalls.lastOrNull()
        assertNotNull(lastCall, "Expected at least one L2 planner LLM call")
        val promptText = lastCall.messages.joinToString("\n") { it.content }
        assertTrue(
            promptText.contains("GROUNDING REQUIREMENT"),
            "Expected grounding section in prompt for trigger ${trigger::class.simpleName}"
        )
    }

    private fun assertLaneOmitsGroundingPrompt(
        route: String?,
        context: PlannerContext,
        trigger: EgoTrigger,
        classifierRequired: Boolean = false,
    ) {
        val llm = stubLlm(route, classifierRequired)
        val planner = buildTestHierarchicalPlanner(modelClient = llm)
        planner.decide(trigger, context)
        val plannerCalls = llm.calls.filter {
            it.options.metadata.callSite != "input_intent_router" &&
                it.options.metadata.callSite != "grounding_classifier"
        }
        val lastCall = plannerCalls.lastOrNull()
        assertNotNull(lastCall, "Expected at least one L2 planner LLM call")
        val promptText = lastCall.messages.joinToString("\n") { it.content }
        assertTrue(
            !promptText.contains("GROUNDING REQUIREMENT"),
            "Expected no grounding section in prompt for trigger ${trigger::class.simpleName}"
        )
    }

    private fun stubLlm(route: String?, classifierRequired: Boolean = false): StubChatModelClient {
        val llm = StubChatModelClient()
        if (route != null) {
            llm.enqueueRawResponseForCallSite("input_intent_router", """{"route":"$route","reasoning":"test"}""")
            llm.enqueueRawResponseForCallSite("grounding_classifier", """{"grounding_required":$classifierRequired}""")
        }
        // Default response for any planner call
        llm.enqueueRawResponse("""{"decision":"intend","intention_kind":"observe","action":"contact_user","payload":"test","summary":"test","reasoning":"test"}""")
        return llm
    }

    private fun contextWith(requirement: GroundingRequirement): PlannerContext =
        PlannerContext(
            recentDialogue = emptyList(),
            queue = QueueSnapshot(0, 0, 0),
            groundingMetadata = GroundingMetadata(requirement, GroundingSource.INPUT_CLASSIFIER),
        )

    private fun inputTrigger(): EgoTrigger.IncomingInput =
        EgoTrigger.IncomingInput(
            input = PendingInput(id = 1L, content = "test")
        )

    private fun continuationTrigger(): EgoTrigger.Continuation =
        EgoTrigger.Continuation(
            continuation = ai.neopsyke.agent.model.QueuedContinuation(
                urgency = Urgency.MEDIUM,
                continuation = ai.neopsyke.agent.model.Continuation.ConvergeNow(
                    content = "test continuation",
                    convergenceReason = "test",
                ),
                groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
            )
        )

    private fun feedbackTrigger(): EgoTrigger.ActionFeedback =
        EgoTrigger.ActionFeedback(
            feedback = PendingFeedback(
                cue = ActionFeedbackCue(
                    rootInputId = "r1",
                    actionType = ActionType.WEB_SEARCH,
                    actionSummary = "search",
                    feedbackContent = "results",
                    statusSummary = "ok",
                    plannerSignal = "ok",
                    executionStatus = ActionExecutionStatus.SUCCESS,
                    conversationContext = ConversationContext.default(),
                groundingMetadata = GroundingMetadata.NOT_REQUIRED_PREFILTER,
                ),
                percept = Percept(
                    id = "p1",
                    family = PerceptFamily.FEEDBACK,
                    summary = "feedback",
                    source = "test",
                    occurredAt = Instant.now(),
                    conversationContext = ConversationContext.default(),
                    cognitiveThreadId = "t1",
                ),
                stimulusId = "s1",
                stimulusContent = "feedback",
                receivedAtMs = System.currentTimeMillis(),
            )
        )

    private fun goalWorkTrigger(): EgoTrigger.DurableWork =
        EgoTrigger.DurableWork(
            workUnit = DurableWorkActivation(
                workItemId = "g1",
                stepId = "s1",
                rootInputId = "r1",
                stepDescription = "Check weather",
                acceptanceCriteria = "Fresh data",
                workingContext = "",
                conversationContext = ConversationContext.default(),
            groundingMetadata = GroundingMetadata(requirement = GroundingRequirement.NOT_REQUIRED, source = GroundingSource.DURABLE_WORK_STEP_POLICY),
            )
        )
}
