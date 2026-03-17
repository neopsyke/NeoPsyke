package psyke.agent.memory.workspace

import psyke.agent.model.ActionOutcome
import psyke.agent.model.PendingAction
import psyke.agent.model.PendingInput
import psyke.agent.config.TaskWorkspaceConfig
import psyke.agent.support.TextSecurity
import kotlin.math.max

data class TaskWorkspaceDestroyed(
    val rootInputId: String,
    val rootInputReceivedAtMs: Long,
    val sectionCount: Int,
    val evidenceCount: Int,
)

data class TaskWorkspaceFinalPassInput(
    val compilation: String,
    val workspaceConfidence: Double,
    val sectionCount: Int,
    val evidenceCount: Int,
    val resolutionDraftCount: Int,
)

data class TaskWorkspaceDebugHead(
    val rootInputId: String,
    val rootInputReceivedAtMs: Long,
    val version: Long,
    val updatedAtMs: Long,
    val goal: String,
    val sectionCount: Int,
    val evidenceCount: Int,
    val workspaceConfidence: Double,
    val bytesEstimate: Int,
)

data class TaskWorkspaceDebugSection(
    val title: String,
    val summary: String,
    val content: String,
    val source: String,
)

data class TaskWorkspaceDebugSnapshot(
    val head: TaskWorkspaceDebugHead,
    val sections: List<TaskWorkspaceDebugSection>,
    val evidence: List<String>,
)

data class WorkspaceDigestEntry(
    val rootInputId: String,
    val goal: String,
    val sectionIndex: List<String>,
    val keyEvidence: List<String>,
    val createdAtMs: Long,
)

class TaskWorkspaceStore(
    private val config: TaskWorkspaceConfig,
) {
    @Synchronized
    fun ensureForInput(input: PendingInput): Boolean {
        if (!config.enabled) return false
        val rootId = input.rootInputId
        if (workspaces.containsKey(rootId) || pendingInputs.containsKey(rootId)) return false
        if (isGateEnabled()) {
            pendingInputs[rootId] = PendingInputRecord(
                rootInputId = rootId,
                receivedAtMs = input.receivedAtMs,
                contentPreview = input.content
            )
            return false
        }
        evictOldestIfNeeded()
        val workspace = TaskWorkspace(
            rootInputId = rootId,
            rootInputReceivedAtMs = input.receivedAtMs,
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
    fun recordPlan(rootInputId: String?, goal: String, steps: List<String>): Boolean {
        val workspace = lookup(rootInputId)
        if (workspace != null) {
            appendPlanToWorkspace(workspace, goal, steps)
            return false
        }
        val activated = maybeActivateFromPending(rootInputId, goal, steps)
        if (activated) {
            val activatedWorkspace = lookup(rootInputId) ?: return true
            appendPlanToWorkspace(activatedWorkspace, goal, steps)
        }
        return activated
    }

    private fun appendPlanToWorkspace(workspace: TaskWorkspace, goal: String, steps: List<String>) {
        val normalizedGoal = TextSecurity.preview(goal, MAX_GOAL_CHARS)
        if (normalizedGoal.isNotBlank()) {
            workspace.updateGoal(normalizedGoal)
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
        rootInputId: String?,
        action: PendingAction,
        outcome: ActionOutcome,
        observedEvidence: Boolean,
    ) {
        val workspace = lookup(rootInputId) ?: return
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
    fun recordResolutionDraft(rootInputId: String?, payload: String) {
        val workspace = lookup(rootInputId) ?: return
        val normalized = TextSecurity.preview(payload, config.maxSectionChars)
        if (normalized.isBlank()) return
        workspace.addSection(
            title = "Resolution draft",
            summary = TextSecurity.preview(normalized, config.maxSectionSummaryChars),
            content = normalized,
            source = SOURCE_RESOLUTION_DRAFT
        )
    }

    @Synchronized
    fun promptSummary(rootInputId: String?, maxTokens: Int): String {
        val workspace = lookup(rootInputId) ?: return ""
        val tokenBudget = minOf(config.maxPromptTokens, max(32, maxTokens))
        val summary = workspace.buildPromptSummary()
        if (summary.isBlank()) return ""
        return TextSecurity.clampToTokenBudget(summary, tokenBudget)
    }

    @Synchronized
    fun buildFinalCompilation(
        rootInputId: String?,
        candidateAnswer: String,
        maxChars: Int,
    ): String {
        val workspace = lookup(rootInputId) ?: return ""
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
        rootInputId: String?,
        candidateAnswer: String,
        maxChars: Int,
    ): TaskWorkspaceFinalPassInput? {
        val workspace = lookup(rootInputId) ?: return null
        val compilation = buildFinalCompilation(rootInputId, candidateAnswer, maxChars)
        if (compilation.isBlank()) return null
        val confidence = workspace.estimateConfidence()
        return TaskWorkspaceFinalPassInput(
            compilation = compilation,
            workspaceConfidence = confidence,
            sectionCount = workspace.sections.size,
            evidenceCount = workspace.evidence.size,
            resolutionDraftCount = workspace.resolutionDraftCount()
        )
    }

    @Synchronized
    fun captureDigest(rootInputId: String?, sessionId: String): WorkspaceDigestEntry? {
        val workspace = lookup(rootInputId) ?: return null
        val sectionIndex = workspace.sections.map { section ->
            if (section.source == SOURCE_PLAN) {
                val stepCount = section.content.count { it == '\n' }
                "${section.title} ($stepCount steps)"
            } else {
                section.title
            }
        }
        val evidencePerItemCap = max(48, config.digestMaxChars / max(1, DIGEST_MAX_EVIDENCE_ITEMS))
        val keyEvidence = workspace.evidence
            .takeLast(DIGEST_MAX_EVIDENCE_ITEMS)
            .map { TextSecurity.preview(it, evidencePerItemCap) }
        val entry = WorkspaceDigestEntry(
            rootInputId = rootInputId ?: "",
            goal = workspace.goal,
            sectionIndex = sectionIndex,
            keyEvidence = keyEvidence,
            createdAtMs = System.currentTimeMillis(),
        )
        val buffer = digestsBySession.getOrPut(sessionId) { ArrayDeque() }
        buffer.addLast(entry)
        while (buffer.size > max(1, config.digestMaxEntries)) {
            buffer.removeFirst()
        }
        return entry
    }

    @Synchronized
    fun digestPromptSummary(sessionId: String, maxTokens: Int): String {
        val buffer = digestsBySession[sessionId]
        if (buffer.isNullOrEmpty()) return ""
        val tokenBudget = minOf(config.digestMaxPromptTokens, max(32, maxTokens))
        val summary = buildString {
            append("Prior workspace digests (session history, most recent last):\n")
            buffer.forEachIndexed { index, entry ->
                append("[${index + 1}] goal=${entry.goal}")
                if (entry.sectionIndex.isNotEmpty()) {
                    append(" | sections=[${entry.sectionIndex.joinToString(", ")}]")
                }
                if (entry.keyEvidence.isNotEmpty()) {
                    append(" | evidence=[${entry.keyEvidence.joinToString(" | ")}]")
                }
                append('\n')
            }
        }.trim()
        if (summary.isBlank()) return ""
        return TextSecurity.clampToTokenBudget(
            TextSecurity.clamp(summary, config.digestMaxChars),
            tokenBudget
        )
    }

    @Synchronized
    fun activeGoalSignals(limit: Int = CROSS_SESSION_ACTIVE_WORKSPACE_LIMIT): List<String> =
        workspaces.values
            .toList()
            .takeLast(max(1, limit))
            .map { workspace -> TextSecurity.preview(workspace.goal, MAX_GOAL_CHARS) }
            .filter { it.isNotBlank() }

    @Synchronized
    fun recentResolvedGoalSignals(limit: Int = CROSS_SESSION_DIGEST_LIMIT): List<String> =
        digestsBySession.values
            .asSequence()
            .flatMap { it.asSequence() }
            .sortedByDescending { it.createdAtMs }
            .take(max(1, limit))
            .map { entry ->
                buildString {
                    append(TextSecurity.preview(entry.goal, MAX_GOAL_CHARS))
                    if (entry.keyEvidence.isNotEmpty()) {
                        append(" | evidence=")
                        append(entry.keyEvidence.joinToString(" | "))
                    }
                }
            }
            .toList()

    @Synchronized
    fun clearDigestsForSession(sessionId: String) {
        digestsBySession.remove(sessionId)
    }

    @Synchronized
    fun clearActiveWorkspaces(): Int {
        if (!config.enabled) return 0
        val cleared = workspaces.size
        workspaces.clear()
        pendingInputs.clear()
        return cleared
    }

    @Synchronized
    fun debugHead(rootInputId: String?): TaskWorkspaceDebugHead? =
        lookup(rootInputId)?.debugHead()

    @Synchronized
    fun debugSnapshot(rootInputId: String?): TaskWorkspaceDebugSnapshot? =
        lookup(rootInputId)?.debugSnapshot()

    @Synchronized
    fun destroy(rootInputId: String?): TaskWorkspaceDestroyed? {
        if (!config.enabled || rootInputId.isNullOrBlank()) return null
        pendingInputs.remove(rootInputId)
        val removed = workspaces.remove(rootInputId) ?: return null
        return TaskWorkspaceDestroyed(
            rootInputId = removed.rootInputId,
            rootInputReceivedAtMs = removed.rootInputReceivedAtMs,
            sectionCount = removed.sections.size,
            evidenceCount = removed.evidence.size
        )
    }

    @Synchronized
    fun clearAll(): Int {
        val cleared = clearActiveWorkspaces()
        digestsBySession.clear()
        return cleared
    }

    @Synchronized
    fun activeTaskCount(): Int = workspaces.size

    private fun lookup(rootInputId: String?): TaskWorkspace? {
        if (!config.enabled || rootInputId.isNullOrBlank()) return null
        return workspaces[rootInputId]
    }

    private fun isGateEnabled(): Boolean =
        config.activationMinPlanSteps >= 2

    private fun maybeActivateFromPending(rootInputId: String?, goal: String, steps: List<String>): Boolean {
        if (!config.enabled || rootInputId.isNullOrBlank()) return false
        val pending = pendingInputs[rootInputId] ?: return false
        if (steps.size < config.activationMinPlanSteps) return false
        pendingInputs.remove(rootInputId)
        evictOldestIfNeeded()
        val workspace = TaskWorkspace(
            rootInputId = pending.rootInputId,
            rootInputReceivedAtMs = pending.receivedAtMs,
            goal = TextSecurity.preview(
                goal.ifBlank { pending.contentPreview },
                MAX_GOAL_CHARS
            )
        )
        workspace.addSection(
            title = "Request",
            summary = TextSecurity.preview(pending.contentPreview, config.maxSectionSummaryChars),
            content = TextSecurity.preview(pending.contentPreview, config.maxSectionChars),
            source = SOURCE_INPUT
        )
        workspaces[pending.rootInputId] = workspace
        return true
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
        val rootInputId: String,
        val rootInputReceivedAtMs: Long,
        var goal: String,
        val sections: ArrayDeque<TaskWorkspaceSection> = ArrayDeque(),
        val evidence: ArrayDeque<String> = ArrayDeque(),
    ) {
        var version: Long = 0L
            private set
        var updatedAtMs: Long = System.currentTimeMillis()
            private set

        fun updateGoal(newGoal: String) {
            if (newGoal == goal) return
            goal = newGoal
            touch()
        }

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
            touch()
        }

        fun addEvidence(value: String) {
            val normalized = TextSecurity.preview(value, evidenceItemCap())
            if (normalized.isBlank()) return
            evidence.addLast(normalized)
            while (evidence.size > evidenceCap()) {
                evidence.removeFirst()
            }
            touch()
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

        fun resolutionDraftCount(): Int =
            sections.count { section -> section.source == SOURCE_RESOLUTION_DRAFT }

        fun debugHead(): TaskWorkspaceDebugHead =
            TaskWorkspaceDebugHead(
                rootInputId = rootInputId,
                rootInputReceivedAtMs = rootInputReceivedAtMs,
                version = version,
                updatedAtMs = updatedAtMs,
                goal = goal,
                sectionCount = sections.size,
                evidenceCount = evidence.size,
                workspaceConfidence = estimateConfidence(),
                bytesEstimate = estimateBytes()
            )

        fun debugSnapshot(): TaskWorkspaceDebugSnapshot =
            TaskWorkspaceDebugSnapshot(
                head = debugHead(),
                sections = sections.map { section ->
                    TaskWorkspaceDebugSection(
                        title = section.title,
                        summary = section.summary,
                        content = section.content,
                        source = section.source
                    )
                },
                evidence = evidence.toList()
            )

        private fun touch() {
            version += 1L
            updatedAtMs = System.currentTimeMillis()
        }

        private fun estimateBytes(): Int {
            var total = goal.length
            sections.forEach { section ->
                total += section.title.length + section.summary.length + section.content.length + section.source.length
            }
            evidence.forEach { item -> total += item.length }
            return total
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
        const val SOURCE_RESOLUTION_DRAFT: String = "resolution_draft"
        const val SECTION_SIGNAL_WEIGHT: Double = 0.45
        const val EVIDENCE_SIGNAL_WEIGHT: Double = 0.45
        const val GOAL_SIGNAL_WEIGHT: Double = 0.10
        const val DIGEST_MAX_EVIDENCE_ITEMS: Int = 3
        const val MAX_DIGEST_TRACKED_SESSIONS: Int = 32
        const val CROSS_SESSION_ACTIVE_WORKSPACE_LIMIT: Int = 4
        const val CROSS_SESSION_DIGEST_LIMIT: Int = 6
    }

    private data class PendingInputRecord(
        val rootInputId: String,
        val receivedAtMs: Long,
        val contentPreview: String,
    )

    private val workspaces = LinkedHashMap<String, TaskWorkspace>()
    private val pendingInputs = LinkedHashMap<String, PendingInputRecord>()
    private val digestsBySession: MutableMap<String, ArrayDeque<WorkspaceDigestEntry>> =
        object : LinkedHashMap<String, ArrayDeque<WorkspaceDigestEntry>>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, ArrayDeque<WorkspaceDigestEntry>>
            ): Boolean = size > MAX_DIGEST_TRACKED_SESSIONS
        }
}
