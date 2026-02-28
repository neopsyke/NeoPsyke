package psyke.instrumentation

import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class ReasoningEvalFlowLogSink : InstrumentationSink {
    override fun onEvent(event: AgentEvent) {
        when (event.type) {
            "eval_run_start" -> {
                logger.info {
                    "[eval.reasoning] run.start mode=${event.data["mode"]} stage=${event.data["stage"]} task_count=${event.data["task_count"]} max_attempts=${event.data["max_attempts_per_task"]}"
                }
            }

            "eval_task_start" -> {
                logger.info {
                    "[eval.reasoning] task.start id=${event.data["task_id"]} title=${event.data["task_title"]} idx=${event.data["task_index"]}/${event.data["task_total"]}"
                }
            }

            "eval_attempt_start" -> {
                logger.trace {
                    "[eval.reasoning] attempt.start task=${event.data["task_id"]} idx=${event.data["task_index"]}/${event.data["task_total"]} attempt=${event.data["attempt"]}/${event.data["max_attempts"]}"
                }
            }

            "llm_raw_response" -> {
                logger.trace {
                    val callSite = event.data["call_site"]
                    val raw = event.data["raw_response"]?.toString().orEmpty().ifBlank { "<empty>" }
                    buildString {
                        append("[eval.reasoning] thought.begin eid=")
                        append(event.id)
                        append(" call_site=")
                        append(callSite)
                        append('\n')
                        append(raw)
                        append('\n')
                        append("[eval.reasoning] thought.end eid=")
                        append(event.id)
                        append(" call_site=")
                        append(callSite)
                    }
                }
            }

            "eval_attempt_result" -> {
                logger.trace {
                    "[eval.reasoning] attempt.result task=${event.data["task_id"]} attempt=${event.data["attempt"]} passed=${event.data["passed"]} validation_errors=${event.data["validation_errors"]}"
                }
            }

            "eval_task_result" -> {
                logger.info {
                    "[eval.reasoning] task.result id=${event.data["task_id"]} passed=${event.data["passed"]} attempts=${event.data["attempts_used"]} duration_ms=${event.data["duration_ms"]} model_calls=${event.data["model_calls"]} total_tokens=${event.data["total_tokens"]} errors=${event.data["validation_errors"]} runtime_error=${event.data["runtime_error"]}"
                }
            }

            "eval_run_complete" -> {
                logger.info {
                    "[eval.reasoning] run.complete mode=${event.data["mode"]} stage=${event.data["stage"]} passed=${event.data["passed_tasks"]}/${event.data["total_tasks"]} failed=${event.data["failed_tasks"]} pass_rate=${event.data["pass_rate"]} avg_attempts=${event.data["avg_attempts"]} avg_duration_ms=${event.data["avg_duration_ms"]} total_model_calls=${event.data["total_model_calls"]}"
                }
            }

            "warning" -> {
                logger.warn { event.data["message"] }
            }
        }
    }
}
