package ai.neopsyke.instrumentation

import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class MemoryEvalFlowLogSink : InstrumentationSink {
    override fun onEvent(event: AgentEvent) {
        val evalType = event.data["eval_type"]?.toString()
        if (evalType != "memory_live" && event.type != "warning" && event.type != "llm_raw_response") {
            return
        }
        when (event.type) {
            "eval_run_start" -> {
                logger.info {
                    "[eval.memory] run.start stage=${event.data["stage"]} task_count=${event.data["task_count"]} max_attempts=${event.data["max_attempts_per_task"]} session_tag=${event.data["session_tag"]}"
                }
            }

            "eval_task_start" -> {
                logger.info {
                    "[eval.memory] task.start id=${event.data["task_id"]} title=${event.data["task_title"]} idx=${event.data["task_index"]}/${event.data["task_total"]}"
                }
            }

            "eval_attempt_start" -> {
                logger.trace {
                    "[eval.memory] attempt.start task=${event.data["task_id"]} idx=${event.data["task_index"]}/${event.data["task_total"]} attempt=${event.data["attempt"]}/${event.data["max_attempts"]}"
                }
            }

            "llm_raw_response" -> {
                val actor = event.data["actor"]?.toString().orEmpty()
                if (actor != "memory_eval") {
                    return
                }
                logger.trace {
                    val callSite = event.data["call_site"]
                    val raw = event.data["raw_response"]?.toString().orEmpty().ifBlank { "<empty>" }
                    buildString {
                        append("[eval.memory] thought.begin eid=")
                        append(event.id)
                        append(" call_site=")
                        append(callSite)
                        append('\n')
                        append(raw)
                        append('\n')
                        append("[eval.memory] thought.end eid=")
                        append(event.id)
                        append(" call_site=")
                        append(callSite)
                    }
                }
            }

            "eval_attempt_result" -> {
                logger.trace {
                    "[eval.memory] attempt.result task=${event.data["task_id"]} attempt=${event.data["attempt"]} passed=${event.data["passed"]} validation_errors=${event.data["validation_errors"]}"
                }
            }

            "eval_memory_imprint" -> {
                logger.trace {
                    "[eval.memory] imprint task=${event.data["task_id"]} saved=${event.data["saved"]} latency_ms=${event.data["latency_ms"]} confidence=${event.data["confidence"]} imprint_error=${event.data["imprint_error"]} summary_preview=${event.data["summary_preview"]}"
                }
            }

            "eval_memory_recall" -> {
                logger.trace {
                    "[eval.memory] recall task=${event.data["task_id"]} provider=${event.data["provider"]} hit_count=${event.data["hit_count"]} chars=${event.data["chars"]} meaningful=${event.data["meaningful"]} preview=${event.data["preview"]}"
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
                    "[eval.memory] task.result id=${event.data["task_id"]} passed=${event.data["passed"]} attempts=${event.data["attempts_used"]} duration_ms=${event.data["duration_ms"]} saved=${event.data["saved"]} recall_hits=${event.data["recall_hit_count"]} judge_pass=${event.data["judge_pass"]} judge_score=${event.data["judge_score"]} judge_reason=${event.data["judge_reason"]} failure_reason=${event.data["failure_reason"]} imprint_error=${event.data["imprint_error"]} judge_parse_error=${event.data["judge_parse_error"]} judge_fallback_reason=${event.data["judge_fallback_reason"]} model_calls=${event.data["model_calls"]} total_tokens=${event.data["total_tokens"]} runtime_error=${event.data["runtime_error"]}"
                }
            }

            "eval_run_complete" -> {
                logger.info {
                    "[eval.memory] run.complete stage=${event.data["stage"]} passed=${event.data["passed_tasks"]}/${event.data["total_tasks"]} failed=${event.data["failed_tasks"]} pass_rate=${event.data["pass_rate"]} avg_attempts=${event.data["avg_attempts"]} avg_duration_ms=${event.data["avg_duration_ms"]} total_model_calls=${event.data["total_model_calls"]} session_tag=${event.data["session_tag"]}"
                }
            }

            "warning" -> {
                logger.warn { event.data["message"] }
            }
        }
    }
}
