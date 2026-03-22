package ai.neopsyke.eval

import ai.neopsyke.agent.memory.longterm.Hippocampus
import ai.neopsyke.agent.memory.longterm.ImprintRequest
import ai.neopsyke.agent.memory.longterm.LongTermMemoryAdvisor
import ai.neopsyke.agent.memory.longterm.LongTermMemoryAssessmentContext
import ai.neopsyke.agent.memory.longterm.LongTermMemoryAssessmentDecision
import ai.neopsyke.agent.memory.longterm.ImprintResult
import ai.neopsyke.agent.memory.longterm.MemoryCapability
import ai.neopsyke.agent.memory.longterm.MemoryImprint
import ai.neopsyke.agent.memory.longterm.MemoryRecall
import ai.neopsyke.agent.memory.longterm.MemoryRecallQuery
import ai.neopsyke.support.RecordingInstrumentation
import ai.neopsyke.support.StubChatModelClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class MemoryLiveEvalRunnerTest {
    @Test
    fun `runner retries judge parse and surfaces parse failure reason`() {
        val llm = StubChatModelClient().apply {
            enqueueRawResponse("""{"pass":true,"score":0.9}""")
            enqueueRawResponse("")
        }
        val instrumentation = RecordingInstrumentation()
        val report = MemoryLiveEvalRunner(
            client = UsageTrackingChatClient(llm),
            longTermMemoryAdvisor = saveAlwaysAdvisor("User favorite color is teal."),
            hippocampus = FakeHippocampus(
                imprintResult = true,
                recallResult = MemoryRecall(
                    provider = "fake",
                    text = "- memory_eval_session=s1 favorite color is teal",
                    hitCount = 1,
                    truncated = false
                )
            ),
            tasks = listOf(
                MemoryLiveEvalTask(
                    id = "t1",
                    title = "T1",
                    userStatement = "Remember color preference.",
                    recallCue = "What color?",
                    expectedFacts = listOf("nonexistent-marker")
                )
            ),
            instrumentation = instrumentation
        ).run(
            MemoryLiveEvalOptions(
                stage = "test",
                sessionTag = "s1"
            )
        )

        val task = report.taskResults.single()
        assertFalse(task.passed)
        assertEquals("judge_parse_error", task.judgeReason)
        assertEquals("judge_parse_error", task.failureReason)
        assertNotNull(task.judgeParseError)
        assertEquals(2, task.modelCalls)
        assertEquals(220, llm.lastOptions.maxTokens)
        assertEquals(
            2,
            instrumentation.events.count { it.type == "eval_memory_judge_parse_error" }
        )
    }

    @Test
    fun `runner skips judge when recall content is empty`() {
        val llm = StubChatModelClient()
        val report = MemoryLiveEvalRunner(
            client = UsageTrackingChatClient(llm),
            longTermMemoryAdvisor = saveAlwaysAdvisor("Preference should be persisted."),
            hippocampus = FakeHippocampus(
                imprintResult = true,
                recallResult = MemoryRecall(
                    provider = "fake",
                    text = "",
                    hitCount = 1,
                    truncated = false
                )
            ),
            tasks = listOf(singleTask())
        ).run(MemoryLiveEvalOptions(stage = "test", sessionTag = "s2"))

        val task = report.taskResults.single()
        assertFalse(task.passed)
        assertEquals("recall_empty", task.judgeReason)
        assertEquals("recall_empty", task.failureReason)
        assertEquals(0, task.modelCalls)
    }

    @Test
    fun `runner reports imprint failure as primary failure reason`() {
        val llm = StubChatModelClient()
        val report = MemoryLiveEvalRunner(
            client = UsageTrackingChatClient(llm),
            longTermMemoryAdvisor = saveAlwaysAdvisor("Preference should be persisted."),
            hippocampus = FakeHippocampus(
                imprintResult = false,
                recallResult = MemoryRecall(
                    provider = "fake",
                    text = "- memory_eval_session=s3 favorite color is teal",
                    hitCount = 1,
                    truncated = false
                )
            ),
            tasks = listOf(singleTask())
        ).run(MemoryLiveEvalOptions(stage = "test", sessionTag = "s3"))

        val task = report.taskResults.single()
        assertFalse(task.passed)
        assertEquals("imprint_failed", task.failureReason)
        assertEquals("not_judged_imprint_failed", task.judgeReason)
        assertEquals("imprint_returned_false", task.imprintError)
        assertEquals(0, task.modelCalls)
    }

    private fun singleTask(): MemoryLiveEvalTask =
        MemoryLiveEvalTask(
            id = "t1",
            title = "T1",
            userStatement = "Remember color preference.",
            recallCue = "What color?",
            expectedFacts = listOf("favorite color", "teal")
        )

    private fun saveAlwaysAdvisor(summary: String): LongTermMemoryAdvisor =
        object : LongTermMemoryAdvisor {
            override fun assess(context: LongTermMemoryAssessmentContext): LongTermMemoryAssessmentDecision =
                LongTermMemoryAssessmentDecision(
                    shouldSave = true,
                    summary = summary,
                    confidence = 0.95,
                    reason = "durable preference"
                )
        }

    private class FakeHippocampus(
        private val imprintResult: Boolean,
        private val recallResult: MemoryRecall,
    ) : Hippocampus {
        override val providerName: String = "fake_memory"
        override val capabilities: Set<MemoryCapability> = setOf(
            MemoryCapability.SEMANTIC_RECALL,
            MemoryCapability.NARRATIVE_IMPRINT,
        )

        override fun recall(request: MemoryRecallQuery): MemoryRecall = recallResult

        override fun imprint(request: ImprintRequest): ImprintResult =
            ImprintResult(
                provider = providerName,
                accepted = imprintResult,
                storedCount = if (imprintResult) 1 else 0,
                detail = if (imprintResult) "" else "imprint_failed"
            )
    }
}
