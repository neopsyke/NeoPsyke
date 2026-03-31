package ai.neopsyke.agent.ego

import ai.neopsyke.agent.goal.GoalRunActivation
import ai.neopsyke.agent.model.CognitiveThread
import ai.neopsyke.agent.model.CognitiveThreadKind
import ai.neopsyke.agent.model.CognitiveThreadSecurityContext
import ai.neopsyke.agent.model.CognitiveThreadStatus
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.ExternalContentArtifact
import ai.neopsyke.agent.model.PendingImpulse
import ai.neopsyke.agent.model.PendingInput
import ai.neopsyke.agent.model.Percept
import ai.neopsyke.agent.model.PerceptFamily
import ai.neopsyke.agent.model.RootInputIds
import ai.neopsyke.agent.support.TextSecurity
import java.time.Instant

internal class CognitiveThreadStore {
    private data class ThreadRecord(
        val thread: CognitiveThread,
        val latestPercept: Percept? = null,
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

    private companion object {
        private const val MAX_THREADS: Int = 256
        private const val MAX_TITLE_CHARS: Int = 160
    }
}
