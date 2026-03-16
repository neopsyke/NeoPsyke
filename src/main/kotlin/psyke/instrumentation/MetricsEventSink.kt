package psyke.instrumentation

import mu.KotlinLogging
import psyke.agent.model.PendingAction
import psyke.metrics.MetricsRuntime

private val logger = KotlinLogging.logger {}

/**
 * Routes agent lifecycle events to MetricsRuntime, replacing the per-callback
 * lambda parameters that previously wired metrics from inside Ego's constructor.
 *
 * [instrumentation] is set after bus creation via [setInstrumentation] because
 * MetricsRuntime is created before the InstrumentationBus.
 */
class MetricsEventSink(
    private val metrics: MetricsRuntime,
    private val longTermMemoryParseFailureAnomalyThreshold: Int = 2,
) : InstrumentationSink {

    @Volatile
    private var instrumentation: AgentInstrumentation = NoopAgentInstrumentation

    @Volatile
    private var latestMemoryServerMetrics: Map<String, Any>? = null

    private var longTermMemoryAssessmentParseFailures = 0

    fun setInstrumentation(instrumentation: AgentInstrumentation) {
        this.instrumentation = instrumentation
    }

    override fun onEvent(event: AgentEvent) {
        try {
            handleEvent(event)
        } catch (ex: Exception) {
            logger.warn(ex) { "MetricsEventSink failed to handle event type=${event.type}" }
        }
    }

    private fun handleEvent(event: AgentEvent) {
        when (event.type) {
            "action_executed" -> {
                val action = event.data["action"] as? PendingAction ?: return
                metrics.recordActionCall(action.type.name.lowercase())
                emitSnapshot()
            }

            "action_denied" -> {
                metrics.recordDeniedAction()
                emitSnapshot()
            }

            "queue_saturation" -> {
                val queueType = event.data["queue_type"] as? String ?: return
                metrics.recordQueueSaturation(queueType)
                emitSnapshot()
            }

            "memory_recall_result" -> {
                val hitCount = (event.data["hit_count"] as? Int) ?: return
                val latencyMs = (event.data["latency_ms"] as? Long) ?: return
                val recallChars = (event.data["recall_chars"] as? Int) ?: return
                val truncated = (event.data["truncated"] as? Boolean) ?: return
                metrics.recordMemoryRecall(
                    hitCount = hitCount,
                    latencyMs = latencyMs,
                    recallChars = recallChars,
                    truncated = truncated
                )
                emitSnapshot()
            }

            "memory_recall_failure" -> {
                val latencyMs = (event.data["latency_ms"] as? Long) ?: return
                metrics.recordMemoryRecallFailure(latencyMs)
                emitSnapshot()
            }

            "long_term_memory_recall_skipped" -> {
                metrics.recordLongTermMemoryRecallSkipped()
                emitSnapshot()
            }

            "long_term_memory_assessment" -> {
                val save = (event.data["save"] as? Boolean) ?: return
                metrics.recordLongTermMemoryAssessment(save)
                emitSnapshot()
            }

            "long_term_memory_assessment_parse_fallback" -> {
                metrics.recordLongTermMemoryAssessmentParseFailure()
                longTermMemoryAssessmentParseFailures += 1
                if (longTermMemoryAssessmentParseFailures == longTermMemoryParseFailureAnomalyThreshold) {
                    instrumentation.emit(
                        AgentEvents.warning(
                            "Anomaly threshold reached: memory_consolidation_parse_failures >= $longTermMemoryParseFailureAnomalyThreshold."
                        )
                    )
                }
                emitSnapshot()
            }

            "lesson_recall" -> {
                val hitCount = (event.data["hit_count"] as? Int) ?: return
                val recallChars = (event.data["recall_chars"] as? Int) ?: return
                metrics.recordLessonRecall(hitCount = hitCount, recallChars = recallChars)
                emitSnapshot()
            }

            "episodic_recall_result" -> {
                val entriesReturned = (event.data["entries_returned"] as? Int) ?: return
                val formattedChars = (event.data["formatted_chars"] as? Int) ?: return
                metrics.recordEpisodicRecall(hitCount = entriesReturned, recallChars = formattedChars)
                emitSnapshot()
            }

            "memory_imprint_result" -> {
                val saved = (event.data["saved"] as? Boolean) ?: return
                val summaryChars = (event.data["summary_chars"] as? Int) ?: return
                val latencyMs = (event.data["latency_ms"] as? Long) ?: return
                metrics.recordMemoryImprint(saved = saved, summaryChars = summaryChars, latencyMs = latencyMs)
                emitSnapshot()
            }

            "response_latency_recorded" -> {
                val latencyMs = (event.data["latency_ms"] as? Long) ?: return
                metrics.recordEndToEndResponseLatency(latencyMs)
                emitSnapshot()
            }

            "memory_server_metrics" -> {
                @Suppress("UNCHECKED_CAST")
                latestMemoryServerMetrics = event.data as? Map<String, Any>
                emitSnapshot()
            }
        }
    }

    private fun emitSnapshot() {
        metrics.snapshot()?.let { snapshot ->
            val enriched = latestMemoryServerMetrics?.let {
                snapshot.copy(memoryServerMetrics = it)
            } ?: snapshot
            instrumentation.emit(
                AgentEvent(
                    type = "metrics_snapshot",
                    data = mapOf("metrics" to enriched)
                )
            )
        }
    }
}
