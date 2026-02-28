package psyke.eval

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import psyke.support.StubChatModelClient
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReasoningSelfEvalRunnerTest {
    @Test
    fun `runner retries with validator feedback and eventually passes`() {
        val stub = StubChatModelClient().apply {
            enqueueRawResponse("""{"x":1}""")
            enqueueRawResponse("""{"x":2}""")
        }
        val task = ReasoningEvalTask(
            id = "x-equals-two",
            title = "X Equals Two",
            prompt = """Return {"x":2}""",
            validator = { json ->
                if (json["x"]?.asInt() == 2) emptyList() else listOf("x must be 2.")
            }
        )

        val report = ReasoningSelfEvalRunner(
            client = UsageTrackingChatClient(stub),
            tasks = listOf(task)
        ).run(
            ReasoningEvalOptions(
                maxAttemptsPerTask = 3,
                stage = "test-stage"
            )
        )

        assertEquals(1, report.summary.passedTasks)
        val result = report.taskResults.single()
        assertTrue(result.passed)
        assertEquals(2, result.attemptsUsed)
        assertEquals(2, result.modelCalls)
    }

    @Test
    fun `logic harness mode validates retry and feedback loop deterministically`() {
        val report = ReasoningSelfEvalRunner(
            client = UsageTrackingChatClient(ReasoningLogicHarnessClient()),
            tasks = ReasoningLogicEvalTasks.defaults()
        ).run(
            ReasoningEvalOptions(
                maxAttemptsPerTask = 4,
                mode = ReasoningEvalMode.LOGIC.id,
                stage = "logic-harness"
            )
        )

        assertEquals(3, report.summary.totalTasks)
        assertEquals(3, report.summary.passedTasks)
        assertTrue(report.taskResults.all { it.attemptsUsed >= 2 })
        assertTrue(report.taskResults.all { it.promptTokens > 0 })
        assertTrue(report.taskResults.all { it.completionTokens > 0 })
        assertTrue(report.taskResults.all { it.totalTokens > 0 })
        val feedbackCarry = report.taskResults.first { it.taskId == "feedback-carry" }
        assertEquals(3, feedbackCarry.attemptsUsed)
    }

    @Test
    fun `logic harness mode fails when retries are capped too low`() {
        val report = ReasoningSelfEvalRunner(
            client = UsageTrackingChatClient(ReasoningLogicHarnessClient()),
            tasks = ReasoningLogicEvalTasks.defaults()
        ).run(
            ReasoningEvalOptions(
                maxAttemptsPerTask = 1,
                mode = ReasoningEvalMode.LOGIC.id,
                stage = "logic-harness-cap"
            )
        )

        assertEquals(3, report.summary.totalTasks)
        assertEquals(0, report.summary.passedTasks)
        assertTrue(report.taskResults.all { it.attemptsUsed == 1 })
        assertTrue(report.taskResults.all { it.validationErrors.isNotEmpty() })
        assertFalse(report.taskResults.any { it.passed })
    }

    @Test
    fun `runner fails fast on unknown task filters`() {
        assertFailsWith<IllegalArgumentException> {
            ReasoningSelfEvalRunner(
                client = UsageTrackingChatClient(ReasoningLogicHarnessClient()),
                tasks = ReasoningLogicEvalTasks.defaults()
            ).run(
                ReasoningEvalOptions(
                    taskFilter = setOf("does-not-exist"),
                    mode = ReasoningEvalMode.LOGIC.id
                )
            )
        }
    }

    @Test
    fun `reporter writes run report and history`() {
        val report = ReasoningEvalReport(
            startedAtUtc = "2026-02-28T00:00:00Z",
            stage = "s1",
            modelName = "stub",
            options = ReasoningEvalOptions(maxAttemptsPerTask = 2, stage = "s1"),
            taskResults = listOf(
                ReasoningTaskResult(
                    taskId = "t1",
                    title = "T1",
                    passed = true,
                    attemptsUsed = 1,
                    durationMs = 10,
                    validationErrors = emptyList(),
                    finalAnswer = """{"ok":true}""",
                    modelCalls = 1,
                    promptTokens = 0,
                    completionTokens = 0,
                    totalTokens = 0,
                    callErrors = 0
                )
            ),
            summary = ReasoningEvalSummary(
                totalTasks = 1,
                passedTasks = 1,
                failedTasks = 0,
                passRate = 1.0,
                averageAttempts = 1.0,
                averageDurationMs = 10,
                totalModelCalls = 1
            )
        )

        val tempDir = Files.createTempDirectory("reasoning-eval-test")
        val runPath = ReasoningEvalReporter.writeRunReport(report, outputDir = tempDir.resolve("runs"))
        val historyPath = tempDir.resolve("history.jsonl")
        ReasoningEvalReporter.appendHistory(report, historyPath = historyPath)

        assertTrue(Files.exists(runPath))
        assertTrue(Files.exists(historyPath))
        val line = Files.readAllLines(historyPath).single()
        val parsed = jacksonObjectMapper().readTree(line)
        assertEquals("s1", parsed["stage"].asText())
        assertEquals(1, parsed["passed_tasks"].asInt())
    }
}
