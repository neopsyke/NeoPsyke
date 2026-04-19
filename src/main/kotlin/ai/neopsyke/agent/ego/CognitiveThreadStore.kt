package ai.neopsyke.agent.ego

import mu.KotlinLogging
import ai.neopsyke.agent.assignments.AssignmentActivation
import ai.neopsyke.agent.model.CommitMode
import ai.neopsyke.agent.model.CognitiveThread
import ai.neopsyke.agent.model.CognitiveThreadKind
import ai.neopsyke.agent.model.CognitiveThreadSecurityContext
import ai.neopsyke.agent.model.CognitiveThreadSnapshot
import ai.neopsyke.agent.model.CognitiveThreadStatus
import ai.neopsyke.agent.model.CognitiveThreadTerminalState
import ai.neopsyke.agent.model.CognitiveThreadWaitState
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.ExternalContentArtifact
import ai.neopsyke.agent.model.InputPriority
import ai.neopsyke.agent.model.Intention
import ai.neopsyke.agent.model.IntentionKind
import ai.neopsyke.agent.model.Opportunity
import ai.neopsyke.agent.model.OpportunityKind
import ai.neopsyke.agent.model.PendingFeedback
import ai.neopsyke.agent.model.PendingImpulse
import ai.neopsyke.agent.model.PendingInput
import ai.neopsyke.agent.model.Percept
import ai.neopsyke.agent.model.PerceptFamily
import ai.neopsyke.agent.model.RootInputIds
import ai.neopsyke.agent.support.TextSecurity
import java.time.Instant

private val threadStoreLogger = KotlinLogging.logger("CognitiveThreadStore")

internal class CognitiveThreadStore {
    private sealed interface ContinuationState {
        data class AssignmentContinuation(
            val work: AssignmentActivation,
        ) : ContinuationState
    }

    private data class ThreadRecord(
        val thread: CognitiveThread,
        val latestPercept: Percept? = null,
        val latestOpportunity: Opportunity? = null,
        val latestIntention: Intention? = null,
        val waitState: CognitiveThreadWaitState? = null,
        val terminalState: CognitiveThreadTerminalState? = null,
        val lastBlockedReason: String? = null,
        val lastBlockedReasonCode: String? = null,
        val lastDeniedReason: String? = null,
        val lastDeniedReasonCode: String? = null,
        val continuation: ContinuationState? = null,
    ) {
        fun snapshot(): CognitiveThreadSnapshot =
            CognitiveThreadSnapshot(
                thread = thread,
                latestPercept = latestPercept,
                latestOpportunity = latestOpportunity,
                latestIntention = latestIntention,
                waitState = waitState,
                terminalState = terminalState,
                lastBlockedReason = lastBlockedReason,
                lastBlockedReasonCode = lastBlockedReasonCode,
                lastDeniedReason = lastDeniedReason,
                lastDeniedReasonCode = lastDeniedReasonCode,
            )
    }

    private val activeThreadsByScope: MutableMap<InputScope, ThreadRecord> =
        boundedRecordMap(MAX_ACTIVE_THREADS)
    private val terminalThreadsByScope: MutableMap<InputScope, ThreadRecord> =
        boundedRecordMap(MAX_TERMINAL_THREADS)

    fun bindInput(input: PendingInput): CognitiveThread =
        ensureThread(
            rootInputId = input.rootInputId,
            conversationContext = input.conversationContext,
            kind = CognitiveThreadKind.CONVERSATION,
            title = input.content,
            rootStimulusId = input.percept?.rootStimulusId ?: input.rootInputId,
            percept = input.percept,
        )

    fun inputOpportunity(input: PendingInput): Opportunity {
        val thread = bindInput(input)
        val percept = latestPercept(input.rootInputId, input.conversationContext)
        return opportunityFor(
            thread = thread,
            kind = when (percept?.family) {
                PerceptFamily.OBSERVATION,
                PerceptFamily.FEEDBACK,
                -> OpportunityKind.INTEGRATE_FEEDBACK

                else -> OpportunityKind.RESPOND
            },
            summary = percept?.summary ?: input.content,
            rootStimulusId = percept?.rootStimulusId ?: input.rootInputId,
            salience = input.priority.level.toDouble(),
            allowedIntentions = setOf(
                IntentionKind.OBSERVE,
                IntentionKind.PREPARE,
                IntentionKind.STAGE,
                IntentionKind.REQUEST_AUTHORIZATION,
                IntentionKind.COMMIT,
            ),
            allowedCommitModes = CognitivePolicyShaper.opportunityCommitModes(thread.securityContext),
        ).also { opportunity ->
            recordOpportunity(input.rootInputId, input.conversationContext, opportunity)
        }
    }

    fun feedbackOpportunity(feedback: PendingFeedback): Opportunity {
        val rootInputId = feedback.cue.rootInputId
        val thread = ensureThread(
            rootInputId = rootInputId,
            conversationContext = feedback.cue.conversationContext,
            kind = thread(rootInputId, feedback.cue.conversationContext)?.kind ?: CognitiveThreadKind.CONVERSATION,
            title = feedback.cue.actionSummary.ifBlank { feedback.stimulusContent },
            rootStimulusId = rootInputId,
            percept = feedback.percept,
        )
        return opportunityFor(
            thread = thread,
            kind = OpportunityKind.INTEGRATE_FEEDBACK,
            summary = feedback.cue.statusSummary.ifBlank {
                feedback.cue.feedbackContent.ifBlank { feedback.cue.actionSummary }
            },
            rootStimulusId = rootInputId,
            salience = InputPriority.HIGH.level.toDouble(),
            allowedIntentions = setOf(
                IntentionKind.OBSERVE,
                IntentionKind.PREPARE,
                IntentionKind.STAGE,
                IntentionKind.REQUEST_AUTHORIZATION,
                IntentionKind.COMMIT,
            ),
            allowedCommitModes = CognitivePolicyShaper.opportunityCommitModes(thread.securityContext),
        ).also { opportunity ->
            recordOpportunity(rootInputId, feedback.cue.conversationContext, opportunity)
        }
    }

    fun bindPercept(
        percept: Percept,
        rootInputId: String = percept.rootStimulusId ?: RootInputIds.next(),
        kind: CognitiveThreadKind = kindFor(percept),
        title: String = percept.summary,
    ): CognitiveThread =
        ensureThread(
            rootInputId = rootInputId,
            conversationContext = percept.conversationContext,
            kind = kind,
            title = title,
            rootStimulusId = percept.rootStimulusId,
            percept = percept,
        )

    fun ensureForImpulse(impulse: PendingImpulse, percept: Percept? = null): CognitiveThread =
        ensureThread(
            rootInputId = impulse.rootImpulseId,
            conversationContext = impulse.conversationContext,
            kind = CognitiveThreadKind.DRIVE,
            title = impulse.prompt,
            rootStimulusId = impulse.rootImpulseId,
            percept = percept,
        )

    fun impulseOpportunity(impulse: PendingImpulse, percept: Percept? = null): Opportunity {
        val thread = ensureForImpulse(impulse, percept)
        return opportunityFor(
            thread = thread,
            kind = OpportunityKind.EXECUTE,
            summary = impulse.prompt,
            rootStimulusId = impulse.rootImpulseId,
            salience = impulse.tension,
            allowedIntentions = setOf(
                IntentionKind.OBSERVE,
                IntentionKind.PREPARE,
                IntentionKind.STAGE,
                IntentionKind.REQUEST_AUTHORIZATION,
                IntentionKind.COMMIT,
            ),
            allowedCommitModes = CognitivePolicyShaper.opportunityCommitModes(thread.securityContext),
        ).also { opportunity ->
            recordOpportunity(impulse.rootImpulseId, impulse.conversationContext, opportunity)
        }
    }

    fun ensureForAssignment(work: AssignmentActivation): CognitiveThread =
        ensureThread(
            rootInputId = work.rootInputId,
            conversationContext = work.conversationContext,
            kind = CognitiveThreadKind.ASSIGNMENT_DIRECTED,
            title = work.stepDescription,
            rootStimulusId = work.rootInputId,
            metadata = mapOf(
                META_ASSIGNMENT_ID to work.workItemId,
                META_ASSIGNMENT_STEP_ID to work.stepId,
                META_ASSIGNMENT_WAKE_REASON to work.wakeReason,
            ),
            workItemId = work.workItemId,
        )

    fun bindAssignment(work: AssignmentActivation): CognitiveThread {
        val thread = ensureForAssignment(work)
        update(work.rootInputId, work.conversationContext) { record ->
            record.copy(
                thread = record.thread.copy(
                    status = CognitiveThreadStatus.ACTIVE,
                    lastUpdatedAt = Instant.now(),
                ),
                waitState = null,
                terminalState = null,
                continuation = ContinuationState.AssignmentContinuation(work),
            )
        }
        return thread
    }

    fun assignmentOpportunity(work: AssignmentActivation): Opportunity {
        val thread = ensureForAssignment(work)
        return opportunityFor(
            thread = thread,
            kind = OpportunityKind.RESUME,
            summary = work.stepDescription,
            rootStimulusId = work.rootInputId,
            salience = ASSIGNMENT_SALIENCE,
            allowedIntentions = setOf(
                IntentionKind.OBSERVE,
                IntentionKind.PREPARE,
                IntentionKind.STAGE,
                IntentionKind.REQUEST_AUTHORIZATION,
                IntentionKind.COMMIT,
            ),
            allowedCommitModes = CognitivePolicyShaper.opportunityCommitModes(thread.securityContext),
        ).also { opportunity ->
            recordOpportunity(work.rootInputId, work.conversationContext, opportunity)
        }
    }

    fun thread(rootInputId: String?, conversationContext: ConversationContext): CognitiveThread? =
        record(rootInputId, conversationContext)?.thread

    fun latestPercept(rootInputId: String?, conversationContext: ConversationContext): Percept? =
        record(rootInputId, conversationContext)?.latestPercept

    fun latestOpportunity(rootInputId: String?, conversationContext: ConversationContext): Opportunity? =
        record(rootInputId, conversationContext)?.latestOpportunity

    fun latestIntention(rootInputId: String?, conversationContext: ConversationContext): Intention? =
        record(rootInputId, conversationContext)?.latestIntention

    fun snapshot(rootInputId: String?, conversationContext: ConversationContext): CognitiveThreadSnapshot? =
        record(rootInputId, conversationContext)?.snapshot()

    fun isBlocked(rootInputId: String?, conversationContext: ConversationContext): Boolean =
        record(rootInputId, conversationContext)?.thread?.status == CognitiveThreadStatus.BLOCKED

    @Synchronized
    fun snapshotByThreadId(threadId: String): CognitiveThreadSnapshot? =
        (activeThreadsByScope.values.asSequence() + terminalThreadsByScope.values.asSequence())
            .firstOrNull { it.thread.id == threadId }
            ?.snapshot()

    /**
     * Returns thread snapshots for dashboard / external inspection.
     * Synchronized because this may be called from outside the runLoop
     * coroutine while mutations are in progress.
     */
    @Synchronized
    fun snapshots(includeTerminal: Boolean = false, limit: Int = DEFAULT_SNAPSHOT_LIMIT): List<CognitiveThreadSnapshot> {
        val active = activeThreadsByScope.values
            .asSequence()
            .map { it.snapshot() }
        val terminal = if (includeTerminal) {
            terminalThreadsByScope.values.asSequence().map { it.snapshot() }
        } else {
            emptySequence()
        }
        return (active + terminal)
            .sortedByDescending { it.thread.lastUpdatedAt }
            .take(limit.coerceAtLeast(0))
            .toList()
    }

    fun threadSecurityContext(
        rootInputId: String?,
        conversationContext: ConversationContext,
    ): CognitiveThreadSecurityContext =
        thread(rootInputId, conversationContext)?.securityContext
            ?: CognitiveThreadSecurityContext.fromConversation(conversationContext.security)

    fun assignment(rootInputId: String?, conversationContext: ConversationContext): AssignmentActivation? =
        when (val continuation = record(rootInputId, conversationContext)?.continuation) {
            is ContinuationState.AssignmentContinuation -> continuation.work
            null -> null
        }

    fun recordOpportunity(rootInputId: String?, conversationContext: ConversationContext, opportunity: Opportunity) {
        update(rootInputId, conversationContext) { record ->
            record.copy(
                latestOpportunity = opportunity,
                thread = record.thread.copy(
                    status = CognitiveThreadStatus.ACTIVE,
                    lastUpdatedAt = Instant.now(),
                ),
                waitState = null,
                terminalState = null,
                lastBlockedReason = null,
                lastBlockedReasonCode = null,
            )
        }
    }

    fun recordIntention(rootInputId: String?, conversationContext: ConversationContext, intention: Intention) {
        update(rootInputId, conversationContext) { record ->
            record.copy(
                latestIntention = intention,
                thread = record.thread.copy(
                    status = CognitiveThreadStatus.ACTIVE,
                    lastUpdatedAt = Instant.now(),
                ),
                waitState = null,
                terminalState = null,
                lastBlockedReason = null,
                lastBlockedReasonCode = null,
            )
        }
    }

    fun markWaiting(
        rootInputId: String?,
        conversationContext: ConversationContext,
        reason: String? = null,
        resumeHint: String? = null,
    ) {
        update(rootInputId, conversationContext) { record ->
            record.copy(
                thread = record.thread.copy(
                    status = CognitiveThreadStatus.WAITING,
                    lastUpdatedAt = Instant.now(),
                    metadata = record.thread.metadata + listOfNotNull(
                        reason?.takeIf { it.isNotBlank() }?.let { META_THREAD_WAIT_REASON to it },
                        resumeHint?.takeIf { it.isNotBlank() }?.let { META_THREAD_RESUME_HINT to it },
                    ).toMap(),
                ),
                waitState = CognitiveThreadWaitState(
                    status = CognitiveThreadStatus.WAITING,
                    reason = reason,
                    since = Instant.now(),
                    resumeHint = resumeHint,
                ),
            )
        }
    }

    fun markBlocked(rootInputId: String?, conversationContext: ConversationContext, reason: String? = null) {
        markBlocked(rootInputId, conversationContext, reason, null)
    }

    fun markBlocked(
        rootInputId: String?,
        conversationContext: ConversationContext,
        reason: String? = null,
        reasonCode: String? = null,
    ) {
        update(rootInputId, conversationContext) { record ->
            record.copy(
                thread = record.thread.copy(
                    status = CognitiveThreadStatus.BLOCKED,
                    lastUpdatedAt = Instant.now(),
                    metadata = record.thread.metadata + listOfNotNull(
                        reason?.takeIf { it.isNotBlank() }?.let { META_THREAD_BLOCK_REASON to it },
                        reasonCode?.takeIf { it.isNotBlank() }?.let { META_THREAD_BLOCK_REASON_CODE to it },
                    ).toMap(),
                ),
                waitState = CognitiveThreadWaitState(
                    status = CognitiveThreadStatus.BLOCKED,
                    reason = reason,
                    since = Instant.now(),
                ),
                lastBlockedReason = reason,
                lastBlockedReasonCode = reasonCode,
            )
        }
    }

    fun recordDenied(
        rootInputId: String?,
        conversationContext: ConversationContext,
        reason: String? = null,
        reasonCode: String? = null,
    ) {
        update(rootInputId, conversationContext) { record ->
            record.copy(
                thread = record.thread.copy(
                    lastUpdatedAt = Instant.now(),
                    metadata = record.thread.metadata + listOfNotNull(
                        reason?.takeIf { it.isNotBlank() }?.let { META_THREAD_DENIAL_REASON to it },
                        reasonCode?.takeIf { it.isNotBlank() }?.let { META_THREAD_DENIAL_REASON_CODE to it },
                    ).toMap(),
                ),
                lastDeniedReason = reason,
                lastDeniedReasonCode = reasonCode,
            )
        }
    }

    fun markResolved(
        rootInputId: String?,
        conversationContext: ConversationContext,
        reason: String? = null,
        summary: String? = null,
    ) {
        update(rootInputId, conversationContext) { record ->
            val terminalRecord = record.copy(
                thread = record.thread.copy(
                    status = CognitiveThreadStatus.RESOLVED,
                    lastUpdatedAt = Instant.now(),
                    metadata = record.thread.metadata + listOfNotNull(
                        reason?.takeIf { it.isNotBlank() }?.let { META_THREAD_RESOLUTION_REASON to it }
                    ).toMap(),
                ),
                waitState = null,
                terminalState = CognitiveThreadTerminalState(
                    status = CognitiveThreadStatus.RESOLVED,
                    summary = summary ?: terminalSummaryFor(record),
                    reason = reason,
                    completedAt = Instant.now(),
                ),
                continuation = null,
            )
            promoteToTerminal(rootInputId, conversationContext, terminalRecord)
            terminalRecord
        }
    }

    fun markFailed(
        rootInputId: String?,
        conversationContext: ConversationContext,
        reason: String? = null,
        summary: String? = null,
    ) {
        update(rootInputId, conversationContext) { record ->
            val terminalRecord = record.copy(
                thread = record.thread.copy(
                    status = CognitiveThreadStatus.FAILED,
                    lastUpdatedAt = Instant.now(),
                    metadata = record.thread.metadata + listOfNotNull(
                        reason?.takeIf { it.isNotBlank() }?.let { META_THREAD_FAILURE_REASON to it }
                    ).toMap(),
                ),
                waitState = null,
                terminalState = CognitiveThreadTerminalState(
                    status = CognitiveThreadStatus.FAILED,
                    summary = summary ?: terminalSummaryFor(record),
                    reason = reason,
                    completedAt = Instant.now(),
                ),
                continuation = null,
            )
            promoteToTerminal(rootInputId, conversationContext, terminalRecord)
            terminalRecord
        }
    }

    fun retainedRootInputIds(): Set<String> =
        activeThreadsByScope.entries
            .asSequence()
            .filter { (_, record) ->
                when (record.thread.status) {
                    CognitiveThreadStatus.ACTIVE,
                    CognitiveThreadStatus.WAITING,
                    CognitiveThreadStatus.BLOCKED,
                    -> true

                    CognitiveThreadStatus.RESOLVED,
                    CognitiveThreadStatus.FAILED,
                    -> false
                }
            }
            .mapNotNull { entry ->
                entry.key.rootInputId?.takeIf { value -> value.isNotBlank() }
            }
            .toSet()

    fun observeArtifacts(
        rootInputId: String?,
        conversationContext: ConversationContext,
        artifacts: List<ExternalContentArtifact>,
    ) {
        if (artifacts.isEmpty()) return
        update(rootInputId, conversationContext) { record ->
            val updatedThread = record.thread.copy(
                securityContext = artifacts.fold(record.thread.securityContext) { acc, artifact ->
                    acc.withObservedArtifact(
                        sourceSummary = artifact.taintSourceSummary(),
                        dataTrust = artifact.dataTrust,
                    )
                },
                lastUpdatedAt = Instant.now(),
            )
            record.copy(thread = updatedThread)
        }
    }

    fun clearForInput(rootInputId: String?, sessionId: String) {
        val scope = scope(rootInputId, sessionId) ?: return
        val activeRecord = activeThreadsByScope[scope]
        if (activeRecord != null && activeRecord.thread.status.isTerminal()) {
            promoteToTerminal(scope, activeRecord)
            activeThreadsByScope.remove(scope)
            return
        }
        activeThreadsByScope.remove(scope)
    }

    fun reset() {
        activeThreadsByScope.clear()
        terminalThreadsByScope.clear()
    }

    private fun ensureThread(
        rootInputId: String,
        conversationContext: ConversationContext,
        kind: CognitiveThreadKind,
        title: String,
        rootStimulusId: String? = null,
        percept: Percept? = null,
        metadata: Map<String, String> = emptyMap(),
        workItemId: String? = null,
    ): CognitiveThread {
        val scope = scope(rootInputId, conversationContext.sessionId)
            ?: error("rootInputId must not be blank")
        val now = Instant.now()
        val existing = activeThreadsByScope[scope] ?: terminalThreadsByScope.remove(scope)
        val thread = if (existing != null) {
            existing.thread.copy(
                kind = kind,
                status = CognitiveThreadStatus.ACTIVE,
                title = previewTitle(title),
                securityContext = existing.thread.securityContext,
                workItemId = workItemId ?: existing.thread.workItemId,
                rootStimulusId = rootStimulusId ?: existing.thread.rootStimulusId,
                lastUpdatedAt = now,
                metadata = existing.thread.metadata + metadata,
            )
        } else {
            CognitiveThread(
                id = RootInputIds.next(),
                kind = kind,
                status = CognitiveThreadStatus.ACTIVE,
                title = previewTitle(title),
                conversationContext = conversationContext,
                securityContext = CognitiveThreadSecurityContext.fromConversation(conversationContext.security),
                workItemId = workItemId,
                rootStimulusId = rootStimulusId,
                lastUpdatedAt = now,
                metadata = metadata,
            )
        }
        val boundPercept = percept?.copy(cognitiveThreadId = thread.id)
        activeThreadsByScope[scope] = ThreadRecord(
            thread = thread,
            latestPercept = boundPercept ?: existing?.latestPercept,
            latestOpportunity = existing?.latestOpportunity,
            latestIntention = existing?.latestIntention,
            waitState = null,
            terminalState = null,
            continuation = existing?.continuation,
        )
        return thread
    }

    private fun update(
        rootInputId: String?,
        conversationContext: ConversationContext,
        transform: (ThreadRecord) -> ThreadRecord,
    ) {
        val scope = scope(rootInputId, conversationContext.sessionId) ?: return
        val current = activeThreadsByScope[scope] ?: terminalThreadsByScope[scope] ?: return
        val updated = transform(current)
        if (updated.thread.status.isTerminal()) {
            promoteToTerminal(scope, updated)
            activeThreadsByScope.remove(scope)
        } else {
            terminalThreadsByScope.remove(scope)
            activeThreadsByScope[scope] = updated
        }
    }

    private fun record(rootInputId: String?, conversationContext: ConversationContext): ThreadRecord? =
        scope(rootInputId, conversationContext.sessionId)?.let { activeThreadsByScope[it] ?: terminalThreadsByScope[it] }

    private fun promoteToTerminal(
        rootInputId: String?,
        conversationContext: ConversationContext,
        record: ThreadRecord,
    ) {
        val scope = scope(rootInputId, conversationContext.sessionId) ?: return
        promoteToTerminal(scope, record)
    }

    private fun promoteToTerminal(scope: InputScope, record: ThreadRecord) {
        activeThreadsByScope.remove(scope)
        terminalThreadsByScope[scope] = record
    }

    private fun scope(rootInputId: String?, sessionId: String): InputScope? {
        if (rootInputId.isNullOrBlank()) {
            threadStoreLogger.debug { "Scope not created: blank rootInputId (session=$sessionId)" }
            return null
        }
        return InputScope(rootInputId = rootInputId, sessionId = sessionId)
    }

    private fun kindFor(percept: Percept): CognitiveThreadKind =
        when (percept.family) {
            PerceptFamily.REQUEST,
            PerceptFamily.OBSERVATION,
            PerceptFamily.FEEDBACK,
            -> CognitiveThreadKind.CONVERSATION

            PerceptFamily.DRIVE_ACTIVATION ->
                CognitiveThreadKind.DRIVE

            PerceptFamily.STATE_CHANGE ->
                CognitiveThreadKind.ASSIGNMENT_DIRECTED
        }

    private fun previewTitle(value: String): String = TextSecurity.preview(value, MAX_TITLE_CHARS)

    private fun opportunityFor(
        thread: CognitiveThread,
        kind: OpportunityKind,
        summary: String,
        rootStimulusId: String?,
        salience: Double,
        allowedIntentions: Set<IntentionKind>,
        allowedCommitModes: Set<CommitMode>,
    ): Opportunity =
        Opportunity(
            id = RootInputIds.next(),
            cognitiveThreadId = thread.id,
            kind = kind,
            summary = previewTitle(summary),
            salience = salience,
            createdAt = Instant.now(),
            conversationContext = thread.conversationContext,
            securityContext = thread.securityContext,
            rootStimulusId = rootStimulusId,
            workItemId = thread.workItemId,
            assignmentRunId = thread.assignmentRunId,
            allowedIntentions = allowedIntentions,
            allowedCommitModes = allowedCommitModes,
            metadata = thread.metadata,
        )

    private fun terminalSummaryFor(record: ThreadRecord): String =
        record.latestIntention?.summary
            ?: record.latestOpportunity?.summary
            ?: record.latestPercept?.summary
            ?: record.thread.title

    private fun boundedRecordMap(limit: Int): MutableMap<InputScope, ThreadRecord> =
        object : LinkedHashMap<InputScope, ThreadRecord>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<InputScope, ThreadRecord>): Boolean =
                size > limit
        }

    private fun CognitiveThreadStatus.isTerminal(): Boolean =
        this == CognitiveThreadStatus.RESOLVED || this == CognitiveThreadStatus.FAILED

    private companion object {
        private const val MAX_ACTIVE_THREADS: Int = 256
        private const val MAX_TERMINAL_THREADS: Int = 512
        private const val MAX_TITLE_CHARS: Int = 160
        private const val ASSIGNMENT_SALIENCE: Double = 0.75
        private const val DEFAULT_SNAPSHOT_LIMIT: Int = 100

        private const val META_ASSIGNMENT_ID: String = "assignment_id"
        private const val META_ASSIGNMENT_STEP_ID: String = "assignment_step_id"
        private const val META_ASSIGNMENT_WAKE_REASON: String = "assignment_wake_reason"
        private const val META_THREAD_WAIT_REASON: String = "thread_wait_reason"
        private const val META_THREAD_RESUME_HINT: String = "thread_resume_hint"
        private const val META_THREAD_BLOCK_REASON: String = "thread_block_reason"
        private const val META_THREAD_BLOCK_REASON_CODE: String = "thread_block_reason_code"
        private const val META_THREAD_DENIAL_REASON: String = "thread_denial_reason"
        private const val META_THREAD_DENIAL_REASON_CODE: String = "thread_denial_reason_code"
        private const val META_THREAD_RESOLUTION_REASON: String = "thread_resolution_reason"
        private const val META_THREAD_FAILURE_REASON: String = "thread_failure_reason"
    }
}
