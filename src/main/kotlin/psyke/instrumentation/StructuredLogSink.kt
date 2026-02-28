package psyke.instrumentation

import mu.KotlinLogging
import psyke.agent.QueueState

private val logger = KotlinLogging.logger {}

class StructuredLogSink : InstrumentationSink {
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
                    "planner.decision trigger=${event.data["trigger"]} type=${event.data["decision_type"]} urgency=${event.data["urgency"]} action=${event.data["action_type"]}"
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
                    "superego.review action_id=${event.data["action_id"]} allow=${event.data["allow"]} reason=${event.data["reason"]}"
                }
            }

            "llm_call" -> {
                logger.trace {
                    "llm.call provider=${event.data["provider"]} model=${event.data["model"]} actor=${event.data["actor"]} call_site=${event.data["call_site"]} status=${event.data["status"]} latency_ms=${event.data["latency_ms"]} total_tokens=${event.data["total_tokens"]}"
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
                    "action.executed action=${event.data["action"]}"
                }
            }

            "warning" -> {
                logger.warn { event.data["message"] }
            }
        }
    }
}
