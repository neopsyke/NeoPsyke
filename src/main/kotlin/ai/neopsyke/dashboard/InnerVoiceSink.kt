package ai.neopsyke.dashboard

import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.instrumentation.AgentEvent
import ai.neopsyke.instrumentation.InstrumentationSink
import java.util.concurrent.atomic.AtomicLong

/**
 * The Censor: deterministically filters AgentEvents and maps them to InnerVoiceEvents
 * for the interlocutor's "thinking" SSE stream.
 *
 * Adaptive gating: tracks planner step count per rootInputId. Single-step direct answers
 * produce no inner voice events. Multi-step deliberation chains surface curated events.
 *
 * Origin-aware: tracks Id-origin rootInputIds (from impulse_processing events) and
 * auto-activates inner voice for them. Id events are tagged with origin="id" so the
 * InnerVoiceStore can route them to the global Id stream instead of the per-session
 * conversation stream.
 */
class InnerVoiceSink(
    private val dashboardStore: DashboardStateStore,
    private val innerVoiceStore: InnerVoiceStore,
    private val config: InnerVoiceConfig = InnerVoiceConfig(),
    private val idFileSink: IdInnerVoiceFileSink? = null,
) : InstrumentationSink {
    private val lock = Any()
    private val nextId = AtomicLong(1)
    private val stepCountByRoot = mutableMapOf<String, Int>()
    private val activatedRoots = mutableSetOf<String>()
    private val idRootInputIds = mutableSetOf<String>()

    override fun onEvent(event: AgentEvent) {
        if (!config.enabled) return

        when (event.type) {
            "loop_step" -> trackStep(event)
            "impulse_processing" -> trackImpulseOrigin(event)
            "planner_decision" -> handlePlannerDecision(event)
            "plan_created" -> handlePlanCreated(event)
            "plan_step_started" -> handlePlanStepStarted(event)
            "action_denied" -> handleActionDenied(event)
            "memory_recall_result" -> handleMemoryRecall(event)
            "action_executed" -> handleActionExecuted(event)
            "scratchpad_destroyed" -> cleanupRoot(event)
        }
    }

    override fun close() {
        synchronized(lock) {
            stepCountByRoot.clear()
            activatedRoots.clear()
            idRootInputIds.clear()
        }
    }

    private fun trackStep(event: AgentEvent) {
        // We don't have rootInputId in loop_step, so we just note progress.
        // Activation is driven by planner_decision events instead.
    }

    private fun trackImpulseOrigin(event: AgentEvent) {
        val rootImpulseId = event.data["root_impulse_id"]?.toString()?.takeIf { it.isNotBlank() } ?: return
        synchronized(lock) {
            idRootInputIds.add(rootImpulseId)
            // Auto-activate inner voice for Id impulses — the user always wants to
            // observe the Id's thought process.
            activatedRoots.add(rootImpulseId)
        }
    }

    private fun handlePlannerDecision(event: AgentEvent) {
        val decisionType = event.data["decision_type"]?.toString() ?: return
        val rootInputId = extractRootInputId(event) ?: return

        synchronized(lock) {
            val steps = (stepCountByRoot[rootInputId] ?: 0) + 1
            stepCountByRoot[rootInputId] = steps

            // Id-origin roots are already activated in trackImpulseOrigin
            if (rootInputId !in activatedRoots) {
                when (decisionType) {
                    "defer", "plan" -> {
                        // Multi-step reasoning detected: activate inner voice for this root
                        activatedRoots.add(rootInputId)
                    }
                    "intention" -> {
                        val actionType = event.data["action_type"]?.toString()
                        if (steps == 1 && actionType == ActionType.CONTACT_USER.id) {
                            // Simple single-step answer: don't activate
                            return
                        }
                        if (actionType != ActionType.CONTACT_USER.id) {
                            // Non-answer action implies multi-step: activate
                            activatedRoots.add(rootInputId)
                        }
                    }
                }

                if (rootInputId !in activatedRoots) return
            }
        }

        when (decisionType) {
            "defer" -> {
                val thought = event.data["thought"]?.toString() ?: return
                emitEvent(
                    type = InnerVoiceEventType.DELIBERATION,
                    content = thought,
                    rootInputId = rootInputId,
                    ts = event.ts,
                    metadata = buildMap {
                        event.data["trigger"]?.let { put("trigger", it) }
                    }
                )
            }
            "intention" -> {
                val actionType = event.data["action_type"]?.toString() ?: return
                if (actionType == ActionType.CONTACT_USER.id) return
                val summary = event.data["summary"]?.toString()
                val payload = event.data["payload"]?.toString()
                val content = summary
                    ?: "${actionType}: ${payload?.take(200).orEmpty()}"
                emitEvent(
                    type = InnerVoiceEventType.INTENTION,
                    content = content,
                    rootInputId = rootInputId,
                    ts = event.ts,
                    metadata = mapOf("action_type" to actionType)
                )
            }
        }
    }

    private fun handlePlanCreated(event: AgentEvent) {
        val rootInputId = extractRootInputId(event)
        val goal = event.data["goal"]?.toString() ?: return

        synchronized(lock) {
            if (rootInputId != null) {
                activatedRoots.add(rootInputId)
            }
        }

        emitEvent(
            type = InnerVoiceEventType.PLAN,
            content = goal,
            rootInputId = rootInputId,
            ts = event.ts,
            metadata = buildMap {
                event.data["steps"]?.let { put("steps", it) }
                event.data["step_count"]?.let { put("step_count", it) }
            }
        )
    }

    private fun handlePlanStepStarted(event: AgentEvent) {
        val rootInputId = extractRootInputId(event) ?: return
        if (!isActivated(rootInputId)) return
        val stepDescription = event.data["step_description"]?.toString() ?: return
        val stepIndex = (event.data["step_index"] as? Number)?.toInt() ?: return
        val totalSteps = (event.data["total_steps"] as? Number)?.toInt() ?: return
        emitEvent(
            type = InnerVoiceEventType.PLAN_STEP,
            content = stepDescription,
            rootInputId = rootInputId,
            ts = event.ts,
            metadata = mapOf("step_index" to stepIndex, "total_steps" to totalSteps)
        )
    }

    private fun handleActionDenied(event: AgentEvent) {
        val action = event.data["action"] as? PendingAction
        val rootInputId = action?.rootInputId ?: extractRootInputId(event) ?: return

        if (!isActivated(rootInputId)) return

        val reason = event.data["reason"]?.toString() ?: "unknown reason"
        val reasonCode = event.data["reason_code"]?.toString()
        emitEvent(
            type = InnerVoiceEventType.RECONSIDERATION,
            content = "Reconsidering: $reason",
            rootInputId = rootInputId,
            ts = event.ts,
            metadata = buildMap {
                reasonCode?.let { put("reason_code", it) }
                action?.type?.id?.let { put("action_type", it) }
            }
        )
    }

    private fun handleMemoryRecall(event: AgentEvent) {
        val hitCount = (event.data["hit_count"] as? Number)?.toInt() ?: return
        if (hitCount <= 0) return

        val rootInputId = extractRootInputId(event)
        // Memory recalls can happen before activation is known, so emit regardless
        // if there are hits — they indicate substantial reasoning.
        val provider = event.data["provider"]?.toString() ?: "memory"
        emitEvent(
            type = InnerVoiceEventType.RECALL,
            content = "Recalled $hitCount memories ($provider)",
            rootInputId = rootInputId,
            ts = event.ts,
            metadata = buildMap {
                put("provider", provider)
                put("hit_count", hitCount)
                event.data["recall_text_preview"]?.let { put("recall_text_preview", it) }
            }
        )
    }

    private fun handleActionExecuted(event: AgentEvent) {
        val action = event.data["action"] as? PendingAction ?: return
        if (action.type == ActionType.CONTACT_USER) return

        val rootInputId = action.rootInputId ?: return

        // Reflect actions always produce inner voice events (proactive insights)
        if (action.type == ActionType.REFLECT_INTERNAL || action.type == ActionType.REFLECT_EVIDENCE) {
            synchronized(lock) { activatedRoots.add(rootInputId) }
            val outcomeSummary = event.data["outcome_summary"]?.toString()
            val payload = action.payload
            val content = outcomeSummary ?: payload.take(400)
            emitEvent(
                type = InnerVoiceEventType.REFLECTION,
                content = content,
                rootInputId = rootInputId,
                ts = event.ts,
                metadata = buildMap {
                    put("action_type", action.type.id)
                    event.data["keywords"]?.let { put("keywords", it) }
                    event.data["summary"]?.let { put("summary", it) }
                }
            )
            return
        }

        if (!isActivated(rootInputId)) return

        val outcomeSummary = event.data["outcome_summary"]?.toString() ?: return
        emitEvent(
            type = InnerVoiceEventType.OBSERVATION,
            content = outcomeSummary,
            rootInputId = rootInputId,
            ts = event.ts,
            metadata = mapOf("action_type" to action.type.id)
        )
    }

    private fun emitEvent(
        type: InnerVoiceEventType,
        content: String,
        rootInputId: String?,
        ts: Long,
        metadata: Map<String, Any?>,
    ) {
        val origin = if (rootInputId != null && isIdOrigin(rootInputId)) "id" else "user"
        val sessionId = rootInputId?.let { dashboardStore.resolveSessionForRootInput(it) }
        val sequence = if (sessionId != null) dashboardStore.nextSequenceNumber(sessionId) else 0L
        val trimmedContent = if (content.length > config.maxContentChars) {
            content.take(config.maxContentChars) + "..."
        } else {
            content
        }
        val event = InnerVoiceEvent(
            id = nextId.getAndIncrement(),
            type = type,
            content = trimmedContent,
            rootInputId = rootInputId,
            sessionId = sessionId,
            ts = ts,
            sequence = sequence,
            metadata = metadata,
            origin = origin
        )
        innerVoiceStore.emit(event)
        if (origin == "id") {
            idFileSink?.write(event)
        }
    }

    private fun isActivated(rootInputId: String): Boolean =
        synchronized(lock) { rootInputId in activatedRoots }

    private fun isIdOrigin(rootInputId: String): Boolean =
        synchronized(lock) { rootInputId in idRootInputIds }

    private fun extractRootInputId(event: AgentEvent): String? {
        // Try common locations for rootInputId in event data
        event.data["root_input_id"]?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        // Some events carry rootInputId inside nested objects
        (event.data["action"] as? PendingAction)?.rootInputId?.let { return it }
        (event.data["input"] as? ai.neopsyke.agent.model.PendingInput)?.rootInputId?.let { return it }
        (event.data["thought"] as? ai.neopsyke.agent.model.PendingThought)?.rootInputId?.let { return it }
        return null
    }

    private fun cleanupRoot(event: AgentEvent) {
        val rootInputId = event.data["root_input_id"]?.toString() ?: return
        synchronized(lock) {
            stepCountByRoot.remove(rootInputId)
            activatedRoots.remove(rootInputId)
            idRootInputIds.remove(rootInputId)
        }
    }
}
