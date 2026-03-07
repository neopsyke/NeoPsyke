package psyke

import mu.KotlinLogging
import psyke.agent.core.AgentConfig
import psyke.agent.ego.Ego
import psyke.agent.ego.LlmEgoPlanner
import psyke.agent.memory.episodic.Logbook
import psyke.agent.memory.episodic.SqliteLogbook
import psyke.agent.memory.longterm.Hippocampus
import psyke.agent.ego.LlmMetaReasoner
import psyke.agent.memory.longterm.LlmLongTermMemoryAdvisor
import psyke.agent.memory.longterm.McpHippocampus
import psyke.agent.tools.mcp.McpStdioClient
import psyke.agent.core.ActionType
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
import psyke.instrumentation.MetricsEventSink
import psyke.instrumentation.MetricsSnapshotObserver
import psyke.instrumentation.ReasoningEvalFlowLogSink
import psyke.instrumentation.StructuredLogSink
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
import psyke.llm.combineChatCallObservers
import psyke.llm.reportProviderStatusAndDecide
import psyke.integrations.groq.websearch.GroqConversationsWebSearchEngine
import psyke.integrations.mistral.websearch.MistralConversationsWebSearchEngine
import psyke.integrations.mistral.websearch.MistralWebSearchMode
import psyke.integrations.mistral.websearch.MistralWebSearchProfile
import psyke.integrations.mistral.websearch.MistralWebSearchAgentSession
import psyke.metrics.MetricsRuntimeFactory
import psyke.agent.tools.mcp.FetchTool
import psyke.agent.tools.mcp.McpTimeTool
import psyke.agent.tools.mcp.ToolHealthStatus
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private val logger = KotlinLogging.logger {}
private val output: ConsoleReporter = StdConsoleReporter

internal object AppModeRunners {
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
        InstrumentationBus(
            sinks = sinks,
            criticalSinks = listOfNotNull(sidecarSink)
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
        InstrumentationBus(
            sinks = sinks,
            criticalSinks = listOfNotNull(sidecarSink)
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
    
    private fun checkProviderHealth(
        endpoint: LlmEndpointConfig,
        modeLabel: String,
        roleLabel: String,
    ): Boolean {
        if (endpoint.apiKey.isBlank()) {
            return reportProviderStatusAndDecide(
                modeLabel = "$modeLabel:$roleLabel",
                status = ProviderStatus(
                    provider = endpoint.providerLabel,
                    state = ProviderHealthState.UNAVAILABLE,
                    detail = "${endpoint.apiKeyEnvVar} is missing."
                )
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
        val status = checker.check()
        return reportProviderStatusAndDecide(
            modeLabel = "$modeLabel:$roleLabel",
            status = status
        )
    }

    private fun checkInteractiveLlmHealth(
        llm: LlmRuntimeConfig,
        modeLabel: String,
    ): Boolean {
        val endpoints = listOf(
            "planner" to llm.planner,
            "action_verifier" to llm.actionVerifier,
            "superego" to llm.superego,
            "meta_reasoner" to llm.metaReasoner,
            "memory_advisor" to llm.memoryAdvisor
        )
        for ((roleLabel, endpoint) in endpoints) {
            if (!checkProviderHealth(endpoint = endpoint, modeLabel = modeLabel, roleLabel = roleLabel)) {
                return false
            }
        }
        return true
    }

    private fun resolveMetricsProviderLabel(llm: LlmRuntimeConfig): String {
        val providers = linkedSetOf(
            llm.planner.providerLabel,
            llm.actionVerifier.providerLabel,
            llm.superego.providerLabel,
            llm.metaReasoner.providerLabel,
            llm.memoryAdvisor.providerLabel
        )
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
    ) {
        if (!checkInteractiveLlmHealth(llm = llm, modeLabel = "interactive")) {
            return
        }
        val dashboardPort = runtimeSettings.dashboardPort
        val dashboardEnabled = runtimeSettings.dashboardEnabled
    
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
                                "max_loop_steps" to config.planner.maxLoopStepsPerInput,
                                "loop_delay_ms" to config.loopDelayMs,
                                "max_thought_passes" to config.planner.maxThoughtPasses,
                                "max_prompt_tokens" to config.planner.maxPromptTokens,
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
                                "max_action_payload_chars" to config.planner.maxActionPayloadChars,
                                "max_action_summary_chars" to config.planner.maxActionSummaryChars,
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
                        maxRawResponseChars = config.planner.maxActionPayloadChars
                    )
    
                    InstrumentedChatModelClient(
                        delegate = TokenBudgetGuardedChatClient(
                            delegate = createChatClient(
                                endpoint = llm.planner,
                                callObserver = callObserverForProvider(llm.planner.providerLabel)
                            ),
                            budgetGate = tokenBudgetGate,
                            provider = llm.planner.providerLabel,
                            role = LlmRoleLabels.PLANNER
                        ),
                        hooks = listOf(rawResponseHook)
                    ).use { plannerClient ->
                        InstrumentedChatModelClient(
                            delegate = TokenBudgetGuardedChatClient(
                                delegate = createChatClient(
                                    endpoint = llm.actionVerifier,
                                    callObserver = callObserverForProvider(llm.actionVerifier.providerLabel)
                                ),
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
                                    delegate = createChatClient(
                                        endpoint = superegoReviewRouting.primaryEndpoint,
                                        callObserver = callObserverForProvider(superegoReviewRouting.primaryEndpoint.providerLabel)
                                    ),
                                    budgetGate = tokenBudgetGate,
                                    provider = superegoReviewRouting.primaryEndpoint.providerLabel,
                                    role = LlmRoleLabels.SUPEREGO
                                ),
                                hooks = listOf(rawResponseHook)
                            ).use { superegoClient ->
                                val superegoEscalationClient = superegoReviewRouting.escalationEndpoint?.let { escalationEndpoint ->
                                    InstrumentedChatModelClient(
                                        delegate = TokenBudgetGuardedChatClient(
                                            delegate = createChatClient(
                                                endpoint = escalationEndpoint,
                                                callObserver = callObserverForProvider(escalationEndpoint.providerLabel)
                                            ),
                                            budgetGate = tokenBudgetGate,
                                            provider = escalationEndpoint.providerLabel,
                                            role = LlmRoleLabels.SUPEREGO
                                        ),
                                        hooks = listOf(rawResponseHook)
                                    )
                                }
                                InstrumentedChatModelClient(
                                    delegate = TokenBudgetGuardedChatClient(
                                        delegate = createChatClient(
                                            endpoint = llm.metaReasoner,
                                            callObserver = callObserverForProvider(llm.metaReasoner.providerLabel)
                                        ),
                                        budgetGate = tokenBudgetGate,
                                        provider = llm.metaReasoner.providerLabel,
                                        role = LlmRoleLabels.META_REASONER
                                    ),
                                    hooks = listOf(rawResponseHook)
                                ).use { metaReasonerClient ->
                                    InstrumentedChatModelClient(
                                        delegate = TokenBudgetGuardedChatClient(
                                            delegate = createChatClient(
                                                endpoint = llm.memoryAdvisor,
                                                callObserver = callObserverForProvider(llm.memoryAdvisor.providerLabel)
                                            ),
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
                                                "memory_advisor=${llm.memoryAdvisor.providerLabel}/${llm.memoryAdvisor.model}, " +
                                                "web_search=${llm.webSearch.providerLabel}/${llm.webSearch.model}"
                                        }
                                        try {
                                            val gatekeeper = Superego(
                                                modelClient = superegoClient,
                                                config = config,
                                                modelTokenWeight = superegoReviewRouting.primaryTokenWeight,
                                                escalationModelClient = superegoEscalationClient,
                                                escalationModelTokenWeight = superegoReviewRouting.escalationTokenWeight
                                                    ?: superegoReviewRouting.primaryTokenWeight,
                                                instrumentation = instrumentation
                                            )
                                            val mcpTimeTool = createMcpTimeTool(config, mcpRuntimeConfig.time)
                                            val fetchTool = createFetchTool(config, mcpRuntimeConfig.fetch)
                                            val webSearchRuntime = createWebSearchRuntime(
                                                llm = llm,
                                                callObserver = webSearchCallObserver,
                                                instrumentation = instrumentation,
                                                maxRawResponseChars = config.planner.maxActionPayloadChars,
                                                tokenBudgetGate = tokenBudgetGate
                                            )

                                            webSearchRuntime.use { runtime ->
                                                val timeTool = mcpTimeTool
                                                val activeFetchTool = fetchTool
                                                try {
                                                    val webSearchActionHandler = WebSearchActionHandler(runtime.engine)
                                                    val motorCortex = MotorCortex(
                                                        webSearchActionHandler = webSearchActionHandler,
                                                        mcpTimeTool = timeTool,
                                                        fetchTool = activeFetchTool
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
                                                    val planner = LlmEgoPlanner(
                                                        modelClient = plannerClient,
                                                        actionVerifierModelClient = actionVerifierClient,
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
                                                    val metaReasoner = LlmMetaReasoner(
                                                        modelClient = metaReasonerClient,
                                                        config = config
                                                    )
                                                    val longTermMemoryAdvisor = LlmLongTermMemoryAdvisor(
                                                        modelClient = longTermMemoryClient,
                                                        config = config,
                                                        modelTokenWeight = llm.modelCatalog.tokenWeightFor(llm.memoryAdvisor),
                                                        instrumentation = instrumentation
                                                    )
                                                    val interactiveMemoryStartup =
                                                        resolveInteractiveMemoryStartup(config, mcpRuntimeConfig.memory)
                                                    val hippocampus = interactiveMemoryStartup.hippocampus
                                                    val memoryProviderDetail = interactiveMemoryStartup.detail
                                                    val allStatuses = actionStatuses + ActionImplementationStatus(
                                                        actionType = ActionType.MEMORY,
                                                        available = hippocampus.enabled,
                                                        detail = memoryProviderDetail,
                                                    )
                                                    instrumentation.emit(AgentEvents.actionCapabilities(allStatuses))
                                                    if (!hippocampus.enabled) {
                                                        instrumentation.emit(
                                                            AgentEvents.warning("Long-term memory is unavailable: $memoryProviderDetail")
                                                        )
                                                    }
                                                    val logbook = createLogbookIfEnabled(config)
                                                    val logbookSummarizer = createLogbookSummarizer(config, longTermMemoryClient)
                                                    try {
                                                        Ego(
                                                            planner = planner,
                                                            superego = gatekeeper,
                                                            motorCortex = motorCortex,
                                                            config = config,
                                                            hippocampus = hippocampus,
                                                            metaReasoner = metaReasoner,
                                                            longTermMemoryAdvisor = longTermMemoryAdvisor,
                                                            instrumentation = instrumentation,
                                                            logbook = logbook,
                                                            logbookSummarizer = logbookSummarizer,
                                                        ).runInteractive()
                                                    } finally {
                                                        hippocampus.close()
                                                        closeQuietly(logbook)
                                                    }
                                                } finally {
                                                    closeQuietly(activeFetchTool)
                                                    closeQuietly(timeTool)
                                                }
                                            }
                                        } finally {
                                            superegoEscalationClient?.close()
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
        val escalationEndpoint: LlmEndpointConfig? = null,
        val escalationTokenWeight: Double? = null,
    )

    private fun resolveSuperegoReviewRouting(
        llm: LlmRuntimeConfig,
        config: AgentConfig,
        instrumentation: psyke.instrumentation.AgentInstrumentation,
    ): SuperegoReviewRouting {
        val escalationEndpoint = llm.superego
        val escalationWeight = llm.modelCatalog.tokenWeightFor(escalationEndpoint)
        if (!config.superego.twoStageReviewEnabled) {
            return SuperegoReviewRouting(
                primaryEndpoint = escalationEndpoint,
                primaryTokenWeight = escalationWeight
            )
        }
        val cheapPrimary = llm.modelCatalog.cheapestProfileForProvider(
            provider = escalationEndpoint.provider,
            excludingModel = escalationEndpoint.model
        )
        if (cheapPrimary == null) {
            instrumentation.emit(
                AgentEvents.warning(
                    "Superego two-stage review enabled but no cheaper model found in catalog for provider=${escalationEndpoint.providerLabel}; using single-stage."
                )
            )
            return SuperegoReviewRouting(
                primaryEndpoint = escalationEndpoint,
                primaryTokenWeight = escalationWeight
            )
        }
        val primaryEndpoint = escalationEndpoint.copy(model = cheapPrimary.model)
        instrumentation.emit(
            AgentEvent(
                type = "superego_two_stage_routing",
                data = mapOf(
                    "enabled" to true,
                    "primary_model" to primaryEndpoint.model,
                    "escalation_model" to escalationEndpoint.model,
                    "provider" to escalationEndpoint.providerLabel,
                    "low_confidence_threshold" to config.superego.twoStageLowConfidenceThreshold,
                    "escalate_on_medium_policy_risk" to config.superego.twoStageEscalateOnMediumPolicyRisk
                )
            )
        )
        return SuperegoReviewRouting(
            primaryEndpoint = primaryEndpoint,
            primaryTokenWeight = cheapPrimary.tokenWeight,
            escalationEndpoint = escalationEndpoint,
            escalationTokenWeight = escalationWeight
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
        override fun getCurrentTime(payload: String): String =
            "MCP time unavailable: $reason"
    
        override fun healthCheck(): ToolHealthStatus =
            ToolHealthStatus(
                available = false,
                detail = reason
            )
    
        override fun close() {}
    }
    
    private class DisabledFetchTool(
        private val reason: String,
    ) : FetchTool, AutoCloseable {
        override fun fetch(payload: String): String =
            "Fetch unavailable: $reason"

        override fun healthCheck(): ToolHealthStatus =
            ToolHealthStatus(
                available = false,
                detail = reason
            )

        override fun close() {}
    }
}
