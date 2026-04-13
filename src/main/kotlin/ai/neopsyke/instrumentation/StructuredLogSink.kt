package ai.neopsyke.instrumentation

import mu.KotlinLogging
import ai.neopsyke.agent.model.PendingInput
import ai.neopsyke.agent.model.QueuedContinuation
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.model.QueueState
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

class StructuredLogSink : InstrumentationSink {
    private val plannerStructuredOutputModes = ConcurrentHashMap<PlannerStructuredOutputScope, String>()

    private fun sessionPrefix(event: AgentEvent): String {
        val sessionId = event.data["session_id"]
            ?: (event.data["input"] as? PendingInput)?.conversationContext?.sessionId
            ?: (event.data["continuation"] as? QueuedContinuation)?.conversationContext?.sessionId
            ?: (event.data["action"] as? PendingAction)?.conversationContext?.sessionId
        val interlocutor = event.data["interlocutor"]
            ?: (event.data["input"] as? PendingInput)?.conversationContext?.interlocutor?.id
            ?: (event.data["continuation"] as? QueuedContinuation)?.conversationContext?.interlocutor?.id
            ?: (event.data["action"] as? PendingAction)?.conversationContext?.interlocutor?.id
        return buildString {
            if (sessionId != null) append("session=$sessionId ")
            if (interlocutor != null) append("interlocutor=$interlocutor ")
        }
    }

    override fun onEvent(event: AgentEvent) {
        when (event.type) {
            "loop_status" -> {
                val status = event.data["status"]
                val message = event.data["message"]
                logger.info { "loop.status status=$status message=$message" }
            }

            "loop_step" -> {
                logger.trace {
                    "loop.step index=${event.data["step"]} task=${event.data["task_type"]}"
                }
            }

            "queue_snapshot" -> {
                val queues = event.data["queues"] as? QueueState
                logger.trace {
                    "queue.snapshot source=${event.data["source"]} in=${queues?.inputs?.size ?: 0} co=${queues?.continuations?.size ?: 0} ac=${queues?.actions?.size ?: 0}"
                }
            }

            "planner_decision" -> {
                val jsonMode = resolvePlannerStructuredOutputMode(event) ?: "unknown"
                logger.trace {
                    "${sessionPrefix(event)}planner.decision trigger=${event.data["trigger"]} type=${event.data["decision_type"]} urgency=${event.data["urgency"]} action=${event.data["action_type"]} json_mode=$jsonMode"
                }
            }

            "action_capabilities" -> {
                val statuses = event.data["statuses"] as? List<*>
                logger.info {
                    "action.capabilities statuses=${statuses ?: emptyList<Any>()}"
                }
            }

            "action_proposed" -> {
                logger.trace {
                    "action.proposed queued=${event.data["queued"]} type=${event.data["action_type"]} urgency=${event.data["urgency"]}"
                }
            }

            "action_review_result" -> {
                logger.trace {
                    "superego.review action_id=${event.data["action_id"]} allow=${event.data["allow"]} reason_code=${event.data["reason_code"]} reason=${event.data["reason"]}"
                }
            }

            "grounding_gate_review" -> {
                logger.info {
                    "grounding_gate.review action_id=${event.data["action_id"]} allow=${event.data["allow"]} " +
                        "grounding_required=${event.data["grounding_required"]} " +
                        "evidence_gathered=${event.data["evidence_gathered"]} " +
                        "evidence_failed_technically=${event.data["evidence_failed_technically"]} " +
                        "evidence_unavailable=${event.data["evidence_unavailable"]} " +
                        "forced_terminal=${event.data["forced_terminal"]} " +
                        "reason_code=${event.data["reason_code"]}"
                }
            }

            "grounding_metadata_propagated" -> {
                logger.info {
                    "grounding.propagated root_input_id=${event.data["root_input_id"]} " +
                        "from=${event.data["from_envelope_type"]} " +
                        "to=${event.data["to_envelope_type"]} " +
                        "grounding_required=${event.data["grounding_required"]} " +
                        "source=${event.data["source"]}"
                }
            }

            "prompt_budget_allocation" -> {
                logger.info {
                    "prompt_budget.allocation call_site=${event.data["call_site"]} " +
                        "max_tokens=${event.data["max_tokens"]} " +
                        "estimated_total_cost=${event.data["estimated_total_cost"]} " +
                        "allocated_total_cost=${event.data["allocated_total_cost"]} " +
                        "reserved_floor_cost=${event.data["reserved_floor_cost"]} " +
                        "single_message_fallback=${event.data["single_message_fallback"]} " +
                        "degradation_path=${event.data["degradation_path"]} " +
                        "dropped_sections=${event.data["dropped_section_count"]} " +
                        "floor_violations=${event.data["floor_violation_count"]}"
                }
            }

            "memory_recall_start" -> {
                logger.trace {
                    "memory.recall.start provider=${event.data["provider"]} trigger=${event.data["trigger"]} cue=${event.data["cue_preview"]}"
                }
            }

            "memory_recall_result" -> {
                logger.trace {
                    "memory.recall.result provider=${event.data["provider"]} trigger=${event.data["trigger"]} hits=${event.data["hit_count"]} latency_ms=${event.data["latency_ms"]} chars=${event.data["recall_chars"]} truncated=${event.data["truncated"]}"
                }
                val recallPreview = event.data["recall_text_preview"]
                if (recallPreview is String && recallPreview.isNotBlank()) {
                    logger.debug { "memory.recall.text trigger=${event.data["trigger"]}\n$recallPreview" }
                }
            }

            "memory_recall_failure" -> {
                logger.warn {
                    "memory.recall.failure provider=${event.data["provider"]} trigger=${event.data["trigger"]} latency_ms=${event.data["latency_ms"]} reason=${event.data["reason"]}"
                }
            }

            "lesson_recall" -> {
                logger.trace {
                    "lesson.recall hits=${event.data["hit_count"]} latency_ms=${event.data["latency_ms"]} chars=${event.data["recall_chars"]} truncated=${event.data["truncated"]}"
                }
                val recallPreview = event.data["recall_text_preview"]
                if (recallPreview is String && recallPreview.isNotBlank()) {
                    logger.debug { "lesson.recall.text\n$recallPreview" }
                }
            }

            "plan_created" -> {
                logger.trace {
                    "plan.created id=${event.data["plan_id"]} workItem=${event.data["goal"]} steps=${event.data["step_count"]} urgency=${event.data["urgency"]}"
                }
                val steps = event.data["steps"]
                if (steps is List<*> && steps.isNotEmpty()) {
                    val planText = steps.mapIndexed { i, s -> "  ${i + 1}. $s" }.joinToString("\n")
                    logger.debug { "plan.created.steps id=${event.data["plan_id"]} workItem=${event.data["goal"]}\n$planText" }
                }
            }

            "planner_output_repaired" -> {
                logger.trace {
                    "planner.output.repaired action=${event.data["action_type"]} repair=${event.data["repair"]}"
                }
            }

            "long_term_memory_recall_requested" -> {
                logger.trace {
                    "memory.recall.requested trigger=${event.data["trigger"]} source=${event.data["source"]} query=${event.data["query_preview"]}"
                }
            }

            "long_term_memory_recall_skipped" -> {
                logger.trace {
                    "memory.recall.skipped trigger=${event.data["trigger"]} reason=${event.data["reason"]}"
                }
            }

            "deliberation_state" -> {
                logger.trace {
                    "deliberation.state task=${event.data["task_type"]} step=${event.data["step_index"]} pressure=${event.data["decision_pressure"]} stale=${event.data["stale_streak"]} progress=${event.data["progress_score"]} denials=${event.data["denial_count"]} evidence_gap=${event.data["steps_since_new_evidence"]} repeats=${event.data["repeat_signature_hits"]} noop_streak=${event.data["noop_streak"]}"
                }
            }

            "external_action_redundancy_signal" -> {
                logger.info {
                    "planner.redundancy_signal action_type=${event.data["action_type"]} " +
                        "signature_hits=${event.data["signature_hits"]} " +
                        "had_successful_evidence=${event.data["had_successful_evidence"]} " +
                        "had_external_failures=${event.data["had_external_failures"]} " +
                        "redundant_risk=${event.data["redundant_risk"]} " +
                        "root_input_id=${event.data["root_input_id"]}"
                }
            }

            "meta_reasoner_assessment" -> {
                logger.trace {
                    "meta.reasoner step=${event.data["step_index"]} pressure=${event.data["decision_pressure"]} verdict=${event.data["verdict"]} confidence=${event.data["confidence"]} reason=${event.data["reason"]}"
                }
            }

            "memory_consolidation_assessment" -> {
                logger.trace {
                    "memory.consolidation.assessment trigger=${event.data["trigger"]} step=${event.data["step_index"]} save=${event.data["save"]} confidence=${event.data["confidence"]} reason=${event.data["reason"]}"
                }
            }

            "long_term_memory_assessment" -> {
                logger.trace {
                    "long_term_memory.assessment trigger=${event.data["trigger"]} step=${event.data["step_index"]} save=${event.data["save"]} confidence=${event.data["confidence"]} reason=${event.data["reason"]}"
                }
            }

            "memory_advisor_prompt_compressed" -> {
                logger.info {
                    "long_term_memory.assessment.prompt_compressed " +
                        "dialogue=${event.data["dialogue_original_chars"]}->${event.data["dialogue_final_chars"]} " +
                        "recall=${event.data["recall_original_chars"]}->${event.data["recall_final_chars"]}"
                }
            }

            "superego_two_stage_routing" -> {
                logger.info {
                    "superego.two_stage.routing enabled=${event.data["enabled"]} " +
                        "provider=${event.data["provider"]} primary_model=${event.data["primary_model"]} " +
                        "escalation_model=${event.data["escalation_model"]} " +
                        "low_conf_threshold=${event.data["low_confidence_threshold"]} " +
                        "escalate_medium_risk=${event.data["escalate_on_medium_policy_risk"]}"
                }
            }

            "superego_two_stage_review" -> {
                logger.info {
                    "superego.two_stage.review stage=${event.data["stage"]} escalated=${event.data["escalated"]} " +
                        "allow=${event.data["allow"]} reason_code=${event.data["reason_code"]} " +
                        "confidence=${event.data["confidence"]} policy_risk=${event.data["policy_risk"]} " +
                        "parse_failed=${event.data["parse_failed"]} technical_fallback=${event.data["technical_fallback"]}"
                }
            }

            "long_term_memory_assessment_parse_fallback" -> {
                logger.warn {
                    "long_term_memory.assessment.parse_fallback trigger=${event.data["trigger"]} step=${event.data["step_index"]} streak=${event.data["streak"]}"
                }
            }

            "long_term_memory_assessment_temporarily_disabled" -> {
                logger.warn {
                    "long_term_memory.assessment.disabled trigger=${event.data["trigger"]} step=${event.data["step_index"]} streak=${event.data["streak"]} threshold=${event.data["threshold"]}"
                }
            }

            "long_term_memory_persistence_skipped" -> {
                logger.info {
                    "long_term_memory.persistence.skipped trigger=${event.data["trigger"]} step=${event.data["step_index"]} reason_code=${event.data["reason_code"]} detail=${event.data["reason_detail"]}"
                }
            }

            "memory_imprint_result" -> {
                logger.trace {
                    "memory.imprint.result trigger=${event.data["trigger"]} provider=${event.data["provider"]} saved=${event.data["saved"]} summary_chars=${event.data["summary_chars"]} latency_ms=${event.data["latency_ms"]} confidence=${event.data["confidence"]} tags=${event.data["tags"]}"
                }
            }

            "episodic_recall_intent_detected" -> {
                logger.trace {
                    "episodic.recall.intent pattern=${event.data["pattern_label"]} start=${event.data["start_time"]} end=${event.data["end_time"]} keyword=${event.data["keyword_search"]}"
                }
            }

            "episodic_recall_result" -> {
                logger.trace {
                    "episodic.recall.result pattern=${event.data["pattern_label"]} entries=${event.data["entries_returned"]} total=${event.data["total_matched"]} truncated=${event.data["truncated"]}"
                }
                val recallPreview = event.data["recall_text_preview"]
                if (recallPreview is String && recallPreview.isNotBlank()) {
                    logger.debug { "episodic.recall.text pattern=${event.data["pattern_label"]}\n$recallPreview" }
                }
            }

            "scratchpad_pre_final_dump" -> {
                logger.info {
                    "scratchpad.pre_final_dump session=${event.data["session_id"]} root_input=${event.data["root_input_id"]}"
                }
            }

            "scratchpad_created" -> {
                logger.trace {
                    "scratchpad.created root_id=${event.data["root_input_id"]} root_received_at_ms=${event.data["root_input_received_at_ms"]} active=${event.data["active_tasks"]} workItem=${event.data["work_item_preview"]}"
                }
            }

            "scratchpad_updated" -> {
                logger.trace {
                    "scratchpad.updated root_id=${event.data["root_input_id"]} root_received_at_ms=${event.data["root_input_received_at_ms"]} type=${event.data["update_type"]} active=${event.data["active_tasks"]}"
                }
            }

            "scratchpad_head" -> {
                logger.trace {
                    "scratchpad.head root_id=${event.data["root_input_id"]} root_received_at_ms=${event.data["root_input_received_at_ms"]} type=${event.data["update_type"]} version=${event.data["version"]} sections=${event.data["section_count"]} evidence=${event.data["evidence_count"]}"
                }
            }

            "scratchpad_debug_snapshot" -> {
                logger.trace {
                    "scratchpad.debug_snapshot root_id=${event.data["root_input_id"]} root_received_at_ms=${event.data["root_input_received_at_ms"]} type=${event.data["update_type"]} version=${event.data["version"]} bytes=${event.data["bytes_estimate"]}"
                }
            }

            "scratchpad_final_pass" -> {
                logger.trace {
                    "scratchpad.final_pass root_id=${event.data["root_input_id"]} root_received_at_ms=${event.data["root_input_received_at_ms"]} action_id=${event.data["action_id"]} scratchpad_confidence=${event.data["scratchpad_confidence"]}"
                }
            }

            "scratchpad_final_pass_skipped" -> {
                logger.trace {
                    "scratchpad.final_pass.skipped root_id=${event.data["root_input_id"]} root_received_at_ms=${event.data["root_input_received_at_ms"]} action_id=${event.data["action_id"]} reason=${event.data["reason"]}"
                }
            }

            "scratchpad_final_pass_applied" -> {
                logger.trace {
                    "scratchpad.final_pass.applied root_id=${event.data["root_input_id"]} root_received_at_ms=${event.data["root_input_received_at_ms"]} action_id=${event.data["action_id"]} scratchpad_confidence=${event.data["scratchpad_confidence"]} model_confidence=${event.data["model_confidence"]}"
                }
            }

            "scratchpad_destroyed" -> {
                logger.trace {
                    "scratchpad.destroyed root_id=${event.data["root_input_id"]} root_received_at_ms=${event.data["root_input_received_at_ms"]} sections=${event.data["section_count"]} evidence=${event.data["evidence_count"]} reason=${event.data["reason"]}"
                }
            }

            "scratchpad_digest_captured" -> {
                logger.trace {
                    "scratchpad.digest_captured root_id=${event.data["root_input_id"]} session=${event.data["session_id"]} sections=${event.data["section_count"]} evidence=${event.data["evidence_count"]} workItem=${event.data["work_item_preview"]}"
                }
            }

            "scratchpad_cleared" -> {
                logger.trace {
                    "scratchpad.cleared count=${event.data["cleared_count"]} reason=${event.data["reason"]}"
                }
            }

            "llm_call" -> {
                val status = event.data["status"]?.toString().orEmpty()
                recordPlannerStructuredOutputMode(event)
                val structuredOutputMode = event.data["structured_output_mode"]?.toString()?.ifBlank { null }
                val contextSuffix = llmContextSuffix(event)
                val providerErrorType = event.data["provider_error_type"]?.toString()?.ifBlank { null }
                val providerErrorCode = event.data["provider_error_code"]?.toString()?.ifBlank { null }
                val failedGenerationPreview = event.data["failed_generation_preview"]?.toString()?.ifBlank { null }
                if (status.equals("error", ignoreCase = true)) {
                    logger.warn {
                        "llm.call provider=${event.data["provider"]} model=${event.data["model"]} actor=${event.data["actor"]} " +
                            "call_site=${event.data["call_site"]} status=${event.data["status"]} structured_output_mode=${structuredOutputMode ?: "-"} latency_ms=${event.data["latency_ms"]} " +
                            "error_code=${event.data["error_code"]} provider_error_type=${providerErrorType ?: "-"} " +
                            "provider_error_code=${providerErrorCode ?: "-"}" +
                            contextSuffix +
                            (failedGenerationPreview?.let { " failed_generation_preview=$it" } ?: "") +
                            " error_message=${event.data["error_message"]}"
                    }
                } else {
                    logger.trace {
                        "llm.call provider=${event.data["provider"]} model=${event.data["model"]} actor=${event.data["actor"]} " +
                            "call_site=${event.data["call_site"]} status=${event.data["status"]} structured_output_mode=${structuredOutputMode ?: "-"} " +
                            "latency_ms=${event.data["latency_ms"]} total_tokens=${event.data["total_tokens"]}$contextSuffix"
                    }
                }
            }

            "llm_raw_response" -> {
                logger.trace {
                    "llm.raw_response actor=${event.data["actor"]} call_site=${event.data["call_site"]} action_type=${event.data["action_type"]} payload=${event.data["raw_response"]}"
                }
            }

            "eval_run_start" -> {
                logger.info {
                    "[eval.reasoning] run.start stage=${event.data["stage"]} task_count=${event.data["task_count"]} max_attempts=${event.data["max_attempts_per_task"]}"
                }
            }

            "eval_task_start" -> {
                logger.trace {
                    "[eval.reasoning] task.start id=${event.data["task_id"]} title=${event.data["task_title"]} idx=${event.data["task_index"]}/${event.data["task_total"]}"
                }
            }

            "eval_attempt_start" -> {
                logger.trace {
                    "[eval.reasoning] attempt.start task=${event.data["task_id"]} idx=${event.data["task_index"]}/${event.data["task_total"]} attempt=${event.data["attempt"]}/${event.data["max_attempts"]}"
                }
            }

            "eval_attempt_result" -> {
                logger.trace {
                    "[eval.reasoning] attempt.result task=${event.data["task_id"]} attempt=${event.data["attempt"]} passed=${event.data["passed"]} answer_preview=${event.data["answer_preview"]} validation_errors=${event.data["validation_errors"]}"
                }
            }

            "eval_memory_imprint" -> {
                logger.trace {
                    "[eval.memory] imprint task=${event.data["task_id"]} saved=${event.data["saved"]} latency_ms=${event.data["latency_ms"]} confidence=${event.data["confidence"]} imprint_error=${event.data["imprint_error"]}"
                }
            }

            "eval_memory_recall" -> {
                logger.trace {
                    "[eval.memory] recall task=${event.data["task_id"]} provider=${event.data["provider"]} hit_count=${event.data["hit_count"]} chars=${event.data["chars"]} meaningful=${event.data["meaningful"]}"
                }
            }

            "eval_memory_judge_parse_error" -> {
                logger.warn {
                    "[eval.memory] judge.parse_error task=${event.data["task_id"]} attempt=${event.data["attempt"]} error=${event.data["error"]} raw_preview=${event.data["raw_preview"]}"
                }
            }

            "eval_memory_cleanup" -> {
                logger.info {
                    "[eval.memory] cleanup phase=${event.data["phase"]} deleted_observations=${event.data["deleted_observations"]}"
                }
            }

            "eval_task_result" -> {
                logger.info {
                    "[eval.reasoning] task.result id=${event.data["task_id"]} passed=${event.data["passed"]} attempts=${event.data["attempts_used"]} duration_ms=${event.data["duration_ms"]} model_calls=${event.data["model_calls"]} total_tokens=${event.data["total_tokens"]} errors=${event.data["validation_errors"]} runtime_error=${event.data["runtime_error"]}"
                }
            }

            "eval_run_complete" -> {
                logger.info {
                    "[eval.reasoning] run.complete stage=${event.data["stage"]} passed=${event.data["passed_tasks"]}/${event.data["total_tasks"]} failed=${event.data["failed_tasks"]} pass_rate=${event.data["pass_rate"]} avg_attempts=${event.data["avg_attempts"]} avg_duration_ms=${event.data["avg_duration_ms"]} total_model_calls=${event.data["total_model_calls"]}"
                }
            }

            "action_executed" -> {
                logger.trace {
                    "${sessionPrefix(event)}action.executed action=${event.data["action"]}"
                }
            }

            "response_latency_recorded" -> {
                logger.trace {
                    "${sessionPrefix(event)}response.latency.recorded latency_ms=${event.data["latency_ms"]} action_id=${event.data["action_id"]}"
                }
            }

            "warning" -> {
                logger.warn { event.data["message"] }
            }

            "llm_cache_hit" -> {
                logger.trace {
                    "llm.cache.hit seq=${event.data["sequence_index"]} actor=${event.data["actor"]} call_site=${event.data["call_site"]}"
                }
            }

            "llm_cache_miss" -> {
                logger.info {
                    "llm.cache.miss seq=${event.data["sequence_index"]} actor=${event.data["actor"]} call_site=${event.data["call_site"]} reason=${event.data["reason"]}"
                }
            }

            "llm_cache_divergence" -> {
                logger.info {
                    "llm.cache.divergence seq=${event.data["sequence_index"]} actor=${event.data["actor"]} call_site=${event.data["call_site"]} expected_hash=${event.data["expected_hash"]} actual_hash=${event.data["actual_hash"]}"
                }
            }

            // ── Impulse processing (Ego side) ─────────────────────────
            "impulse_processing" -> {
                logger.info {
                    "impulse.processing need=${event.data["need_id"]} tension=${event.data["tension"]} raw_value=${event.data["raw_value"]} root_impulse_id=${event.data["root_impulse_id"]} prompt=${event.data["prompt"]}"
                }
            }

            "impulse_noop" -> {
                logger.info {
                    "impulse.noop need=${event.data["need_id"]} root_impulse_id=${event.data["root_impulse_id"]}"
                }
            }

            "impulse_lifecycle_finalized" -> {
                logger.info {
                    "impulse.lifecycle.finalized need=${event.data["need_id"]} root_impulse_id=${event.data["root_impulse_id"]} result=${event.data["result"]}"
                }
            }

            // ── Id (autonomous drive) events ──────────────────────────
            "id_pulse" -> {
                val needs = event.data["needs"]
                logger.trace {
                    "id.pulse pulse=${event.data["pulse"]} ego_busy=${event.data["ego_busy"]} needs=$needs"
                }
            }

            "id_impulse_fired" -> {
                logger.info {
                    "id.impulse.fired need=${event.data["need_id"]} tension=${event.data["tension"]} raw_value=${event.data["raw_value"]} root_impulse_id=${event.data["root_impulse_id"]}"
                }
            }

            "id_impulse_accepted" -> {
                logger.info {
                    "id.impulse.accepted need=${event.data["need_id"]}"
                }
            }

            "id_impulse_completed" -> {
                logger.info {
                    "id.impulse.completed need=${event.data["need_id"]} success=${event.data["success"]} new_value=${event.data["new_value"]}"
                }
            }

            "id_impulse_denied" -> {
                logger.info {
                    "id.impulse.denied need=${event.data["need_id"]} consecutive_denials=${event.data["consecutive_denials"]}"
                }
            }

            "id_activity_decay" -> {
                logger.trace {
                    "id.activity.decay need=${event.data["need_id"]} event=${event.data["event_type"]} decay=${event.data["decay"]} before=${event.data["before"]} after=${event.data["after"]}"
                }
            }

            "id_pregate_blocked" -> {
                logger.trace {
                    "id.pregate.blocked need=${event.data["need_id"]} reason=${event.data["reason"]}"
                }
            }
        }
    }

    private fun recordPlannerStructuredOutputMode(event: AgentEvent) {
        val scope = plannerScopeForLlmCall(event) ?: return
        val mode = event.data["structured_output_mode"]?.toString()?.trim()
        if (!mode.isNullOrBlank()) {
            plannerStructuredOutputModes[scope] = mode
        }
    }

    private fun resolvePlannerStructuredOutputMode(event: AgentEvent): String? {
        val scope = plannerScopeForPlannerDecision(event) ?: return null
        return plannerStructuredOutputModes.remove(scope)
    }

    private fun plannerScopeForLlmCall(event: AgentEvent): PlannerStructuredOutputScope? {
        if (event.type != "llm_call") return null
        val actor = event.data["actor"]?.toString()?.trim()?.lowercase().orEmpty()
        if (actor != "ego") return null
        val callSite = normalizePlannerCallSite(event.data["call_site"]?.toString()) ?: return null
        val sessionId = event.data["session_id"]?.toString()?.trim()?.ifEmpty { null } ?: return null
        val rootInputId = event.data["root_input_id"]?.toString()?.trim()?.ifEmpty { null }
        return PlannerStructuredOutputScope(sessionId = sessionId, rootInputId = rootInputId, callSite = callSite)
    }

    private fun plannerScopeForPlannerDecision(event: AgentEvent): PlannerStructuredOutputScope? {
        if (event.type != "planner_decision") return null
        val callSite = normalizePlannerCallSite(event.data["trigger"]?.toString()) ?: return null
        val sessionId = event.data["session_id"]?.toString()?.trim()?.ifEmpty { null } ?: return null
        val rootInputId = event.data["root_input_id"]?.toString()?.trim()?.ifEmpty { null }
        return PlannerStructuredOutputScope(sessionId = sessionId, rootInputId = rootInputId, callSite = callSite)
    }

    private fun normalizePlannerCallSite(raw: String?): String? {
        val callSite = raw?.trim()?.lowercase()?.ifEmpty { return null } ?: return null
        return when {
            callSite in PLANNER_CALL_SITES -> callSite
            callSite.endsWith("_json_retry") -> callSite.removeSuffix("_json_retry").takeIf { it in PLANNER_CALL_SITES }
            callSite.endsWith("_truncation_retry") -> callSite.removeSuffix("_truncation_retry").takeIf { it in PLANNER_CALL_SITES }
            else -> null
        }
    }

    private fun llmContextSuffix(event: AgentEvent): String {
        val parts = mutableListOf<String>()
        event.data["cognitive_role"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            parts += "cognitive_role=$it"
        }
        event.data["trigger"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            parts += "trigger=$it"
        }
        event.data["origin_source"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            parts += "origin_source=$it"
        }
        event.data["need_id"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            parts += "need_id=$it"
        }
        event.data["root_impulse_id"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            parts += "root_impulse_id=$it"
        }
        event.data["thought_id"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            parts += "thought_id=$it"
        }
        val planStep = planStepSummary(event)
        if (planStep != null) {
            parts += planStep
        }
        return if (parts.isEmpty()) "" else " " + parts.joinToString(" ")
    }

    private fun planStepSummary(event: AgentEvent): String? {
        val planId = event.data["plan_id"]?.toString()?.trim()?.ifEmpty { null }
        val stepIndex = (event.data["plan_step_index"] as? Number)?.toInt()
        val totalSteps = (event.data["plan_total_steps"] as? Number)?.toInt()
        val stepDescription = event.data["plan_step_description"]?.toString()?.trim()?.ifEmpty { null }
        if (planId == null && stepIndex == null && totalSteps == null && stepDescription == null) {
            return null
        }
        return buildString {
            if (planId != null) append("plan_id=").append(planId).append(' ')
            if (stepIndex != null && totalSteps != null) {
                append("plan_step=").append(stepIndex + 1).append('/').append(totalSteps).append(' ')
            }
            if (stepDescription != null) {
                append("plan_step_description=").append(stepDescription.take(120))
            }
        }.trim()
    }

    companion object {
        private val PLANNER_CALL_SITES: Set<String> = setOf(
            "input",
            "continuation",
            "feedback",
            "durable_work",
            "impulse",
        )
    }

    private data class PlannerStructuredOutputScope(
        val sessionId: String,
        val rootInputId: String?,
        val callSite: String,
    )
}
