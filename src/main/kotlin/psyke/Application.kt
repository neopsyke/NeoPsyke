package psyke

import mu.KotlinLogging
import psyke.agent.AgentConfig
import psyke.agent.EgoAgent
import psyke.agent.EgoPlanner
import psyke.agent.Hippocampus
import psyke.agent.LlmMetaReasoner
import psyke.agent.LlmLongTermMemoryAdvisor
import psyke.agent.McpHippocampus
import psyke.agent.McpStdioClient
import psyke.agent.MotorCortex
import psyke.agent.NoopHippocampus
import psyke.agent.SdkMcpFetchTool
import psyke.agent.SdkMcpTimeTool
import psyke.agent.SuperegoDirectives
import psyke.agent.SuperegoGatekeeper
import psyke.agent.actions.websearch.WebSearchActionHandler
import psyke.agent.actions.websearch.WebSearchEngine
import psyke.config.McpCapabilityConfig
import psyke.config.LlmProvider
import psyke.config.LlmRuntimeConfig
import psyke.config.LlmRuntimeConfigLoader
import psyke.config.McpRuntimeConfig
import psyke.config.McpRuntimeConfigLoader
import psyke.dashboard.DashboardServer
import psyke.dashboard.DashboardStateStore
import psyke.eval.MemoryLiveEvalOptions
import psyke.eval.MemoryLiveEvalReporter
import psyke.eval.MemoryLiveEvalRunner
import psyke.eval.MemoryLiveEvalTasks
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
import psyke.instrumentation.MemoryEvalFlowLogSink
import psyke.instrumentation.MetricsSnapshotObserver
import psyke.instrumentation.ReasoningEvalFlowLogSink
import psyke.instrumentation.StructuredLogSink
import psyke.llm.InstrumentedChatModelClient
import psyke.llm.ChatModelClient
import psyke.llm.GroqChatClient
import psyke.llm.GroqProviderStatusChecker
import psyke.llm.MistralChatClient
import psyke.llm.MistralProviderStatusChecker
import psyke.llm.ProviderHealthState
import psyke.llm.ProviderStatus
import psyke.llm.combineChatCallObservers
import psyke.llm.reportProviderStatusAndDecide
import psyke.integrations.groq.websearch.GroqConversationsWebSearchEngine
import psyke.integrations.mistral.websearch.MistralConversationsWebSearchEngine
import psyke.integrations.mistral.websearch.MistralWebSearchMode
import psyke.integrations.mistral.websearch.MistralWebSearchProfile
import psyke.integrations.mistral.websearch.MistralWebSearchAgentSession
import psyke.metrics.MetricsRuntimeFactory
import psyke.agent.McpFetchTool
import psyke.agent.McpTimeTool
import psyke.agent.ToolHealthStatus
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private val logger = KotlinLogging.logger {}

private data class AppCliOptions(
    val showHelp: Boolean,
    val evalReasoningOnly: Boolean,
    val evalMemoryLiveOnly: Boolean,
    val evalReasoningMode: ReasoningEvalMode,
    val evalStage: String?,
    val evalReasoningMaxAttempts: Int,
    val evalReasoningTaskFilter: Set<String>,
    val evalMemoryMaxAttempts: Int,
    val evalMemoryTaskFilter: Set<String>,
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
    val mcpRuntimeConfig = McpRuntimeConfigLoader.load()
    val llmRuntimeConfig = LlmRuntimeConfigLoader.load()
    if (llmRuntimeConfig == null) {
        System.err.println("Unsupported LLM_PROVIDER. Expected one of: groq, mistral.")
        logger.warn { "Unsupported LLM_PROVIDER. Expected one of: groq, mistral." }
        return
    }

    if (cliOptions.evalReasoningOnly) {
        runReasoningOnlyEval(
            llm = llmRuntimeConfig,
            cliOptions = cliOptions
        )
        return
    }
    if (cliOptions.evalMemoryLiveOnly) {
        runMemoryLiveEval(
            llm = llmRuntimeConfig,
            config = config,
            mcpRuntimeConfig = mcpRuntimeConfig,
            cliOptions = cliOptions
        )
        return
    }

    if (llmRuntimeConfig.apiKey.isBlank()) {
        val message = "${llmRuntimeConfig.apiKeyEnvVar} is not set. Export it to talk to ${llmRuntimeConfig.providerLabel}."
        System.err.println(message)
        logger.warn { message }
        return
    }
    if (!checkProviderHealth(llm = llmRuntimeConfig, modeLabel = "interactive")) {
        return
    }

    runInteractiveMode(
        llm = llmRuntimeConfig,
        config = config,
        mcpRuntimeConfig = mcpRuntimeConfig
    )
}

private fun runReasoningOnlyEval(
    llm: LlmRuntimeConfig,
    cliOptions: AppCliOptions,
) {
    println("Running reasoning-only self-eval (mode=${cliOptions.evalReasoningMode.id})...")
    val sidecarPath = resolveEvalEventSidecarPath()
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
    val sinks = listOf(
        ReasoningEvalFlowLogSink()
    )
    val evalRawResponseCharLimit = System.getenv("PSYKE_EVAL_MAX_RAW_RESPONSE_CHARS")
        ?.toIntOrNull()
        ?.takeIf { it > 0 }
        ?: Int.MAX_VALUE
    InstrumentationBus(
        sinks = sinks,
        criticalSinks = listOfNotNull(sidecarSink)
    ).use { instrumentation ->
        val provider = if (cliOptions.evalReasoningMode == ReasoningEvalMode.LOGIC) {
            "logic-harness"
        } else {
            llm.providerLabel
        }
        MetricsRuntimeFactory.create(
            provider = provider,
            apiKey = llm.apiKey.ifBlank { provider },
            egoModel = llm.egoModel,
            superegoModel = llm.superegoModel
        ).use { metrics ->
            val llmCallObserver = LlmCallEventObserver(
                provider = provider,
                instrumentation = instrumentation
            )
            val metricsSnapshotObserver = MetricsSnapshotObserver(
                metricsRuntime = metrics,
                instrumentation = instrumentation
            )
            val callObserver = combineChatCallObservers(
                metrics.chatCallObserver(provider = provider),
                llmCallObserver,
                metricsSnapshotObserver
            )
            val rawResponseHook = LlmRawResponseEventHook(
                instrumentation = instrumentation,
                maxRawResponseChars = evalRawResponseCharLimit
            )
            val baseClient: ChatModelClient = when (cliOptions.evalReasoningMode) {
                ReasoningEvalMode.LOGIC -> ReasoningLogicHarnessClient(
                    callObserver = callObserver
                )

                ReasoningEvalMode.MODEL -> {
                    val resolvedApiKey = llm.apiKey.trim()
                    if (resolvedApiKey.isBlank()) {
                        val message = "${llm.apiKeyEnvVar} is required for --eval-reasoning-mode model."
                        System.err.println(message)
                        logger.warn { message }
                        return
                    }
                    if (!checkProviderHealth(llm = llm, modeLabel = "eval_reasoning_model")) {
                        return
                    }
                    createChatClient(
                        llm = llm,
                        modelName = llm.egoModel,
                        callObserver = callObserver
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
}

private fun runMemoryLiveEval(
    llm: LlmRuntimeConfig,
    config: AgentConfig,
    mcpRuntimeConfig: McpRuntimeConfig,
    cliOptions: AppCliOptions,
) {
    println("Running memory live eval (real LLM + real MCP memory)...")
    val resolvedApiKey = llm.apiKey.trim()
    if (resolvedApiKey.isBlank()) {
        val message = "${llm.apiKeyEnvVar} is required for --eval-memory-live."
        System.err.println(message)
        logger.warn { message }
        return
    }
    if (!checkProviderHealth(llm = llm, modeLabel = "eval_memory_live")) {
        return
    }
    val memoryCommand = resolveMcpCommand(mcpRuntimeConfig.memory)
    if (memoryCommand == null) {
        val reason = disabledReason("memory", mcpRuntimeConfig.memory)
        System.err.println(
            "Memory MCP command is unavailable for --eval-memory-live. $reason Configure mcp-runtime.yaml or override with MCP_MEMORY_SERVER_CMD."
        )
        logger.warn {
            "Memory MCP command is unavailable for --eval-memory-live. $reason"
        }
        return
    }
    if (!checkMcpMemoryProviderHealth(command = memoryCommand, timeoutMs = config.mcpMemoryCallTimeoutMs, modeLabel = "eval_memory_live")) {
        return
    }

    val sidecarPath = resolveEvalEventSidecarPath()
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
    val sinks = listOf(
        MemoryEvalFlowLogSink()
    )
    val evalRawResponseCharLimit = System.getenv("PSYKE_EVAL_MAX_RAW_RESPONSE_CHARS")
        ?.toIntOrNull()
        ?.takeIf { it > 0 }
        ?: Int.MAX_VALUE
    InstrumentationBus(
        sinks = sinks,
        criticalSinks = listOfNotNull(sidecarSink)
    ).use { instrumentation ->
        MetricsRuntimeFactory.create(
            provider = llm.providerLabel,
            apiKey = llm.apiKey,
            egoModel = llm.egoModel,
            superegoModel = llm.superegoModel
        ).use { metrics ->
            val llmCallObserver = LlmCallEventObserver(
                provider = llm.providerLabel,
                instrumentation = instrumentation
            )
            val metricsSnapshotObserver = MetricsSnapshotObserver(
                metricsRuntime = metrics,
                instrumentation = instrumentation
            )
            val callObserver = combineChatCallObservers(
                metrics.chatCallObserver(provider = llm.providerLabel),
                llmCallObserver,
                metricsSnapshotObserver
            )
            val rawResponseHook = LlmRawResponseEventHook(
                instrumentation = instrumentation,
                maxRawResponseChars = evalRawResponseCharLimit
            )
            UsageTrackingChatClient(
                delegate = InstrumentedChatModelClient(
                    delegate = createChatClient(
                        llm = llm,
                        modelName = llm.egoModel,
                        callObserver = callObserver
                    ),
                    hooks = listOf(rawResponseHook)
                )
            ).use { client ->
                val stage = cliOptions.evalStage ?: (System.getenv("PSYKE_EVAL_STAGE") ?: "")
                    .ifBlank { java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString() }
                McpHippocampus(
                    command = memoryCommand,
                    callTimeoutMs = config.mcpMemoryCallTimeoutMs,
                    defaultMaxItems = config.longTermMemoryRecallMaxItems,
                    defaultMaxChars = config.longTermMemoryRecallMaxChars
                ).use { hippocampus ->
                    val report = MemoryLiveEvalRunner(
                        client = client,
                        longTermMemoryAdvisor = LlmLongTermMemoryAdvisor(
                            modelClient = client,
                            config = config
                        ),
                        hippocampus = hippocampus,
                        tasks = MemoryLiveEvalTasks.defaults(),
                        instrumentation = instrumentation
                    ).run(
                        MemoryLiveEvalOptions(
                            stage = stage,
                            taskFilter = cliOptions.evalMemoryTaskFilter,
                            maxConsolidationAttempts = cliOptions.evalMemoryMaxAttempts
                        )
                    )
                    println(MemoryLiveEvalReporter.render(report))
                    val runPath = MemoryLiveEvalReporter.writeRunReport(report)
                    MemoryLiveEvalReporter.appendHistory(report)
                    println("Run report: $runPath")
                    println("History: .psyke/evals/memory-live/history.jsonl")
                }
            }
        }
    }
}

private fun resolveEvalEventSidecarPath(): Path? {
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

private fun checkProviderHealth(llm: LlmRuntimeConfig, modeLabel: String): Boolean {
    val checker = when (llm.provider) {
        LlmProvider.GROQ -> GroqProviderStatusChecker(
            apiKey = llm.apiKey,
            baseUrl = llm.baseUrl
        )

        LlmProvider.MISTRAL -> MistralProviderStatusChecker(
            apiKey = llm.apiKey,
            baseUrl = llm.baseUrl
        )
    }
    val status = checker.check()
    return reportProviderStatusAndDecide(
        modeLabel = modeLabel,
        status = status
    )
}

private fun checkMcpMemoryProviderHealth(
    command: List<String>,
    timeoutMs: Long,
    modeLabel: String,
): Boolean {
    val status = try {
        McpStdioClient.start(command = command, serverLabel = "memory-health").use { client ->
            val tools = client.listTools(timeoutMs)
            val hasSearchLike = tools.any { tool ->
                val lower = tool.lowercase()
                lower.contains("search") || lower.contains("recall") || lower.contains("query")
            }
            val hasWriteLike = tools.any { tool ->
                val lower = tool.lowercase()
                lower.contains("add_observations") ||
                    lower.contains("remember") ||
                    lower.contains("imprint") ||
                    lower.contains("create_memory") ||
                    lower.contains("add_memory") ||
                    lower.contains("write_memory")
            }
            when {
                !hasSearchLike || !hasWriteLike -> {
                    ProviderStatus(
                        provider = "mcp_memory",
                        state = ProviderHealthState.UNAVAILABLE,
                        detail = "MCP memory server reachable but required tools are missing. search_like=$hasSearchLike write_like=$hasWriteLike tools=${tools.sorted().joinToString(",")}"
                    )
                }

                else -> {
                    ProviderStatus(
                        provider = "mcp_memory",
                        state = ProviderHealthState.AVAILABLE,
                        detail = "MCP memory server reachable; required tools detected."
                    )
                }
            }
        }
    } catch (ex: Exception) {
        ProviderStatus(
            provider = "mcp_memory",
            state = ProviderHealthState.UNAVAILABLE,
            detail = "MCP memory provider check failed: ${ex.message ?: ex::class.simpleName ?: "unknown error"}"
        )
    }

    return reportProviderStatusAndDecide(
        modeLabel = modeLabel,
        status = status
    )
}

private fun runInteractiveMode(
    llm: LlmRuntimeConfig,
    config: AgentConfig,
    mcpRuntimeConfig: McpRuntimeConfig,
) {
    val superegoDirectives = SuperegoDirectives.load()
    val dashboardPort = System.getenv("PSYKE_DASHBOARD_PORT")?.toIntOrNull() ?: 8787
    val dashboardEnabled = (System.getenv("PSYKE_DASHBOARD_ENABLED") ?: "true").equals("true", ignoreCase = true)

    val dashboardStore = DashboardStateStore()
    val sidecarPath = resolveEvalEventSidecarPath()
    val sidecarSink = if (sidecarPath == null) {
        null
    } else {
        try {
            JsonlEventSink(sidecarPath).also {
                logger.info { "Event sidecar enabled at $sidecarPath" }
            }
        } catch (ex: Exception) {
            logger.warn(ex) { "Failed to initialize event sidecar at $sidecarPath; continuing without sidecar." }
            null
        }
    }
    InstrumentationBus(
        sinks = listOfNotNull(
            StructuredLogSink(),
            dashboardStore
        ),
        criticalSinks = listOfNotNull(sidecarSink)
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
                            "short_term_context_max_chars" to config.maxShortTermContextChars,
                            "short_term_context_max_prompt_tokens" to config.maxShortTermContextPromptTokens,
                            "max_thought_chars" to config.maxThoughtChars,
                            "max_action_payload_chars" to config.maxActionPayloadChars,
                            "max_action_summary_chars" to config.maxActionSummaryChars,
                            "mcp_call_timeout_ms" to config.mcpCallTimeoutMs,
                            "mcp_fetch_max_chars" to config.mcpFetchMaxChars,
                            "mcp_memory_call_timeout_ms" to config.mcpMemoryCallTimeoutMs,
                            "long_term_memory_recall_max_items" to config.longTermMemoryRecallMaxItems,
                            "long_term_memory_recall_max_chars" to config.longTermMemoryRecallMaxChars,
                            "pressure_assessment_min_step" to config.deliberationPressureAssessmentMinStep,
                            "pressure_assess_every_steps" to config.deliberationPressureAssessmentEverySteps,
                            "pressure_assess_threshold" to config.deliberationPressureAssessmentThreshold,
                            "meta_reasoner_cooldown_steps" to config.metaReasonerCooldownSteps,
                            "meta_reasoner_max_tokens" to config.metaReasonerMaxTokens,
                            "long_term_memory_assess_every_steps" to config.longTermMemoryAssessEverySteps,
                            "long_term_memory_assess_cooldown_steps" to config.longTermMemoryAssessCooldownSteps,
                            "long_term_memory_min_confidence" to config.longTermMemoryMinConfidence,
                            "long_term_memory_max_tokens" to config.longTermMemoryMaxTokens,
                            "long_term_memory_max_summary_chars" to config.longTermMemoryMaxSummaryChars,
                            "long_term_memory_force_assess_on_allowed_action" to config.longTermMemoryForceAssessOnAllowedAction,
                            "long_term_memory_parse_fallback_disable_after" to config.longTermMemoryParseFallbackDisableAfter
                        )
                    )
                )
            )

            MetricsRuntimeFactory.create(
                provider = llm.providerLabel,
                apiKey = llm.apiKey,
                egoModel = llm.egoModel,
                superegoModel = llm.superegoModel
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
                    provider = llm.providerLabel,
                    instrumentation = instrumentation
                )
                val metricsSnapshotObserver = MetricsSnapshotObserver(
                    metricsRuntime = metrics,
                    instrumentation = instrumentation
                )
                val callObserver = combineChatCallObservers(
                    metrics.chatCallObserver(provider = llm.providerLabel),
                    instrumentationObserver,
                    metricsSnapshotObserver
                )
                val rawResponseHook = LlmRawResponseEventHook(
                    instrumentation = instrumentation,
                    maxRawResponseChars = config.maxActionPayloadChars
                )

                InstrumentedChatModelClient(
                    delegate = createChatClient(
                        llm = llm,
                        modelName = llm.egoModel,
                        callObserver = callObserver
                    ),
                    hooks = listOf(rawResponseHook)
                ).use { egoPlannerClient ->
                    InstrumentedChatModelClient(
                        delegate = createChatClient(
                            llm = llm,
                            modelName = llm.superegoModel,
                            callObserver = callObserver
                        ),
                        hooks = listOf(rawResponseHook)
                    ).use { superegoClient ->
                        InstrumentedChatModelClient(
                            delegate = createChatClient(
                                llm = llm,
                                modelName = llm.metaReasonerModel,
                                callObserver = callObserver
                            ),
                            hooks = listOf(rawResponseHook)
                        ).use { metaReasonerClient ->
                            InstrumentedChatModelClient(
                                delegate = createChatClient(
                                    llm = llm,
                                    modelName = llm.memoryConsolidationModel,
                                    callObserver = callObserver
                                ),
                                hooks = listOf(rawResponseHook)
                            ).use { longTermMemoryClient ->
                                logger.info {
                                    "Provider=${llm.providerLabel} Ego model=${llm.egoModel} Superego model=${llm.superegoModel} " +
                                        "Meta model=${llm.metaReasonerModel} Memory model=${llm.memoryConsolidationModel} " +
                                        "WebSearch model=${llm.webSearchModel}"
                                }

                                val gatekeeper = SuperegoGatekeeper(
                                    modelClient = superegoClient,
                                    config = config,
                                    directives = superegoDirectives,
                                    instrumentation = instrumentation
                                )
                                val mcpTimeTool = createMcpTimeTool(config, mcpRuntimeConfig.time)
                                val mcpFetchTool = createMcpFetchTool(config, mcpRuntimeConfig.fetch)
                                val webSearchRuntime = createWebSearchRuntime(
                                    llm = llm,
                                    callObserver = callObserver,
                                    instrumentation = instrumentation,
                                    maxRawResponseChars = config.maxActionPayloadChars
                                )

                                webSearchRuntime.use { runtime ->
                                    val timeTool = mcpTimeTool
                                    val fetchTool = mcpFetchTool
                                    try {
                                        val webSearchActionHandler = WebSearchActionHandler(runtime.engine)
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
                                        var plannerNoopCount = 0
                                        var plannerOutputRepairedCount = 0
                                        var longTermMemoryAssessmentParseFailures = 0
                                        val planner = EgoPlanner(
                                            modelClient = egoPlannerClient,
                                            config = config,
                                            instrumentation = instrumentation,
                                            onPlannerNoop = {
                                                metrics.recordPlannerNoop()
                                                plannerNoopCount += 1
                                                if (plannerNoopCount == 3) {
                                                    instrumentation.emit(
                                                        AgentEvents.warning(
                                                            "Anomaly threshold reached: noop_count >= 3."
                                                        )
                                                    )
                                                }
                                                emitMetricsSnapshot()
                                            },
                                            onPlannerOutputRepaired = {
                                                metrics.recordPlannerOutputRepaired()
                                                plannerOutputRepairedCount += 1
                                                if (plannerOutputRepairedCount == 3) {
                                                    instrumentation.emit(
                                                        AgentEvents.warning(
                                                            "Anomaly threshold reached: planner_output_repaired_count >= 3."
                                                        )
                                                    )
                                                }
                                                emitMetricsSnapshot()
                                            }
                                        )
                                        val metaReasoner = LlmMetaReasoner(
                                            modelClient = metaReasonerClient,
                                            config = config
                                        )
                                        val longTermMemoryAdvisor = LlmLongTermMemoryAdvisor(
                                            modelClient = longTermMemoryClient,
                                            config = config
                                        )
                                        val hippocampus = createHippocampus(config, mcpRuntimeConfig.memory)
                                        try {
                                            EgoAgent(
                                                planner = planner,
                                                superego = gatekeeper,
                                                motorCortex = motorCortex,
                                                config = config,
                                                hippocampus = hippocampus,
                                                metaReasoner = metaReasoner,
                                                longTermMemoryAdvisor = longTermMemoryAdvisor,
                                                onActionDenied = {
                                                    metrics.recordDeniedAction()
                                                    emitMetricsSnapshot()
                                                },
                                                onQueueSaturation = { queueType, _, _ ->
                                                    metrics.recordQueueSaturation(queueType)
                                                    emitMetricsSnapshot()
                                                },
                                                onMemoryRecall = { hitCount, latencyMs, recallChars, truncated ->
                                                    metrics.recordMemoryRecall(
                                                        hitCount = hitCount,
                                                        latencyMs = latencyMs,
                                                        recallChars = recallChars,
                                                        truncated = truncated
                                                    )
                                                    emitMetricsSnapshot()
                                                },
                                                onMemoryRecallFailure = { latencyMs ->
                                                    metrics.recordMemoryRecallFailure(latencyMs)
                                                    emitMetricsSnapshot()
                                                },
                                                onLongTermMemoryRecallSkipped = {
                                                    metrics.recordLongTermMemoryRecallSkipped()
                                                    emitMetricsSnapshot()
                                                },
                                                onLongTermMemoryAssessment = { saveRecommended ->
                                                    metrics.recordLongTermMemoryAssessment(saveRecommended)
                                                    emitMetricsSnapshot()
                                                },
                                                onLongTermMemoryAssessmentParseFailure = {
                                                    metrics.recordLongTermMemoryAssessmentParseFailure()
                                                    longTermMemoryAssessmentParseFailures += 1
                                                    if (longTermMemoryAssessmentParseFailures == 2) {
                                                        instrumentation.emit(
                                                            AgentEvents.warning(
                                                                "Anomaly threshold reached: memory_consolidation_parse_failures >= 2."
                                                            )
                                                        )
                                                    }
                                                    emitMetricsSnapshot()
                                                },
                                                onMemoryImprintResult = { saved, summaryChars, latencyMs ->
                                                    metrics.recordMemoryImprint(
                                                        saved = saved,
                                                        summaryChars = summaryChars,
                                                        latencyMs = latencyMs
                                                    )
                                                    emitMetricsSnapshot()
                                                },
                                                onEndToEndResponseLatency = { latencyMs ->
                                                    metrics.recordEndToEndResponseLatency(latencyMs)
                                                    emitMetricsSnapshot()
                                                },
                                                instrumentation = instrumentation
                                            ).runInteractive()
                                        } finally {
                                            hippocampus.close()
                                        }
                                    } finally {
                                        closeQuietly(fetchTool)
                                        closeQuietly(timeTool)
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

private data class WebSearchRuntime(
    val engine: WebSearchEngine,
    private val closeAction: () -> Unit = {},
) : AutoCloseable {
    override fun close() {
        closeAction()
    }
}

private fun createWebSearchRuntime(
    llm: LlmRuntimeConfig,
    callObserver: psyke.llm.ChatCallObserver?,
    instrumentation: psyke.instrumentation.AgentInstrumentation,
    maxRawResponseChars: Int,
): WebSearchRuntime {
    return when (llm.provider) {
        LlmProvider.MISTRAL -> {
            val session = MistralWebSearchAgentSession.start(
                apiKey = llm.apiKey,
                model = llm.webSearchModel,
                providedAgentId = System.getenv("MISTRAL_WEBSEARCH_AGENT_ID"),
                baseUrl = llm.baseUrl
            )
            val profile = MistralWebSearchProfile(
                mode = MistralWebSearchMode.AGENT_ID,
                model = llm.webSearchModel,
                agentId = session.agentId
            )
            val engine = MistralConversationsWebSearchEngine(
                apiKey = llm.apiKey,
                profile = profile,
                baseUrl = llm.baseUrl,
                callObserver = callObserver,
                instrumentation = instrumentation,
                maxRawResponseChars = maxRawResponseChars
            )
            WebSearchRuntime(
                engine = engine,
                closeAction = {
                    closeQuietly(engine)
                    closeQuietly(session)
                }
            )
        }

        LlmProvider.GROQ -> WebSearchRuntime(
            engine = GroqConversationsWebSearchEngine(
                apiKey = llm.apiKey,
                model = llm.webSearchModel,
                baseUrl = llm.baseUrl,
                callObserver = callObserver,
                instrumentation = instrumentation,
                maxRawResponseChars = maxRawResponseChars
            )
        )
    }
}

private fun createChatClient(
    llm: LlmRuntimeConfig,
    modelName: String,
    callObserver: psyke.llm.ChatCallObserver? = null,
): ChatModelClient {
    return when (llm.provider) {
        LlmProvider.GROQ -> GroqChatClient(
            apiKey = llm.apiKey,
            baseUrl = llm.baseUrl,
            modelName = modelName,
            callObserver = callObserver
        )

        LlmProvider.MISTRAL -> MistralChatClient(
            apiKey = llm.apiKey,
            baseUrl = llm.baseUrl,
            modelName = modelName,
            callObserver = callObserver
        )
    }
}

private fun createMcpTimeTool(config: AgentConfig, capability: McpCapabilityConfig): McpTimeTool {
    val command = resolveMcpCommand(capability)
    if (command == null) {
        val reason = disabledReason("time", capability)
        logger.info { reason }
        return DisabledMcpTimeTool(reason)
    }
    return SdkMcpTimeTool(
        command = command,
        callTimeoutMs = config.mcpCallTimeoutMs
    )
}

private fun createMcpFetchTool(config: AgentConfig, capability: McpCapabilityConfig): McpFetchTool {
    val command = resolveMcpCommand(capability)
    if (command == null) {
        val reason = disabledReason("fetch", capability)
        logger.info { reason }
        return DisabledMcpFetchTool(reason)
    }
    return SdkMcpFetchTool(
        command = command,
        callTimeoutMs = config.mcpCallTimeoutMs,
        maxChars = config.mcpFetchMaxChars
    )
}

private fun createHippocampus(config: AgentConfig, capability: McpCapabilityConfig): Hippocampus {
    val command = resolveMcpCommand(capability)
    if (command == null) {
        logger.info { disabledReason("memory", capability) }
        return NoopHippocampus
    }
    return McpHippocampus(
        command = command,
        callTimeoutMs = config.mcpMemoryCallTimeoutMs,
        defaultMaxItems = config.longTermMemoryRecallMaxItems,
        defaultMaxChars = config.longTermMemoryRecallMaxChars
    )
}

private fun resolveMcpCommand(capability: McpCapabilityConfig): List<String>? {
    if (!capability.enabled) {
        return null
    }
    if (!capability.mode.equals("stdio", ignoreCase = true)) {
        return null
    }

    val candidateCommands = buildList {
        val primary = capability.command.trim()
        if (primary.isNotBlank()) {
            add(primary)
        }
        capability.fallbackCommands.map { it.trim() }.filter { it.isNotBlank() }.forEach { add(it) }
    }
    if (candidateCommands.isEmpty()) {
        return null
    }

    val parsedCandidates = candidateCommands
        .map { McpStdioClient.parseCommand(it) }
        .filter { it.isNotEmpty() }
    if (parsedCandidates.isEmpty()) {
        return null
    }

    val available = parsedCandidates.firstOrNull { command -> isExecutableAvailable(command.first()) }
    if (available != null) {
        return available
    }
    return null
}

private fun isExecutableAvailable(binary: String): Boolean {
    val candidate = binary.trim()
    if (candidate.isBlank()) {
        return false
    }
    val explicitPath = Paths.get(candidate)
    if (explicitPath.isAbsolute || candidate.contains("/") || candidate.contains("\\")) {
        return Files.isExecutable(explicitPath)
    }
    val pathVar = System.getenv("PATH").orEmpty()
    if (pathVar.isBlank()) {
        return false
    }
    return pathVar.split(java.io.File.pathSeparatorChar).any { segment ->
        val base = segment.trim()
        if (base.isBlank()) {
            return@any false
        }
        Files.isExecutable(Paths.get(base, candidate))
    }
}

private fun disabledReason(capabilityName: String, capability: McpCapabilityConfig): String {
    if (!capability.enabled) {
        return "MCP $capabilityName capability disabled by configuration."
    }
    if (!capability.mode.equals("stdio", ignoreCase = true)) {
        return "MCP $capabilityName mode '${capability.mode}' is not supported in this runtime (only stdio)."
    }
    val configured = listOf(capability.command) + capability.fallbackCommands
    val nonBlank = configured.map { it.trim() }.filter { it.isNotBlank() }
    if (nonBlank.isEmpty()) {
        return "MCP $capabilityName command is not configured."
    }
    return "MCP $capabilityName command is configured but not executable in PATH: ${nonBlank.joinToString(" | ")}"
}

private fun closeQuietly(value: Any?) {
    val closeable = value as? AutoCloseable ?: return
    try {
        closeable.close()
    } catch (_: Exception) {
        // ignore best-effort shutdown
    }
}

private class DisabledMcpTimeTool(
    private val reason: String,
) : McpTimeTool, AutoCloseable {
    override fun getCurrentTime(payload: String): String =
        "MCP time unavailable: $reason"

    override fun healthCheck(): ToolHealthStatus =
        ToolHealthStatus(
            available = false,
            detail = reason
        )

    override fun close() {}
}

private class DisabledMcpFetchTool(
    private val reason: String,
) : McpFetchTool, AutoCloseable {
    override fun fetch(payload: String): String =
        "MCP fetch unavailable: $reason"

    override fun healthCheck(): ToolHealthStatus =
        ToolHealthStatus(
            available = false,
            detail = reason
        )

    override fun close() {}
}

private fun printAppHelp() {
    println(
        """
        psyke app options:
          --eval-reasoning-only           Run deterministic reasoning self-eval (no tools/actions)
          --eval-reasoning-mode MODE      Eval mode: logic (default) or model
          --eval-memory-live              Run live memory eval (real LLM + real MCP memory)
          --eval-stage ID                 Label this eval run (default: UTC date, e.g. 2026-02-28)
          --eval-reasoning-max-attempts N Max retries per reasoning task (default: 4)
          --eval-reasoning-tasks id1,id2  Run only selected reasoning task ids
          --eval-memory-max-attempts N    Max long-term memory assessment retries per memory task (default: 2)
          --eval-memory-tasks id1,id2     Run only selected memory eval task ids
          -h, --help                      Show this help message
        """.trimIndent()
    )
}

private fun parseCliOptions(args: Array<String>): AppCliOptions {
    var showHelp = false
    var evalReasoningOnly = false
    var evalMemoryLiveOnly = false
    var evalReasoningMode = ReasoningEvalMode.LOGIC
    var evalStage: String? = null
    var evalReasoningMaxAttempts = 4
    var evalReasoningTaskFilter: Set<String> = emptySet()
    var evalMemoryMaxAttempts = 2
    var evalMemoryTaskFilter: Set<String> = emptySet()
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
            arg == "--eval-memory-live" -> {
                evalMemoryLiveOnly = true
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

            arg == "--eval-memory-max-attempts" -> {
                val next = args.getOrNull(index + 1)
                val parsed = next?.toIntOrNull()
                if (parsed == null || parsed < 1) {
                    parseErrors += "Invalid value for --eval-memory-max-attempts: '${next ?: "<missing>"}'. Expected positive integer."
                } else {
                    evalMemoryMaxAttempts = parsed
                }
                index += 2
            }
            arg.startsWith("--eval-memory-max-attempts=") -> {
                val raw = arg.substringAfter('=')
                val parsed = raw.toIntOrNull()
                if (parsed == null || parsed < 1) {
                    parseErrors += "Invalid value for --eval-memory-max-attempts: '$raw'. Expected positive integer."
                } else {
                    evalMemoryMaxAttempts = parsed
                }
                index += 1
            }

            arg == "--eval-memory-tasks" -> {
                val next = args.getOrNull(index + 1)
                if (next.isNullOrBlank()) {
                    parseErrors += "Missing value for --eval-memory-tasks."
                } else {
                    evalMemoryTaskFilter = next.split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet()
                }
                index += 2
            }
            arg.startsWith("--eval-memory-tasks=") -> {
                val raw = arg.substringAfter('=')
                evalMemoryTaskFilter = raw.split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet()
                index += 1
            }

            else -> {
                unknownArgs += arg
                index += 1
            }
        }
    }

    if (evalReasoningOnly && evalMemoryLiveOnly) {
        parseErrors += "Choose only one eval mode: --eval-reasoning-only or --eval-memory-live."
    }

    return AppCliOptions(
        showHelp = showHelp,
        evalReasoningOnly = evalReasoningOnly,
        evalMemoryLiveOnly = evalMemoryLiveOnly,
        evalReasoningMode = evalReasoningMode,
        evalStage = evalStage,
        evalReasoningMaxAttempts = evalReasoningMaxAttempts,
        evalReasoningTaskFilter = evalReasoningTaskFilter,
        evalMemoryMaxAttempts = evalMemoryMaxAttempts,
        evalMemoryTaskFilter = evalMemoryTaskFilter,
        unknownArgs = unknownArgs,
        parseErrors = parseErrors
    )
}
