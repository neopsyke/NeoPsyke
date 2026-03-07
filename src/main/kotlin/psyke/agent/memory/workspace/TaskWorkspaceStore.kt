package psyke.agent.memory.workspace

import psyke.agent.core.ActionOutcome
import psyke.agent.core.ActionType
import psyke.agent.core.PendingAction
import psyke.agent.core.PendingInput
import psyke.agent.core.TaskWorkspaceConfig
import psyke.agent.support.TextSecurity
import kotlin.math.max

data class TaskWorkspaceDestroyed(
    val rootInputEnqueuedAtMs: Long,
    val sectionCount: Int,
    val evidenceCount: Int,
)

data class TaskWorkspaceFinalPassInput(
    val compilation: String,
    val workspaceConfidence: Double,
    val sectionCount: Int,
    val evidenceCount: Int,
)

class TaskWorkspaceStore(
    private val config: TaskWorkspaceConfig,
) {
    @Synchronized
    fun ensureForInput(input: PendingInput): Boolean {
        if (!config.enabled) return false
        val rootId = input.enqueuedAtMs
        if (workspaces.containsKey(rootId)) return false
        evictOldestIfNeeded()
        val workspace = TaskWorkspace(
            rootInputEnqueuedAtMs = rootId,
            goal = TextSecurity.preview(input.content, MAX_GOAL_CHARS)
        )
        workspace.addSection(
            title = "Request",
            summary = TextSecurity.preview(input.content, config.maxSectionSummaryChars),
            content = TextSecurity.preview(input.content, config.maxSectionChars),
            source = SOURCE_INPUT
        )
        workspaces[rootId] = workspace
        return true
    }

    @Synchronized
    fun recordPlan(rootInputEnqueuedAtMs: Long?, goal: String, steps: List<String>) {
        val workspace = lookup(rootInputEnqueuedAtMs) ?: return
        val normalizedGoal = TextSecurity.preview(goal, MAX_GOAL_CHARS)
        if (normalizedGoal.isNotBlank()) {
            workspace.goal = normalizedGoal
        }
        val stepLines = steps.mapIndexed { index, step ->
            "${index + 1}. ${TextSecurity.preview(step, MAX_PLAN_STEP_CHARS)}"
        }
        workspace.addSection(
            title = "Plan",
            summary = "Planned ${steps.size} step(s) toward task goal.",
            content = buildString {
                append("Goal: ")
                append(normalizedGoal.ifBlank { "none" })
                append('\n')
                if (stepLines.isEmpty()) {
                    append("Steps: none")
                } else {
                    append("Steps:\n")
                    append(stepLines.joinToString("\n"))
                }
            },
            source = SOURCE_PLAN
        )
    }

    @Synchronized
    fun recordActionOutcome(
        rootInputEnqueuedAtMs: Long?,
        action: PendingAction,
        outcome: ActionOutcome,
        observedEvidence: Boolean,
    ) {
        val workspace = lookup(rootInputEnqueuedAtMs) ?: return
        if (action.type == ActionType.ANSWER) {
            return
        }
        val actionLabel = action.type.name.lowercase()
        workspace.addSection(
            title = "${actionLabel}_result",
            summary = TextSecurity.preview(outcome.statusSummary, config.maxSectionSummaryChars),
            content = TextSecurity.preview(
                if (outcome.plannerSignal.isNotBlank()) outcome.plannerSignal else outcome.statusSummary,
                config.maxSectionChars
            ),
            source = SOURCE_ACTION
        )
        if (observedEvidence) {
            workspace.addEvidence(
                TextSecurity.preview(outcome.plannerSignal.ifBlank { outcome.statusSummary }, config.maxEvidenceChars)
            )
        }
    }

    @Synchronized
    fun recordAnswerDraft(rootInputEnqueuedAtMs: Long?, payload: String) {
        val workspace = lookup(rootInputEnqueuedAtMs) ?: return
        val normalized = TextSecurity.preview(payload, config.maxSectionChars)
        if (normalized.isBlank()) return
        workspace.addSection(
            title = "Candidate answer",
            summary = TextSecurity.preview(normalized, config.maxSectionSummaryChars),
            content = normalized,
            source = SOURCE_ANSWER_DRAFT
        )
    }

    @Synchronized
    fun promptSummary(rootInputEnqueuedAtMs: Long?, maxTokens: Int): String {
        val workspace = lookup(rootInputEnqueuedAtMs) ?: return ""
        val tokenBudget = minOf(config.maxPromptTokens, max(32, maxTokens))
        val summary = workspace.buildPromptSummary()
        if (summary.isBlank()) return ""
        return TextSecurity.clampToTokenBudget(summary, tokenBudget)
    }

    @Synchronized
    fun buildFinalCompilation(
        rootInputEnqueuedAtMs: Long?,
        candidateAnswer: String,
        maxChars: Int,
    ): String {
        val workspace = lookup(rootInputEnqueuedAtMs) ?: return ""
        val cap = minOf(config.finalCompilationMaxChars, maxChars)
        if (cap <= 0) return ""
        val sections = workspace.sections.takeLast(FINAL_COMPILATION_SECTION_LIMIT)
        val evidence = workspace.evidence.takeLast(FINAL_COMPILATION_EVIDENCE_LIMIT)
        if (sections.isEmpty() && evidence.isEmpty()) return ""
        val compiled = buildString {
            append("Task workspace final compilation:\n")
            append("goal: ")
            append(workspace.goal.ifBlank { "none" })
            append('\n')
            if (sections.isNotEmpty()) {
                append("sections:\n")
                sections.forEachIndexed { index, section ->
                    append("${index + 1}. ")
                    append(section.title)
                    append(": ")
                    append(section.summary)
                    if (section.content.isNotBlank()) {
                        append(" | detail=")
                        append(TextSecurity.preview(section.content, FINAL_COMPILATION_SECTION_CONTENT_PREVIEW_CHARS))
                    }
                    append('\n')
                }
            }
            if (evidence.isNotEmpty()) {
                append("evidence:\n")
                evidence.forEach { item ->
                    append("- ")
                    append(item)
                    append('\n')
                }
            }
            val normalizedCandidate = candidateAnswer.trim()
            if (normalizedCandidate.isNotEmpty()) {
                append("candidate_answer:\n")
                append(normalizedCandidate)
            }
        }.trim()
        return TextSecurity.clamp(compiled, cap)
    }

    @Synchronized
    fun buildFinalPassInput(
        rootInputEnqueuedAtMs: Long?,
        candidateAnswer: String,
        maxChars: Int,
    ): TaskWorkspaceFinalPassInput? {
        val workspace = lookup(rootInputEnqueuedAtMs) ?: return null
        val compilation = buildFinalCompilation(rootInputEnqueuedAtMs, candidateAnswer, maxChars)
        if (compilation.isBlank()) return null
        val confidence = workspace.estimateConfidence()
        return TaskWorkspaceFinalPassInput(
            compilation = compilation,
            workspaceConfidence = confidence,
            sectionCount = workspace.sections.size,
            evidenceCount = workspace.evidence.size
        )
    }

    @Synchronized
    fun destroy(rootInputEnqueuedAtMs: Long?): TaskWorkspaceDestroyed? {
        if (!config.enabled || rootInputEnqueuedAtMs == null) return null
        val removed = workspaces.remove(rootInputEnqueuedAtMs) ?: return null
        return TaskWorkspaceDestroyed(
            rootInputEnqueuedAtMs = removed.rootInputEnqueuedAtMs,
            sectionCount = removed.sections.size,
            evidenceCount = removed.evidence.size
        )
    }

    @Synchronized
    fun clearAll(): Int {
        if (!config.enabled) return 0
        val cleared = workspaces.size
        workspaces.clear()
        return cleared
    }

    @Synchronized
    fun activeTaskCount(): Int = workspaces.size

    private fun lookup(rootInputEnqueuedAtMs: Long?): TaskWorkspace? {
        if (!config.enabled || rootInputEnqueuedAtMs == null) return null
        return workspaces[rootInputEnqueuedAtMs]
    }

    private fun evictOldestIfNeeded() {
        while (workspaces.size >= config.maxActiveTasks && workspaces.isNotEmpty()) {
            val oldestKey = workspaces.entries.first().key
            workspaces.remove(oldestKey)
        }
    }

    private data class TaskWorkspaceSection(
        val title: String,
        val summary: String,
        val content: String,
        val source: String,
    )

    private inner class TaskWorkspace(
        val rootInputEnqueuedAtMs: Long,
        var goal: String,
        val sections: ArrayDeque<TaskWorkspaceSection> = ArrayDeque(),
        val evidence: ArrayDeque<String> = ArrayDeque(),
    ) {
        fun addSection(title: String, summary: String, content: String, source: String) {
            if (summary.isBlank() && content.isBlank()) return
            sections.addLast(
                TaskWorkspaceSection(
                    title = title,
                    summary = TextSecurity.preview(summary, sectionSummaryCap()),
                    content = TextSecurity.preview(content, sectionContentCap()),
                    source = source
                )
            )
            while (sections.size > sectionCap()) {
                sections.removeFirst()
            }
        }

        fun addEvidence(value: String) {
            val normalized = TextSecurity.preview(value, evidenceItemCap())
            if (normalized.isBlank()) return
            evidence.addLast(normalized)
            while (evidence.size > evidenceCap()) {
                evidence.removeFirst()
            }
        }

        fun buildPromptSummary(): String {
            if (sections.isEmpty() && evidence.isEmpty()) {
                return ""
            }
            return buildString {
                append("Ephemeral task workspace (scoped to current request only):\n")
                append("goal: ")
                append(goal.ifBlank { "none" })
                append('\n')
                append("index:\n")
                sections.forEachIndexed { index, section ->
                    append("- [")
                    append(index + 1)
                    append("] ")
                    append(section.title)
                    append(" | source=")
                    append(section.source)
                    append(" | ")
                    append(section.summary)
                    append('\n')
                }
                if (evidence.isNotEmpty()) {
                    append("recent_evidence:\n")
                    evidence.forEach { item ->
                        append("- ")
                        append(item)
                        append('\n')
                    }
                }
            }.trim()
        }

        private fun sectionCap(): Int = max(1, config.maxSections)
        private fun sectionContentCap(): Int = max(64, config.maxSectionChars)
        private fun sectionSummaryCap(): Int = max(48, config.maxSectionSummaryChars)
        private fun evidenceCap(): Int = max(1, config.maxEvidenceItems)
        private fun evidenceItemCap(): Int = max(48, config.maxEvidenceChars)

        fun estimateConfidence(): Double {
            val sectionSignal = (sections.size.coerceAtMost(4).toDouble() / 4.0)
            val evidenceSignal = (evidence.size.coerceAtMost(4).toDouble() / 4.0)
            val goalSignal = if (goal.isBlank()) 0.0 else 1.0
            return (sectionSignal * SECTION_SIGNAL_WEIGHT) +
                (evidenceSignal * EVIDENCE_SIGNAL_WEIGHT) +
                (goalSignal * GOAL_SIGNAL_WEIGHT)
        }
    }

    private companion object {
        const val MAX_GOAL_CHARS: Int = 220
        const val MAX_PLAN_STEP_CHARS: Int = 160
        const val FINAL_COMPILATION_SECTION_LIMIT: Int = 6
        const val FINAL_COMPILATION_EVIDENCE_LIMIT: Int = 6
        const val FINAL_COMPILATION_SECTION_CONTENT_PREVIEW_CHARS: Int = 220
        const val SOURCE_INPUT: String = "input"
        const val SOURCE_PLAN: String = "plan"
        const val SOURCE_ACTION: String = "action_outcome"
        const val SOURCE_ANSWER_DRAFT: String = "answer_draft"
        const val SECTION_SIGNAL_WEIGHT: Double = 0.45
        const val EVIDENCE_SIGNAL_WEIGHT: Double = 0.45
        const val GOAL_SIGNAL_WEIGHT: Double = 0.10
    }

    private val workspaces = LinkedHashMap<Long, TaskWorkspace>()
}
