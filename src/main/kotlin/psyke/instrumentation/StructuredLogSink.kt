package psyke.instrumentation

import mu.KotlinLogging
import psyke.agent.core.PendingInput
import psyke.agent.core.PendingThought
import psyke.agent.core.PendingAction
import psyke.agent.core.QueueState

private val logger = KotlinLogging.logger {}

class StructuredLogSink : InstrumentationSink {
    private fun sessionPrefix(event: AgentEvent): String {
        val sessionId = event.data["session_id"]
            ?: (event.data["input"] as? PendingInput)?.conversationContext?.sessionId
            ?: (event.data["thought"] as? PendingThought)?.conversationContext?.sessionId
            ?: (event.data["action"] as? PendingAction)?.conversationContext?.sessionId
        val interlocutor = event.data["interlocutor"]
            ?: (event.data["input"] as? PendingInput)?.conversationContext?.interlocutor?.id
            ?: (event.data["thought"] as? PendingThought)?.conversationContext?.interlocutor?.id
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
                    "queue.snapshot source=${event.data["source"]} in=${queues?.inputs?.size ?: 0} th=${queues?.thoughts?.size ?: 0} ac=${queues?.actions?.size ?: 0}"
                }
            }

            "planner_decision" -> {
                logger.trace {
                    "${sessionPrefix(event)}planner.decision trigger=${event.data["trigger"]} type=${event.data["decision_type"]} urgency=${event.data["urgency"]} action=${event.data["action_type"]}"
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

            "memory_recall_start" -> {
                logger.trace {
                    "memory.recall.start provider=${event.data["provider"]} trigger=${event.data["trigger"]} cue=${event.data["cue_preview"]}"
                }
            }

            "memory_recall_result" -> {
                logger.trace {
                    "memory.recall.result provider=${event.data["provider"]} trigger=${event.data["trigger"]} hits=${event.data["hit_count"]} latency_ms=${event.data["latency_ms"]} chars=${event.data["recall_chars"]} truncated=${event.data["truncated"]}"
                }
            }

            "memory_recall_failure" -> {
                logger.warn {
                    "memory.recall.failure provider=${event.data["provider"]} trigger=${event.data["trigger"]} latency_ms=${event.data["latency_ms"]} reason=${event.data["reason"]}"
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
            }

            "task_workspace_created" -> {
                logger.trace {
                    "task_workspace.created root_id=${event.data["root_input_id"]} root_received_at_ms=${event.data["root_input_received_at_ms"]} active=${event.data["active_tasks"]} goal=${event.data["goal_preview"]}"
                }
            }

            "task_workspace_updated" -> {
                logger.trace {
                    "task_workspace.updated root_id=${event.data["root_input_id"]} root_received_at_ms=${event.data["root_input_received_at_ms"]} type=${event.data["update_type"]} active=${event.data["active_tasks"]}"
                }
            }

            "task_workspace_head" -> {
                logger.trace {
                    "task_workspace.head root_id=${event.data["root_input_id"]} root_received_at_ms=${event.data["root_input_received_at_ms"]} type=${event.data["update_type"]} version=${event.data["version"]} sections=${event.data["section_count"]} evidence=${event.data["evidence_count"]}"
                }
            }

            "task_workspace_debug_snapshot" -> {
                logger.trace {
                    "task_workspace.debug_snapshot root_id=${event.data["root_input_id"]} root_received_at_ms=${event.data["root_input_received_at_ms"]} type=${event.data["update_type"]} version=${event.data["version"]} bytes=${event.data["bytes_estimate"]}"
                }
            }

            "task_workspace_final_pass" -> {
                logger.trace {
                    "task_workspace.final_pass root_id=${event.data["root_input_id"]} root_received_at_ms=${event.data["root_input_received_at_ms"]} action_id=${event.data["action_id"]} workspace_confidence=${event.data["workspace_confidence"]}"
                }
            }

            "task_workspace_final_pass_skipped" -> {
                logger.trace {
                    "task_workspace.final_pass.skipped root_id=${event.data["root_input_id"]} root_received_at_ms=${event.data["root_input_received_at_ms"]} action_id=${event.data["action_id"]} reason=${event.data["reason"]}"
                }
            }

            "task_workspace_final_pass_applied" -> {
                logger.trace {
                    "task_workspace.final_pass.applied root_id=${event.data["root_input_id"]} root_received_at_ms=${event.data["root_input_received_at_ms"]} action_id=${event.data["action_id"]} workspace_confidence=${event.data["workspace_confidence"]} model_confidence=${event.data["model_confidence"]}"
                }
            }

            "task_workspace_destroyed" -> {
                logger.trace {
                    "task_workspace.destroyed root_id=${event.data["root_input_id"]} root_received_at_ms=${event.data["root_input_received_at_ms"]} sections=${event.data["section_count"]} evidence=${event.data["evidence_count"]} reason=${event.data["reason"]}"
                }
            }

            "task_workspace_cleared" -> {
                logger.trace {
                    "task_workspace.cleared count=${event.data["cleared_count"]} reason=${event.data["reason"]}"
                }
            }

            "llm_call" -> {
                val status = event.data["status"]?.toString().orEmpty()
                if (status.equals("error", ignoreCase = true)) {
                    logger.warn {
                        "llm.call provider=${event.data["provider"]} model=${event.data["model"]} actor=${event.data["actor"]} " +
                            "call_site=${event.data["call_site"]} status=${event.data["status"]} latency_ms=${event.data["latency_ms"]} " +
                            "error_code=${event.data["error_code"]} error_message=${event.data["error_message"]}"
                    }
                } else {
                    logger.trace {
                        "llm.call provider=${event.data["provider"]} model=${event.data["model"]} actor=${event.data["actor"]} call_site=${event.data["call_site"]} status=${event.data["status"]} latency_ms=${event.data["latency_ms"]} total_tokens=${event.data["total_tokens"]}"
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
        }
    }
}
