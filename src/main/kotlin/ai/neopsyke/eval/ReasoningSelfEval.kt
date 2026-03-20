package ai.neopsyke.eval

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ai.neopsyke.agent.support.TextSecurity
import ai.neopsyke.instrumentation.AgentEvent
import ai.neopsyke.instrumentation.AgentInstrumentation
import ai.neopsyke.instrumentation.NoopAgentInstrumentation
import ai.neopsyke.llm.ChatCallMetadata
import ai.neopsyke.llm.ChatMessage
import ai.neopsyke.llm.ChatRequestOptions
import ai.neopsyke.llm.ChatRole
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.max

private val reasoningMapper = jacksonObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL)

data class ReasoningEvalOptions(
    val maxAttemptsPerTask: Int = 4,
    val taskFilter: Set<String> = emptySet(),
    val stage: String = defaultStageTag(),
    val mode: String = ReasoningEvalMode.LOGIC.id,
)

data class ReasoningEvalReport(
    val startedAtUtc: String,
    val stage: String,
    val modelName: String,
    val options: ReasoningEvalOptions,
    val taskResults: List<ReasoningTaskResult>,
    val summary: ReasoningEvalSummary,
)

data class ReasoningTaskResult(
    val taskId: String,
    val title: String,
    val passed: Boolean,
    val attemptsUsed: Int,
    val durationMs: Long,
    val validationErrors: List<String>,
    val finalAnswer: String,
    val modelCalls: Int,
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val callErrors: Int,
    val runtimeError: String? = null,
)

data class ReasoningEvalSummary(
    val totalTasks: Int,
    val passedTasks: Int,
    val failedTasks: Int,
    val passRate: Double,
    val averageAttempts: Double,
    val averageDurationMs: Long,
    val totalModelCalls: Int,
)

data class ReasoningEvalTask(
    val id: String,
    val title: String,
    val prompt: String,
    val validator: (JsonNode) -> List<String>,
)

class ReasoningSelfEvalRunner(
    private val client: UsageTrackingChatClient,
    private val tasks: List<ReasoningEvalTask> = ReasoningEvalTasks.defaults(),
    private val instrumentation: AgentInstrumentation = NoopAgentInstrumentation,
) {
    fun run(options: ReasoningEvalOptions = ReasoningEvalOptions()): ReasoningEvalReport {
        val selectedTasks = selectTasks(options.taskFilter)
        require(selectedTasks.isNotEmpty()) { "No reasoning eval tasks selected." }
        instrumentation.emit(
            AgentEvent(
                type = "eval_run_start",
                data = mapOf(
                    "eval_type" to "reasoning",
                    "mode" to options.mode,
                    "stage" to options.stage,
                    "task_count" to selectedTasks.size,
                    "max_attempts_per_task" to options.maxAttemptsPerTask
                )
            )
        )

        val taskResults = selectedTasks.mapIndexed { index, task ->
            instrumentation.emit(
                AgentEvent(
                    type = "eval_task_start",
                    data = mapOf(
                        "eval_type" to "reasoning",
                        "mode" to options.mode,
                        "task_id" to task.id,
                        "task_title" to task.title,
                        "task_index" to (index + 1),
                        "task_total" to selectedTasks.size
                    )
                )
            )
            runTask(task = task, options = options, index = index + 1, total = selectedTasks.size)
        }
        val passedTasks = taskResults.count { it.passed }
        val failedTasks = taskResults.size - passedTasks
        val avgAttempts = taskResults.map { it.attemptsUsed.toDouble() }.average()
        val avgDuration = taskResults.map { it.durationMs }.average().toLong()
        val totalCalls = taskResults.sumOf { it.modelCalls }

        val report = ReasoningEvalReport(
            startedAtUtc = Instant.now().toString(),
            stage = options.stage,
            modelName = client.modelName,
            options = options,
            taskResults = taskResults,
            summary = ReasoningEvalSummary(
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
                    "eval_type" to "reasoning",
                    "mode" to report.options.mode,
                    "stage" to report.stage,
                    "passed_tasks" to report.summary.passedTasks,
                    "total_tasks" to report.summary.totalTasks,
                    "failed_tasks" to report.summary.failedTasks,
                    "pass_rate" to report.summary.passRate,
                    "avg_attempts" to report.summary.averageAttempts,
                    "avg_duration_ms" to report.summary.averageDurationMs,
                    "total_model_calls" to report.summary.totalModelCalls
                )
            )
        )
        return report
    }

    private fun runTask(
        task: ReasoningEvalTask,
        options: ReasoningEvalOptions,
        index: Int,
        total: Int,
    ): ReasoningTaskResult {
        val usageBefore = client.snapshot()
        val startedAt = System.nanoTime()
        val messages = mutableListOf(
            ChatMessage(
                role = ChatRole.SYSTEM,
                content = """
                You are a reasoning assistant in an eval loop.
                Return STRICT JSON only.
                When validator feedback is provided, fix all listed issues exactly.
                Do not include markdown fences.
                """.trimIndent()
            ),
            ChatMessage(
                role = ChatRole.USER,
                content = task.prompt
            )
        )

        var passed = false
        var attemptsUsed = 0
        var finalAnswer = ""
        var runtimeError: String? = null
        var lastValidationErrors: List<String> = listOf("No valid JSON output produced.")

        for (attempt in 1..options.maxAttemptsPerTask) {
            attemptsUsed = attempt
            instrumentation.emit(
                AgentEvent(
                    type = "eval_attempt_start",
                    data = mapOf(
                        "eval_type" to "reasoning",
                        "mode" to options.mode,
                        "task_id" to task.id,
                        "task_index" to index,
                        "task_total" to total,
                        "attempt" to attempt,
                        "max_attempts" to options.maxAttemptsPerTask
                    )
                )
            )
            val answer = try {
                client.chat(
                    messages = messages,
                    options = ChatRequestOptions(
                        temperature = 0.1,
                        maxTokens = 500,
                        metadata = ChatCallMetadata(
                            actor = "reasoning_eval",
                            callSite = "${task.id}:attempt=$attempt"
                        )
                    )
                ).content.trim()
            } catch (ex: Exception) {
                runtimeError = ex.message ?: ex::class.simpleName ?: "reasoning_eval_error"
                instrumentation.emit(
                    AgentEvent(
                        type = "warning",
                        data = mapOf(
                            "message" to "[EVAL:reasoning] task=${task.id} runtime error on attempt=$attempt: ${runtimeError}"
                        )
                    )
                )
                break
            }

            finalAnswer = answer
            val validation = validateAnswer(task, answer)
            if (validation.isEmpty()) {
                passed = true
                lastValidationErrors = emptyList()
                instrumentation.emit(
                    AgentEvent(
                        type = "eval_attempt_result",
                        data = mapOf(
                            "eval_type" to "reasoning",
                            "mode" to options.mode,
                            "task_id" to task.id,
                            "attempt" to attempt,
                            "passed" to true,
                            "response_chars" to answer.length,
                            "validation_errors" to emptyList<String>()
                        )
                    )
                )
                break
            }

            lastValidationErrors = validation
            instrumentation.emit(
                AgentEvent(
                    type = "eval_attempt_result",
                    data = mapOf(
                        "eval_type" to "reasoning",
                        "mode" to options.mode,
                        "task_id" to task.id,
                        "attempt" to attempt,
                        "passed" to false,
                        "response_chars" to answer.length,
                        "validation_errors" to validation
                    )
                )
            )
            messages += ChatMessage(role = ChatRole.ASSISTANT, content = answer)
            messages += ChatMessage(
                role = ChatRole.USER,
                content = """
                Validator feedback:
                ${validation.joinToString("\n") { "- $it" }}
                Rewrite your answer as STRICT JSON only. Fix every issue.
                """.trimIndent()
            )
        }

        val durationMs = elapsedMillis(startedAt)
        val usageAfter = client.snapshot()
        val usage = usageAfter - usageBefore
        instrumentation.emit(
            AgentEvent(
                type = "eval_task_result",
                data = mapOf(
                    "eval_type" to "reasoning",
                    "mode" to options.mode,
                    "task_id" to task.id,
                    "task_title" to task.title,
                    "passed" to passed,
                    "attempts_used" to attemptsUsed,
                    "duration_ms" to durationMs,
                    "validation_errors" to (if (passed) emptyList<String>() else lastValidationErrors),
                    "model_calls" to usage.calls,
                    "prompt_tokens" to usage.promptTokens,
                    "completion_tokens" to usage.completionTokens,
                    "total_tokens" to usage.totalTokens,
                    "call_errors" to usage.callErrors,
                    "runtime_error" to runtimeError
                )
            )
        )

        return ReasoningTaskResult(
            taskId = task.id,
            title = task.title,
            passed = passed,
            attemptsUsed = attemptsUsed,
            durationMs = durationMs,
            validationErrors = if (passed) emptyList() else lastValidationErrors,
            finalAnswer = TextSecurity.clamp(finalAnswer, 800),
            modelCalls = usage.calls,
            promptTokens = usage.promptTokens,
            completionTokens = usage.completionTokens,
            totalTokens = usage.totalTokens,
            callErrors = usage.callErrors,
            runtimeError = runtimeError
        )
    }

    private fun validateAnswer(task: ReasoningEvalTask, answer: String): List<String> {
        return try {
            val jsonText = TextSecurity.extractJsonObject(answer)
            val node = reasoningMapper.readTree(jsonText)
            task.validator(node)
        } catch (ex: Exception) {
            listOf("Output is not parseable JSON: ${ex.message ?: "parse error"}")
        }
    }

    private fun selectTasks(filter: Set<String>): List<ReasoningEvalTask> {
        if (filter.isEmpty()) return tasks
        val selected = tasks.filter { filter.contains(it.id) }
        require(selected.isNotEmpty()) {
            "No reasoning eval tasks matched filter: ${filter.joinToString(",")}."
        }
        return selected
    }
}

object ReasoningEvalReporter {
    fun render(report: ReasoningEvalReport): String {
        val lines = mutableListOf<String>()
        lines += "Reasoning-only self-eval"
        lines += "Model=${report.modelName} Mode=${report.options.mode} Stage=${report.stage}"
        lines += "Tasks=${report.summary.totalTasks} Pass=${report.summary.passedTasks} Fail=${report.summary.failedTasks} PassRate=${"%.2f".format(report.summary.passRate)}"
        lines += "Avg attempts=${"%.2f".format(report.summary.averageAttempts)} Avg duration ms=${report.summary.averageDurationMs} Total calls=${report.summary.totalModelCalls}"
        lines += ""
        report.taskResults.forEach { task ->
            lines += "[${task.taskId}] pass=${task.passed} attempts=${task.attemptsUsed} duration_ms=${task.durationMs} model_calls=${task.modelCalls}"
            if (task.validationErrors.isNotEmpty()) {
                lines += "  errors: ${task.validationErrors.joinToString(" | ")}"
            }
        }
        return lines.joinToString("\n")
    }

    fun writeRunReport(report: ReasoningEvalReport, outputDir: Path = Path.of(".neopsyke/evals/reasoning/runs")): Path {
        Files.createDirectories(outputDir)
        val timestamp = timestampForFile()
        val outputPath = outputDir.resolve("reasoning-eval-$timestamp.json")
        Files.newBufferedWriter(outputPath).use { writer ->
            reasoningMapper.writerWithDefaultPrettyPrinter().writeValue(writer, report)
        }
        return outputPath
    }

    fun appendHistory(report: ReasoningEvalReport, historyPath: Path = Path.of(".neopsyke/evals/reasoning/history.jsonl")) {
        Files.createDirectories(historyPath.parent)
        val entry = mapOf(
            "run_started_at_utc" to report.startedAtUtc,
            "stage" to report.stage,
            "mode" to report.options.mode,
            "model" to report.modelName,
            "total_tasks" to report.summary.totalTasks,
            "passed_tasks" to report.summary.passedTasks,
            "failed_tasks" to report.summary.failedTasks,
            "pass_rate" to report.summary.passRate,
            "avg_attempts" to report.summary.averageAttempts,
            "avg_duration_ms" to report.summary.averageDurationMs,
            "total_model_calls" to report.summary.totalModelCalls
        )
        val line = reasoningMapper.writeValueAsString(entry) + "\n"
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

object ReasoningEvalTasks {
    fun defaults(): List<ReasoningEvalTask> = listOf(
        balanceLedgerTask(),
        assignmentTask(),
        stateMachineTask()
    )

    private fun balanceLedgerTask(): ReasoningEvalTask =
        ReasoningEvalTask(
            id = "balance-ledger",
            title = "Ledger Balance Tracking",
            prompt = """
            Compute final balances.
            Initial balances: A=10, B=5, C=0
            Operations in order:
            1) transfer A->B amount 3
            2) transfer B->C amount 4
            3) deposit A amount 6
            4) transfer A->C amount 8
            5) withdraw C amount 5

            Output STRICT JSON exactly with this shape:
            {"balances":{"A":<int>,"B":<int>,"C":<int>},"checksum":<int>}

            checksum must be A+B+C.
            """.trimIndent()
        ) { json ->
            val errors = mutableListOf<String>()
            val balances = json["balances"]
            if (balances == null || !balances.isObject) {
                return@ReasoningEvalTask listOf("Missing object field balances.")
            }
            val a = balances["A"]?.asInt(Int.MIN_VALUE) ?: Int.MIN_VALUE
            val b = balances["B"]?.asInt(Int.MIN_VALUE) ?: Int.MIN_VALUE
            val c = balances["C"]?.asInt(Int.MIN_VALUE) ?: Int.MIN_VALUE
            val checksum = json["checksum"]?.asInt(Int.MIN_VALUE) ?: Int.MIN_VALUE

            if (a != 5) errors += "balances.A must be 5."
            if (b != 4) errors += "balances.B must be 4."
            if (c != 7) errors += "balances.C must be 7."
            if (checksum != 16) errors += "checksum must be 16."
            errors
        }

    private fun assignmentTask(): ReasoningEvalTask =
        ReasoningEvalTask(
            id = "constraint-assignment",
            title = "Constraint Assignment",
            prompt = """
            Assign jobs J1,J2,J3,J4 to workers Ada,Ben,Cy.
            Constraints:
            - each job has exactly one worker
            - Cy must do J2
            - Ben must do exactly one job
            - J3 must be Ben
            - J4 cannot be Cy
            - Ada must do more jobs than Ben
            - J1 must be Ada

            Output STRICT JSON:
            {"assignment":{"J1":"<name>","J2":"<name>","J3":"<name>","J4":"<name>"}}
            """.trimIndent()
        ) { json ->
            val errors = mutableListOf<String>()
            val assign = json["assignment"]
            if (assign == null || !assign.isObject) {
                return@ReasoningEvalTask listOf("Missing object field assignment.")
            }
            val expected = mapOf(
                "J1" to "Ada",
                "J2" to "Cy",
                "J3" to "Ben",
                "J4" to "Ada"
            )
            expected.forEach { (job, worker) ->
                val actual = assign[job]?.asText()
                if (actual != worker) {
                    errors += "assignment.$job must be $worker."
                }
            }
            errors
        }

    private fun stateMachineTask(): ReasoningEvalTask =
        ReasoningEvalTask(
            id = "state-machine",
            title = "State Machine Update",
            prompt = """
            Apply operations in order on state x,y.
            Initial: x=2, y=1
            Rules:
            - mulx N => x = x * N
            - addy N => y = y + N
            - swap => swap x and y
            - mix => x = x + y

            Sequence:
            1) mulx 3
            2) addy 4
            3) swap
            4) mix
            5) addy 2
            6) mulx 2

            Output STRICT JSON:
            {"x":<int>,"y":<int>,"checksum":"<x>-<y>-<x+y>"}
            """.trimIndent()
        ) { json ->
            val errors = mutableListOf<String>()
            val x = json["x"]?.asInt(Int.MIN_VALUE) ?: Int.MIN_VALUE
            val y = json["y"]?.asInt(Int.MIN_VALUE) ?: Int.MIN_VALUE
            val checksum = json["checksum"]?.asText()
            if (x != 22) errors += "x must be 22."
            if (y != 8) errors += "y must be 8."
            if (checksum != "22-8-30") errors += "checksum must be 22-8-30."
            errors
        }
}

object ReasoningLogicEvalTasks {
    fun defaults(): List<ReasoningEvalTask> = listOf(
        shapeLockTask(),
        feedbackCarryTask(),
        multiFixTask()
    )

    private fun shapeLockTask(): ReasoningEvalTask =
        ReasoningEvalTask(
            id = "shape-lock",
            title = "Shape Lock",
            prompt = """
            Return STRICT JSON exactly with:
            {"ok":true,"tag":"shape-lock"}
            """.trimIndent()
        ) { json ->
            val errors = mutableListOf<String>()
            val ok = json["ok"]?.asBoolean()
            val tag = json["tag"]?.asText()
            if (ok != true) errors += "ok must be true."
            if (tag != "shape-lock") errors += "tag must be shape-lock."
            errors
        }

    private fun feedbackCarryTask(): ReasoningEvalTask =
        ReasoningEvalTask(
            id = "feedback-carry",
            title = "Feedback Carry",
            prompt = """
            Return STRICT JSON exactly with:
            {"mode":"carry","attempt":2}
            """.trimIndent()
        ) { json ->
            val errors = mutableListOf<String>()
            val mode = json["mode"]?.asText()
            val attempt = json["attempt"]?.asInt(Int.MIN_VALUE) ?: Int.MIN_VALUE
            if (mode != "carry") errors += "mode must be carry."
            if (attempt != 2) errors += "attempt must be 2."
            errors
        }

    private fun multiFixTask(): ReasoningEvalTask =
        ReasoningEvalTask(
            id = "multi-fix",
            title = "Multi Field Fix",
            prompt = """
            Return STRICT JSON exactly with:
            {"left":7,"right":5,"total":12}
            """.trimIndent()
        ) { json ->
            val errors = mutableListOf<String>()
            val left = json["left"]?.asInt(Int.MIN_VALUE) ?: Int.MIN_VALUE
            val right = json["right"]?.asInt(Int.MIN_VALUE) ?: Int.MIN_VALUE
            val total = json["total"]?.asInt(Int.MIN_VALUE) ?: Int.MIN_VALUE
            if (left != 7) errors += "left must be 7."
            if (right != 5) errors += "right must be 5."
            if (total != 12) errors += "total must be 12."
            errors
        }
}

object ReasoningBehavioralLogicEvalTasks {
    private const val PARAPHRASE_VARIANTS: Int = 5
    private const val NOISE_VARIANTS: Int = 4
    private const val REORDER_VARIANTS: Int = 3
    private const val REPAIR_VARIANTS: Int = 3

    fun defaults(): List<ReasoningEvalTask> = buildList {
        addAll(ledgerTasks())
        addAll(assignmentTasks())
        addAll(stateMachineTasks())
    }

    private fun ledgerTasks(): List<ReasoningEvalTask> =
        buildFamilyTasks(
            familyPrefix = "ledger",
            titlePrefix = "Ledger",
            paraphrasePrompts = listOf(
                ledgerPrompt("Track the balances after all transactions complete."),
                ledgerPrompt("Compute the ending balances after applying every transaction in order."),
                ledgerPrompt("Work through the transfer/deposit/withdrawal sequence and report the final balances."),
                ledgerPrompt("Resolve the account state after the ordered money movements below."),
                ledgerPrompt("Calculate the final ledger positions after processing the full transaction list.")
            ),
            noisePrompts = listOf(
                ledgerPrompt(
                    lead = "Compute the final balances exactly.",
                    note = "Ignore this irrelevant note: balances are whole numbers, so no decimal rounding is needed."
                ),
                ledgerPrompt(
                    lead = "Process the ledger updates and return the ending balances.",
                    note = "Distractor: the finance team labels this batch 'north-17', but that label does not affect the math."
                ),
                ledgerPrompt(
                    lead = "Apply the ordered transactions and produce the final state.",
                    note = "Noise: A, B, and C are just account names. Do not infer any extra business rules beyond the listed operations."
                ),
                ledgerPrompt(
                    lead = "Solve the balance tracking task below.",
                    note = "Extra context: the checksum is only a reporting field; it does not change any balances."
                )
            ),
            reorderPrompts = listOf(
                ledgerPromptReordered(
                    lead = "Read the required JSON shape first, then solve the transaction sequence."
                ),
                ledgerPromptReordered(
                    lead = "The output contract comes first; the account setup and operations follow afterward."
                ),
                ledgerPromptReordered(
                    lead = "Use the schema below, then evaluate the balances from the later sections."
                )
            ),
            repairPrompts = listOf(
                ledgerPrompt(
                    lead = "This response is auto-validated. Return one JSON object and keep every field internally consistent."
                ),
                ledgerPrompt(
                    lead = "You may be asked to revise this answer. If so, fix only what is wrong and preserve already-correct fields."
                ),
                ledgerPrompt(
                    lead = "Answer with strict JSON only. The validator checks both balances and checksum, so ensure they agree."
                )
            ),
            validator = ::validateLedgerAnswer
        )

    private fun assignmentTasks(): List<ReasoningEvalTask> =
        buildFamilyTasks(
            familyPrefix = "assignment",
            titlePrefix = "Assignment",
            paraphrasePrompts = listOf(
                assignmentPrompt("Assign each job to the correct worker while satisfying every rule."),
                assignmentPrompt("Work out the only valid job allocation from the constraints below."),
                assignmentPrompt("Solve the worker-to-job mapping using all listed restrictions."),
                assignmentPrompt("Determine a valid assignment for J1 through J4 that matches every constraint."),
                assignmentPrompt("Compute the final staffing plan from the rules below.")
            ),
            noisePrompts = listOf(
                assignmentPrompt(
                    lead = "Find the valid assignment.",
                    note = "Ignore this note: Ada, Ben, and Cy are only names; seniority does not matter unless stated."
                ),
                assignmentPrompt(
                    lead = "Resolve the constrained assignment problem.",
                    note = "Distractor: J1 through J4 happen on the same day, but timing has no effect on the rules."
                ),
                assignmentPrompt(
                    lead = "Use the constraints to assign every job.",
                    note = "Noise: the team prefers short meetings, but that preference is unrelated to the assignment."
                ),
                assignmentPrompt(
                    lead = "Return the worker for each job.",
                    note = "Extra context: output order matters only for JSON field names, not for solving the puzzle."
                )
            ),
            reorderPrompts = listOf(
                assignmentPromptReordered("The JSON contract is shown first; the rules appear afterward."),
                assignmentPromptReordered("Read the output shape, then process the constraints in the later section."),
                assignmentPromptReordered("Start with the required JSON, then solve the constraint list below it.")
            ),
            repairPrompts = listOf(
                assignmentPrompt("This answer is auto-validated, so keep every assignment field correct in the same response."),
                assignmentPrompt("Return strict JSON only. If revised later, preserve correct job assignments while fixing wrong ones."),
                assignmentPrompt("The validator checks every job independently. Ensure J1, J2, J3, and J4 are all correct.")
            ),
            validator = ::validateAssignmentAnswer
        )

    private fun stateMachineTasks(): List<ReasoningEvalTask> =
        buildFamilyTasks(
            familyPrefix = "state_machine",
            titlePrefix = "State Machine",
            paraphrasePrompts = listOf(
                stateMachinePrompt("Apply the operations in order and report the final state."),
                stateMachinePrompt("Execute the transition sequence exactly and compute the ending x and y."),
                stateMachinePrompt("Follow the update rules step by step, then return the final state."),
                stateMachinePrompt("Evaluate the ordered state-machine operations and emit the resulting values."),
                stateMachinePrompt("Work through the sequence carefully and report the final checksum.")
            ),
            noisePrompts = listOf(
                stateMachinePrompt(
                    lead = "Solve the state update task below.",
                    note = "Ignore this irrelevant detail: the variables could have been named left and right instead of x and y."
                ),
                stateMachinePrompt(
                    lead = "Run the ordered operations and return the final state.",
                    note = "Distractor: all values remain integers throughout; no rounding is ever needed."
                ),
                stateMachinePrompt(
                    lead = "Process the sequence exactly as written.",
                    note = "Noise: the checksum is a reporting string and should be derived from the final values only."
                ),
                stateMachinePrompt(
                    lead = "Compute the terminal x and y values.",
                    note = "Extra context: the listed rules are exhaustive; do not assume any hidden transitions."
                )
            ),
            reorderPrompts = listOf(
                stateMachinePromptReordered("Read the output contract first, then evaluate the rules and sequence."),
                stateMachinePromptReordered("The schema comes first; the rule definitions and ordered steps follow."),
                stateMachinePromptReordered("Use the JSON shape below, then solve the later state-transition description.")
            ),
            repairPrompts = listOf(
                stateMachinePrompt("This answer is auto-validated. Keep x, y, and checksum mutually consistent."),
                stateMachinePrompt("Return strict JSON only. If you revise later, fix incorrect fields without regressing correct ones."),
                stateMachinePrompt("The validator checks both numeric fields and checksum, so ensure the final tuple is coherent.")
            ),
            validator = ::validateStateMachineAnswer
        )

    private fun buildFamilyTasks(
        familyPrefix: String,
        titlePrefix: String,
        paraphrasePrompts: List<String>,
        noisePrompts: List<String>,
        reorderPrompts: List<String>,
        repairPrompts: List<String>,
        validator: (JsonNode) -> List<String>,
    ): List<ReasoningEvalTask> {
        require(paraphrasePrompts.size == PARAPHRASE_VARIANTS) { "Expected $PARAPHRASE_VARIANTS paraphrase prompts." }
        require(noisePrompts.size == NOISE_VARIANTS) { "Expected $NOISE_VARIANTS noise prompts." }
        require(reorderPrompts.size == REORDER_VARIANTS) { "Expected $REORDER_VARIANTS reorder prompts." }
        require(repairPrompts.size == REPAIR_VARIANTS) { "Expected $REPAIR_VARIANTS repair prompts." }
        return buildList {
            addAll(buildVariantTasks(familyPrefix, titlePrefix, "paraphrase", paraphrasePrompts, validator))
            addAll(buildVariantTasks(familyPrefix, titlePrefix, "noise", noisePrompts, validator))
            addAll(buildVariantTasks(familyPrefix, titlePrefix, "reorder", reorderPrompts, validator))
            addAll(buildVariantTasks(familyPrefix, titlePrefix, "repair", repairPrompts, validator))
        }
    }

    private fun buildVariantTasks(
        familyPrefix: String,
        titlePrefix: String,
        variantLabel: String,
        prompts: List<String>,
        validator: (JsonNode) -> List<String>,
    ): List<ReasoningEvalTask> =
        prompts.mapIndexed { index, prompt ->
            val ordinal = index + 1
            ReasoningEvalTask(
                id = "${familyPrefix}_${variantLabel}_${ordinal.toString().padStart(2, '0')}",
                title = "$titlePrefix ${variantLabel.replaceFirstChar { it.uppercase() }} $ordinal",
                prompt = prompt,
                validator = validator
            )
        }

    private fun ledgerPrompt(lead: String, note: String? = null): String =
        composePrompt(
            lead,
            """
            Initial balances: A=10, B=5, C=0
            Operations in order:
            1) transfer A->B amount 3
            2) transfer B->C amount 4
            3) deposit A amount 6
            4) transfer A->C amount 8
            5) withdraw C amount 5
            """.trimIndent(),
            """
            Output STRICT JSON exactly with this shape:
            {"balances":{"A":<int>,"B":<int>,"C":<int>},"checksum":<int>}

            checksum must be A+B+C.
            """.trimIndent(),
            note
        )

    private fun ledgerPromptReordered(lead: String): String =
        composePrompt(
            lead,
            """
            Output STRICT JSON exactly with this shape:
            {"balances":{"A":<int>,"B":<int>,"C":<int>},"checksum":<int>}

            checksum must be A+B+C.
            """.trimIndent(),
            """
            Initial balances: A=10, B=5, C=0
            Operations in order:
            1) transfer A->B amount 3
            2) transfer B->C amount 4
            3) deposit A amount 6
            4) transfer A->C amount 8
            5) withdraw C amount 5
            """.trimIndent()
        )

    private fun assignmentPrompt(lead: String, note: String? = null): String =
        composePrompt(
            lead,
            """
            Assign jobs J1,J2,J3,J4 to workers Ada,Ben,Cy.
            Constraints:
            - each job has exactly one worker
            - Cy must do J2
            - Ben must do exactly one job
            - J3 must be Ben
            - J4 cannot be Cy
            - Ada must do more jobs than Ben
            - J1 must be Ada
            """.trimIndent(),
            """
            Output STRICT JSON:
            {"assignment":{"J1":"<name>","J2":"<name>","J3":"<name>","J4":"<name>"}}
            """.trimIndent(),
            note
        )

    private fun assignmentPromptReordered(lead: String): String =
        composePrompt(
            lead,
            """
            Output STRICT JSON:
            {"assignment":{"J1":"<name>","J2":"<name>","J3":"<name>","J4":"<name>"}}
            """.trimIndent(),
            """
            Assign jobs J1,J2,J3,J4 to workers Ada,Ben,Cy.
            Constraints:
            - each job has exactly one worker
            - Cy must do J2
            - Ben must do exactly one job
            - J3 must be Ben
            - J4 cannot be Cy
            - Ada must do more jobs than Ben
            - J1 must be Ada
            """.trimIndent()
        )

    private fun stateMachinePrompt(lead: String, note: String? = null): String =
        composePrompt(
            lead,
            """
            Apply operations in order on state x,y.
            Initial: x=2, y=1
            Rules:
            - mulx N => x = x * N
            - addy N => y = y + N
            - swap => swap x and y
            - mix => x = x + y

            Sequence:
            1) mulx 3
            2) addy 4
            3) swap
            4) mix
            5) addy 2
            6) mulx 2
            """.trimIndent(),
            """
            Output STRICT JSON:
            {"x":<int>,"y":<int>,"checksum":"<x>-<y>-<x+y>"}
            """.trimIndent(),
            note
        )

    private fun stateMachinePromptReordered(lead: String): String =
        composePrompt(
            lead,
            """
            Output STRICT JSON:
            {"x":<int>,"y":<int>,"checksum":"<x>-<y>-<x+y>"}
            """.trimIndent(),
            """
            Apply operations in order on state x,y.
            Initial: x=2, y=1
            Rules:
            - mulx N => x = x * N
            - addy N => y = y + N
            - swap => swap x and y
            - mix => x = x + y

            Sequence:
            1) mulx 3
            2) addy 4
            3) swap
            4) mix
            5) addy 2
            6) mulx 2
            """.trimIndent()
        )

    private fun composePrompt(vararg sections: String?): String =
        sections.filterNotNull().filter { it.isNotBlank() }.joinToString("\n\n")
}

private fun validateLedgerAnswer(json: JsonNode): List<String> {
    val errors = mutableListOf<String>()
    val balances = json["balances"]
    if (balances == null || !balances.isObject) {
        return listOf("Missing object field balances.")
    }
    val a = balances["A"]?.asInt(Int.MIN_VALUE) ?: Int.MIN_VALUE
    val b = balances["B"]?.asInt(Int.MIN_VALUE) ?: Int.MIN_VALUE
    val c = balances["C"]?.asInt(Int.MIN_VALUE) ?: Int.MIN_VALUE
    val checksum = json["checksum"]?.asInt(Int.MIN_VALUE) ?: Int.MIN_VALUE

    if (a != 5) errors += "balances.A must be 5."
    if (b != 4) errors += "balances.B must be 4."
    if (c != 7) errors += "balances.C must be 7."
    if (checksum != 16) errors += "checksum must be 16."
    return errors
}

private fun validateAssignmentAnswer(json: JsonNode): List<String> {
    val errors = mutableListOf<String>()
    val assign = json["assignment"]
    if (assign == null || !assign.isObject) {
        return listOf("Missing object field assignment.")
    }
    val expected = mapOf(
        "J1" to "Ada",
        "J2" to "Cy",
        "J3" to "Ben",
        "J4" to "Ada"
    )
    expected.forEach { (job, worker) ->
        val actual = assign[job]?.asText()
        if (actual != worker) {
            errors += "assignment.$job must be $worker."
        }
    }
    return errors
}

private fun validateStateMachineAnswer(json: JsonNode): List<String> {
    val errors = mutableListOf<String>()
    val x = json["x"]?.asInt(Int.MIN_VALUE) ?: Int.MIN_VALUE
    val y = json["y"]?.asInt(Int.MIN_VALUE) ?: Int.MIN_VALUE
    val checksum = json["checksum"]?.asText()
    if (x != 22) errors += "x must be 22."
    if (y != 8) errors += "y must be 8."
    if (checksum != "22-8-30") errors += "checksum must be 22-8-30."
    return errors
}

private fun elapsedMillis(startedAtNanos: Long): Long =
    max(1L, (System.nanoTime() - startedAtNanos) / 1_000_000L)

private fun defaultStageTag(): String =
    DateTimeFormatter.ISO_LOCAL_DATE
        .withZone(ZoneOffset.UTC)
        .format(Instant.now())
