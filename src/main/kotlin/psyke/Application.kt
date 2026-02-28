package psyke

import mu.KotlinLogging
import psyke.agent.AgentConfig
import psyke.agent.EgoAgent
import psyke.agent.EgoPlanner
import psyke.agent.McpStdioClient
import psyke.agent.MotorCortex
import psyke.agent.SdkMcpFetchTool
import psyke.agent.SdkMcpTimeTool
import psyke.agent.SuperegoDirectives
import psyke.agent.SuperegoGatekeeper
import psyke.agent.actions.websearch.WebSearchActionHandler
import psyke.dashboard.DashboardServer
import psyke.dashboard.DashboardStateStore
import psyke.eval.ReasoningEvalOptions
import psyke.eval.ReasoningEvalReporter
import psyke.eval.ReasoningEvalMode
import psyke.eval.ReasoningEvalTasks
import psyke.eval.ReasoningLogicEvalTasks
import psyke.eval.ReasoningLogicHarnessClient
import psyke.eval.ReasoningSelfEvalRunner
import psyke.eval.UsageTrackingChatClient
import psyke.instrumentation.AgentEvent
import psyke.instrumentation.AgentEvents
import psyke.instrumentation.InstrumentationBus
import psyke.instrumentation.JsonlEventSink
import psyke.instrumentation.LlmCallEventObserver
import psyke.instrumentation.LlmRawResponseEventHook
import psyke.instrumentation.MetricsSnapshotObserver
import psyke.instrumentation.ReasoningEvalFlowLogSink
import psyke.instrumentation.StructuredLogSink
import psyke.llm.InstrumentedChatModelClient
import psyke.llm.ChatModelClient
import psyke.llm.MistralChatClient
import psyke.llm.combineChatCallObservers
import psyke.integrations.mistral.websearch.MistralConversationsWebSearchEngine
import psyke.integrations.mistral.websearch.MistralWebSearchMode
import psyke.integrations.mistral.websearch.MistralWebSearchProfile
import psyke.integrations.mistral.websearch.MistralWebSearchAgentSession
import psyke.metrics.MetricsRuntimeFactory
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

private data class AppCliOptions(
    val showHelp: Boolean,
    val evalReasoningOnly: Boolean,
    val evalReasoningMode: ReasoningEvalMode,
    val evalStage: String?,
    val evalReasoningMaxAttempts: Int,
    val evalReasoningTaskFilter: Set<String>,
    val unknownArgs: List<String>,
    val parseErrors: List<String>,
)

fun main(args: Array<String>) {
    logger.info { "Starting psyke Kotlin app." }

    val cliOptions = parseCliOptions(args)
    if (cliOptions.showHelp) {
        printAppHelp()
        return
    }
    if (cliOptions.parseErrors.isNotEmpty()) {
        cliOptions.parseErrors.forEach { logger.warn { it } }
        printAppHelp()
        return
    }
    if (cliOptions.unknownArgs.isNotEmpty()) {
        logger.warn { "Ignoring unknown app args: ${cliOptions.unknownArgs.joinToString(" ")}" }
    }

    val config = AgentConfig.fromEnv()
    val egoModel = System.getenv("MISTRAL_EGO_MODEL") ?: MistralChatClient.DEFAULT_MODEL
    val superegoModel = System.getenv("MISTRAL_SUPEREGO_MODEL") ?: egoModel

    if (cliOptions.evalReasoningOnly) {
        runReasoningOnlyEval(
            apiKey = System.getenv("MISTRAL_API_KEY"),
            egoModel = egoModel,
            cliOptions = cliOptions
        )
        return
    }

    val apiKey = System.getenv("MISTRAL_API_KEY")
    if (apiKey.isNullOrBlank()) {
        System.err.println("MISTRAL_API_KEY is not set. Export it to talk to Mistral.")
        logger.warn { "MISTRAL_API_KEY is not set. Export it to talk to Mistral." }
        return
    }

    runInteractiveMode(
        apiKey = apiKey,
        egoModel = egoModel,
        superegoModel = superegoModel,
        config = config
    )
}

private fun runReasoningOnlyEval(
    apiKey: String?,
    egoModel: String,
    cliOptions: AppCliOptions,
) {
    println("Running reasoning-only self-eval (mode=${cliOptions.evalReasoningMode.id})...")
    val sidecarPath = resolveReasoningEventSidecarPath()
    val sidecarSink = if (sidecarPath == null) {
        null
    } else {
        try {
            JsonlEventSink(sidecarPath).also {
                println("Event sidecar: $sidecarPath")
            }
        } catch (ex: Exception) {
            logger.warn(ex) { "Failed to initialize event sidecar at $sidecarPath; continuing without sidecar." }
            null
        }
    }
    val sinks = listOfNotNull(
        ReasoningEvalFlowLogSink(),
        sidecarSink
    )
    val evalRawResponseCharLimit = System.getenv("PSYKE_EVAL_MAX_RAW_RESPONSE_CHARS")
        ?.toIntOrNull()
        ?.takeIf { it > 0 }
        ?: Int.MAX_VALUE
    InstrumentationBus(
        sinks = sinks
    ).use { instrumentation ->
        val provider = if (cliOptions.evalReasoningMode == ReasoningEvalMode.LOGIC) {
            "logic-harness"
        } else {
            "mistral"
        }
        val llmCallObserver = LlmCallEventObserver(
            provider = provider,
            instrumentation = instrumentation
        )
        val rawResponseHook = LlmRawResponseEventHook(
            instrumentation = instrumentation,
            maxRawResponseChars = evalRawResponseCharLimit
        )
        val baseClient: ChatModelClient = when (cliOptions.evalReasoningMode) {
            ReasoningEvalMode.LOGIC -> ReasoningLogicHarnessClient(
                callObserver = llmCallObserver
            )

            ReasoningEvalMode.MODEL -> {
                val resolvedApiKey = apiKey?.trim().orEmpty()
                if (resolvedApiKey.isBlank()) {
                    System.err.println("MISTRAL_API_KEY is required for --eval-reasoning-mode model.")
                    logger.warn { "MISTRAL_API_KEY is required for --eval-reasoning-mode model." }
                    return
                }
                MistralChatClient(
                    apiKey = resolvedApiKey,
                    modelName = egoModel,
                    callObserver = llmCallObserver
                )
            }
        }
        val evalTasks = when (cliOptions.evalReasoningMode) {
            ReasoningEvalMode.LOGIC -> ReasoningLogicEvalTasks.defaults()
            ReasoningEvalMode.MODEL -> ReasoningEvalTasks.defaults()
        }
        UsageTrackingChatClient(
            delegate = InstrumentedChatModelClient(
                delegate = baseClient,
                hooks = listOf(rawResponseHook)
            )
        ).use { client ->
            val options = ReasoningEvalOptions(
                maxAttemptsPerTask = cliOptions.evalReasoningMaxAttempts,
                taskFilter = cliOptions.evalReasoningTaskFilter,
                stage = cliOptions.evalStage ?: (System.getenv("PSYKE_EVAL_STAGE") ?: "")
                    .ifBlank { java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString() },
                mode = cliOptions.evalReasoningMode.id
            )
            val report = ReasoningSelfEvalRunner(
                client = client,
                tasks = evalTasks,
                instrumentation = instrumentation
            ).run(options)
            println(ReasoningEvalReporter.render(report))
            val runPath = ReasoningEvalReporter.writeRunReport(report)
            ReasoningEvalReporter.appendHistory(report)
            println("Run report: $runPath")
            println("History: .psyke/evals/reasoning/history.jsonl")
        }
    }
}

private fun resolveReasoningEventSidecarPath(): Path? {
    val explicit = System.getenv("PSYKE_EVENT_LOG_FILE")?.trim().orEmpty()
    if (explicit.isNotBlank()) {
        return Path.of(explicit)
    }
    val runLogFile = System.getenv("PSYKE_LOG_FILE")?.trim().orEmpty()
    if (runLogFile.isBlank()) {
        return null
    }
    val logPath = Path.of(runLogFile)
    val logName = logPath.fileName?.toString().orEmpty()
    if (logName.isBlank()) {
        return null
    }
    val sidecarName = if (logName.endsWith(".log")) {
        "${logName.removeSuffix(".log")}.events.jsonl"
    } else {
        "$logName.events.jsonl"
    }
    return logPath.resolveSibling(sidecarName)
}

private fun runInteractiveMode(
    apiKey: String,
    egoModel: String,
    superegoModel: String,
    config: AgentConfig,
) {
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
                            "max_action_summary_chars" to config.maxActionSummaryChars,
                            "mcp_call_timeout_ms" to config.mcpCallTimeoutMs,
                            "mcp_fetch_max_chars" to config.mcpFetchMaxChars
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

                        val gatekeeper = SuperegoGatekeeper(
                            modelClient = superegoClient,
                            config = config,
                            directives = superegoDirectives,
                            instrumentation = instrumentation
                        )
                        val webSearchAgentSession = MistralWebSearchAgentSession.start(
                            apiKey = apiKey,
                            model = egoModel,
                            providedAgentId = System.getenv("MISTRAL_WEBSEARCH_AGENT_ID")
                        )
                        val mcpTimeTool = SdkMcpTimeTool(
                            command = resolveMcpCommand(
                                envName = "MCP_TIME_SERVER_CMD",
                                fallback = listOf("uvx", "mcp-server-time")
                            ),
                            callTimeoutMs = config.mcpCallTimeoutMs
                        )
                        val mcpFetchTool = SdkMcpFetchTool(
                            command = resolveMcpCommand(
                                envName = "MCP_FETCH_SERVER_CMD",
                                fallback = listOf("uvx", "mcp-server-fetch")
                            ),
                            callTimeoutMs = config.mcpCallTimeoutMs,
                            maxChars = config.mcpFetchMaxChars
                        )

                        webSearchAgentSession.use { agentSession ->
                            val webSearchProfile = MistralWebSearchProfile(
                                mode = MistralWebSearchMode.AGENT_ID,
                                model = egoModel,
                                agentId = agentSession.agentId
                            )
                            val webSearchEngine = MistralConversationsWebSearchEngine(
                                apiKey = apiKey,
                                profile = webSearchProfile,
                                callObserver = combineChatCallObservers(
                                    metrics.chatCallObserver(provider = "mistral"),
                                    instrumentationObserver,
                                    metricsSnapshotObserver
                                ),
                                instrumentation = instrumentation,
                                maxRawResponseChars = config.maxActionPayloadChars
                            )

                            webSearchEngine.use { searchEngine ->
                                mcpTimeTool.use { timeTool ->
                                    mcpFetchTool.use { fetchTool ->
                                        val webSearchActionHandler = WebSearchActionHandler(searchEngine)
                                        val motorCortex = MotorCortex(
                                            webSearchActionHandler = webSearchActionHandler,
                                            mcpTimeTool = timeTool,
                                            mcpFetchTool = fetchTool
                                        )
                                        val actionStatuses = motorCortex.startupSmokeTest()
                                        instrumentation.emit(AgentEvents.actionCapabilities(actionStatuses))
                                        actionStatuses.filterNot { it.available }.forEach { status ->
                                            instrumentation.emit(
                                                AgentEvents.warning(
                                                    "Action ${status.actionType.name.lowercase()} unavailable at startup: ${status.detail}"
                                                )
                                            )
                                        }
                                        val planner = EgoPlanner(egoClient, config, instrumentation)

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
            }
        }
    }
}

private fun resolveMcpCommand(envName: String, fallback: List<String>): List<String> {
    val raw = System.getenv(envName)?.trim().orEmpty()
    if (raw.isBlank()) {
        return fallback
    }
    return McpStdioClient.parseCommand(raw).ifEmpty { fallback }
}

private fun printAppHelp() {
    println(
        """
        psyke app options:
          --eval-reasoning-only           Run deterministic reasoning self-eval (no tools/actions)
          --eval-reasoning-mode MODE      Eval mode: logic (default) or model
          --eval-stage ID                 Label this eval run (default: UTC date, e.g. 2026-02-28)
          --eval-reasoning-max-attempts N Max retries per reasoning task (default: 4)
          --eval-reasoning-tasks id1,id2  Run only selected reasoning task ids
          -h, --help                      Show this help message
        """.trimIndent()
    )
}

private fun parseCliOptions(args: Array<String>): AppCliOptions {
    var showHelp = false
    var evalReasoningOnly = false
    var evalReasoningMode = ReasoningEvalMode.LOGIC
    var evalStage: String? = null
    var evalReasoningMaxAttempts = 4
    var evalReasoningTaskFilter: Set<String> = emptySet()
    val unknownArgs = mutableListOf<String>()
    val parseErrors = mutableListOf<String>()

    var index = 0
    while (index < args.size) {
        val arg = args[index]
        when {
            arg == "-h" || arg == "--help" -> {
                showHelp = true
                index += 1
            }

            arg == "--eval-reasoning-only" -> {
                evalReasoningOnly = true
                index += 1
            }
            arg == "--eval-reasoning-mode" -> {
                val next = args.getOrNull(index + 1)
                val parsed = ReasoningEvalMode.parse(next)
                if (parsed == null) {
                    parseErrors += "Invalid value for --eval-reasoning-mode: '${next ?: "<missing>"}'. Expected logic or model."
                } else {
                    evalReasoningMode = parsed
                }
                index += 2
            }
            arg.startsWith("--eval-reasoning-mode=") -> {
                val raw = arg.substringAfter('=')
                val parsed = ReasoningEvalMode.parse(raw)
                if (parsed == null) {
                    parseErrors += "Invalid value for --eval-reasoning-mode: '$raw'. Expected logic or model."
                } else {
                    evalReasoningMode = parsed
                }
                index += 1
            }
            arg == "--eval-stage" -> {
                val next = args.getOrNull(index + 1)
                if (next.isNullOrBlank()) {
                    parseErrors += "Missing value for --eval-stage."
                } else {
                    evalStage = next.trim()
                }
                index += 2
            }
            arg.startsWith("--eval-stage=") -> {
                val raw = arg.substringAfter('=').trim()
                if (raw.isBlank()) {
                    parseErrors += "Invalid blank value for --eval-stage."
                } else {
                    evalStage = raw
                }
                index += 1
            }

            arg == "--eval-reasoning-max-attempts" -> {
                val next = args.getOrNull(index + 1)
                val parsed = next?.toIntOrNull()
                if (parsed == null || parsed < 1) {
                    parseErrors += "Invalid value for --eval-reasoning-max-attempts: '${next ?: "<missing>"}'. Expected positive integer."
                } else {
                    evalReasoningMaxAttempts = parsed
                }
                index += 2
            }
            arg.startsWith("--eval-reasoning-max-attempts=") -> {
                val raw = arg.substringAfter('=')
                val parsed = raw.toIntOrNull()
                if (parsed == null || parsed < 1) {
                    parseErrors += "Invalid value for --eval-reasoning-max-attempts: '$raw'. Expected positive integer."
                } else {
                    evalReasoningMaxAttempts = parsed
                }
                index += 1
            }

            arg == "--eval-reasoning-tasks" -> {
                val next = args.getOrNull(index + 1)
                if (next.isNullOrBlank()) {
                    parseErrors += "Missing value for --eval-reasoning-tasks."
                } else {
                    evalReasoningTaskFilter = next.split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet()
                }
                index += 2
            }
            arg.startsWith("--eval-reasoning-tasks=") -> {
                val raw = arg.substringAfter('=')
                evalReasoningTaskFilter = raw.split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet()
                index += 1
            }

            else -> {
                unknownArgs += arg
                index += 1
            }
        }
    }

    return AppCliOptions(
        showHelp = showHelp,
        evalReasoningOnly = evalReasoningOnly,
        evalReasoningMode = evalReasoningMode,
        evalStage = evalStage,
        evalReasoningMaxAttempts = evalReasoningMaxAttempts,
        evalReasoningTaskFilter = evalReasoningTaskFilter,
        unknownArgs = unknownArgs,
        parseErrors = parseErrors
    )
}
