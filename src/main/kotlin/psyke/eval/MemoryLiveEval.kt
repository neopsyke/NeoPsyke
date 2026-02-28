package psyke.eval

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import psyke.agent.DeliberationState
import psyke.agent.DialogueRole
import psyke.agent.DialogueTurn
import psyke.agent.Hippocampus
import psyke.agent.MemoryConsolidationAdvisor
import psyke.agent.MemoryConsolidationContext
import psyke.agent.MemoryConsolidationDecision
import psyke.agent.MemoryImprint
import psyke.agent.MemoryRecallQuery
import psyke.agent.TextSecurity
import psyke.instrumentation.AgentEvent
import psyke.instrumentation.AgentInstrumentation
import psyke.instrumentation.NoopAgentInstrumentation
import psyke.llm.ChatCallMetadata
import psyke.llm.ChatMessage
import psyke.llm.ChatRequestOptions
import psyke.llm.ChatRole
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.max

private val memoryEvalMapper = jacksonObjectMapper()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    .setSerializationInclusion(JsonInclude.Include.NON_NULL)

data class MemoryLiveEvalOptions(
    val stage: String = defaultMemoryEvalStageTag(),
    val taskFilter: Set<String> = emptySet(),
    val maxConsolidationAttempts: Int = 2,
    val sessionTag: String = defaultMemoryEvalSessionTag(),
)

data class MemoryLiveEvalTask(
    val id: String,
    val title: String,
    val userStatement: String,
    val recallCue: String,
    val expectedFacts: List<String>,
    val tags: List<String> = emptyList(),
)

data class MemoryLiveEvalTaskResult(
    val taskId: String,
    val title: String,
    val passed: Boolean,
    val attemptsUsed: Int,
    val durationMs: Long,
    val saved: Boolean,
    val saveConfidence: Double,
    val recallHitCount: Int,
    val recallChars: Int,
    val judgePass: Boolean,
    val judgeScore: Double,
    val judgeReason: String,
    val savedSummary: String,
    val recallPreview: String,
    val modelCalls: Int,
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val callErrors: Int,
    val runtimeError: String? = null,
)

data class MemoryLiveEvalSummary(
    val totalTasks: Int,
    val passedTasks: Int,
    val failedTasks: Int,
    val passRate: Double,
    val averageAttempts: Double,
    val averageDurationMs: Long,
    val totalModelCalls: Int,
)

data class MemoryLiveEvalReport(
    val startedAtUtc: String,
    val stage: String,
    val modelName: String,
    val options: MemoryLiveEvalOptions,
    val taskResults: List<MemoryLiveEvalTaskResult>,
    val summary: MemoryLiveEvalSummary,
)

class MemoryLiveEvalRunner(
    private val client: UsageTrackingChatClient,
    private val memoryConsolidationAdvisor: MemoryConsolidationAdvisor,
    private val hippocampus: Hippocampus,
    private val tasks: List<MemoryLiveEvalTask> = MemoryLiveEvalTasks.defaults(),
    private val instrumentation: AgentInstrumentation = NoopAgentInstrumentation,
) {
    fun run(options: MemoryLiveEvalOptions = MemoryLiveEvalOptions()): MemoryLiveEvalReport {
        require(hippocampus.enabled) { "Memory live eval requires an enabled Hippocampus provider." }
        require(memoryConsolidationAdvisor.enabled) { "Memory live eval requires an enabled MemoryConsolidationAdvisor." }

        val selectedTasks = selectTasks(options.taskFilter)
        require(selectedTasks.isNotEmpty()) { "No memory live eval tasks selected." }
        instrumentation.emit(
            AgentEvent(
                type = "eval_run_start",
                data = mapOf(
                    "eval_type" to "memory_live",
                    "mode" to "live",
                    "stage" to options.stage,
                    "task_count" to selectedTasks.size,
                    "max_attempts_per_task" to options.maxConsolidationAttempts,
                    "session_tag" to options.sessionTag
                )
            )
        )

        val taskResults = selectedTasks.mapIndexed { index, task ->
            instrumentation.emit(
                AgentEvent(
                    type = "eval_task_start",
                    data = mapOf(
                        "eval_type" to "memory_live",
                        "mode" to "live",
                        "task_id" to task.id,
                        "task_title" to task.title,
                        "task_index" to (index + 1),
                        "task_total" to selectedTasks.size
                    )
                )
            )
            runTask(task, options, index + 1, selectedTasks.size)
        }

        val passedTasks = taskResults.count { it.passed }
        val failedTasks = taskResults.size - passedTasks
        val avgAttempts = taskResults.map { it.attemptsUsed.toDouble() }.average()
        val avgDuration = taskResults.map { it.durationMs }.average().toLong()
        val totalCalls = taskResults.sumOf { it.modelCalls }
        val report = MemoryLiveEvalReport(
            startedAtUtc = Instant.now().toString(),
            stage = options.stage,
            modelName = client.modelName,
            options = options,
            taskResults = taskResults,
            summary = MemoryLiveEvalSummary(
                totalTasks = taskResults.size,
                passedTasks = passedTasks,
                failedTasks = failedTasks,
                passRate = if (taskResults.isEmpty()) 0.0 else passedTasks.toDouble() / taskResults.size.toDouble(),
                averageAttempts = avgAttempts,
                averageDurationMs = avgDuration,
                totalModelCalls = totalCalls
            )
        )
        instrumentation.emit(
            AgentEvent(
                type = "eval_run_complete",
                data = mapOf(
                    "eval_type" to "memory_live",
                    "mode" to "live",
                    "stage" to report.stage,
                    "passed_tasks" to report.summary.passedTasks,
                    "total_tasks" to report.summary.totalTasks,
                    "failed_tasks" to report.summary.failedTasks,
                    "pass_rate" to report.summary.passRate,
                    "avg_attempts" to report.summary.averageAttempts,
                    "avg_duration_ms" to report.summary.averageDurationMs,
                    "total_model_calls" to report.summary.totalModelCalls,
                    "session_tag" to options.sessionTag
                )
            )
        )
        return report
    }

    private fun runTask(
        task: MemoryLiveEvalTask,
        options: MemoryLiveEvalOptions,
        index: Int,
        total: Int,
    ): MemoryLiveEvalTaskResult {
        val usageBefore = client.snapshot()
        val startedAt = System.nanoTime()
        var attemptsUsed = 0
        var runtimeError: String? = null
        var decision = MemoryConsolidationDecision(
            shouldSave = false,
            summary = "",
            confidence = 0.0,
            reason = "not_attempted"
        )

        for (attempt in 1..options.maxConsolidationAttempts) {
            attemptsUsed = attempt
            instrumentation.emit(
                AgentEvent(
                    type = "eval_attempt_start",
                    data = mapOf(
                        "eval_type" to "memory_live",
                        "mode" to "live",
                        "task_id" to task.id,
                        "task_index" to index,
                        "task_total" to total,
                        "attempt" to attempt,
                        "max_attempts" to options.maxConsolidationAttempts
                    )
                )
            )
            val context = buildConsolidationContext(task, options.sessionTag, attempt)
            decision = try {
                memoryConsolidationAdvisor.assess(context)
            } catch (ex: Exception) {
                runtimeError = ex.message ?: ex::class.simpleName ?: "memory_eval_consolidation_error"
                instrumentation.emit(
                    AgentEvent(
                        type = "warning",
                        data = mapOf(
                            "message" to "[EVAL:memory_live] task=${task.id} consolidation runtime error on attempt=$attempt: $runtimeError"
                        )
                    )
                )
                break
            }
            val attemptPassed = decision.shouldSave && decision.summary.isNotBlank()
            instrumentation.emit(
                AgentEvent(
                    type = "eval_attempt_result",
                    data = mapOf(
                        "eval_type" to "memory_live",
                        "mode" to "live",
                        "task_id" to task.id,
                        "attempt" to attempt,
                        "passed" to attemptPassed,
                        "response_chars" to decision.summary.length,
                        "validation_errors" to if (attemptPassed) {
                            emptyList<String>()
                        } else {
                            listOf("Consolidation advisor did not produce a saveable summary.")
                        }
                    )
                )
            )
            if (attemptPassed) {
                break
            }
        }

        if (runtimeError != null) {
            return buildFailureResult(task, usageBefore, startedAt, attemptsUsed, runtimeError)
        }
        if (!decision.shouldSave || decision.summary.isBlank()) {
            return buildFailureResult(
                task = task,
                usageBefore = usageBefore,
                startedAtNanos = startedAt,
                attemptsUsed = attemptsUsed,
                runtimeError = "consolidation_did_not_save"
            )
        }

        val taggedSummary = "[memory-eval:${options.sessionTag}:${task.id}] ${decision.summary.trim()}"
        val imprintStartedAt = System.nanoTime()
        val saved = try {
            hippocampus.imprint(
                MemoryImprint(
                    summary = taggedSummary,
                    source = "memory_eval_live",
                    confidence = decision.confidence,
                    tags = task.tags + listOf("memory_eval_live", task.id, options.sessionTag)
                )
            )
        } catch (ex: Exception) {
            runtimeError = ex.message ?: ex::class.simpleName ?: "memory_eval_imprint_error"
            false
        }
        val imprintLatencyMs = elapsedMillis(imprintStartedAt)

        val recall = try {
            val cue = buildRecallCue(task, options.sessionTag)
            hippocampus.recall(
                MemoryRecallQuery(
                    cue = cue,
                    recentDialogue = listOf(
                        DialogueTurn(DialogueRole.USER, task.recallCue)
                    ),
                    memorySummary = "",
                    maxItems = 4,
                    maxChars = 1_600
                )
            )
        } catch (ex: Exception) {
            runtimeError = runtimeError ?: (ex.message ?: ex::class.simpleName ?: "memory_eval_recall_error")
            null
        }

        val judge = if (recall == null) {
            JudgeResult(
                pass = false,
                score = 0.0,
                reason = "recall_failed"
            )
        } else {
            judgeRecall(task, sessionTag = options.sessionTag, retrievedText = recall.text)
        }

        val durationMs = elapsedMillis(startedAt)
        val usageAfter = client.snapshot()
        val usage = usageAfter - usageBefore
        val passed = saved && recall != null && judge.pass && recall.hitCount > 0
        instrumentation.emit(
            AgentEvent(
                type = "eval_task_result",
                data = mapOf(
                    "eval_type" to "memory_live",
                    "mode" to "live",
                    "task_id" to task.id,
                    "task_title" to task.title,
                    "passed" to passed,
                    "attempts_used" to attemptsUsed,
                    "duration_ms" to durationMs,
                    "saved" to saved,
                    "save_confidence" to decision.confidence,
                    "imprint_latency_ms" to imprintLatencyMs,
                    "recall_hit_count" to (recall?.hitCount ?: 0),
                    "judge_pass" to judge.pass,
                    "judge_score" to judge.score,
                    "judge_reason" to judge.reason,
                    "model_calls" to usage.calls,
                    "prompt_tokens" to usage.promptTokens,
                    "completion_tokens" to usage.completionTokens,
                    "total_tokens" to usage.totalTokens,
                    "call_errors" to usage.callErrors,
                    "runtime_error" to runtimeError
                )
            )
        )
        return MemoryLiveEvalTaskResult(
            taskId = task.id,
            title = task.title,
            passed = passed,
            attemptsUsed = attemptsUsed,
            durationMs = durationMs,
            saved = saved,
            saveConfidence = decision.confidence,
            recallHitCount = recall?.hitCount ?: 0,
            recallChars = recall?.text?.length ?: 0,
            judgePass = judge.pass,
            judgeScore = judge.score,
            judgeReason = judge.reason,
            savedSummary = TextSecurity.clamp(taggedSummary, 360),
            recallPreview = TextSecurity.preview(recall?.text ?: "", 360),
            modelCalls = usage.calls,
            promptTokens = usage.promptTokens,
            completionTokens = usage.completionTokens,
            totalTokens = usage.totalTokens,
            callErrors = usage.callErrors,
            runtimeError = runtimeError
        )
    }

    private fun buildFailureResult(
        task: MemoryLiveEvalTask,
        usageBefore: UsageSnapshot,
        startedAtNanos: Long,
        attemptsUsed: Int,
        runtimeError: String?,
    ): MemoryLiveEvalTaskResult {
        val durationMs = elapsedMillis(startedAtNanos)
        val usageAfter = client.snapshot()
        val usage = usageAfter - usageBefore
        instrumentation.emit(
            AgentEvent(
                type = "eval_task_result",
                data = mapOf(
                    "eval_type" to "memory_live",
                    "mode" to "live",
                    "task_id" to task.id,
                    "task_title" to task.title,
                    "passed" to false,
                    "attempts_used" to attemptsUsed,
                    "duration_ms" to durationMs,
                    "saved" to false,
                    "save_confidence" to 0.0,
                    "imprint_latency_ms" to 0,
                    "recall_hit_count" to 0,
                    "judge_pass" to false,
                    "judge_score" to 0.0,
                    "judge_reason" to "not_judged",
                    "model_calls" to usage.calls,
                    "prompt_tokens" to usage.promptTokens,
                    "completion_tokens" to usage.completionTokens,
                    "total_tokens" to usage.totalTokens,
                    "call_errors" to usage.callErrors,
                    "runtime_error" to runtimeError
                )
            )
        )
        return MemoryLiveEvalTaskResult(
            taskId = task.id,
            title = task.title,
            passed = false,
            attemptsUsed = attemptsUsed,
            durationMs = durationMs,
            saved = false,
            saveConfidence = 0.0,
            recallHitCount = 0,
            recallChars = 0,
            judgePass = false,
            judgeScore = 0.0,
            judgeReason = "not_judged",
            savedSummary = "",
            recallPreview = "",
            modelCalls = usage.calls,
            promptTokens = usage.promptTokens,
            completionTokens = usage.completionTokens,
            totalTokens = usage.totalTokens,
            callErrors = usage.callErrors,
            runtimeError = runtimeError
        )
    }

    private fun buildConsolidationContext(
        task: MemoryLiveEvalTask,
        sessionTag: String,
        attempt: Int,
    ): MemoryConsolidationContext {
        val marker = "memory_eval_session=$sessionTag task=${task.id} attempt=$attempt"
        return MemoryConsolidationContext(
            trigger = "memory_eval_live:${task.id}:attempt=$attempt",
            deliberation = DeliberationState(
                stepIndex = 8,
                decisionPressure = 0.35,
                staleStreak = 0,
                progressScore = 0.7,
                denialCount = 0,
                stepsSinceNewEvidence = 0,
                repeatSignatureHits = 0,
                noopStreak = 0
            ),
            recentDialogue = listOf(
                DialogueTurn(
                    role = DialogueRole.USER,
                    content = "$marker ${task.userStatement}"
                ),
                DialogueTurn(
                    role = DialogueRole.ASSISTANT,
                    content = "Acknowledged. I will retain this for future context."
                )
            ),
            memorySummary = "",
            memoryRecall = "",
            metaGuidance = "Prefer storing durable user/project memory for future recall."
        )
    }

    private fun buildRecallCue(task: MemoryLiveEvalTask, sessionTag: String): String =
        buildString {
            append(task.recallCue.trim())
            append('\n')
            append("memory_eval_session=")
            append(sessionTag)
            append('\n')
            append("task_id=")
            append(task.id)
        }

    private fun judgeRecall(
        task: MemoryLiveEvalTask,
        sessionTag: String,
        retrievedText: String,
    ): JudgeResult {
        val messages = listOf(
            ChatMessage(
                role = ChatRole.SYSTEM,
                content = """
                You are evaluating memory retrieval quality for an automated test.
                Return STRICT JSON only:
                {"pass":true|false,"score":0.0-1.0,"reason":"<=140 chars"}
                pass=true only if retrieved text includes BOTH:
                1) session tag marker
                2) expected task facts semantically
                """.trimIndent()
            ),
            ChatMessage(
                role = ChatRole.USER,
                content = """
                session_tag=$sessionTag
                task_id=${task.id}
                expected_facts=${task.expectedFacts.joinToString(" | ")}
                retrieved_text:
                $retrievedText
                """.trimIndent()
            )
        )
        return try {
            val raw = client.chat(
                messages = messages,
                options = ChatRequestOptions(
                    temperature = 0.0,
                    maxTokens = 120,
                    metadata = ChatCallMetadata(
                        actor = "memory_eval",
                        callSite = "${task.id}:judge"
                    )
                )
            ).content
            val json = TextSecurity.extractJsonObject(raw)
            val parsed = memoryEvalMapper.readValue<JudgePayload>(json)
            JudgeResult(
                pass = parsed.pass == true,
                score = parsed.score?.coerceIn(0.0, 1.0) ?: 0.0,
                reason = TextSecurity.clamp(parsed.reason?.trim().orEmpty().ifBlank { "no_reason" }, 140)
            )
        } catch (_: Exception) {
            fallbackJudge(task, sessionTag, retrievedText)
        }
    }

    private fun fallbackJudge(
        task: MemoryLiveEvalTask,
        sessionTag: String,
        retrievedText: String,
    ): JudgeResult {
        val lower = retrievedText.lowercase()
        val hasTag = lower.contains(sessionTag.lowercase())
        val expectedMatchCount = task.expectedFacts.count { fact ->
            lower.contains(fact.lowercase())
        }
        val allMatched = expectedMatchCount >= task.expectedFacts.size
        return if (hasTag && allMatched) {
            JudgeResult(
                pass = true,
                score = 0.8,
                reason = "fallback lexical match"
            )
        } else {
            JudgeResult(
                pass = false,
                score = 0.2,
                reason = "fallback lexical mismatch"
            )
        }
    }

    private fun selectTasks(filter: Set<String>): List<MemoryLiveEvalTask> {
        if (filter.isEmpty()) return tasks
        val selected = tasks.filter { filter.contains(it.id) }
        require(selected.isNotEmpty()) {
            "No memory live eval tasks matched filter: ${filter.joinToString(",")}."
        }
        return selected
    }

    private data class JudgePayload(
        val pass: Boolean? = null,
        val score: Double? = null,
        val reason: String? = null,
    )

    private data class JudgeResult(
        val pass: Boolean,
        val score: Double,
        val reason: String,
    )
}

object MemoryLiveEvalTasks {
    fun defaults(): List<MemoryLiveEvalTask> = listOf(
        MemoryLiveEvalTask(
            id = "user-preference-color",
            title = "User Preference Memory",
            userStatement = "Remember this preference for future replies: my favorite color is teal.",
            recallCue = "What is my favorite color?",
            expectedFacts = listOf("favorite color", "teal"),
            tags = listOf("preference", "user_profile")
        ),
        MemoryLiveEvalTask(
            id = "project-constraint-timezone",
            title = "Project Constraint Memory",
            userStatement = "Project policy: all schedule times must be in Europe/Berlin timezone.",
            recallCue = "Which timezone should schedules use for this project?",
            expectedFacts = listOf("europe/berlin", "timezone"),
            tags = listOf("constraint", "project_policy")
        ),
        MemoryLiveEvalTask(
            id = "output-style-preference",
            title = "Output Style Preference",
            userStatement = "When you summarize for me, keep it concise and use bullet points.",
            recallCue = "How should summaries be formatted for me?",
            expectedFacts = listOf("concise", "bullet"),
            tags = listOf("preference", "style")
        )
    )
}

object MemoryLiveEvalReporter {
    fun render(report: MemoryLiveEvalReport): String {
        val lines = mutableListOf<String>()
        lines += "Memory live eval"
        lines += "Model=${report.modelName} Stage=${report.stage} SessionTag=${report.options.sessionTag}"
        lines += "Tasks=${report.summary.totalTasks} Pass=${report.summary.passedTasks} Fail=${report.summary.failedTasks} PassRate=${"%.2f".format(report.summary.passRate)}"
        lines += "Avg attempts=${"%.2f".format(report.summary.averageAttempts)} Avg duration ms=${report.summary.averageDurationMs} Total calls=${report.summary.totalModelCalls}"
        lines += ""
        report.taskResults.forEach { task ->
            lines += "[${task.taskId}] pass=${task.passed} attempts=${task.attemptsUsed} saved=${task.saved} recall_hits=${task.recallHitCount} judge=${task.judgePass} score=${"%.2f".format(task.judgeScore)}"
            if (task.runtimeError != null) {
                lines += "  runtime_error: ${task.runtimeError}"
            } else if (!task.passed) {
                lines += "  judge_reason: ${task.judgeReason}"
            }
        }
        return lines.joinToString("\n")
    }

    fun writeRunReport(report: MemoryLiveEvalReport, outputDir: Path = Path.of(".psyke/evals/memory-live/runs")): Path {
        Files.createDirectories(outputDir)
        val timestamp = timestampForFile()
        val outputPath = outputDir.resolve("memory-live-eval-$timestamp.json")
        Files.newBufferedWriter(outputPath).use { writer ->
            memoryEvalMapper.writerWithDefaultPrettyPrinter().writeValue(writer, report)
        }
        return outputPath
    }

    fun appendHistory(report: MemoryLiveEvalReport, historyPath: Path = Path.of(".psyke/evals/memory-live/history.jsonl")) {
        Files.createDirectories(historyPath.parent)
        val entry = mapOf(
            "run_started_at_utc" to report.startedAtUtc,
            "stage" to report.stage,
            "model" to report.modelName,
            "session_tag" to report.options.sessionTag,
            "total_tasks" to report.summary.totalTasks,
            "passed_tasks" to report.summary.passedTasks,
            "failed_tasks" to report.summary.failedTasks,
            "pass_rate" to report.summary.passRate,
            "avg_attempts" to report.summary.averageAttempts,
            "avg_duration_ms" to report.summary.averageDurationMs,
            "total_model_calls" to report.summary.totalModelCalls
        )
        val line = memoryEvalMapper.writeValueAsString(entry) + "\n"
        Files.writeString(
            historyPath,
            line,
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.APPEND
        )
    }

    private fun timestampForFile(): String =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC)
            .format(Instant.now())
}

private fun elapsedMillis(startedAtNanos: Long): Long =
    max(1L, (System.nanoTime() - startedAtNanos) / 1_000_000L)

private fun defaultMemoryEvalStageTag(): String =
    DateTimeFormatter.ISO_LOCAL_DATE
        .withZone(ZoneOffset.UTC)
        .format(Instant.now())

private fun defaultMemoryEvalSessionTag(): String =
    DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        .withZone(ZoneOffset.UTC)
        .format(Instant.now())
