package psyke.agent.ego

import psyke.agent.config.AgentConfig
import psyke.agent.model.DeliberationState
import psyke.agent.memory.workspace.TaskWorkspaceStore
import psyke.agent.support.TextSecurity
import psyke.instrumentation.AgentEvent
import psyke.instrumentation.AgentEvents
import psyke.instrumentation.AgentInstrumentation

internal class EgoTelemetry(
    private val instrumentation: AgentInstrumentation,
    private val scheduler: AttentionScheduler,
    private val memory: MemoryCoordinator,
    private val taskWorkspaceStore: TaskWorkspaceStore,
    private val config: AgentConfig,
) {
    fun emitDeliberationState(taskType: String, state: DeliberationState) {
        instrumentation.emit(
            AgentEvent(
                type = "deliberation_state",
                data = mapOf(
                    "task_type" to taskType,
                    "step_index" to state.stepIndex,
                    "decision_pressure" to state.decisionPressure,
                    "stale_streak" to state.staleStreak,
                    "progress_score" to state.progressScore,
                    "denial_count" to state.denialCount,
                    "steps_since_new_evidence" to state.stepsSinceNewEvidence,
                    "repeat_signature_hits" to state.repeatSignatureHits,
                    "noop_streak" to state.noopStreak,
                    "model_error_streak" to state.modelErrorStreak
                )
            )
        )
    }

    fun emitQueueSnapshot(source: String) {
        instrumentation.emit(
            AgentEvents.queueSnapshot(source = source, queues = scheduler.queueState())
        )
    }

    fun emitHeapSnapshot() {
        val rt = Runtime.getRuntime()
        val usedBytes = rt.totalMemory() - rt.freeMemory()
        val maxBytes = rt.maxMemory()
        val memStats = memory.activeMemoryStats()
        val queueSnap = scheduler.queueSnapshot()
        val modules = buildMap<String, Map<String, Any?>> {
            put("memory_store", mapOf(
                "label" to "Short-term memory",
                "item_count" to memStats.recentTurns,
                "chars_or_bytes" to memStats.totalChars.toLong(),
                "summary_chars" to memStats.summaryChars.toLong(),
                "unit" to "chars",
            ))
            put("task_workspaces", mapOf(
                "label" to "Task workspaces",
                "item_count" to taskWorkspaceStore.activeTaskCount(),
                "chars_or_bytes" to 0L,
                "unit" to "items",
            ))
            put("attention_queues", mapOf(
                "label" to "Attention queues",
                "item_count" to (queueSnap.pendingInputCount + queueSnap.pendingThoughtCount + queueSnap.pendingActionCount),
                "chars_or_bytes" to 0L,
                "unit" to "items",
            ))
        }
        instrumentation.emit(
            AgentEvents.heapSnapshot(
                jvmTotalBytes = rt.totalMemory(),
                jvmFreeBytes = rt.freeMemory(),
                jvmMaxBytes = maxBytes,
                jvmUsedBytes = usedBytes,
                jvmUsedPercent = if (maxBytes > 0) (usedBytes.toDouble() / maxBytes) * 100.0 else 0.0,
                moduleEstimates = modules,
            )
        )
    }

    fun emitTaskWorkspaceTelemetry(
        rootInputId: String?,
        rootInputReceivedAtMs: Long?,
        updateType: String,
    ) {
        val head = taskWorkspaceStore.debugHead(rootInputId) ?: return
        instrumentation.emit(
            AgentEvent(
                type = "task_workspace_head",
                data = mapOf(
                    "root_input_id" to head.rootInputId,
                    "root_input_received_at_ms" to head.rootInputReceivedAtMs,
                    "update_type" to updateType,
                    "version" to head.version,
                    "updated_at_ms" to head.updatedAtMs,
                    "goal_preview" to TextSecurity.preview(head.goal, 140),
                    "section_count" to head.sectionCount,
                    "evidence_count" to head.evidenceCount,
                    "workspace_confidence" to head.workspaceConfidence,
                    "bytes_estimate" to head.bytesEstimate
                )
            )
        )
        if (!config.memory.taskWorkspace.debugCaptureEnabled) return
        val snapshot = taskWorkspaceStore.debugSnapshot(rootInputId) ?: return
        instrumentation.emit(
            AgentEvent(
                type = "task_workspace_debug_snapshot",
                data = mapOf(
                    "root_input_id" to snapshot.head.rootInputId,
                    "root_input_received_at_ms" to snapshot.head.rootInputReceivedAtMs,
                    "update_type" to updateType,
                    "version" to snapshot.head.version,
                    "updated_at_ms" to snapshot.head.updatedAtMs,
                    "goal" to snapshot.head.goal,
                    "section_count" to snapshot.head.sectionCount,
                    "evidence_count" to snapshot.head.evidenceCount,
                    "workspace_confidence" to snapshot.head.workspaceConfidence,
                    "bytes_estimate" to snapshot.head.bytesEstimate,
                    "sections" to snapshot.sections.map { section ->
                        mapOf(
                            "title" to section.title,
                            "summary" to section.summary,
                            "content" to section.content,
                            "source" to section.source
                        )
                    },
                    "evidence" to snapshot.evidence
                )
            )
        )
    }

    fun recordQueueSaturation(queueType: String, capacity: Int, reason: String) {
        val pending = scheduler.queueSnapshot().let { snapshot ->
            when (queueType) {
                "input" -> snapshot.pendingInputCount
                "thought" -> snapshot.pendingThoughtCount
                "action" -> snapshot.pendingActionCount
                else -> 0
            }
        }
        instrumentation.emit(
            AgentEvents.queueSaturation(
                queueType = queueType,
                pending = pending,
                capacity = capacity,
                reason = reason
            )
        )
    }
}
