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
