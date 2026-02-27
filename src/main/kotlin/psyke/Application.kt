package psyke

import mu.KotlinLogging
import psyke.agent.AgentConfig
import psyke.agent.EgoAgent
import psyke.agent.EgoPlanner
import psyke.agent.MistralWebSearchProvider
import psyke.agent.MotorCortex
import psyke.agent.SuperegoDirectives
import psyke.agent.SuperegoGatekeeper
import psyke.dashboard.DashboardServer
import psyke.dashboard.DashboardStateStore
import psyke.instrumentation.AgentEvent
import psyke.instrumentation.AgentEvents
import psyke.instrumentation.InstrumentationBus
import psyke.instrumentation.LlmCallEventObserver
import psyke.instrumentation.LlmRawResponseEventHook
import psyke.instrumentation.MetricsSnapshotObserver
import psyke.instrumentation.StructuredLogSink
import psyke.llm.InstrumentedChatModelClient
import psyke.llm.combineChatCallObservers
import psyke.llm.MistralChatClient
import psyke.metrics.MetricsRuntimeFactory

private val logger = KotlinLogging.logger {}

fun main() {
    logger.info { "Starting psyke Kotlin app." }

    val apiKey = System.getenv("MISTRAL_API_KEY")
    if (apiKey.isNullOrBlank()) {
        logger.warn { "MISTRAL_API_KEY is not set. Export it to talk to Mistral." }
        return
    }

    val config = AgentConfig.fromEnv()
    val egoModel = System.getenv("MISTRAL_EGO_MODEL") ?: MistralChatClient.DEFAULT_MODEL
    val superegoModel = System.getenv("MISTRAL_SUPEREGO_MODEL") ?: egoModel
    val superegoDirectives = SuperegoDirectives.load()
    val dashboardPort = System.getenv("PSYKE_DASHBOARD_PORT")?.toIntOrNull() ?: 8787
    val dashboardEnabled = (System.getenv("PSYKE_DASHBOARD_ENABLED") ?: "true").equals("true", ignoreCase = true)

    val dashboardStore = DashboardStateStore()
    InstrumentationBus(
        sinks = listOf(
            StructuredLogSink(),
            dashboardStore
        )
    ).use { instrumentation ->
        val dashboardServer = if (dashboardEnabled) {
            try {
                DashboardServer(
                    store = dashboardStore,
                    port = dashboardPort
                ).also { it.start() }
            } catch (ex: Exception) {
                logger.warn(ex) { "Dashboard failed to start on port $dashboardPort. Continuing without dashboard server." }
                null
            }
        } else {
            null
        }

        dashboardServer.use {
            if (dashboardEnabled) {
                logger.info { "Dashboard available at ${dashboardServer?.url}" }
            }
            instrumentation.emit(AgentEvents.loopStatus(status = "booting", message = "application_start"))
            instrumentation.emit(
                AgentEvent(
                    type = "limits_config",
                    data = mapOf(
                        "limits" to mapOf(
                            "max_loop_steps" to config.maxLoopStepsPerInput,
                            "loop_delay_ms" to config.loopDelayMs,
                            "max_thought_passes" to config.maxThoughtPasses,
                            "max_prompt_tokens" to config.maxPromptTokens,
                            "max_completion_tokens" to config.maxCompletionTokens,
                            "max_pending_inputs" to config.maxPendingInputs,
                            "max_pending_thoughts" to config.maxPendingThoughts,
                            "max_pending_actions" to config.maxPendingActions,
                            "max_input_chars" to config.maxInputChars,
                            "max_memory_chars" to config.maxMemoryChars,
                            "max_memory_prompt_tokens" to config.maxMemoryPromptTokens,
                            "max_thought_chars" to config.maxThoughtChars,
                            "max_action_payload_chars" to config.maxActionPayloadChars,
                            "max_action_summary_chars" to config.maxActionSummaryChars
                        )
                    )
                )
            )

            MetricsRuntimeFactory.create(
                apiKey = apiKey,
                egoModel = egoModel,
                superegoModel = superegoModel
            ).use { metrics ->
                instrumentation.setDroppedEventsObserver { delta, total ->
                    metrics.recordDroppedEvents(delta)
                    dashboardStore.recordDroppedEvents(total)
                }

                fun emitMetricsSnapshot() {
                    metrics.snapshot()?.let { snapshot ->
                        instrumentation.emit(
                            AgentEvent(
                                type = "metrics_snapshot",
                                data = mapOf("metrics" to snapshot)
                            )
                        )
                    }
                }

                emitMetricsSnapshot()
                val instrumentationObserver = LlmCallEventObserver(
                    provider = "mistral",
                    instrumentation = instrumentation
                )
                val metricsSnapshotObserver = MetricsSnapshotObserver(
                    metricsRuntime = metrics,
                    instrumentation = instrumentation
                )
                val rawResponseHook = LlmRawResponseEventHook(
                    instrumentation = instrumentation,
                    maxRawResponseChars = config.maxActionPayloadChars
                )

                InstrumentedChatModelClient(
                    delegate = MistralChatClient(
                        apiKey = apiKey,
                        modelName = egoModel,
                        callObserver = combineChatCallObservers(
                            metrics.chatCallObserver(provider = "mistral"),
                            instrumentationObserver,
                            metricsSnapshotObserver
                        )
                    ),
                    hooks = listOf(rawResponseHook)
                ).use { egoClient ->
                    InstrumentedChatModelClient(
                        delegate = MistralChatClient(
                            apiKey = apiKey,
                            modelName = superegoModel,
                            callObserver = combineChatCallObservers(
                                metrics.chatCallObserver(provider = "mistral"),
                                instrumentationObserver,
                                metricsSnapshotObserver
                            )
                        ),
                        hooks = listOf(rawResponseHook)
                    ).use { superegoClient ->
                        logger.info { "Ego model=$egoModel Superego model=$superegoModel" }

                        val planner = EgoPlanner(egoClient, config, instrumentation)
                        val gatekeeper = SuperegoGatekeeper(
                            modelClient = superegoClient,
                            config = config,
                            directives = superegoDirectives,
                            instrumentation = instrumentation
                        )
                        val webSearchProvider = MistralWebSearchProvider(
                            modelClient = egoClient,
                            config = config
                        )
                        val motorCortex = MotorCortex(webSearchProvider)

                        EgoAgent(
                            planner = planner,
                            superego = gatekeeper,
                            motorCortex = motorCortex,
                            config = config,
                            onActionDenied = {
                                metrics.recordDeniedAction()
                                emitMetricsSnapshot()
                            },
                            onQueueSaturation = { queueType, _, _ ->
                                metrics.recordQueueSaturation(queueType)
                                emitMetricsSnapshot()
                            },
                            instrumentation = instrumentation
                        ).runInteractive()
                    }
                }
            }
        }
    }
}
