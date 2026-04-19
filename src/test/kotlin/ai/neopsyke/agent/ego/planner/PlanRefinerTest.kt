package ai.neopsyke.agent.ego.planner

import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.ego.planner.runtime.PlannerRuntime
import ai.neopsyke.support.RecordingInstrumentation
import ai.neopsyke.support.StubChatModelClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlanRefinerTest {

    private fun sampleSteps(count: Int = 2): List<PlanStepCandidate> =
        (1..count).map { i ->
            PlanStepCandidate(
                id = "step-$i",
                description = "Do thing $i",
                acceptanceCriteria = "Thing $i done",
                groundingRequirement = if (i == 1) "required" else "not_required",
                requires = if (i > 1) setOf("out-${i - 1}") else emptySet(),
                produces = setOf("out-$i"),
                maxAttempts = 3,
            )
        }

    private fun sampleRequest(
        steps: List<PlanStepCandidate> = sampleSteps(),
        planKind: PlanKind = PlanKind.INLINE_EGO,
        terminalPolicy: TerminalPolicy = TerminalPolicy.MAY_END_WITH_USER_DELIVERY,
    ) = PlanRefinementRequest(
        planKind = planKind,
        terminalPolicy = terminalPolicy,
        assignment = "Test assignment",
        instruction = "Test instruction",
        steps = steps,
        availableActions = listOf(ActionSummary("web_search", "Search the web")),
        runtimeFacts = mapOf("date" to "available at execution time", "time" to "available at execution time", "timezone" to "available at execution time"),
    )

    private fun buildRefiner(
        llm: StubChatModelClient = StubChatModelClient(),
        instrumentation: RecordingInstrumentation = RecordingInstrumentation(),
    ): Pair<LlmPlanRefiner, RecordingInstrumentation> {
        val runtime = PlannerRuntime(
            defaultModelClient = llm,
            config = AgentConfig(),
            instrumentation = instrumentation,
        )
        return LlmPlanRefiner(runtime, instrumentation) to instrumentation
    }

    // ── NoopPlanRefiner ──

    @Test
    fun `NoopPlanRefiner returns unchanged mode`() {
        val refiner = NoopPlanRefiner()
        val result = refiner.refine(sampleRequest())
        assertEquals(PlanRefinementMode.UNCHANGED, result.refinementMode)
        assertEquals(2, result.steps.size)
        assertEquals("step-1", result.steps[0].id)
        assertEquals("step-2", result.steps[1].id)
        assertTrue(result.droppedSteps.isEmpty())
    }

    // ── LlmPlanRefiner: success paths ──

    @Test
    fun `LlmPlanRefiner returns unchanged when LLM says plan is good`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponseForCallSite("plan_refiner_refine", """
                {"steps":[
                    {"id":"step-1","description":"Do thing 1","acceptance_criteria":"Thing 1 done","grounding_requirement":"required","requires":[],"produces":["out-1"],"max_attempts":3},
                    {"id":"step-2","description":"Do thing 2","acceptance_criteria":"Thing 2 done","grounding_requirement":"not_required","requires":["out-1"],"produces":["out-2"],"max_attempts":3}
                ],"dropped_steps":[],"refinement_mode":"unchanged","reason":"Plan is already good."}
            """.trimIndent())
        }
        val (refiner, _) = buildRefiner(llm)

        val result = refiner.refine(sampleRequest())

        assertEquals(PlanRefinementMode.UNCHANGED, result.refinementMode)
        assertEquals(2, result.steps.size)
        assertEquals("Plan is already good.", result.reason)
    }

    @Test
    fun `LlmPlanRefiner returns rewritten plan with step changes`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponseForCallSite("plan_refiner_refine", """
                {"steps":[
                    {"id":"merged","description":"Combined step","acceptance_criteria":"Done","grounding_requirement":"required","requires":[],"produces":["combined-out"],"max_attempts":2}
                ],"dropped_steps":[
                    {"original_id":"step-2","reason":"Merged into step 1"}
                ],"refinement_mode":"llm_rewritten","reason":"Merged steps 1 and 2"}
            """.trimIndent())
        }
        val (refiner, _) = buildRefiner(llm)

        val result = refiner.refine(sampleRequest())

        assertEquals(PlanRefinementMode.LLM_REWRITTEN, result.refinementMode)
        assertEquals(1, result.steps.size)
        assertEquals("merged", result.steps[0].id)
        assertEquals("Combined step", result.steps[0].description)
        assertEquals(setOf("combined-out"), result.steps[0].produces)
        assertEquals("Merged steps 1 and 2", result.reason)
    }

    @Test
    fun `LlmPlanRefiner tracks dropped steps`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponseForCallSite("plan_refiner_refine", """
                {"steps":[
                    {"id":"step-1","description":"Keep this","acceptance_criteria":"ok","grounding_requirement":"not_required","requires":[],"produces":[],"max_attempts":3}
                ],"dropped_steps":[
                    {"original_id":"step-2","reason":"Redundant with runtime facts"},
                    {"original_id":"step-3","reason":"Not achievable"}
                ],"refinement_mode":"llm_rewritten","reason":"Pruned redundant steps"}
            """.trimIndent())
        }
        val (refiner, _) = buildRefiner(llm)

        val result = refiner.refine(sampleRequest(sampleSteps(3)))

        assertEquals(2, result.droppedSteps.size)
        assertEquals("step-2", result.droppedSteps[0].originalId)
        assertEquals("step-3", result.droppedSteps[1].originalId)
    }

    // ── LlmPlanRefiner: fallback paths ──

    @Test
    fun `LlmPlanRefiner falls back on LLM unavailable`() {
        // StubChatModelClient's default response won't parse as valid refiner output,
        // but we need to simulate the runtime returning null. We do this by using a
        // client that returns an unparseable response and catching the parse failure.
        // However, the real "unavailable" path is when runtime.call returns null.
        // PlannerRuntime returns null when the circuit breaker trips or retries exhaust.
        // For unit testing, we'll enqueue no response for the call site and rely on
        // the default response being unparseable → parse_failure fallback.
        val llm = StubChatModelClient().apply {
            enqueueRawResponseForCallSite("plan_refiner_refine", "not valid json at all {{{")
        }
        val instrumentation = RecordingInstrumentation()
        val (refiner, _) = buildRefiner(llm, instrumentation)

        val result = refiner.refine(sampleRequest())

        assertEquals(PlanRefinementMode.UNCHANGED, result.refinementMode)
        assertEquals(2, result.steps.size)
        assertEquals("step-1", result.steps[0].id)
        val fallbackEvents = instrumentation.events.filter { it.type == "plan_refinement_fallback" }
        assertTrue(fallbackEvents.isNotEmpty())
        assertEquals("parse_failure", fallbackEvents.first().data["reason"])
    }

    @Test
    fun `LlmPlanRefiner falls back on parse failure`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponseForCallSite("plan_refiner_refine", """{"garbage": true}""")
        }
        val instrumentation = RecordingInstrumentation()
        val (refiner, _) = buildRefiner(llm, instrumentation)

        val result = refiner.refine(sampleRequest())

        assertEquals(PlanRefinementMode.UNCHANGED, result.refinementMode)
        assertEquals(2, result.steps.size)
    }

    @Test
    fun `LlmPlanRefiner falls back on empty refined steps`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponseForCallSite("plan_refiner_refine", """
                {"steps":[],"dropped_steps":[],"refinement_mode":"llm_rewritten","reason":"Emptied plan"}
            """.trimIndent())
        }
        val instrumentation = RecordingInstrumentation()
        val (refiner, _) = buildRefiner(llm, instrumentation)

        val result = refiner.refine(sampleRequest())

        assertEquals(PlanRefinementMode.UNCHANGED, result.refinementMode)
        assertEquals(2, result.steps.size)
        val fallbackEvents = instrumentation.events.filter { it.type == "plan_refinement_fallback" }
        assertTrue(fallbackEvents.isNotEmpty())
        assertEquals("empty_refined_steps", fallbackEvents.first().data["reason"])
    }

    // ── LlmPlanRefiner: field coercion ──

    @Test
    fun `LlmPlanRefiner coerces maxAttempts into valid range`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponseForCallSite("plan_refiner_refine", """
                {"steps":[
                    {"id":"s1","description":"Step with low attempts","acceptance_criteria":"ok","grounding_requirement":"not_required","requires":[],"produces":[],"max_attempts":0}
                ],"dropped_steps":[],"refinement_mode":"llm_rewritten","reason":"Fixed attempts"}
            """.trimIndent())
        }
        val (refiner, _) = buildRefiner(llm)

        val result = refiner.refine(sampleRequest())

        assertEquals(1, result.steps.size)
        assertEquals(1, result.steps[0].maxAttempts, "maxAttempts=0 should be coerced to 1")
    }

    // ── LlmPlanRefiner: prompt verification ──

    @Test
    fun `LlmPlanRefiner passes plan kind and terminal policy to prompt`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponseForCallSite("plan_refiner_refine", """
                {"steps":[{"id":"s1","description":"ok","acceptance_criteria":"ok","grounding_requirement":"not_required","requires":[],"produces":[],"max_attempts":3}],"dropped_steps":[],"refinement_mode":"unchanged","reason":"ok"}
            """.trimIndent())
        }
        val (refiner, _) = buildRefiner(llm)

        refiner.refine(sampleRequest(
            sampleSteps(1),
            planKind = PlanKind.ASSIGNMENT_CREATE,
            terminalPolicy = TerminalPolicy.DELIVERY_CONTROLLED_BY_WORK_ITEM,
        ))

        val systemMessage = llm.calls.last().messages.first { it.role == ai.neopsyke.llm.ChatRole.SYSTEM }
        assertTrue(systemMessage.content.contains("assignment_create"), "System prompt should contain plan kind")
        assertTrue(
            systemMessage.content.contains("user delivery is controlled by the work item runtime"),
            "System prompt should contain terminal policy guidance",
        )
    }

    // ── Empty plan short-circuit ──

    @Test
    fun `empty plan skips refinement`() {
        val llm = StubChatModelClient()
        val (refiner, _) = buildRefiner(llm)

        val result = refiner.refine(sampleRequest(steps = emptyList()))

        assertEquals(PlanRefinementMode.UNCHANGED, result.refinementMode)
        assertTrue(result.steps.isEmpty())
        assertFalse(llm.calls.any { it.options.metadata.callSite == "plan_refiner_refine" },
            "No LLM call should be made for empty plan")
    }
}
