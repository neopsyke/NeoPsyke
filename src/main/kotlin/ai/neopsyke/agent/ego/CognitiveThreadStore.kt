package ai.neopsyke.agent.ego

import ai.neopsyke.agent.goal.GoalRunActivation
import ai.neopsyke.agent.model.CommitMode
import ai.neopsyke.agent.model.CognitiveThread
import ai.neopsyke.agent.model.CognitiveThreadKind
import ai.neopsyke.agent.model.CognitiveThreadSecurityContext
import ai.neopsyke.agent.model.CognitiveThreadStatus
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.ExternalContentArtifact
import ai.neopsyke.agent.model.IntentionKind
import ai.neopsyke.agent.model.Opportunity
import ai.neopsyke.agent.model.OpportunityKind
import ai.neopsyke.agent.model.PendingImpulse
import ai.neopsyke.agent.model.PendingInput
import ai.neopsyke.agent.model.Percept
import ai.neopsyke.agent.model.PerceptFamily
import ai.neopsyke.agent.model.RootInputIds
import ai.neopsyke.agent.support.TextSecurity
import java.time.Instant

internal class CognitiveThreadStore {
    private sealed interface ContinuationState {
        data class GoalActivation(
            val work: GoalRunActivation,
        ) : ContinuationState
    }

    private data class ThreadRecord(
        val thread: CognitiveThread,
        val latestPercept: Percept? = null,
        val continuation: ContinuationState? = null,
    )

    private val threadsByScope: MutableMap<InputScope, ThreadRecord> =
        object : LinkedHashMap<InputScope, ThreadRecord>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<InputScope, ThreadRecord>): Boolean =
                size > MAX_THREADS
        }

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
                PerceptFamily.OBSERVATION -> OpportunityKind.INTEGRATE_FEEDBACK
                PerceptFamily.FEEDBACK -> OpportunityKind.INTEGRATE_FEEDBACK
                else -> OpportunityKind.RESPOND
            },
            summary = percept?.summary ?: input.content,
            rootStimulusId = percept?.rootStimulusId ?: input.rootInputId,
            salience = input.priority.level.toDouble(),
            allowedIntentions = setOf(
                IntentionKind.OBSERVE,
                IntentionKind.PREPARE,
                IntentionKind.DEFER,
            ),
            allowedCommitModes = CognitivePolicyShaper.opportunityCommitModes(thread.securityContext),
        )
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

    fun ensureForImpulse(impulse: PendingImpulse): CognitiveThread =
        ensureThread(
            rootInputId = impulse.rootImpulseId,
            conversationContext = impulse.conversationContext,
            kind = CognitiveThreadKind.DRIVE,
            title = impulse.prompt,
            rootStimulusId = impulse.rootImpulseId,
        )

    fun impulseOpportunity(impulse: PendingImpulse): Opportunity {
        val thread = ensureForImpulse(impulse)
        return opportunityFor(
            thread = thread,
            kind = OpportunityKind.EXECUTE,
            summary = impulse.prompt,
            rootStimulusId = impulse.rootImpulseId,
            salience = impulse.tension,
            allowedIntentions = setOf(
                IntentionKind.OBSERVE,
                IntentionKind.PREPARE,
                IntentionKind.DEFER,
            ),
            allowedCommitModes = CognitivePolicyShaper.opportunityCommitModes(thread.securityContext),
        )
    }

    fun ensureForGoalWork(work: GoalRunActivation): CognitiveThread =
        ensureThread(
            rootInputId = work.rootInputId,
            conversationContext = work.conversationContext,
            kind = CognitiveThreadKind.GOAL_DIRECTED,
            title = work.stepDescription,
            rootStimulusId = work.rootInputId,
            metadata = mapOf(
                "goal_id" to work.goalId,
                "goal_step_id" to work.stepId,
                "goal_wake_reason" to work.wakeReason,
            ),
            goalId = work.goalId,
        )

    fun bindGoalWork(work: GoalRunActivation): CognitiveThread {
        val thread = ensureForGoalWork(work)
        update(work.rootInputId, work.conversationContext) { record ->
            record.copy(
                thread = record.thread.copy(
                    status = CognitiveThreadStatus.ACTIVE,
                    lastUpdatedAt = Instant.now(),
                ),
                continuation = ContinuationState.GoalActivation(work),
            )
        }
        return thread
    }

    fun goalOpportunity(work: GoalRunActivation): Opportunity {
        val thread = ensureForGoalWork(work)
        return opportunityFor(
            thread = thread,
            kind = OpportunityKind.RESUME,
            summary = work.stepDescription,
            rootStimulusId = work.rootInputId,
            salience = GOAL_SALIENCE,
            allowedIntentions = setOf(
                IntentionKind.PREPARE,
                IntentionKind.STAGE,
                IntentionKind.COMMIT,
                IntentionKind.DEFER,
            ),
            allowedCommitModes = CognitivePolicyShaper.opportunityCommitModes(thread.securityContext),
        )
    }

    fun thread(rootInputId: String?, conversationContext: ConversationContext): CognitiveThread? =
        record(rootInputId, conversationContext)?.thread

    fun latestPercept(rootInputId: String?, conversationContext: ConversationContext): Percept? =
        record(rootInputId, conversationContext)?.latestPercept

    fun threadSecurityContext(
        rootInputId: String?,
        conversationContext: ConversationContext,
    ): CognitiveThreadSecurityContext =
        thread(rootInputId, conversationContext)?.securityContext
            ?: CognitiveThreadSecurityContext.fromConversation(conversationContext.security)

    fun goalWork(rootInputId: String?, conversationContext: ConversationContext): GoalRunActivation? =
        when (val continuation = record(rootInputId, conversationContext)?.continuation) {
            is ContinuationState.GoalActivation -> continuation.work
            null -> null
        }

    fun markWaiting(rootInputId: String?, conversationContext: ConversationContext, reason: String? = null) {
        update(rootInputId, conversationContext) { record ->
            record.copy(
                thread = record.thread.copy(
                    status = CognitiveThreadStatus.WAITING,
                    lastUpdatedAt = Instant.now(),
                    metadata = record.thread.metadata + listOfNotNull(
                        reason?.takeIf { it.isNotBlank() }?.let { "thread_wait_reason" to it }
                    ).toMap(),
                )
            )
        }
    }

    fun markBlocked(rootInputId: String?, conversationContext: ConversationContext, reason: String? = null) {
        update(rootInputId, conversationContext) { record ->
            record.copy(
                thread = record.thread.copy(
                    status = CognitiveThreadStatus.BLOCKED,
                    lastUpdatedAt = Instant.now(),
                    metadata = record.thread.metadata + listOfNotNull(
                        reason?.takeIf { it.isNotBlank() }?.let { "thread_block_reason" to it }
                    ).toMap(),
                )
            )
        }
    }

    fun markResolved(rootInputId: String?, conversationContext: ConversationContext) {
        update(rootInputId, conversationContext) { record ->
            record.copy(
                thread = record.thread.copy(
                    status = CognitiveThreadStatus.RESOLVED,
                    lastUpdatedAt = Instant.now(),
                ),
                continuation = null,
            )
        }
    }

    fun markFailed(rootInputId: String?, conversationContext: ConversationContext, reason: String? = null) {
        update(rootInputId, conversationContext) { record ->
            record.copy(
                thread = record.thread.copy(
                    status = CognitiveThreadStatus.FAILED,
                    lastUpdatedAt = Instant.now(),
                    metadata = record.thread.metadata + listOfNotNull(
                        reason?.takeIf { it.isNotBlank() }?.let { "thread_failure_reason" to it }
                    ).toMap(),
                ),
                continuation = null,
            )
        }
    }

    fun retainedRootInputIds(): Set<String> =
        threadsByScope.entries
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
        scope(rootInputId, sessionId)?.let { threadsByScope.remove(it) }
    }

    fun reset() {
        threadsByScope.clear()
    }

    private fun ensureThread(
        rootInputId: String,
        conversationContext: ConversationContext,
        kind: CognitiveThreadKind,
        title: String,
        rootStimulusId: String? = null,
        percept: Percept? = null,
        metadata: Map<String, String> = emptyMap(),
        goalId: String? = null,
    ): CognitiveThread {
        val scope = scope(rootInputId, conversationContext.sessionId)
            ?: error("rootInputId must not be blank")
        val now = Instant.now()
        val existing = threadsByScope[scope]
        val thread = if (existing != null) {
            existing.thread.copy(
                status = CognitiveThreadStatus.ACTIVE,
                title = previewTitle(title),
                securityContext = existing.thread.securityContext,
                lastUpdatedAt = now,
                metadata = existing.thread.metadata + metadata,
                goalId = goalId ?: existing.thread.goalId,
            )
        } else {
            CognitiveThread(
                id = RootInputIds.next(),
                kind = kind,
                status = CognitiveThreadStatus.ACTIVE,
                title = previewTitle(title),
                conversationContext = conversationContext,
                securityContext = CognitiveThreadSecurityContext.fromConversation(conversationContext.security),
                goalId = goalId,
                rootStimulusId = rootStimulusId,
                lastUpdatedAt = now,
                metadata = metadata,
            )
        }
        val boundPercept = percept?.copy(cognitiveThreadId = thread.id)
        threadsByScope[scope] = ThreadRecord(
            thread = thread,
            latestPercept = boundPercept ?: existing?.latestPercept,
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
        val current = threadsByScope[scope] ?: return
        threadsByScope[scope] = transform(current)
    }

    private fun record(rootInputId: String?, conversationContext: ConversationContext): ThreadRecord? =
        scope(rootInputId, conversationContext.sessionId)?.let { threadsByScope[it] }

    private fun scope(rootInputId: String?, sessionId: String): InputScope? {
        if (rootInputId.isNullOrBlank()) return null
        return InputScope(rootInputId = rootInputId, sessionId = sessionId)
    }

    private fun kindFor(percept: Percept): CognitiveThreadKind =
        when (percept.family) {
            PerceptFamily.REQUEST, PerceptFamily.OBSERVATION, PerceptFamily.FEEDBACK ->
                CognitiveThreadKind.CONVERSATION
            PerceptFamily.DRIVE_ACTIVATION ->
                CognitiveThreadKind.DRIVE
            PerceptFamily.STATE_CHANGE ->
                CognitiveThreadKind.GOAL_DIRECTED
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
            goalId = thread.goalId,
            goalRunId = thread.goalRunId,
            allowedIntentions = allowedIntentions,
            allowedCommitModes = allowedCommitModes,
            metadata = thread.metadata,
        )

    private companion object {
        private const val MAX_THREADS: Int = 256
        private const val MAX_TITLE_CHARS: Int = 160
        private const val GOAL_SALIENCE: Double = 0.75
    }
}
