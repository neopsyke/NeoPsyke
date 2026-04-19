package ai.neopsyke.agent.memory.scratchpad

import ai.neopsyke.agent.model.ActionOutcome
import ai.neopsyke.agent.model.DataTrust
import ai.neopsyke.agent.model.ExternalContentArtifact
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.model.PendingInput
import ai.neopsyke.agent.assignments.AssignmentActivation
import ai.neopsyke.agent.config.ScratchpadConfig
import ai.neopsyke.agent.support.TextSecurity
import kotlin.math.max

data class ScratchpadDestroyed(
    val rootInputId: String,
    val rootInputReceivedAtMs: Long,
    val sectionCount: Int,
    val evidenceCount: Int,
)

data class ScratchpadFinalPassInput(
    val compilation: String,
    val workspaceConfidence: Double,
    val sectionCount: Int,
    val evidenceCount: Int,
    val resolutionDraftCount: Int,
)

data class ScratchpadDebugHead(
    val rootInputId: String,
    val rootInputReceivedAtMs: Long,
    val version: Long,
    val updatedAtMs: Long,
    val assignment: String,
    val sectionCount: Int,
    val evidenceCount: Int,
    val workspaceConfidence: Double,
    val bytesEstimate: Int,
)

data class ScratchpadDebugSection(
    val title: String,
    val summary: String,
    val content: String,
    val source: String,
)

data class ScratchpadDebugSnapshot(
    val head: ScratchpadDebugHead,
    val sections: List<ScratchpadDebugSection>,
    val evidence: List<String>,
    val evidenceRecords: List<ScratchpadDebugEvidence>,
)

data class ScratchpadDebugEvidence(
    val content: String,
    val trust: DataTrust,
    val source: String,
)

data class WorkspaceDigestEntry(
    val rootInputId: String,
    val assignment: String,
    val sectionIndex: List<String>,
    val keyEvidence: List<String>,
    val createdAtMs: Long,
)

class ScratchpadStore(
    private val config: ScratchpadConfig,
) {
    @Volatile
    private var activeAssignmentSignalsSnapshot: List<String> = emptyList()

    @Volatile
    private var recentResolvedAssignmentSignalsSnapshot: List<String> = emptyList()

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
        val workspace = Scratchpad(
            rootInputId = rootId,
            rootInputReceivedAtMs = input.receivedAtMs,
            assignment = TextSecurity.preview(input.content, MAX_ASSIGNMENT_CHARS)
        )
        workspace.addSection(
            title = "Request",
            summary = TextSecurity.preview(input.content, config.maxSectionSummaryChars),
            content = TextSecurity.preview(input.content, config.maxSectionChars),
            source = SOURCE_INPUT
        )
        workspaces[rootId] = workspace
        refreshAmbientSnapshotsLocked()
        return true
    }

    @Synchronized
    fun ensureForAssignment(work: AssignmentActivation): Boolean {
        if (!config.enabled) return false
        val rootId = work.rootInputId
        if (workspaces.containsKey(rootId)) return false
        evictOldestIfNeeded()
        val workspace = Scratchpad(
            rootInputId = rootId,
            rootInputReceivedAtMs = System.currentTimeMillis(),
            assignment = TextSecurity.preview(work.stepDescription, MAX_ASSIGNMENT_CHARS)
        )
        workspace.addSection(
            title = "Assignment step",
            summary = TextSecurity.preview(work.stepDescription, config.maxSectionSummaryChars),
            content = TextSecurity.preview(work.acceptanceCriteria, config.maxSectionChars),
            source = SOURCE_GOAL_STEP
        )
        if (work.workingContext.isNotBlank()) {
            workspace.addSection(
                title = "Assignment context",
                summary = TextSecurity.preview(work.workingContext, config.maxSectionSummaryChars),
                content = TextSecurity.preview(work.workingContext, config.maxSectionChars),
                source = SOURCE_GOAL_CONTEXT
            )
        }
        workspaces[rootId] = workspace
        refreshAmbientSnapshotsLocked()
        return true
    }

    @Synchronized
    fun recordPlan(rootInputId: String?, assignment: String, steps: List<String>): Boolean {
        val workspace = lookup(rootInputId)
        if (workspace != null) {
            appendPlanToWorkspace(workspace, assignment, steps)
            refreshAmbientSnapshotsLocked()
            return false
        }
        val activated = maybeActivateFromPending(rootInputId, assignment, steps)
        if (activated) {
            val activatedWorkspace = lookup(rootInputId) ?: return true
            appendPlanToWorkspace(activatedWorkspace, assignment, steps)
            refreshAmbientSnapshotsLocked()
        }
        return activated
    }

    private fun appendPlanToWorkspace(workspace: Scratchpad, assignment: String, steps: List<String>) {
        val normalizedAssignment = TextSecurity.preview(assignment, MAX_ASSIGNMENT_CHARS)
        if (normalizedAssignment.isNotBlank()) {
            workspace.updateAssignment(normalizedAssignment)
        }
        val stepLines = steps.mapIndexed { index, step ->
            "${index + 1}. ${TextSecurity.preview(step, MAX_PLAN_STEP_CHARS)}"
        }
        workspace.addSection(
            title = "Plan",
            summary = "Planned ${steps.size} step(s) toward the assignment.",
            content = buildString {
                append("Assignment: ")
                append(normalizedAssignment.ifBlank { "none" })
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
            if (outcome.resultArtifacts.isNotEmpty()) {
                outcome.resultArtifacts.forEach { workspace.addEvidence(it) }
            } else {
                workspace.addEvidence(
                    TextSecurity.preview(outcome.plannerSignal.ifBlank { outcome.statusSummary }, config.maxEvidenceChars)
                )
            }
        }
    }

    @Synchronized
    fun recordResolutionDraft(rootInputId: String?, payload: String, intentionId: String? = null) {
        if (!config.enabled || rootInputId.isNullOrBlank()) return
        val normalized = TextSecurity.preview(payload, config.maxSectionChars)
        if (normalized.isBlank()) return
        val draftsByIntention = intentionDraftsByRootInput.getOrPut(rootInputId) { LinkedHashMap() }
        val drafts = draftsByIntention.getOrPut(draftBucketKeyForWrite(rootInputId, intentionId)) { ArrayDeque() }
        drafts.addLast(
            ScratchpadDraft(
                intentionId = intentionId,
                content = normalized,
                createdAtMs = System.currentTimeMillis(),
            )
        )
        while (drafts.size > max(1, config.maxSections)) {
            drafts.removeFirst()
        }
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
        intentionId: String? = null,
    ): String {
        val workspace = lookup(rootInputId) ?: return ""
        val cap = minOf(config.finalCompilationMaxChars, maxChars)
        if (cap <= 0) return ""
        val sections = workspace.sections.takeLast(FINAL_COMPILATION_SECTION_LIMIT)
        val evidence = workspace.evidence.takeLast(FINAL_COMPILATION_EVIDENCE_LIMIT)
        val drafts = recentDrafts(rootInputId, intentionId).takeLast(FINAL_COMPILATION_DRAFT_LIMIT)
        if (sections.isEmpty() && evidence.isEmpty() && drafts.isEmpty()) return ""
        val compiled = buildString {
            append("Scratchpad final compilation:\n")
            append("work: ")
            append(workspace.assignment.ifBlank { "none" })
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
                    append(item.render())
                    append('\n')
                }
            }
            if (drafts.isNotEmpty()) {
                append("intention_drafts:\n")
                drafts.forEachIndexed { index, draft ->
                    append("${index + 1}. ")
                    append(draft.content)
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
        intentionId: String? = null,
    ): ScratchpadFinalPassInput? {
        val workspace = lookup(rootInputId) ?: return null
        val compilation = buildFinalCompilation(rootInputId, candidateAnswer, maxChars, intentionId)
        if (compilation.isBlank()) return null
        val confidence = workspace.estimateConfidence()
        return ScratchpadFinalPassInput(
            compilation = compilation,
            workspaceConfidence = confidence,
            sectionCount = workspace.sections.size,
            evidenceCount = workspace.evidence.size,
            resolutionDraftCount = recentDrafts(rootInputId, intentionId).size
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
            .map { TextSecurity.preview(it.render(), evidencePerItemCap) }
        val entry = WorkspaceDigestEntry(
            rootInputId = rootInputId ?: "",
            assignment = workspace.assignment,
            sectionIndex = sectionIndex,
            keyEvidence = keyEvidence,
            createdAtMs = System.currentTimeMillis(),
        )
        val buffer = digestsBySession.getOrPut(sessionId) { ArrayDeque() }
        buffer.addLast(entry)
        while (buffer.size > max(1, config.digestMaxEntries)) {
            buffer.removeFirst()
        }
        refreshAmbientSnapshotsLocked()
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
                append("[${index + 1}] workItem=${entry.assignment}")
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

    fun activeGoalSignals(limit: Int = CROSS_SESSION_ACTIVE_WORKSPACE_LIMIT): List<String> =
        activeAssignmentSignalsSnapshot.take(max(1, limit))

    fun recentResolvedGoalSignals(limit: Int = CROSS_SESSION_DIGEST_LIMIT): List<String> =
        recentResolvedAssignmentSignalsSnapshot.take(max(1, limit))

    @Synchronized
    fun clearDigestsForSession(sessionId: String) {
        digestsBySession.remove(sessionId)
        refreshAmbientSnapshotsLocked()
    }

    @Synchronized
    fun clearActiveWorkspaces(): Int {
        if (!config.enabled) return 0
        val cleared = workspaces.size
        workspaces.clear()
        pendingInputs.clear()
        intentionDraftsByRootInput.clear()
        activeDraftBucketByRootInput.clear()
        refreshAmbientSnapshotsLocked()
        return cleared
    }

    @Synchronized
    fun clearOrphanedThreadWorkspaces(activeRootInputIds: Set<String>): Int {
        if (!config.enabled) return 0
        val active = activeRootInputIds.filter { it.isNotBlank() }.toSet()
        val before = workspaces.size
        workspaces.entries.removeIf { (rootInputId, _) -> rootInputId !in active }
        pendingInputs.entries.removeIf { (rootInputId, _) -> rootInputId !in active }
        intentionDraftsByRootInput.entries.removeIf { (rootInputId, _) -> rootInputId !in active }
        activeDraftBucketByRootInput.entries.removeIf { (rootInputId, _) -> rootInputId !in active }
        refreshAmbientSnapshotsLocked()
        return before - workspaces.size
    }

    @Synchronized
    fun clearIntentionDrafts(rootInputId: String? = null): Int {
        if (!config.enabled) return 0
        return if (rootInputId.isNullOrBlank()) {
            val cleared = intentionDraftsByRootInput.values.sumOf { draftsByIntention ->
                draftsByIntention.values.sumOf { it.size }
            }
            intentionDraftsByRootInput.clear()
            activeDraftBucketByRootInput.clear()
            cleared
        } else {
            activeDraftBucketByRootInput.remove(rootInputId)
            intentionDraftsByRootInput.remove(rootInputId)
                ?.values
                ?.sumOf { it.size }
                ?: 0
        }
    }

    @Synchronized
    fun resetDraftSequence(rootInputId: String?) {
        if (!config.enabled || rootInputId.isNullOrBlank()) return
        activeDraftBucketByRootInput.remove(rootInputId)
    }

    @Synchronized
    fun debugHead(rootInputId: String?): ScratchpadDebugHead? =
        lookup(rootInputId)?.debugHead()

    @Synchronized
    fun debugSnapshot(rootInputId: String?): ScratchpadDebugSnapshot? =
        lookup(rootInputId)?.debugSnapshot()

    @Synchronized
    fun destroy(rootInputId: String?): ScratchpadDestroyed? {
        if (!config.enabled || rootInputId.isNullOrBlank()) return null
        pendingInputs.remove(rootInputId)
        activeDraftBucketByRootInput.remove(rootInputId)
        intentionDraftsByRootInput.remove(rootInputId)
        val removed = workspaces.remove(rootInputId) ?: return null
        refreshAmbientSnapshotsLocked()
        return ScratchpadDestroyed(
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
        refreshAmbientSnapshotsLocked()
        return cleared
    }

    @Synchronized
    fun activeTaskCount(): Int = workspaces.size

    private fun lookup(rootInputId: String?): Scratchpad? {
        if (!config.enabled || rootInputId.isNullOrBlank()) return null
        return workspaces[rootInputId]
    }

    private fun recentDrafts(rootInputId: String?, _intentionId: String?): List<ScratchpadDraft> {
        if (!config.enabled || rootInputId.isNullOrBlank()) return emptyList()
        val draftsByIntention = intentionDraftsByRootInput[rootInputId] ?: return emptyList()
        val explicitBucket = draftBucketKey(_intentionId)
        val activeBucket = activeDraftBucketByRootInput[rootInputId]
        val bucketKey = when {
            explicitBucket in draftsByIntention -> explicitBucket
            !activeBucket.isNullOrBlank() && activeBucket in draftsByIntention -> activeBucket
            else -> explicitBucket
        }
        return draftsByIntention[bucketKey].orEmpty().toList()
    }

    private fun isGateEnabled(): Boolean =
        config.activationMinPlanSteps >= 2

    private fun maybeActivateFromPending(rootInputId: String?, assignment: String, steps: List<String>): Boolean {
        if (!config.enabled || rootInputId.isNullOrBlank()) return false
        val pending = pendingInputs[rootInputId] ?: return false
        if (steps.size < config.activationMinPlanSteps) return false
        pendingInputs.remove(rootInputId)
        evictOldestIfNeeded()
        val workspace = Scratchpad(
            rootInputId = pending.rootInputId,
            rootInputReceivedAtMs = pending.receivedAtMs,
            assignment = TextSecurity.preview(
                assignment.ifBlank { pending.contentPreview },
                MAX_ASSIGNMENT_CHARS
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

    private fun refreshAmbientSnapshotsLocked() {
        activeAssignmentSignalsSnapshot =
            workspaces.values
                .toList()
                .takeLast(max(1, CROSS_SESSION_ACTIVE_WORKSPACE_LIMIT))
                .map { workspace -> TextSecurity.preview(workspace.assignment, MAX_ASSIGNMENT_CHARS) }
                .filter { it.isNotBlank() }

        recentResolvedAssignmentSignalsSnapshot =
            digestsBySession.values
                .asSequence()
                .flatMap { it.asSequence() }
                .sortedByDescending { it.createdAtMs }
                .take(max(1, CROSS_SESSION_DIGEST_LIMIT))
                .map { entry ->
                    buildString {
                        append(TextSecurity.preview(entry.assignment, MAX_ASSIGNMENT_CHARS))
                        if (entry.keyEvidence.isNotEmpty()) {
                            append(" | evidence=")
                            append(entry.keyEvidence.joinToString(" | "))
                        }
                    }
                }
                .toList()
    }

    private data class ScratchpadSection(
        val title: String,
        val summary: String,
        val content: String,
        val source: String,
    )

    private data class ScratchpadEvidence(
        val content: String,
        val trust: DataTrust,
        val source: String,
    ) {
        fun render(): String = "[${trust.name.lowercase()} | $source] $content"
    }

    private inner class Scratchpad(
        val rootInputId: String,
        val rootInputReceivedAtMs: Long,
        var assignment: String,
        val sections: ArrayDeque<ScratchpadSection> = ArrayDeque(),
        val evidence: ArrayDeque<ScratchpadEvidence> = ArrayDeque(),
    ) {
        var version: Long = 0L
            private set
        var updatedAtMs: Long = System.currentTimeMillis()
            private set

        fun updateAssignment(newAssignment: String) {
            if (newAssignment == assignment) return
            assignment = newAssignment
            touch()
        }

        fun addSection(title: String, summary: String, content: String, source: String) {
            if (summary.isBlank() && content.isBlank()) return
            sections.addLast(
                ScratchpadSection(
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
            evidence.addLast(
                ScratchpadEvidence(
                    content = normalized,
                    trust = DataTrust.SANITIZED_EXTERNAL_DATA,
                    source = "legacy_evidence",
                )
            )
            while (evidence.size > evidenceCap()) {
                evidence.removeFirst()
            }
            touch()
        }

        fun addEvidence(artifact: ExternalContentArtifact) {
            val normalized = TextSecurity.preview(artifact.content, evidenceItemCap())
            if (normalized.isBlank()) return
            evidence.addLast(
                ScratchpadEvidence(
                    content = normalized,
                    trust = artifact.dataTrust,
                    source = artifact.taintSourceSummary(),
                )
            )
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
                append("Thread scratchpad (persists for this cognitive thread across resume/wait):\n")
                append("work: ")
                append(assignment.ifBlank { "none" })
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
                        append(item.render())
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
            val assignmentSignal = if (assignment.isBlank()) 0.0 else 1.0
            return (sectionSignal * SECTION_SIGNAL_WEIGHT) +
                (evidenceSignal * EVIDENCE_SIGNAL_WEIGHT) +
                (assignmentSignal * ASSIGNMENT_SIGNAL_WEIGHT)
        }

        fun debugHead(): ScratchpadDebugHead =
            ScratchpadDebugHead(
                rootInputId = rootInputId,
                rootInputReceivedAtMs = rootInputReceivedAtMs,
                version = version,
                updatedAtMs = updatedAtMs,
                assignment = assignment,
                sectionCount = sections.size,
                evidenceCount = evidence.size,
                workspaceConfidence = estimateConfidence(),
                bytesEstimate = estimateBytes()
            )

        fun debugSnapshot(): ScratchpadDebugSnapshot =
            ScratchpadDebugSnapshot(
                head = debugHead(),
                sections = sections.map { section ->
                    ScratchpadDebugSection(
                        title = section.title,
                        summary = section.summary,
                        content = section.content,
                        source = section.source
                    )
                },
                evidence = evidence.map { it.render() },
                evidenceRecords = evidence.map { item ->
                    ScratchpadDebugEvidence(
                        content = item.content,
                        trust = item.trust,
                        source = item.source,
                    )
                }
            )

        private fun touch() {
            version += 1L
            updatedAtMs = System.currentTimeMillis()
        }

        private fun estimateBytes(): Int {
            var total = assignment.length
            sections.forEach { section ->
                total += section.title.length + section.summary.length + section.content.length + section.source.length
            }
            evidence.forEach { item -> total += item.content.length + item.source.length + item.trust.name.length }
            return total
        }
    }

    private companion object {
        const val MAX_ASSIGNMENT_CHARS: Int = 220
        const val MAX_PLAN_STEP_CHARS: Int = 160
        const val FINAL_COMPILATION_SECTION_LIMIT: Int = 6
        const val FINAL_COMPILATION_EVIDENCE_LIMIT: Int = 6
        const val FINAL_COMPILATION_DRAFT_LIMIT: Int = 4
        const val FINAL_COMPILATION_SECTION_CONTENT_PREVIEW_CHARS: Int = 220
        const val SOURCE_INPUT: String = "input"
        const val SOURCE_GOAL_STEP: String = "assignment_step"
        const val SOURCE_GOAL_CONTEXT: String = "assignment_context"
        const val SOURCE_PLAN: String = "plan"
        const val SOURCE_ACTION: String = "action_outcome"
        const val SECTION_SIGNAL_WEIGHT: Double = 0.45
        const val EVIDENCE_SIGNAL_WEIGHT: Double = 0.45
        const val ASSIGNMENT_SIGNAL_WEIGHT: Double = 0.10
        const val DIGEST_MAX_EVIDENCE_ITEMS: Int = 3
        const val MAX_DIGEST_TRACKED_SESSIONS: Int = 32
        const val CROSS_SESSION_ACTIVE_WORKSPACE_LIMIT: Int = 4
        const val CROSS_SESSION_DIGEST_LIMIT: Int = 6
        const val DEFAULT_INTENTION_DRAFT_BUCKET: String = "__default__"
    }

    private data class PendingInputRecord(
        val rootInputId: String,
        val receivedAtMs: Long,
        val contentPreview: String,
    )

    private data class ScratchpadDraft(
        val intentionId: String?,
        val content: String,
        val createdAtMs: Long,
    )

    private val workspaces = LinkedHashMap<String, Scratchpad>()
    private val pendingInputs = LinkedHashMap<String, PendingInputRecord>()
    private val intentionDraftsByRootInput =
        LinkedHashMap<String, LinkedHashMap<String, ArrayDeque<ScratchpadDraft>>>()
    private val activeDraftBucketByRootInput = LinkedHashMap<String, String>()
    private val digestsBySession: MutableMap<String, ArrayDeque<WorkspaceDigestEntry>> =
        object : LinkedHashMap<String, ArrayDeque<WorkspaceDigestEntry>>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, ArrayDeque<WorkspaceDigestEntry>>
            ): Boolean = size > MAX_DIGEST_TRACKED_SESSIONS
        }

    private fun draftBucketKey(intentionId: String?): String =
        intentionId?.trim()?.takeIf { it.isNotBlank() } ?: DEFAULT_INTENTION_DRAFT_BUCKET

    private fun draftBucketKeyForWrite(rootInputId: String, intentionId: String?): String {
        val existing = activeDraftBucketByRootInput[rootInputId]
        if (!existing.isNullOrBlank()) {
            return existing
        }
        return draftBucketKey(intentionId).also { activeDraftBucketByRootInput[rootInputId] = it }
    }
}
