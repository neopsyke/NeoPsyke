package psyke

import mu.KotlinLogging
import psyke.agent.config.AgentConfig
import psyke.agent.ego.Ego
import psyke.agent.ego.LlmEgoPlanner
import psyke.agent.memory.episodic.Logbook
import psyke.agent.memory.episodic.SqliteLogbook
import psyke.agent.ego.LlmTaskWorkspaceFinalizer
import psyke.agent.memory.longterm.Hippocampus
import psyke.agent.ego.LlmMetaReasoner
import psyke.agent.ego.NoopTaskWorkspaceFinalizer
import psyke.agent.memory.longterm.LlmLongTermMemoryAdvisor
import psyke.agent.memory.longterm.McpHippocampus
import psyke.agent.tools.mcp.McpStdioClient
import psyke.agent.model.ActionType
import psyke.agent.actions.ActionPluginFactoryContext
import psyke.agent.actions.ActionRegistry
import psyke.agent.cortex.sensory.AsyncSensoryInputSource
import psyke.agent.cortex.sensory.SensoryCortex
import psyke.agent.cortex.motor.ActionImplementationStatus
import psyke.agent.cortex.motor.MotorCortex
import psyke.agent.memory.longterm.NoopHippocampus
import psyke.agent.tools.mcp.NativeFetchTool
import psyke.agent.tools.mcp.SdkMcpTimeTool
import psyke.agent.superego.Superego
import psyke.agent.actions.websearch.WebSearchActionHandler
import psyke.agent.actions.websearch.WebSearchEngine
import psyke.agent.actions.websearch.WebSearchEngineHealth
import psyke.agent.actions.websearch.WebSearchResult
import psyke.config.AgentRuntimeSettings
import psyke.config.McpCapabilityConfig
import psyke.config.LlmEndpointConfig
import psyke.config.LlmProvider
import psyke.config.LlmRuntimeConfig
import psyke.config.McpRuntimeConfig
import psyke.dashboard.DashboardServer
import psyke.dashboard.DashboardStateStore
import psyke.dashboard.ChatRuntimeBridge
import psyke.dashboard.InnerVoiceSink
import psyke.dashboard.InnerVoiceStore
import psyke.eval.MemoryLiveEvalOptions
import psyke.eval.MemoryLiveEvalReporter
import psyke.eval.MemoryLiveEvalRunner
import psyke.eval.MemoryLiveEvalTasks
import psyke.eval.ReasoningEvalOptions
import psyke.eval.ReasoningEvalReporter
import psyke.eval.ReasoningEvalMode
import psyke.eval.ReasoningEvalTasks
import psyke.eval.ReasoningBehavioralLogicEvalTasks
import psyke.eval.ReasoningLogicEvalTasks
import psyke.eval.ReasoningLogicHarnessClient
import psyke.eval.ReasoningSelfEvalRunner
import psyke.eval.UsageTrackingChatClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import psyke.async.agentScope
import psyke.instrumentation.AgentEvent
import psyke.instrumentation.AgentEvents
import psyke.instrumentation.InstrumentationBus
import psyke.instrumentation.JsonlEventSink
import psyke.instrumentation.LlmCallEventObserver
import psyke.instrumentation.LlmRawResponseEventHook
import psyke.instrumentation.MemoryEvalFlowLogSink
import psyke.instrumentation.MetricsEventSink
import psyke.instrumentation.MetricsSnapshotObserver
import psyke.instrumentation.ReasoningEvalFlowLogSink
import psyke.instrumentation.StructuredLogSink
import psyke.instrumentation.TaskWorkspaceDumpSink
import psyke.llm.InstrumentedChatModelClient
import psyke.llm.ChatModelClient
import psyke.integrations.google.websearch.GeminiWebSearchEngine
import psyke.llm.GeminiChatClient
import psyke.llm.GeminiProviderStatusChecker
import psyke.llm.GroqChatClient
import psyke.llm.GroqProviderStatusChecker
import psyke.llm.LlmRoleLabels
import psyke.llm.LlmTokenBudgetConfig
import psyke.llm.LlmTokenBudgetGate
import psyke.llm.MistralChatClient
import psyke.llm.MistralProviderStatusChecker
import psyke.llm.OpenAiChatClient
import psyke.llm.OpenAiProviderStatusChecker
import psyke.llm.ProviderHealthState
import psyke.llm.ProviderStatus
import psyke.llm.TokenBudgetGuardedChatClient
import psyke.llm.LlmCacheMode
import psyke.llm.LlmCacheManager
import psyke.llm.combineChatCallObservers
import psyke.llm.isRetryableProviderHealthFailure
import psyke.llm.reportProviderStatusAndDecide
import psyke.integrations.groq.websearch.GroqConversationsWebSearchEngine
import psyke.integrations.mistral.websearch.MistralConversationsWebSearchEngine
import psyke.integrations.mistral.websearch.MistralWebSearchMode
import psyke.integrations.mistral.websearch.MistralWebSearchProfile
import psyke.integrations.mistral.websearch.MistralWebSearchAgentSession
import psyke.metrics.MetricsQueryProvider
import psyke.metrics.MetricsRuntimeFactory
import psyke.agent.tools.mcp.FetchTool
import psyke.agent.tools.mcp.McpTimeTool
import psyke.agent.tools.mcp.ToolHealthStatus
import kotlin.system.exitProcess
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private val logger = KotlinLogging.logger {}
private val output: ConsoleReporter = StdConsoleReporter
private const val PROVIDER_HEALTH_CHECK_MAX_ATTEMPTS: Int = 2
private const val PROVIDER_HEALTH_CHECK_RETRY_DELAY_MS: Long = 250L

internal object AppModeRunners {
    private data class InteractiveLlmStartupConfig(
        val metaReasonerFallback: LlmEndpointConfig?,
    )

    internal fun runReasoningOnlyEval(
        llm: LlmRuntimeConfig,
        cliOptions: AppCliOptions,
        runtimeSettings: AgentRuntimeSettings,
    ) {
        output.info("Running reasoning-only self-eval (mode=${cliOptions.evalReasoningMode.id})...")
        val sidecarPath = resolveEvalEventSidecarPath()
        val sidecarSink = if (sidecarPath == null) {
            null
        } else {
            try {
                JsonlEventSink(sidecarPath).also {
                    output.info("Event sidecar: $sidecarPath")
                }
            } catch (ex: Exception) {
                logger.warn(ex) { "Failed to initialize event sidecar at $sidecarPath; continuing without sidecar." }
                null
            }
        }
        val sinks = listOf(
            ReasoningEvalFlowLogSink()
        )
        val evalRawResponseCharLimit = runtimeSettings.evalMaxRawResponseChars
        val evalScope = agentScope("psyke-reasoning-eval")
        InstrumentationBus(
            sinks = sinks,
            criticalSinks = listOfNotNull(sidecarSink),
            scope = evalScope
        ).use { instrumentation ->
            val provider = if (cliOptions.evalReasoningMode == ReasoningEvalMode.LOGIC) {
                "logic-harness"
            } else {
                llm.planner.providerLabel
            }
            MetricsRuntimeFactory.create(
                provider = provider,
                apiKey = llm.planner.apiKey.ifBlank { provider },
                egoModel = llm.planner.model,
                superegoModel = llm.superego.model
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
                        val resolvedApiKey = llm.planner.apiKey.trim()
                        if (resolvedApiKey.isBlank()) {
                            val message = "${llm.planner.apiKeyEnvVar} is required for --eval-reasoning-mode model."
                            output.error(message)
                            logger.warn { message }
                            return
                        }
                        if (!checkProviderHealth(endpoint = llm.planner, modeLabel = "eval_reasoning_model", roleLabel = "planner")) {
                            return
                        }
                        createChatClient(
                            endpoint = llm.planner,
                            callObserver = callObserver
                        )
                    }
                }
                val evalTasks = when (cliOptions.evalReasoningMode) {
                    ReasoningEvalMode.LOGIC -> {
                        val coreTasks = ReasoningLogicEvalTasks.defaults()
                        if (cliOptions.evalReasoningTaskFilter.isEmpty()) {
                            coreTasks
                        } else {
                            coreTasks + ReasoningBehavioralLogicEvalTasks.defaults()
                        }
                    }
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
                        stage = cliOptions.evalStage
                            ?: runtimeSettings.evalDefaultStage
                            ?: java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString(),
                        mode = cliOptions.evalReasoningMode.id
                    )
                    val report = ReasoningSelfEvalRunner(
                        client = client,
                        tasks = evalTasks,
                        instrumentation = instrumentation
                    ).run(options)
                    output.info(ReasoningEvalReporter.render(report))
                    val runPath = ReasoningEvalReporter.writeRunReport(report)
                    ReasoningEvalReporter.appendHistory(report)
                    output.info("Run report: $runPath")
                    output.info("History: .psyke/evals/reasoning/history.jsonl")
                }
            }
        }
    }
    
    internal fun runMemoryLiveEval(
        llm: LlmRuntimeConfig,
        config: AgentConfig,
        mcpRuntimeConfig: McpRuntimeConfig,
        cliOptions: AppCliOptions,
        runtimeSettings: AgentRuntimeSettings,
    ) {
        output.info("Running memory live eval (real LLM + real MCP memory)...")
        val resolvedApiKey = llm.planner.apiKey.trim()
        if (resolvedApiKey.isBlank()) {
            val message = "${llm.planner.apiKeyEnvVar} is required for --eval-memory-live."
            output.error(message)
            logger.warn { message }
            return
        }
        if (!checkProviderHealth(endpoint = llm.planner, modeLabel = "eval_memory_live", roleLabel = "planner")) {
            return
        }
        val memoryCommand = resolveMcpCommand(mcpRuntimeConfig.memory)
        if (memoryCommand == null) {
            val reason = disabledReason("memory", mcpRuntimeConfig.memory)
            output.error(
                "Memory MCP command is unavailable for --eval-memory-live. $reason Configure mcp-runtime.yaml or override with MCP_MEMORY_SERVER_CMD."
            )
            logger.warn {
                "Memory MCP command is unavailable for --eval-memory-live. $reason"
            }
            return
        }
        if (!checkMcpMemoryProviderHealth(command = memoryCommand, timeoutMs = config.memory.mcpMemoryCallTimeoutMs, modeLabel = "eval_memory_live")) {
            return
        }
    
        val sidecarPath = resolveEvalEventSidecarPath()
        val sidecarSink = if (sidecarPath == null) {
            null
        } else {
            try {
                JsonlEventSink(sidecarPath).also {
                    output.info("Event sidecar: $sidecarPath")
                }
            } catch (ex: Exception) {
                logger.warn(ex) { "Failed to initialize event sidecar at $sidecarPath; continuing without sidecar." }
                null
            }
        }
        val sinks = listOf(
            MemoryEvalFlowLogSink()
        )
        val evalRawResponseCharLimit = runtimeSettings.evalMaxRawResponseChars
        val evalScope = agentScope("psyke-memory-eval")
        InstrumentationBus(
            sinks = sinks,
            criticalSinks = listOfNotNull(sidecarSink),
            scope = evalScope
        ).use { instrumentation ->
            MetricsRuntimeFactory.create(
                provider = llm.planner.providerLabel,
                apiKey = llm.planner.apiKey,
                egoModel = llm.planner.model,
                superegoModel = llm.superego.model
            ).use { metrics ->
                val llmCallObserver = LlmCallEventObserver(
                    provider = llm.planner.providerLabel,
                    instrumentation = instrumentation
                )
                val metricsSnapshotObserver = MetricsSnapshotObserver(
                    metricsRuntime = metrics,
                    instrumentation = instrumentation
                )
                val callObserver = combineChatCallObservers(
                    metrics.chatCallObserver(provider = llm.planner.providerLabel),
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
                            endpoint = llm.planner,
                            callObserver = callObserver
                        ),
                        hooks = listOf(rawResponseHook)
                    )
                ).use { client ->
                    val stage = cliOptions.evalStage
                        ?: runtimeSettings.evalDefaultStage
                        ?: java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()
                    McpHippocampus(
                        command = memoryCommand,
                        callTimeoutMs = config.memory.mcpMemoryCallTimeoutMs,
                        defaultMaxItems = config.memory.longTermMemoryRecallMaxItems,
                        defaultMaxChars = config.memory.longTermMemoryRecallMaxChars
                    ).use { hippocampus ->
                        val report = MemoryLiveEvalRunner(
                            client = client,
                            longTermMemoryAdvisor = LlmLongTermMemoryAdvisor(
                                modelClient = client,
                                config = config,
                                modelTokenWeight = llm.modelCatalog.tokenWeightFor(llm.planner),
                                modelContextWindow = llm.modelCatalog.contextWindowFor(llm.planner),
                                instrumentation = instrumentation
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
                        output.info(MemoryLiveEvalReporter.render(report))
                        val runPath = MemoryLiveEvalReporter.writeRunReport(report)
                        MemoryLiveEvalReporter.appendHistory(report)
                        output.info("Run report: $runPath")
                        output.info("History: .psyke/evals/memory-live/history.jsonl")
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
    
    private fun resolveProviderHealthStatus(
        endpoint: LlmEndpointConfig,
        modeLabel: String,
        roleLabel: String,
    ): ProviderStatus {
        if (endpoint.apiKey.isBlank()) {
            return ProviderStatus(
                provider = endpoint.providerLabel,
                state = ProviderHealthState.UNAVAILABLE,
                detail = "${endpoint.apiKeyEnvVar} is missing."
            )
        }
        val checker = when (endpoint.provider) {
            LlmProvider.GROQ -> GroqProviderStatusChecker(
                apiKey = endpoint.apiKey,
                baseUrl = endpoint.baseUrl
            )

            LlmProvider.MISTRAL -> MistralProviderStatusChecker(
                apiKey = endpoint.apiKey,
                baseUrl = endpoint.baseUrl
            )

            LlmProvider.GOOGLE -> GeminiProviderStatusChecker(
                apiKey = endpoint.apiKey,
                baseUrl = endpoint.baseUrl
            )

            LlmProvider.OPENAI -> OpenAiProviderStatusChecker(
                apiKey = endpoint.apiKey,
                baseUrl = endpoint.baseUrl
            )
        }
        var status = checker.check()
        for (attempt in 1 until PROVIDER_HEALTH_CHECK_MAX_ATTEMPTS) {
            if (!isRetryableProviderHealthFailure(status)) {
                break
            }
            logger.warn {
                "Provider health check failed for role=$roleLabel mode=$modeLabel " +
                    "(attempt $attempt/$PROVIDER_HEALTH_CHECK_MAX_ATTEMPTS); retrying. detail=${status.detail}"
            }
            try {
                Thread.sleep(PROVIDER_HEALTH_CHECK_RETRY_DELAY_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            status = checker.check()
        }
        return status
    }

    private fun checkProviderHealth(
        endpoint: LlmEndpointConfig,
        modeLabel: String,
        roleLabel: String,
    ): Boolean {
        return reportProviderStatusAndDecide(
            modeLabel = "$modeLabel:$roleLabel",
            status = resolveProviderHealthStatus(
                endpoint = endpoint,
                modeLabel = modeLabel,
                roleLabel = roleLabel
            )
        )
    }

    private fun prepareInteractiveLlmStartup(
        llm: LlmRuntimeConfig,
        modeLabel: String,
    ): InteractiveLlmStartupConfig? {
        val requiredEndpoints = listOf(
            "planner" to llm.planner,
            "action_verifier" to llm.actionVerifier,
            "superego" to llm.superego,
            "meta_reasoner" to llm.metaReasoner,
            "memory_advisor" to llm.memoryAdvisor
        )
        for ((roleLabel, endpoint) in requiredEndpoints) {
            val status = resolveProviderHealthStatus(
                endpoint = endpoint,
                modeLabel = modeLabel,
                roleLabel = roleLabel
            )
            if (!reportProviderStatusAndDecide(modeLabel = "$modeLabel:$roleLabel", status = status)) {
                return null
            }
        }
        val optionalFallback = llm.metaReasonerFallback?.let { fallbackEndpoint ->
            val status = resolveProviderHealthStatus(
                endpoint = fallbackEndpoint,
                modeLabel = modeLabel,
                roleLabel = "meta_reasoner_fallback"
            )
            if (status.state == ProviderHealthState.UNAVAILABLE) {
                reportProviderStatusAndDecide(
                    modeLabel = "$modeLabel:meta_reasoner_fallback",
                    status = status.copy(
                        state = ProviderHealthState.DEGRADED,
                        detail = "${status.detail} Optional role disabled for this run."
                    )
                )
                null
            } else {
                reportProviderStatusAndDecide(
                    modeLabel = "$modeLabel:meta_reasoner_fallback",
                    status = status
                )
                fallbackEndpoint
            }
        }
        return InteractiveLlmStartupConfig(metaReasonerFallback = optionalFallback)
    }

    private fun resolveMetricsProviderLabel(llm: LlmRuntimeConfig): String {
        val providers = linkedSetOf(
            llm.planner.providerLabel,
            llm.actionVerifier.providerLabel,
            llm.superego.providerLabel,
            llm.metaReasoner.providerLabel,
            llm.metaReasonerFallback?.providerLabel ?: "",
            llm.memoryAdvisor.providerLabel
        ).filter { it.isNotBlank() }
        return if (providers.size == 1) {
            providers.first()
        } else {
            "multi"
        }
    }
    
    private fun checkMcpMemoryProviderHealth(
        command: List<String>,
        timeoutMs: Long,
        modeLabel: String,
    ): Boolean {
        val status = try {
            runBlocking {
                McpStdioClient.start(command = command, serverLabel = "memory-health")
            }.use { client ->
                runBlocking {
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
            }
        } catch (ex: Exception) {
            val postgresStatus = localPostgresStatus()
            ProviderStatus(
                provider = "mcp_memory",
                state = ProviderHealthState.UNAVAILABLE,
                detail = "MCP memory provider check failed. PostgreSQL status: $postgresStatus"
            )
        }
    
        return reportProviderStatusAndDecide(
            modeLabel = modeLabel,
            status = status
        )
    }
    
    internal fun runInteractiveMode(
        llm: LlmRuntimeConfig,
        config: AgentConfig,
        mcpRuntimeConfig: McpRuntimeConfig,
        runtimeSettings: AgentRuntimeSettings,
        cliOptions: AppCliOptions? = null,
    ) {
        val startupConfig = prepareInteractiveLlmStartup(llm = llm, modeLabel = "interactive") ?: return
        val metaReasonerFallbackEndpoint = startupConfig.metaReasonerFallback
        val dashboardPort = runtimeSettings.dashboardPort
        val dashboardEnabled = runtimeSettings.dashboardEnabled
        if (!dashboardEnabled) {
            logger.warn {
                "Interactive mode requires the web dashboard input path. Enable dashboard mode to continue."
            }
            return
        }
    
        val agentScope = agentScope("psyke-agent")
        val dashboardStore = DashboardStateStore()
        val interlocutorResolver = psyke.agent.config.DefaultInterlocutorResolver()
        val sensoryInput = AsyncSensoryInputSource(
            includeStdin = true,
            emitStdinClosedSignal = false,
            stdinMode = AsyncSensoryInputSource.StdinMode.CONTROL_ONLY,
            prompt = { print("control> ") },
            scope = agentScope
        )
        val sensoryCortex = SensoryCortex(
            config = config,
            source = sensoryInput,
            interlocutorResolver = interlocutorResolver
        )
        val chatBridge = ChatRuntimeBridge(
            store = dashboardStore,
            sensoryInput = sensoryInput,
            interlocutorResolver = interlocutorResolver
        )
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
        val innerVoiceStore = if (config.innerVoice.enabled) {
            InnerVoiceStore(maxEventsPerSession = config.innerVoice.maxEventsPerSession)
        } else {
            null
        }
        val innerVoiceSink = if (innerVoiceStore != null) {
            InnerVoiceSink(
                dashboardStore = dashboardStore,
                innerVoiceStore = innerVoiceStore,
                config = config.innerVoice
            )
        } else {
            null
        }
        try {
            InstrumentationBus(
                sinks = listOfNotNull(
                    StructuredLogSink(),
                    dashboardStore,
                    TaskWorkspaceDumpSink(scope = agentScope),
                    innerVoiceSink
                ),
                criticalSinks = listOfNotNull(sidecarSink),
                scope = agentScope
            ).use { instrumentation ->
                val dashboardServer = if (dashboardEnabled) {
                    try {
                        DashboardServer(
                            store = dashboardStore,
                            chatBridge = chatBridge,
                            innerVoiceStore = innerVoiceStore,
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
                                "max_loop_steps" to config.planner.maxLoopStepsPerInput,
                                "loop_delay_ms" to config.loopDelayMs,
                                "max_thought_passes" to config.planner.maxThoughtPasses,
                                "max_prompt_tokens" to config.maxLlmPromptTokens,
                                "max_completion_tokens" to config.planner.maxCompletionTokens,
                                "max_run_total_tokens" to config.planner.maxRunTotalTokens,
                                "max_run_tokens_per_provider" to config.planner.maxRunTokensPerProvider,
                                "max_run_tokens_per_role" to config.planner.maxRunTokensPerRole,
                                "max_pending_inputs" to config.maxPendingInputs,
                                "max_pending_thoughts" to config.maxPendingThoughts,
                                "max_pending_actions" to config.maxPendingActions,
                                "max_input_chars" to config.planner.maxInputChars,
                                "short_term_context_max_chars" to config.memory.maxShortTermContextChars,
                                "short_term_context_max_prompt_tokens" to config.memory.maxShortTermContextPromptTokens,
                                "max_thought_chars" to config.planner.maxThoughtChars,
                                "max_action_payload_chars" to config.maxActionPayloadChars,
                                "max_action_summary_chars" to config.maxActionSummaryChars,
                                "mcp_call_timeout_ms" to config.mcpCallTimeoutMs,
                                "fetch_max_chars" to config.fetchMaxChars,
                                "mcp_memory_call_timeout_ms" to config.memory.mcpMemoryCallTimeoutMs,
                                "long_term_memory_recall_max_items" to config.memory.longTermMemoryRecallMaxItems,
                                "long_term_memory_recall_max_chars" to config.memory.longTermMemoryRecallMaxChars,
                                "long_term_memory_prompt_compression_enabled" to
                                    config.memory.longTermMemoryPromptCompressionEnabled,
                                "long_term_memory_prompt_dialogue_max_chars" to
                                    config.memory.longTermMemoryPromptDialogueMaxChars,
                                "long_term_memory_prompt_recall_max_chars" to
                                    config.memory.longTermMemoryPromptRecallMaxChars,
                                "pressure_assessment_min_step" to config.metaReasoner.deliberationPressureAssessmentMinStep,
                                "pressure_assess_every_steps" to config.metaReasoner.deliberationPressureAssessmentEverySteps,
                                "pressure_assess_threshold" to config.metaReasoner.deliberationPressureAssessmentThreshold,
                                "meta_reasoner_cooldown_steps" to config.metaReasoner.cooldownSteps,
                                "meta_reasoner_max_tokens" to config.metaReasoner.maxTokens,
                                "meta_reasoner_dynamic_completion_enabled" to config.metaReasoner.dynamicCompletionEnabled,
                                "meta_reasoner_dynamic_completion_hard_max_tokens" to
                                    config.metaReasoner.dynamicCompletionHardMaxTokens,
                                "meta_reasoner_dynamic_prompt_to_completion_ratio" to
                                    config.metaReasoner.dynamicPromptToCompletionRatio,
                                "meta_reasoner_dynamic_completion_min_prompt_tokens" to
                                    config.metaReasoner.dynamicCompletionMinPromptTokens,
                                "long_term_memory_assess_every_steps" to config.memory.longTermMemoryAssessEverySteps,
                                "long_term_memory_assess_cooldown_steps" to config.memory.longTermMemoryAssessCooldownSteps,
                                "long_term_memory_min_confidence" to config.memory.longTermMemoryMinConfidence,
                                "long_term_memory_max_tokens" to config.memory.longTermMemoryMaxTokens,
                                "long_term_memory_max_summary_chars" to config.memory.longTermMemoryMaxSummaryChars,
                                "long_term_memory_force_assess_on_allowed_action" to config.memory.longTermMemoryForceAssessOnAllowedAction,
                                "long_term_memory_force_assess_on_terminal_answer" to config.memory.longTermMemoryForceAssessOnTerminalAnswer,
                                "long_term_memory_parse_fallback_disable_after" to config.memory.longTermMemoryParseFallbackDisableAfter,
                                "long_term_memory_recall_echo_min_summary_chars" to config.memory.longTermMemoryRecallEchoMinSummaryChars,
                                "long_term_memory_recall_echo_min_token_length" to config.memory.longTermMemoryRecallEchoMinTokenLength,
                                "long_term_memory_recall_echo_min_token_count" to config.memory.longTermMemoryRecallEchoMinTokenCount,
                                "long_term_memory_recall_echo_token_overlap_threshold" to
                                    config.memory.longTermMemoryRecallEchoTokenOverlapThreshold,
                                "superego_dynamic_completion_enabled" to config.superego.dynamicCompletionEnabled,
                                "superego_dynamic_completion_hard_max_tokens" to config.superego.dynamicCompletionHardMaxTokens,
                                "superego_dynamic_prompt_to_completion_ratio" to config.superego.dynamicPromptToCompletionRatio,
                                "superego_dynamic_completion_min_prompt_tokens" to config.superego.dynamicCompletionMinPromptTokens,
                                "superego_two_stage_review_enabled" to config.superego.twoStageReviewEnabled,
                                "superego_two_stage_low_confidence_threshold" to
                                    config.superego.twoStageLowConfidenceThreshold,
                                "superego_two_stage_escalate_on_medium_policy_risk" to
                                    config.superego.twoStageEscalateOnMediumPolicyRisk,
                                "long_term_memory_dynamic_completion_enabled" to config.memory.longTermMemoryDynamicCompletionEnabled,
                                "long_term_memory_dynamic_completion_hard_max_tokens" to
                                    config.memory.longTermMemoryDynamicCompletionHardMaxTokens,
                                "long_term_memory_dynamic_prompt_to_completion_ratio" to
                                    config.memory.longTermMemoryDynamicPromptToCompletionRatio,
                                "long_term_memory_dynamic_completion_min_prompt_tokens" to
                                    config.memory.longTermMemoryDynamicCompletionMinPromptTokens,
                                "superego_model_token_weight" to llm.modelCatalog.tokenWeightFor(llm.superego),
                                "meta_reasoner_model_token_weight" to llm.modelCatalog.tokenWeightFor(llm.metaReasoner),
                                "memory_advisor_model_token_weight" to llm.modelCatalog.tokenWeightFor(llm.memoryAdvisor)
                            )
                        )
                    )
                )
    
                val metricsProvider = resolveMetricsProviderLabel(llm)
                MetricsRuntimeFactory.create(
                    provider = metricsProvider,
                    apiKey = llm.planner.apiKey.ifBlank { metricsProvider },
                    egoModel = llm.planner.model,
                    superegoModel = llm.superego.model
                ).use { metrics ->
                    val metricsEventSink = MetricsEventSink(
                        metrics = metrics,
                        longTermMemoryParseFailureAnomalyThreshold = config.memory.longTermMemoryParseFallbackDisableAfter
                    )
                    instrumentation.addSink(metricsEventSink)
                    metricsEventSink.setInstrumentation(instrumentation)
                    if (metrics is MetricsQueryProvider) {
                        dashboardServer?.metricsQueryProvider = metrics
                    }
                    instrumentation.setDroppedEventsObserver { delta, total ->
                        metrics.recordDroppedEvents(delta)
                        dashboardStore.recordDroppedEvents(total)
                    }
                    val metricsSnapshotObserver = MetricsSnapshotObserver(
                        metricsRuntime = metrics,
                        instrumentation = instrumentation
                    )
                    val tokenBudgetGate = LlmTokenBudgetGate(
                        metricsRuntime = metrics,
                        config = LlmTokenBudgetConfig(
                            maxRunTotalTokens = config.planner.maxRunTotalTokens,
                            maxRunTokensPerProvider = config.planner.maxRunTokensPerProvider,
                            maxRunTokensPerRole = config.planner.maxRunTokensPerRole
                        )
                    )
                    val callObserversByProvider = mutableMapOf<String, psyke.llm.ChatCallObserver?>()
                    fun callObserverForProvider(provider: String): psyke.llm.ChatCallObserver? {
                        return callObserversByProvider.getOrPut(provider) {
                            val instrumentationObserver = LlmCallEventObserver(
                                provider = provider,
                                instrumentation = instrumentation
                            )
                            combineChatCallObservers(
                                metrics.chatCallObserver(provider = provider),
                                instrumentationObserver,
                                metricsSnapshotObserver
                            )
                        }
                    }
                    val webSearchCallObserver = callObserverForProvider(llm.webSearch.providerLabel)
                    val rawResponseHook = LlmRawResponseEventHook(
                        instrumentation = instrumentation,
                        maxRawResponseChars = config.maxActionPayloadChars
                    )
                    val (llmCacheMode, llmCacheFile) = resolveLlmCacheConfig()
                    val llmCacheManager = if (llmCacheMode != LlmCacheMode.OFF && llmCacheFile != null) {
                        LlmCacheManager(
                            mode = llmCacheMode,
                            cacheFile = llmCacheFile,
                            instrumentation = instrumentation
                        )
                    } else {
                        null
                    }
                    fun maybeCacheWrap(client: ChatModelClient): ChatModelClient =
                        llmCacheManager?.wrapClient(client) ?: client

                    InstrumentedChatModelClient(
                        delegate = TokenBudgetGuardedChatClient(
                            delegate = maybeCacheWrap(createChatClient(
                                endpoint = llm.planner,
                                callObserver = callObserverForProvider(llm.planner.providerLabel)
                            )),
                            budgetGate = tokenBudgetGate,
                            provider = llm.planner.providerLabel,
                            role = LlmRoleLabels.PLANNER
                        ),
                        hooks = listOf(rawResponseHook)
                    ).use { plannerClient ->
                        InstrumentedChatModelClient(
                            delegate = TokenBudgetGuardedChatClient(
                                delegate = maybeCacheWrap(createChatClient(
                                    endpoint = llm.actionVerifier,
                                    callObserver = callObserverForProvider(llm.actionVerifier.providerLabel)
                                )),
                                budgetGate = tokenBudgetGate,
                                provider = llm.actionVerifier.providerLabel,
                                role = LlmRoleLabels.ACTION_VERIFIER
                            ),
                            hooks = listOf(rawResponseHook)
                        ).use { actionVerifierClient ->
                            val superegoReviewRouting = resolveSuperegoReviewRouting(
                                llm = llm,
                                config = config,
                                instrumentation = instrumentation
                            )
                            InstrumentedChatModelClient(
                                delegate = TokenBudgetGuardedChatClient(
                                    delegate = maybeCacheWrap(createChatClient(
                                        endpoint = superegoReviewRouting.primaryEndpoint,
                                        callObserver = callObserverForProvider(superegoReviewRouting.primaryEndpoint.providerLabel)
                                    )),
                                    budgetGate = tokenBudgetGate,
                                    provider = superegoReviewRouting.primaryEndpoint.providerLabel,
                                    role = LlmRoleLabels.SUPEREGO
                                ),
                                hooks = listOf(rawResponseHook)
                            ).use { superegoClient ->
                                val superegoEscalationClient = superegoReviewRouting.escalationEndpoint?.let { escalationEndpoint ->
                                    InstrumentedChatModelClient(
                                        delegate = TokenBudgetGuardedChatClient(
                                            delegate = maybeCacheWrap(createChatClient(
                                                endpoint = escalationEndpoint,
                                                callObserver = callObserverForProvider(escalationEndpoint.providerLabel)
                                            )),
                                            budgetGate = tokenBudgetGate,
                                            provider = escalationEndpoint.providerLabel,
                                            role = LlmRoleLabels.SUPEREGO
                                        ),
                                        hooks = listOf(rawResponseHook)
                                    )
                                }
                                val metaReasonerFallbackClient = metaReasonerFallbackEndpoint?.let { fallbackEndpoint ->
                                    InstrumentedChatModelClient(
                                        delegate = TokenBudgetGuardedChatClient(
                                            delegate = maybeCacheWrap(createChatClient(
                                                endpoint = fallbackEndpoint,
                                                callObserver = callObserverForProvider(fallbackEndpoint.providerLabel)
                                            )),
                                            budgetGate = tokenBudgetGate,
                                            provider = fallbackEndpoint.providerLabel,
                                            role = LlmRoleLabels.META_REASONER
                                        ),
                                        hooks = listOf(rawResponseHook)
                                    )
                                }
                                InstrumentedChatModelClient(
                                    delegate = TokenBudgetGuardedChatClient(
                                        delegate = maybeCacheWrap(createChatClient(
                                            endpoint = llm.metaReasoner,
                                            callObserver = callObserverForProvider(llm.metaReasoner.providerLabel)
                                        )),
                                        budgetGate = tokenBudgetGate,
                                        provider = llm.metaReasoner.providerLabel,
                                        role = LlmRoleLabels.META_REASONER
                                    ),
                                    hooks = listOf(rawResponseHook)
                                ).use { metaReasonerClient ->
                                    InstrumentedChatModelClient(
                                        delegate = TokenBudgetGuardedChatClient(
                                            delegate = maybeCacheWrap(createChatClient(
                                                endpoint = llm.memoryAdvisor,
                                                callObserver = callObserverForProvider(llm.memoryAdvisor.providerLabel)
                                            )),
                                            budgetGate = tokenBudgetGate,
                                            provider = llm.memoryAdvisor.providerLabel,
                                            role = LlmRoleLabels.MEMORY_ADVISOR
                                        ),
                                        hooks = listOf(rawResponseHook)
                                    ).use { longTermMemoryClient ->
                                        logger.info {
                                            "Cognitive role routing: " +
                                                "planner=${llm.planner.providerLabel}/${llm.planner.model}, " +
                                                "action_verifier=${llm.actionVerifier.providerLabel}/${llm.actionVerifier.model}, " +
                                                "superego_primary=${superegoReviewRouting.primaryEndpoint.providerLabel}/${superegoReviewRouting.primaryEndpoint.model}, " +
                                                "superego_escalation=${superegoReviewRouting.escalationEndpoint?.let { "${it.providerLabel}/${it.model}" } ?: "disabled"}, " +
                                                "meta_reasoner=${llm.metaReasoner.providerLabel}/${llm.metaReasoner.model}, " +
                                                "meta_reasoner_fallback=${metaReasonerFallbackEndpoint?.let { "${it.providerLabel}/${it.model}" } ?: "disabled"}, " +
                                                "memory_advisor=${llm.memoryAdvisor.providerLabel}/${llm.memoryAdvisor.model}, " +
                                                "web_search=${llm.webSearch.providerLabel}/${llm.webSearch.model}"
                                        }
                                        try {
                                            val mcpTimeTool = createMcpTimeTool(config, mcpRuntimeConfig.time, agentScope)
                                            val fetchTool = createFetchTool(config, mcpRuntimeConfig.fetch)
                                            val webSearchRuntime = createWebSearchRuntime(
                                                llm = llm,
                                                callObserver = webSearchCallObserver,
                                                instrumentation = instrumentation,
                                                maxRawResponseChars = config.maxActionPayloadChars,
                                                tokenBudgetGate = tokenBudgetGate
                                            )

                                            webSearchRuntime.use { runtime ->
                                                val timeTool = mcpTimeTool
                                                val activeFetchTool = fetchTool
                                                try {
                                                    val earlyMemoryStartup =
                                                        resolveInteractiveMemoryStartup(config, mcpRuntimeConfig.memory)
                                                    val earlyHippocampus = earlyMemoryStartup.hippocampus
                                                    val earlyLogbook = createLogbookIfEnabled(config)
                                                    val webSearchActionHandler = WebSearchActionHandler(runtime.engine)
                                                    val actionRegistry = ActionRegistry.discover(
                                                        ActionPluginFactoryContext(
                                                            config = config,
                                                            webSearchActionHandler = webSearchActionHandler,
                                                            mcpTimeTool = timeTool,
                                                            fetchTool = activeFetchTool,
                                                            output = {},
                                                            hippocampus = earlyHippocampus,
                                                            logbook = earlyLogbook,
                                                        )
                                                    )
                                                    actionRegistry.loadWarnings.forEach { warning ->
                                                        instrumentation.emit(AgentEvents.warning(warning))
                                                    }
                                                    actionRegistry.use { registry ->
                                                        val egoDispatcher = Executors.newSingleThreadExecutor { Thread(it, "psyke-ego") }.asCoroutineDispatcher()
                                                        try { runBlocking(egoDispatcher) {
                                                        val motorCortex = MotorCortex(
                                                            actionRegistry = registry
                                                        )
                                                        val actionStatuses = motorCortex.startupSmokeTest()
                                                        instrumentation.emit(AgentEvents.actionCapabilities(actionStatuses))
                                                        actionStatuses.filterNot { it.available }.forEach { status ->
                                                            instrumentation.emit(
                                                                AgentEvents.warning(
                                                                    "Action ${status.actionType.id} unavailable at startup: ${status.detail}"
                                                                )
                                                            )
                                                        }
                                                        var plannerNoopCount = 0
                                                        var plannerOutputRepairedCount = 0
                                                        val planner = LlmEgoPlanner(
                                                            modelClient = plannerClient,
                                                            actionVerifierModelClient = actionVerifierClient,
                                                            actionVerifierContextWindow = llm.modelCatalog.contextWindowFor(llm.actionVerifier),
                                                            config = config,
                                                            actionPayloadRepair = motorCortex::repairPlannerPayload,
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
                                                            }
                                                        )
                                                        val gatekeeper = Superego(
                                                            modelClient = superegoClient,
                                                            config = config,
                                                            actionRegistry = registry,
                                                            modelTokenWeight = superegoReviewRouting.primaryTokenWeight,
                                                            modelContextWindow = superegoReviewRouting.primaryContextWindow,
                                                            modelReasoningOverhead = superegoReviewRouting.primaryReasoningOverhead,
                                                            escalationModelClient = superegoEscalationClient,
                                                            escalationModelTokenWeight = superegoReviewRouting.escalationTokenWeight
                                                                ?: superegoReviewRouting.primaryTokenWeight,
                                                            escalationModelContextWindow = superegoReviewRouting.escalationContextWindow,
                                                            escalationModelReasoningOverhead = superegoReviewRouting.escalationReasoningOverhead,
                                                            instrumentation = instrumentation
                                                        )
                                                        val metaReasoner = LlmMetaReasoner(
                                                            modelClient = metaReasonerClient,
                                                            config = config,
                                                            modelTokenWeight = llm.modelCatalog.tokenWeightFor(llm.metaReasoner),
                                                            modelContextWindow = llm.modelCatalog.contextWindowFor(llm.metaReasoner),
                                                            fallbackModelClient = metaReasonerFallbackClient,
                                                            instrumentation = instrumentation
                                                        )
                                                        val longTermMemoryAdvisor = LlmLongTermMemoryAdvisor(
                                                            modelClient = longTermMemoryClient,
                                                            config = config,
                                                            modelTokenWeight = llm.modelCatalog.tokenWeightFor(llm.memoryAdvisor),
                                                            modelContextWindow = llm.modelCatalog.contextWindowFor(llm.memoryAdvisor),
                                                            instrumentation = instrumentation
                                                        )
                                                        val taskWorkspaceFinalizer =
                                                            if (config.memory.taskWorkspace.enabled &&
                                                                config.memory.taskWorkspace.finalPassRewriteEnabled
                                                            ) {
                                                                LlmTaskWorkspaceFinalizer(
                                                                    modelClient = plannerClient,
                                                                    config = config,
                                                                    instrumentation = instrumentation
                                                                )
                                                            } else {
                                                                NoopTaskWorkspaceFinalizer
                                                            }
                                                        val hippocampus = earlyHippocampus
                                                        val memoryProviderDetail = earlyMemoryStartup.detail
                                                        instrumentation.emit(AgentEvents.actionCapabilities(actionStatuses))
                                                        instrumentation.emit(
                                                            AgentEvent(
                                                                type = "memory_status",
                                                                data = mapOf(
                                                                    "available" to hippocampus.enabled,
                                                                    "detail" to memoryProviderDetail
                                                                )
                                                            )
                                                        )
                                                        if (!hippocampus.enabled) {
                                                            instrumentation.emit(
                                                                AgentEvents.warning("Long-term memory is unavailable: $memoryProviderDetail")
                                                            )
                                                        }
                                                        val logbook = earlyLogbook
                                                        val logbookSummarizer = createLogbookSummarizer(config, longTermMemoryClient)
                                                        if (cliOptions?.hasClearMemoryRequest == true) {
                                                            executeLongTermMemoryClear(
                                                                cliOptions = cliOptions,
                                                                hippocampus = hippocampus,
                                                                logbook = logbook,
                                                                instrumentation = instrumentation
                                                            )
                                                        }
                                                        val idConfig = psyke.config.IdRuntimeConfigLoader.load()
                                                        val ego = Ego(
                                                            planner = planner,
                                                            superego = gatekeeper,
                                                            motorCortex = motorCortex,
                                                            config = config,
                                                            hippocampus = hippocampus,
                                                            metaReasoner = metaReasoner,
                                                            longTermMemoryAdvisor = longTermMemoryAdvisor,
                                                            sensoryCortex = sensoryCortex,
                                                            taskWorkspaceFinalizer = taskWorkspaceFinalizer,
                                                            instrumentation = instrumentation,
                                                            logbook = logbook,
                                                            logbookSummarizer = logbookSummarizer,
                                                        )
                                                        val idModule = if (idConfig.enabled) {
                                                            psyke.agent.id.Id(
                                                                config = idConfig,
                                                                instrumentation = instrumentation,
                                                                scope = agentScope,
                                                                enqueueImpulse = { impulse ->
                                                                    ego.enqueueImpulse(impulse, idConfig.maxPendingImpulses)
                                                                },
                                                                hasPendingWork = { ego.hasPendingWork() },
                                                                notifyEgo = { sensoryInput.notifyImpulseReady() },
                                                            ).also { id ->
                                                                ego.setId(id)
                                                                id.start()
                                                            }
                                                        } else {
                                                            null
                                                        }
                                                        try {
                                                            ego.runInteractive()
                                                        } finally {
                                                            idModule?.close()
                                                            hippocampus.close()
                                                            closeQuietly(logbook)
                                                        }
                                                        } } finally { egoDispatcher.close() }
                                                    }
                                                } finally {
                                                    closeQuietly(activeFetchTool)
                                                    closeQuietly(timeTool)
                                                }
                                            }
                                        } finally {
                                            superegoEscalationClient?.close()
                                            metaReasonerFallbackClient?.close()
                                        }
                                    }
                                }
                            }
                        }
                    }
                    llmCacheManager?.close()
                    dumpEndOfRunMetrics(metrics)
                }
            }
            }
        } finally {
            innerVoiceStore?.close()
            sensoryInput.close()
            agentScope.cancel()
        }
    }

    internal fun runFreudLiveMode(
        llm: LlmRuntimeConfig,
        config: AgentConfig,
        mcpRuntimeConfig: McpRuntimeConfig,
        runtimeSettings: AgentRuntimeSettings,
        cliOptions: AppCliOptions,
    ) {
        val startupConfig = prepareInteractiveLlmStartup(llm = llm, modeLabel = "freud-live") ?: run {
            exitProcess(1)
        }
        val metaReasonerFallbackEndpoint = startupConfig.metaReasonerFallback

        val stdinContent = System.`in`.bufferedReader().readText().trim()
        if (stdinContent.isBlank()) {
            logger.warn { "No input provided on stdin for freud-live mode." }
            output.error("freud-live: no input on stdin.")
            exitProcess(1)
        }
        logger.info { "freud-live: read ${stdinContent.length} chars from stdin" }

        val answerDeferred = CompletableDeferred<String>()
        val liveOutput: (String) -> Unit = { msg ->
            println(msg)
            System.out.flush()
            val answer = msg.removePrefix("ego> ")
            answerDeferred.complete(answer)
        }

        val agentScope = agentScope("psyke-freud-live")
        val sensoryInput = AsyncSensoryInputSource(
            includeStdin = false,
            emitStdinClosedSignal = false,
            scope = agentScope
        )
        val interlocutorResolver = psyke.agent.config.DefaultInterlocutorResolver()
        val sensoryCortex = SensoryCortex(
            config = config,
            source = sensoryInput,
            interlocutorResolver = interlocutorResolver
        )
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

        try {
            InstrumentationBus(
                sinks = listOfNotNull(
                    StructuredLogSink(),
                    TaskWorkspaceDumpSink(scope = agentScope),
                ),
                criticalSinks = listOfNotNull(sidecarSink),
                scope = agentScope
            ).use { instrumentation ->
                instrumentation.emit(AgentEvents.loopStatus(status = "booting", message = "freud_live_start"))

                val metricsProvider = resolveMetricsProviderLabel(llm)
                MetricsRuntimeFactory.create(
                    provider = metricsProvider,
                    apiKey = llm.planner.apiKey.ifBlank { metricsProvider },
                    egoModel = llm.planner.model,
                    superegoModel = llm.superego.model
                ).use { metrics ->
                    val metricsEventSink = MetricsEventSink(
                        metrics = metrics,
                        longTermMemoryParseFailureAnomalyThreshold = config.memory.longTermMemoryParseFallbackDisableAfter
                    )
                    instrumentation.addSink(metricsEventSink)
                    metricsEventSink.setInstrumentation(instrumentation)
                    instrumentation.setDroppedEventsObserver { delta, _ ->
                        metrics.recordDroppedEvents(delta)
                    }
                    val metricsSnapshotObserver = MetricsSnapshotObserver(
                        metricsRuntime = metrics,
                        instrumentation = instrumentation
                    )
                    val tokenBudgetGate = LlmTokenBudgetGate(
                        metricsRuntime = metrics,
                        config = LlmTokenBudgetConfig(
                            maxRunTotalTokens = config.planner.maxRunTotalTokens,
                            maxRunTokensPerProvider = config.planner.maxRunTokensPerProvider,
                            maxRunTokensPerRole = config.planner.maxRunTokensPerRole
                        )
                    )

                    val callObserversByProvider = mutableMapOf<String, psyke.llm.ChatCallObserver?>()
                    fun callObserverForProvider(provider: String): psyke.llm.ChatCallObserver? {
                        return callObserversByProvider.getOrPut(provider) {
                            val instrumentationObserver = LlmCallEventObserver(
                                provider = provider,
                                instrumentation = instrumentation
                            )
                            combineChatCallObservers(
                                metrics.chatCallObserver(provider = provider),
                                instrumentationObserver,
                                metricsSnapshotObserver
                            )
                        }
                    }
                    val rawResponseHook = LlmRawResponseEventHook(
                        instrumentation = instrumentation,
                        maxRawResponseChars = config.maxActionPayloadChars
                    )
                    val (llmCacheMode, llmCacheFile) = resolveLlmCacheConfig()
                    val llmCacheManager = if (llmCacheMode != LlmCacheMode.OFF && llmCacheFile != null) {
                        LlmCacheManager(
                            mode = llmCacheMode,
                            cacheFile = llmCacheFile,
                            instrumentation = instrumentation
                        )
                    } else {
                        null
                    }
                    fun maybeCacheWrap(client: ChatModelClient): ChatModelClient =
                        llmCacheManager?.wrapClient(client) ?: client

                    InstrumentedChatModelClient(
                        delegate = TokenBudgetGuardedChatClient(
                            delegate = maybeCacheWrap(createChatClient(
                                endpoint = llm.planner,
                                callObserver = callObserverForProvider(llm.planner.providerLabel)
                            )),
                            budgetGate = tokenBudgetGate,
                            provider = llm.planner.providerLabel,
                            role = LlmRoleLabels.PLANNER
                        ),
                        hooks = listOf(rawResponseHook)
                    ).use { plannerClient ->
                        InstrumentedChatModelClient(
                            delegate = TokenBudgetGuardedChatClient(
                                delegate = maybeCacheWrap(createChatClient(
                                    endpoint = llm.actionVerifier,
                                    callObserver = callObserverForProvider(llm.actionVerifier.providerLabel)
                                )),
                                budgetGate = tokenBudgetGate,
                                provider = llm.actionVerifier.providerLabel,
                                role = LlmRoleLabels.ACTION_VERIFIER
                            ),
                            hooks = listOf(rawResponseHook)
                        ).use { actionVerifierClient ->
                            val superegoReviewRouting = resolveSuperegoReviewRouting(
                                llm = llm,
                                config = config,
                                instrumentation = instrumentation
                            )
                            InstrumentedChatModelClient(
                                delegate = TokenBudgetGuardedChatClient(
                                    delegate = maybeCacheWrap(createChatClient(
                                        endpoint = superegoReviewRouting.primaryEndpoint,
                                        callObserver = callObserverForProvider(superegoReviewRouting.primaryEndpoint.providerLabel)
                                    )),
                                    budgetGate = tokenBudgetGate,
                                    provider = superegoReviewRouting.primaryEndpoint.providerLabel,
                                    role = LlmRoleLabels.SUPEREGO
                                ),
                                hooks = listOf(rawResponseHook)
                            ).use { superegoClient ->
                                val superegoEscalationClient = superegoReviewRouting.escalationEndpoint?.let { escalationEndpoint ->
                                    InstrumentedChatModelClient(
                                        delegate = TokenBudgetGuardedChatClient(
                                            delegate = maybeCacheWrap(createChatClient(
                                                endpoint = escalationEndpoint,
                                                callObserver = callObserverForProvider(escalationEndpoint.providerLabel)
                                            )),
                                            budgetGate = tokenBudgetGate,
                                            provider = escalationEndpoint.providerLabel,
                                            role = LlmRoleLabels.SUPEREGO
                                        ),
                                        hooks = listOf(rawResponseHook)
                                    )
                                }
                                val metaReasonerFallbackClient = metaReasonerFallbackEndpoint?.let { fallbackEndpoint ->
                                    InstrumentedChatModelClient(
                                        delegate = TokenBudgetGuardedChatClient(
                                            delegate = maybeCacheWrap(createChatClient(
                                                endpoint = fallbackEndpoint,
                                                callObserver = callObserverForProvider(fallbackEndpoint.providerLabel)
                                            )),
                                            budgetGate = tokenBudgetGate,
                                            provider = fallbackEndpoint.providerLabel,
                                            role = LlmRoleLabels.META_REASONER
                                        ),
                                        hooks = listOf(rawResponseHook)
                                    )
                                }
                                InstrumentedChatModelClient(
                                    delegate = TokenBudgetGuardedChatClient(
                                        delegate = maybeCacheWrap(createChatClient(
                                            endpoint = llm.metaReasoner,
                                            callObserver = callObserverForProvider(llm.metaReasoner.providerLabel)
                                        )),
                                        budgetGate = tokenBudgetGate,
                                        provider = llm.metaReasoner.providerLabel,
                                        role = LlmRoleLabels.META_REASONER
                                    ),
                                    hooks = listOf(rawResponseHook)
                                ).use { metaReasonerClient ->
                                    InstrumentedChatModelClient(
                                        delegate = TokenBudgetGuardedChatClient(
                                            delegate = maybeCacheWrap(createChatClient(
                                                endpoint = llm.memoryAdvisor,
                                                callObserver = callObserverForProvider(llm.memoryAdvisor.providerLabel)
                                            )),
                                            budgetGate = tokenBudgetGate,
                                            provider = llm.memoryAdvisor.providerLabel,
                                            role = LlmRoleLabels.MEMORY_ADVISOR
                                        ),
                                        hooks = listOf(rawResponseHook)
                                    ).use { longTermMemoryClient ->
                                        logger.info {
                                            "freud-live cognitive role routing: " +
                                                "planner=${llm.planner.providerLabel}/${llm.planner.model}, " +
                                                "superego=${superegoReviewRouting.primaryEndpoint.providerLabel}/${superegoReviewRouting.primaryEndpoint.model}, " +
                                                "meta_reasoner=${llm.metaReasoner.providerLabel}/${llm.metaReasoner.model}, " +
                                                "meta_reasoner_fallback=${metaReasonerFallbackEndpoint?.let { "${it.providerLabel}/${it.model}" } ?: "disabled"}, " +
                                                "memory_advisor=${llm.memoryAdvisor.providerLabel}/${llm.memoryAdvisor.model}"
                                        }
                                        try {
                                        val mcpTimeTool = createMcpTimeTool(config, mcpRuntimeConfig.time, agentScope)
                                        val fetchTool = createFetchTool(config, mcpRuntimeConfig.fetch)
                                        val webSearchRuntime = createWebSearchRuntime(
                                            llm = llm,
                                            callObserver = callObserverForProvider(llm.webSearch.providerLabel),
                                            instrumentation = instrumentation,
                                            maxRawResponseChars = config.maxActionPayloadChars,
                                            tokenBudgetGate = tokenBudgetGate
                                        )

                                        webSearchRuntime.use { runtime ->
                                            val timeTool = mcpTimeTool
                                            val activeFetchTool = fetchTool
                                            try {
                                                val earlyMemoryStartup2 =
                                                    resolveInteractiveMemoryStartup(config, mcpRuntimeConfig.memory)
                                                val earlyHippocampus2 = earlyMemoryStartup2.hippocampus
                                                val earlyLogbook2 = createLogbookIfEnabled(config)
                                                val webSearchActionHandler = WebSearchActionHandler(runtime.engine)
                                                val actionRegistry = ActionRegistry.discover(
                                                    ActionPluginFactoryContext(
                                                        config = config,
                                                        webSearchActionHandler = webSearchActionHandler,
                                                        mcpTimeTool = timeTool,
                                                        fetchTool = activeFetchTool,
                                                        output = liveOutput,
                                                        hippocampus = earlyHippocampus2,
                                                        logbook = earlyLogbook2,
                                                    )
                                                )
                                                actionRegistry.loadWarnings.forEach { warning ->
                                                    instrumentation.emit(AgentEvents.warning(warning))
                                                }
                                                actionRegistry.use { registry ->
                                                    val egoDispatcher = Executors.newSingleThreadExecutor { Thread(it, "psyke-freud-live-ego") }.asCoroutineDispatcher()
                                                    try { runBlocking(egoDispatcher) {
                                                    val motorCortex = MotorCortex(
                                                        actionRegistry = registry
                                                    )
                                                    val actionStatuses = motorCortex.startupSmokeTest()
                                                    instrumentation.emit(AgentEvents.actionCapabilities(actionStatuses))

                                                    val planner = LlmEgoPlanner(
                                                        modelClient = plannerClient,
                                                        actionVerifierModelClient = actionVerifierClient,
                                                        actionVerifierContextWindow = llm.modelCatalog.contextWindowFor(llm.actionVerifier),
                                                        config = config,
                                                        actionPayloadRepair = motorCortex::repairPlannerPayload,
                                                        instrumentation = instrumentation,
                                                        onPlannerNoop = { metrics.recordPlannerNoop() },
                                                        onPlannerOutputRepaired = { metrics.recordPlannerOutputRepaired() }
                                                    )
                                                    val gatekeeper = Superego(
                                                        modelClient = superegoClient,
                                                        config = config,
                                                        actionRegistry = registry,
                                                        modelTokenWeight = superegoReviewRouting.primaryTokenWeight,
                                                        modelContextWindow = superegoReviewRouting.primaryContextWindow,
                                                        modelReasoningOverhead = superegoReviewRouting.primaryReasoningOverhead,
                                                        escalationModelClient = superegoEscalationClient,
                                                        escalationModelTokenWeight = superegoReviewRouting.escalationTokenWeight
                                                            ?: superegoReviewRouting.primaryTokenWeight,
                                                        escalationModelContextWindow = superegoReviewRouting.escalationContextWindow,
                                                        escalationModelReasoningOverhead = superegoReviewRouting.escalationReasoningOverhead,
                                                        instrumentation = instrumentation
                                                    )
                                                    val metaReasoner = LlmMetaReasoner(
                                                        modelClient = metaReasonerClient,
                                                        config = config,
                                                        modelTokenWeight = llm.modelCatalog.tokenWeightFor(llm.metaReasoner),
                                                        modelContextWindow = llm.modelCatalog.contextWindowFor(llm.metaReasoner),
                                                        fallbackModelClient = metaReasonerFallbackClient,
                                                        instrumentation = instrumentation
                                                    )
                                                    val longTermMemoryAdvisor = LlmLongTermMemoryAdvisor(
                                                        modelClient = longTermMemoryClient,
                                                        config = config,
                                                        modelTokenWeight = llm.modelCatalog.tokenWeightFor(llm.memoryAdvisor),
                                                        modelContextWindow = llm.modelCatalog.contextWindowFor(llm.memoryAdvisor),
                                                        instrumentation = instrumentation
                                                    )
                                                    val taskWorkspaceFinalizer =
                                                        if (config.memory.taskWorkspace.enabled &&
                                                            config.memory.taskWorkspace.finalPassRewriteEnabled
                                                        ) {
                                                            LlmTaskWorkspaceFinalizer(
                                                                modelClient = plannerClient,
                                                                config = config,
                                                                instrumentation = instrumentation
                                                            )
                                                        } else {
                                                            NoopTaskWorkspaceFinalizer
                                                        }
                                                    val hippocampus = earlyHippocampus2
                                                    instrumentation.emit(
                                                        AgentEvent(
                                                            type = "memory_status",
                                                            data = mapOf(
                                                                "available" to hippocampus.enabled,
                                                                "detail" to earlyMemoryStartup2.detail
                                                            )
                                                        )
                                                    )
                                                    val logbook = earlyLogbook2
                                                    val logbookSummarizer = createLogbookSummarizer(config, longTermMemoryClient)
                                                    val ego = Ego(
                                                        planner = planner,
                                                        superego = gatekeeper,
                                                        motorCortex = motorCortex,
                                                        config = config,
                                                        hippocampus = hippocampus,
                                                        metaReasoner = metaReasoner,
                                                        longTermMemoryAdvisor = longTermMemoryAdvisor,
                                                        sensoryCortex = sensoryCortex,
                                                        taskWorkspaceFinalizer = taskWorkspaceFinalizer,
                                                        instrumentation = instrumentation,
                                                        logbook = logbook,
                                                        logbookSummarizer = logbookSummarizer,
                                                    )

                                                    sensoryInput.submitInput(
                                                        content = stdinContent,
                                                        source = "freud-live"
                                                    )

                                                    val watchdog = launch {
                                                        answerDeferred.await()
                                                        kotlinx.coroutines.delay(500)
                                                        sensoryInput.close()
                                                    }

                                                    try {
                                                        val completed = withTimeoutOrNull(
                                                            cliOptions.freudLiveTimeoutSeconds * 1000L
                                                        ) {
                                                            try {
                                                                ego.runInteractive()
                                                            } catch (ex: ClosedReceiveChannelException) {
                                                                if (answerDeferred.isCompleted) {
                                                                    logger.info {
                                                                        "freud-live sensory channel closed after answer delivery; treating as normal shutdown."
                                                                    }
                                                                    instrumentation.emit(
                                                                        AgentEvents.loopStatus(
                                                                            status = "stopped",
                                                                            message = "freud_live_contact_delivered_input_closed"
                                                                        )
                                                                    )
                                                                    Unit
                                                                } else {
                                                                    throw ex
                                                                }
                                                            }
                                                        }
                                                        if (completed == null) {
                                                            instrumentation.emit(
                                                                AgentEvents.loopStatus(
                                                                    status = "timeout",
                                                                    message = "freud-live timed out after ${cliOptions.freudLiveTimeoutSeconds}s"
                                                                )
                                                            )
                                                        }
                                                    } finally {
                                                        watchdog.cancel()
                                                        hippocampus.close()
                                                        closeQuietly(logbook)
                                                    }

                                                    val exitCode = when {
                                                        answerDeferred.isCompleted -> 0
                                                        else -> 2
                                                    }
                                                    llmCacheManager?.close()
                                                    dumpEndOfRunMetrics(metrics)
                                                    instrumentation.emit(
                                                        AgentEvents.loopStatus(
                                                            status = if (exitCode == 0) "completed" else "timeout",
                                                            message = "freud_live_exit code=$exitCode"
                                                        )
                                                    )
                                                    exitProcess(exitCode)

                                                    } } finally { egoDispatcher.close() }
                                                }
                                            } finally {
                                                closeQuietly(activeFetchTool)
                                                closeQuietly(timeTool)
                                            }
                                        }
                                    } finally {
                                        superegoEscalationClient?.close()
                                        metaReasonerFallbackClient?.close()
                                    }
                                }
                            }
                        }
                    }
                    }
                }
            }
        } finally {
            sensoryInput.close()
            agentScope.cancel()
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
        tokenBudgetGate: LlmTokenBudgetGate? = null,
    ): WebSearchRuntime {
        val webSearch = llm.webSearch
        return try {
            when (webSearch.provider) {
                LlmProvider.MISTRAL -> {
                    val session = MistralWebSearchAgentSession.start(
                        apiKey = webSearch.apiKey,
                        model = webSearch.model,
                        providedAgentId = System.getenv("MISTRAL_WEBSEARCH_AGENT_ID"),
                        baseUrl = webSearch.baseUrl
                    )
                    val profile = MistralWebSearchProfile(
                        mode = MistralWebSearchMode.AGENT_ID,
                        model = webSearch.model,
                        agentId = session.agentId
                    )
                    val engine = MistralConversationsWebSearchEngine(
                        apiKey = webSearch.apiKey,
                        profile = profile,
                        baseUrl = webSearch.baseUrl,
                        callObserver = callObserver,
                        instrumentation = instrumentation,
                        maxRawResponseChars = maxRawResponseChars
                    )
                    WebSearchRuntime(
                        engine = TokenBudgetGuardedWebSearchEngine(
                            delegate = engine,
                            budgetGate = tokenBudgetGate,
                            provider = webSearch.providerLabel
                        ),
                        closeAction = {
                            closeQuietly(engine)
                            closeQuietly(session)
                        }
                    )
                }

                LlmProvider.GROQ -> WebSearchRuntime(
                    engine = TokenBudgetGuardedWebSearchEngine(
                        delegate = GroqConversationsWebSearchEngine(
                            apiKey = webSearch.apiKey,
                            model = webSearch.model,
                            baseUrl = webSearch.baseUrl,
                            callObserver = callObserver,
                            instrumentation = instrumentation,
                            maxRawResponseChars = maxRawResponseChars
                        ),
                        budgetGate = tokenBudgetGate,
                        provider = webSearch.providerLabel
                    )
                )

                LlmProvider.GOOGLE -> WebSearchRuntime(
                    engine = TokenBudgetGuardedWebSearchEngine(
                        delegate = GeminiWebSearchEngine(
                            apiKey = webSearch.apiKey,
                            model = webSearch.model,
                            baseUrl = webSearch.baseUrl,
                            callObserver = callObserver,
                            instrumentation = instrumentation,
                            maxRawResponseChars = maxRawResponseChars
                        ),
                        budgetGate = tokenBudgetGate,
                        provider = webSearch.providerLabel
                    )
                )

                LlmProvider.OPENAI -> WebSearchRuntime(
                    engine = UnavailableWebSearchEngine(
                        "Web search provider 'openai' is not implemented. Use groq, mistral, or google for web_search."
                    )
                )
            }
        } catch (ex: Exception) {
            val detail = "Web search unavailable: ${ex.message ?: ex::class.simpleName ?: "initialization failed"}"
            logger.warn(ex) { detail }
            instrumentation.emit(AgentEvents.warning(detail))
            WebSearchRuntime(engine = UnavailableWebSearchEngine(detail))
        }
    }

    private class UnavailableWebSearchEngine(
        private val detail: String,
    ) : WebSearchEngine {
        override fun search(query: String, maxResults: Int): WebSearchResult =
            WebSearchResult(
                summary = detail,
                snippets = emptyList(),
                sources = emptyList()
            )

        override fun healthCheck(): WebSearchEngineHealth =
            WebSearchEngineHealth(
                available = false,
                detail = detail
            )
    }

    private class TokenBudgetGuardedWebSearchEngine(
        private val delegate: WebSearchEngine,
        private val budgetGate: LlmTokenBudgetGate?,
        private val provider: String,
    ) : WebSearchEngine {
        override fun search(query: String, maxResults: Int): WebSearchResult {
            budgetGate?.enforceEstimatedCall(
                provider = provider,
                role = LlmRoleLabels.WEB_SEARCH,
                estimatedPromptTokens = maxOf(
                    WEB_SEARCH_MIN_PROMPT_ESTIMATE_TOKENS,
                    query.length / WEB_SEARCH_PROMPT_DIVISOR
                ),
                requestedMaxCompletionTokens = WEB_SEARCH_COMPLETION_ESTIMATE_TOKENS
            )
            return delegate.search(query, maxResults)
        }

        override fun healthCheck(): WebSearchEngineHealth = delegate.healthCheck()

        companion object {
            private const val WEB_SEARCH_PROMPT_DIVISOR: Int = 3
            private const val WEB_SEARCH_MIN_PROMPT_ESTIMATE_TOKENS: Int = 64
            private const val WEB_SEARCH_COMPLETION_ESTIMATE_TOKENS: Int = 384
        }
    }

    private data class SuperegoReviewRouting(
        val primaryEndpoint: LlmEndpointConfig,
        val primaryTokenWeight: Double,
        val primaryContextWindow: Int? = null,
        val primaryReasoningOverhead: Double = 1.0,
        val escalationEndpoint: LlmEndpointConfig? = null,
        val escalationTokenWeight: Double? = null,
        val escalationContextWindow: Int? = null,
        val escalationReasoningOverhead: Double = 1.0,
    )

    private fun resolveSuperegoReviewRouting(
        llm: LlmRuntimeConfig,
        config: AgentConfig,
        instrumentation: psyke.instrumentation.AgentInstrumentation,
    ): SuperegoReviewRouting {
        val explicitPrimary = llm.superegoPrimary
        val explicitEscalation = llm.superegoEscalation

        // Case 1: Both superego_primary and superego_escalation are explicitly configured.
        if (explicitPrimary != null && explicitEscalation != null) {
            if (!config.superego.twoStageReviewEnabled) {
                instrumentation.emit(
                    AgentEvents.warning(
                        "superego_primary and superego_escalation are both configured but superego_two_stage_review_enabled=false; using superego_primary only as single-stage."
                    )
                )
                return SuperegoReviewRouting(
                    primaryEndpoint = explicitPrimary,
                    primaryTokenWeight = llm.modelCatalog.tokenWeightFor(explicitPrimary),
                    primaryContextWindow = llm.modelCatalog.contextWindowFor(explicitPrimary)
                )
            }
            instrumentation.emit(
                AgentEvent(
                    type = "superego_two_stage_routing",
                    data = mapOf(
                        "enabled" to true,
                        "selection_mode" to "explicit",
                        "primary_model" to explicitPrimary.model,
                        "primary_provider" to explicitPrimary.providerLabel,
                        "escalation_model" to explicitEscalation.model,
                        "escalation_provider" to explicitEscalation.providerLabel,
                        "low_confidence_threshold" to config.superego.twoStageLowConfidenceThreshold,
                        "escalate_on_medium_policy_risk" to config.superego.twoStageEscalateOnMediumPolicyRisk
                    )
                )
            )
            return SuperegoReviewRouting(
                primaryEndpoint = explicitPrimary,
                primaryTokenWeight = llm.modelCatalog.tokenWeightFor(explicitPrimary),
                primaryContextWindow = llm.modelCatalog.contextWindowFor(explicitPrimary),
                primaryReasoningOverhead = llm.modelCatalog.reasoningOverheadFor(explicitPrimary),
                escalationEndpoint = explicitEscalation,
                escalationTokenWeight = llm.modelCatalog.tokenWeightFor(explicitEscalation),
                escalationContextWindow = llm.modelCatalog.contextWindowFor(explicitEscalation),
                escalationReasoningOverhead = llm.modelCatalog.reasoningOverheadFor(explicitEscalation)
            )
        }

        // Case 2: Only superego_primary configured (no escalation) → single-stage.
        if (explicitPrimary != null) {
            return SuperegoReviewRouting(
                primaryEndpoint = explicitPrimary,
                primaryTokenWeight = llm.modelCatalog.tokenWeightFor(explicitPrimary),
                primaryContextWindow = llm.modelCatalog.contextWindowFor(explicitPrimary)
            )
        }

        // Case 3: Only superego_escalation configured (no primary) → single-stage with warning.
        if (explicitEscalation != null) {
            instrumentation.emit(
                AgentEvents.warning(
                    "superego_escalation configured without superego_primary; using superego_escalation as single-stage."
                )
            )
            return SuperegoReviewRouting(
                primaryEndpoint = explicitEscalation,
                primaryTokenWeight = llm.modelCatalog.tokenWeightFor(explicitEscalation),
                primaryContextWindow = llm.modelCatalog.contextWindowFor(explicitEscalation)
            )
        }

        // Case 4: Neither configured → legacy fallback using `superego` field.
        val legacyEndpoint = llm.superego
        if (config.superego.twoStageReviewEnabled) {
            instrumentation.emit(
                AgentEvents.warning(
                    "superego_two_stage_review_enabled=true but superego_primary/superego_escalation are not configured; " +
                        "add both to cognitive_roles in llm-runtime.yaml for two-stage review. Falling back to single-stage with superego model."
                )
            )
        }
        instrumentation.emit(
            AgentEvent(
                type = "superego_two_stage_routing",
                data = mapOf(
                    "enabled" to false,
                    "selection_mode" to "legacy_single_stage",
                    "primary_model" to legacyEndpoint.model,
                    "primary_provider" to legacyEndpoint.providerLabel
                )
            )
        )
        return SuperegoReviewRouting(
            primaryEndpoint = legacyEndpoint,
            primaryTokenWeight = llm.modelCatalog.tokenWeightFor(legacyEndpoint),
            primaryContextWindow = llm.modelCatalog.contextWindowFor(legacyEndpoint)
        )
    }
    
    private fun createChatClient(
        endpoint: LlmEndpointConfig,
        callObserver: psyke.llm.ChatCallObserver? = null,
    ): ChatModelClient {
        return when (endpoint.provider) {
            LlmProvider.GROQ -> GroqChatClient(
                apiKey = endpoint.apiKey,
                baseUrl = endpoint.baseUrl,
                modelName = endpoint.model,
                callObserver = callObserver
            )
    
            LlmProvider.MISTRAL -> MistralChatClient(
                apiKey = endpoint.apiKey,
                baseUrl = endpoint.baseUrl,
                modelName = endpoint.model,
                callObserver = callObserver
            )

            LlmProvider.GOOGLE -> GeminiChatClient(
                apiKey = endpoint.apiKey,
                baseUrl = endpoint.baseUrl,
                modelName = endpoint.model,
                callObserver = callObserver
            )

            LlmProvider.OPENAI -> OpenAiChatClient(
                apiKey = endpoint.apiKey,
                baseUrl = endpoint.baseUrl,
                modelName = endpoint.model,
                callObserver = callObserver
            )
        }
    }
    
    private fun resolveLlmCacheConfig(): Pair<LlmCacheMode, java.nio.file.Path?> {
        val mode = LlmCacheMode.parse(System.getenv("PSYKE_LLM_CACHE_MODE"))
        val file = System.getenv("PSYKE_LLM_CACHE_FILE")?.trim()?.ifBlank { null }
            ?.let { java.nio.file.Path.of(it) }
        return mode to file
    }

    private fun createMcpTimeTool(
        config: AgentConfig,
        capability: McpCapabilityConfig,
        scope: kotlinx.coroutines.CoroutineScope? = null,
    ): McpTimeTool {
        val command = resolveMcpCommand(capability)
        if (command == null) {
            val reason = disabledReason("time", capability)
            logger.info { reason }
            return DisabledMcpTimeTool(reason)
        }
        return SdkMcpTimeTool(
            command = command,
            callTimeoutMs = config.mcpCallTimeoutMs,
            scope = scope
        )
    }
    
    private fun createFetchTool(config: AgentConfig, capability: McpCapabilityConfig): FetchTool {
        if (!capability.enabled) {
            val reason = "Fetch capability disabled by configuration."
            logger.info { reason }
            return DisabledFetchTool(reason)
        }
        logger.info { "Using native JVM fetch tool (OkHttp + Jsoup). No external process." }
        return NativeFetchTool(
            callTimeoutMs = config.mcpCallTimeoutMs,
            maxChars = config.fetchMaxChars
        )
    }
    
    private fun createHippocampus(config: AgentConfig, capability: McpCapabilityConfig): Hippocampus {
        return createHippocampus(config = config, capability = capability, resolvedCommand = null)
    }

    private fun createHippocampus(
        config: AgentConfig,
        capability: McpCapabilityConfig,
        resolvedCommand: List<String>?,
    ): Hippocampus {
        val command = resolvedCommand ?: resolveMcpCommand(capability)
        if (command == null) {
            logger.info { disabledReason("memory", capability) }
            return NoopHippocampus
        }
        return McpHippocampus(
            command = command,
            callTimeoutMs = config.memory.mcpMemoryCallTimeoutMs,
            defaultMaxItems = config.memory.longTermMemoryRecallMaxItems,
            defaultMaxChars = config.memory.longTermMemoryRecallMaxChars
        )
    }

    private fun resolveInteractiveMemoryStartup(
        config: AgentConfig,
        capability: McpCapabilityConfig,
    ): InteractiveMemoryStartup {
        val command = resolveMcpCommand(capability)
        if (command == null) {
            return InteractiveMemoryStartup(
                hippocampus = NoopHippocampus,
                detail = disabledReason("memory", capability)
            )
        }

        val memoryHealthy = checkMcpMemoryProviderHealth(
            command = command,
            timeoutMs = config.memory.mcpMemoryCallTimeoutMs,
            modeLabel = "interactive_memory"
        )
        if (!memoryHealthy) {
            return InteractiveMemoryStartup(
                hippocampus = NoopHippocampus,
                detail = "MCP memory startup health check failed; long-term memory disabled for this run."
            )
        }

        val hippocampus = createHippocampus(
            config = config,
            capability = capability,
            resolvedCommand = command
        )
        return InteractiveMemoryStartup(
            hippocampus = hippocampus,
            detail = "Provider: ${hippocampus.providerName} (${capability.provider})"
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
    
    private fun createLogbookIfEnabled(config: AgentConfig): Logbook? {
        if (!config.logbook.enabled) return null
        return try {
            SqliteLogbook(config.logbook)
        } catch (ex: Exception) {
            logger.warn(ex) { "Episodic logbook initialization failed; continuing without." }
            null
        }
    }

    private fun createLogbookSummarizer(
        config: AgentConfig,
        memoryClient: psyke.llm.ChatModelClient,
    ): psyke.agent.memory.episodic.LogbookSummarizer {
        if (!config.logbook.useLlmSummarizer) {
            return psyke.agent.memory.episodic.DeterministicLogbookSummarizer(config.logbook)
        }
        return psyke.agent.memory.episodic.LlmLogbookSummarizer(
            modelClient = memoryClient,
            config = config,
        )
    }

    private fun executeLongTermMemoryClear(
        cliOptions: AppCliOptions,
        hippocampus: Hippocampus,
        logbook: psyke.agent.memory.episodic.Logbook?,
        instrumentation: psyke.instrumentation.AgentInstrumentation,
    ) {
        val clearVector = cliOptions.clearMemoryAll || cliOptions.clearMemoryVector
        val clearEpisodic = cliOptions.clearMemoryAll || cliOptions.clearMemoryEpisodic
        val clearLessons = cliOptions.clearMemoryLessons && !clearVector

        if (clearVector) {
            if (hippocampus.enabled) {
                output.info("Clearing vector/hippocampus memory...")
                try {
                    val deleted = hippocampus.clearAll()
                    output.info("Vector memory cleared ($deleted observations removed).")
                    logger.info { "CLI --clear-memory: vector/hippocampus cleared, $deleted observations removed." }
                    instrumentation.emit(
                        psyke.instrumentation.AgentEvents.warning(
                            "Memory cleared via CLI: vector/hippocampus ($deleted observations removed)."
                        )
                    )
                } catch (ex: Exception) {
                    output.error("Failed to clear vector memory: ${ex.message}")
                    logger.warn(ex) { "CLI --clear-memory: vector/hippocampus clear failed." }
                }
            } else {
                output.info("Vector memory is not enabled; skipping --clear-memory-vector.")
            }
        }

        if (clearLessons) {
            if (hippocampus.enabled) {
                output.info("Clearing lessons from vector memory...")
                try {
                    val deleted = hippocampus.purgeTaggedObservations(listOf("kind:lesson"))
                    output.info("Lessons cleared ($deleted observations removed).")
                    logger.info { "CLI --clear-memory: lessons cleared, $deleted observations removed." }
                    instrumentation.emit(
                        psyke.instrumentation.AgentEvents.warning(
                            "Memory cleared via CLI: lessons ($deleted observations removed)."
                        )
                    )
                } catch (ex: Exception) {
                    output.error("Failed to clear lessons: ${ex.message}")
                    logger.warn(ex) { "CLI --clear-memory: lessons clear failed." }
                }
            } else {
                output.info("Vector memory is not enabled; skipping --clear-memory-lessons.")
            }
        }

        if (clearEpisodic) {
            if (logbook != null) {
                output.info("Clearing episodic logbook memory...")
                try {
                    val deleted = logbook.clearAll()
                    output.info("Episodic logbook cleared ($deleted entries removed).")
                    logger.info { "CLI --clear-memory: episodic logbook cleared, $deleted entries removed." }
                    instrumentation.emit(
                        psyke.instrumentation.AgentEvents.warning(
                            "Memory cleared via CLI: episodic logbook ($deleted entries removed)."
                        )
                    )
                } catch (ex: Exception) {
                    output.error("Failed to clear episodic logbook: ${ex.message}")
                    logger.warn(ex) { "CLI --clear-memory: episodic logbook clear failed." }
                }
            } else {
                output.info("Episodic logbook is not enabled; skipping --clear-memory-episodic.")
            }
        }
    }

    private fun closeQuietly(value: Any?) {
        val closeable = value as? AutoCloseable ?: return
        try {
            closeable.close()
        } catch (_: Exception) {
            // ignore best-effort shutdown
        }
    }

    private fun localPostgresStatus(): String {
        val host = "127.0.0.1"
        val port = 5432
        val timeoutMs = 500
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            }
            "available at localhost:5432"
        } catch (_: Exception) {
            "unavailable at localhost:5432"
        }
    }

    private data class InteractiveMemoryStartup(
        val hippocampus: Hippocampus,
        val detail: String,
    )
    
    private class DisabledMcpTimeTool(
        private val reason: String,
    ) : McpTimeTool, AutoCloseable {
        override suspend fun getCurrentTime(payload: String): String =
            "MCP time unavailable: $reason"

        override suspend fun healthCheck(): ToolHealthStatus =
            ToolHealthStatus(
                available = false,
                detail = reason
            )

        override fun close() {}
    }

    private class DisabledFetchTool(
        private val reason: String,
    ) : FetchTool, AutoCloseable {
        override suspend fun fetch(payload: String): String =
            "Fetch unavailable: $reason"

        override suspend fun healthCheck(): ToolHealthStatus =
            ToolHealthStatus(
                available = false,
                detail = reason
            )

        override fun close() {}
    }

    private fun dumpEndOfRunMetrics(metrics: psyke.metrics.MetricsRuntime) {
        if (!logger.isTraceEnabled) return
        try {
            val snapshot = metrics.snapshot() ?: return
            val run = snapshot.runTotals
            val all = snapshot.persistentTotals
            val sb = StringBuilder(2048)
            sb.appendLine()
            sb.appendLine("=== PSYKE END-OF-RUN METRICS ===")
            sb.appendLine("run_id=${snapshot.runId}")
            sb.appendLine("provider=${snapshot.provider}")
            sb.appendLine("key_fingerprint=${snapshot.keyFingerprint}")
            sb.appendLine("run_count_for_scope=${snapshot.runCountForScope}")
            sb.appendLine()
            sb.appendLine("-- Run Totals --")
            sb.appendLine("calls=${run.calls} tokens=${run.totalTokens} denied=${run.deniedActions} errors=${run.errorCount}")
            sb.appendLine("queue_saturation=${run.queueSaturationEvents} dropped=${run.droppedEvents}")
            sb.appendLine("planner_noops=${run.plannerNoopCount} planner_repaired=${run.plannerOutputRepairedCount}")
            sb.appendLine("memory_recall: attempts=${run.memoryRecallAttempts} hits=${run.memoryRecallHits} failures=${run.memoryRecallFailures} skipped=${run.longTermMemoryRecallSkipped}")
            sb.appendLine("memory_imprint: attempts=${run.memoryImprintAttempts} saved=${run.memoryImprintSaved} failures=${run.memoryImprintFailures}")
            sb.appendLine("ltm_assessments=${run.memoryConsolidationAssessments} save_recommended=${run.memoryConsolidationSaveRecommended} parse_failures=${run.memoryConsolidationParseFailures}")
            if (run.responseLatencyCount > 0) {
                val avgLatency = run.responseLatencySumMs / run.responseLatencyCount
                sb.appendLine("response_latency: count=${run.responseLatencyCount} avg=${avgLatency}ms median=${run.medianEndToEndResponseLatencyMs ?: "-"}ms")
            }
            sb.appendLine()
            sb.appendLine("-- Persistent Totals --")
            sb.appendLine("calls=${all.calls} tokens=${all.totalTokens} denied=${all.deniedActions} errors=${all.errorCount}")
            sb.appendLine()
            sb.appendLine("-- Tokens by Role (run) --")
            snapshot.runTokensByRole.entries.sortedByDescending { it.value }.forEach { (role, tokens) ->
                val models = snapshot.runModelsByRole[role]?.joinToString(", ") ?: ""
                sb.appendLine("  $role: $tokens tokens${if (models.isNotEmpty()) "  models=[$models]" else ""}")
            }
            sb.appendLine()
            sb.appendLine("-- Tokens by Model (run) --")
            snapshot.runTokensByModel.entries.sortedByDescending { it.value }.forEach { (model, tokens) ->
                sb.appendLine("  $model: $tokens tokens")
            }
            sb.appendLine()
            sb.appendLine("-- Action Calls (run) --")
            snapshot.runActionCallsByType.entries.sortedByDescending { it.value }.forEach { (type, count) ->
                sb.appendLine("  $type: $count")
            }

            if (metrics is MetricsQueryProvider) {
                try {
                    val llmStats = metrics.llmCallStats(runOnly = true)
                    if (llmStats.byModel.isNotEmpty()) {
                        sb.appendLine()
                        sb.appendLine("-- LLM Call Stats (run) --")
                        llmStats.byModel.entries.sortedByDescending { it.value.callCount }.forEach { (model, stats) ->
                            sb.appendLine("  $model: calls=${stats.callCount} avg_latency=${stats.avgLatencyMs}ms p50=${stats.p50LatencyMs}ms p95=${stats.p95LatencyMs}ms errors=${stats.errorCount} tokens=${stats.totalTokens} (prompt=${stats.promptTokens} completion=${stats.completionTokens})")
                        }
                    }
                    if (llmStats.errorBreakdown.isNotEmpty()) {
                        sb.appendLine()
                        sb.appendLine("-- Error Breakdown (run) --")
                        llmStats.errorBreakdown.forEach { (model, errors) ->
                            errors.forEach { (code, count) ->
                                sb.appendLine("  $model: $code=$count")
                            }
                        }
                    }
                } catch (ex: Exception) {
                    sb.appendLine("  (llm stats query failed: ${ex.message})")
                }
            }

            sb.appendLine("=== END METRICS ===")
            logger.trace { sb.toString() }
        } catch (ex: Exception) {
            logger.trace { "Failed to dump end-of-run metrics: ${ex.message}" }
        }
    }
}
