package ai.neopsyke

import mu.KotlinLogging
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.config.TelegramIngressMode
import ai.neopsyke.agent.ego.Ego
import ai.neopsyke.agent.ego.EgoAssembler
import ai.neopsyke.agent.ego.LlmEgoPlanner
import ai.neopsyke.agent.ego.LlmScratchpadFinalizer
import ai.neopsyke.agent.memory.longterm.Logbook
import ai.neopsyke.agent.memory.longterm.SqliteLogbook
import ai.neopsyke.agent.memory.longterm.DeterministicLogbookSummarizer
import ai.neopsyke.agent.memory.longterm.Hippocampus
import ai.neopsyke.agent.memory.longterm.HippocampusAdmin
import ai.neopsyke.agent.memory.longterm.ForgetRequest
import ai.neopsyke.agent.ego.LlmMetaReasoner
import ai.neopsyke.agent.ego.NoopScratchpadFinalizer
import ai.neopsyke.agent.memory.longterm.LlmLongTermMemoryAdvisor
import ai.neopsyke.agent.memory.provider.HttpMemoryProviderClient
import ai.neopsyke.agent.memory.provider.DefaultMemoryProviderInstaller
import ai.neopsyke.agent.memory.provider.ManagedHttpMemoryProviderProcess
import ai.neopsyke.agent.memory.provider.ProviderBackedHippocampus
import ai.neopsyke.agent.tools.mcp.McpStdioClient
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.ConversationSecurityContexts
import ai.neopsyke.agent.model.Interlocutor
import ai.neopsyke.agent.cortex.sensory.AsyncSignalSource
import ai.neopsyke.agent.cortex.sensory.SensoryCortex
import ai.neopsyke.agent.cortex.motor.ActionImplementationStatus
import ai.neopsyke.agent.cortex.motor.MotorCortex
import ai.neopsyke.agent.memory.longterm.NoopHippocampus
import ai.neopsyke.agent.memory.longterm.ResetRequest
import ai.neopsyke.agent.tools.mcp.NativeFetchTool
import ai.neopsyke.agent.superego.Superego
import ai.neopsyke.agent.actions.websearch.WebSearchActionHandler
import ai.neopsyke.agent.actions.websearch.WebSearchEngine
import ai.neopsyke.agent.actions.websearch.WebSearchEngineHealth
import ai.neopsyke.agent.actions.websearch.WebSearchResult
import ai.neopsyke.agent.actions.EnvActionSecretProvider
import ai.neopsyke.agent.actions.RoutedConversationOutputGateway
import ai.neopsyke.agent.actions.SecretHandle
import ai.neopsyke.config.AgentRuntimeSettings
import ai.neopsyke.config.LlmEndpointConfig
import ai.neopsyke.config.LlmProvider
import ai.neopsyke.config.LlmRuntimeConfig
import ai.neopsyke.config.MemoryMode
import ai.neopsyke.config.MemoryRuntimeConfig
import ai.neopsyke.dashboard.DashboardServer
import ai.neopsyke.dashboard.DashboardStateStore
import ai.neopsyke.dashboard.ChatRuntimeBridge
import ai.neopsyke.dashboard.IdInnerVoiceFileSink
import ai.neopsyke.dashboard.InnerVoiceSink
import ai.neopsyke.dashboard.InnerVoiceStore
import ai.neopsyke.eval.MemoryLiveEvalOptions
import ai.neopsyke.eval.MemoryLiveEvalReporter
import ai.neopsyke.eval.MemoryLiveEvalRunner
import ai.neopsyke.eval.MemoryLiveEvalTasks
import ai.neopsyke.eval.ReasoningEvalOptions
import ai.neopsyke.eval.ReasoningEvalReporter
import ai.neopsyke.eval.ReasoningEvalMode
import ai.neopsyke.eval.ReasoningEvalTasks
import ai.neopsyke.eval.ReasoningBehavioralLogicEvalTasks
import ai.neopsyke.eval.ReasoningLogicEvalTasks
import ai.neopsyke.eval.ReasoningLogicHarnessClient
import ai.neopsyke.eval.ReasoningSelfEvalRunner
import ai.neopsyke.eval.UsageTrackingChatClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import ai.neopsyke.async.agentScope
import ai.neopsyke.instrumentation.AgentEvent
import ai.neopsyke.instrumentation.AgentEvents
import ai.neopsyke.instrumentation.InstrumentationBus
import ai.neopsyke.instrumentation.JsonlEventSink
import ai.neopsyke.instrumentation.LlmCallEventObserver
import ai.neopsyke.instrumentation.LlmRawResponseEventHook
import ai.neopsyke.instrumentation.MemoryEvalFlowLogSink
import ai.neopsyke.instrumentation.MetricsEventSink
import ai.neopsyke.instrumentation.MetricsSnapshotObserver
import ai.neopsyke.instrumentation.ReasoningEvalFlowLogSink
import ai.neopsyke.instrumentation.StructuredLogSink
import ai.neopsyke.instrumentation.ScratchpadDumpSink
import ai.neopsyke.llm.AdaptiveStructuredOutputChatClient
import ai.neopsyke.llm.InstrumentedChatModelClient
import ai.neopsyke.llm.ChatModelClient
import ai.neopsyke.integrations.google.websearch.GeminiWebSearchEngine
import ai.neopsyke.integrations.google.GoogleWorkspaceApiClient
import ai.neopsyke.integrations.google.GoogleWorkspaceCredentialStore
import ai.neopsyke.integrations.google.GoogleWorkspaceOAuthBridge
import ai.neopsyke.integrations.auth.OAuthPendingAuthorizationStore
import ai.neopsyke.integrations.auth.OAuthStateCodec
import ai.neopsyke.llm.AnthropicChatClient
import ai.neopsyke.llm.AnthropicProviderStatusChecker
import ai.neopsyke.llm.GeminiChatClient
import ai.neopsyke.llm.GeminiProviderStatusChecker
import ai.neopsyke.llm.GroqChatClient
import ai.neopsyke.llm.GroqProviderStatusChecker
import ai.neopsyke.llm.LlmRoleLabels
import ai.neopsyke.llm.LlmTokenBudgetConfig
import ai.neopsyke.llm.LlmTokenBudgetGate
import ai.neopsyke.llm.MistralChatClient
import ai.neopsyke.llm.MistralProviderStatusChecker
import ai.neopsyke.llm.OpenAiChatClient
import ai.neopsyke.llm.OpenAiProviderStatusChecker
import ai.neopsyke.llm.OllamaChatClient
import ai.neopsyke.llm.OllamaProviderStatusChecker
import ai.neopsyke.llm.ProviderHealthState
import ai.neopsyke.llm.ProviderStatus
import ai.neopsyke.llm.TokenBudgetGuardedChatClient
import ai.neopsyke.llm.LlmCacheMode
import ai.neopsyke.llm.LlmCacheManager
import ai.neopsyke.llm.combineChatCallObservers
import ai.neopsyke.llm.isRetryableProviderHealthFailure
import ai.neopsyke.llm.reportProviderStatusAndDecide
import ai.neopsyke.integrations.groq.websearch.GroqConversationsWebSearchEngine
import ai.neopsyke.integrations.mistral.websearch.MistralConversationsWebSearchEngine
import ai.neopsyke.integrations.mistral.websearch.MistralWebSearchMode
import ai.neopsyke.integrations.mistral.websearch.MistralWebSearchProfile
import ai.neopsyke.integrations.mistral.websearch.MistralWebSearchAgentSession
import ai.neopsyke.integrations.telegram.TelegramBotApiClient
import ai.neopsyke.integrations.telegram.TelegramPollingBridge
import ai.neopsyke.integrations.telegram.TelegramUpdateProcessor
import ai.neopsyke.integrations.telegram.TelegramWebhookBridge
import ai.neopsyke.metrics.MetricsQueryProvider
import ai.neopsyke.metrics.MetricsRuntimeFactory
import ai.neopsyke.agent.tools.mcp.FetchTool
import ai.neopsyke.agent.tools.mcp.ToolHealthStatus
import kotlin.system.exitProcess
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private val logger = KotlinLogging.logger {}
private val output: ConsoleReporter = StdConsoleReporter
private const val PROVIDER_HEALTH_CHECK_MAX_ATTEMPTS: Int = 2
private const val PROVIDER_HEALTH_CHECK_RETRY_DELAY_MS: Long = 250L
private const val FREUD_LIVE_SESSION_ID: String = "freud-live"
private const val FREUD_LIVE_INTERLOCUTOR_ID: String = "freud-live-user"

internal object AppModeRunners {
    private data class InteractiveLlmStartupConfig(
        val metaReasonerFallback: LlmEndpointConfig?,
    )

    internal fun freudLiveConversationContext(
        sessionId: String = FREUD_LIVE_SESSION_ID,
    ): ConversationContext =
        ConversationContext(
            sessionId = sessionId,
            interlocutor = Interlocutor.named(FREUD_LIVE_INTERLOCUTOR_ID),
            security = ConversationSecurityContexts.ownerDirect(
                provider = "freud-live",
                channelId = sessionId,
            ),
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
        val evalScope = agentScope("neopsyke-reasoning-eval")
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
                    output.info("History: .neopsyke/evals/reasoning/history.jsonl")
                }
            }
        }
    }
    
    internal fun runMemoryLiveEval(
        llm: LlmRuntimeConfig,
        config: AgentConfig,
        memoryRuntimeConfig: MemoryRuntimeConfig,
        cliOptions: AppCliOptions,
        runtimeSettings: AgentRuntimeSettings,
    ) {
        output.info("Running memory live eval (real LLM + real long-term memory provider)...")
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
        val hippocampus = try {
            createHippocampus(config = config, runtimeConfig = memoryRuntimeConfig)
        } catch (ex: Exception) {
            output.error("Memory provider startup failed for --eval-memory-live: ${ex.message}")
            logger.warn(ex) { "Memory provider startup failed for --eval-memory-live." }
            return
        }
        if (!hippocampus.enabled) {
            output.error(
                "A non-off memory provider is required for --eval-memory-live. Current mode=${memoryRuntimeConfig.mode.name.lowercase()}."
            )
            logger.warn { "Memory live eval requires an enabled memory provider. mode=${memoryRuntimeConfig.mode}" }
            closeQuietly(hippocampus)
            return
        }
    
        val shutdownGuard = ShutdownCloseGuard("memory-live").apply {
            register(hippocampus)
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
        val evalScope = agentScope("neopsyke-memory-eval")
        try {
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
                    hippocampus.use { activeHippocampus ->
                        val report = MemoryLiveEvalRunner(
                            client = client,
                            longTermMemoryAdvisor = LlmLongTermMemoryAdvisor(
                                modelClient = client,
                                config = config,
                                modelTokenWeight = llm.modelCatalog.tokenWeightFor(llm.planner),
                                modelContextWindow = llm.modelCatalog.contextWindowFor(llm.planner),
                                instrumentation = instrumentation
                            ),
                            hippocampus = activeHippocampus,
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
                        output.info("History: .neopsyke/evals/memory-live/history.jsonl")
                    }
                }
            }
        }
        } finally {
            shutdownGuard.close()
        }
    }
    
    private fun resolveEvalEventSidecarPath(): Path? {
        val explicit = System.getenv("NEOPSYKE_EVENT_LOG_FILE")?.trim().orEmpty()
        if (explicit.isNotBlank()) {
            return Path.of(explicit)
        }
        val runLogFile = System.getenv("NEOPSYKE_LOG_FILE")?.trim().orEmpty()
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

    private fun resolveIdInnerVoiceFilePath(): Path? {
        val runLogFile = System.getenv("NEOPSYKE_LOG_FILE")?.trim().orEmpty()
        if (runLogFile.isBlank()) return null
        val logPath = Path.of(runLogFile)
        val logName = logPath.fileName?.toString().orEmpty()
        if (logName.isBlank()) return null
        val baseName = if (logName.endsWith(".log")) logName.removeSuffix(".log") else logName
        return logPath.resolveSibling("$baseName.id-inner-voice-${System.currentTimeMillis()}.jsonl")
    }

    private fun resolveProviderHealthStatus(
        endpoint: LlmEndpointConfig,
        modeLabel: String,
        roleLabel: String,
    ): ProviderStatus {
        if (endpoint.provider.requiresApiKey() && endpoint.apiKey.isBlank()) {
            return ProviderStatus(
                provider = endpoint.providerLabel,
                state = ProviderHealthState.UNAVAILABLE,
                detail = "${endpoint.apiKeyEnvVar} is missing."
            )
        }
        val checker = when (endpoint.provider) {
            LlmProvider.ANTHROPIC -> AnthropicProviderStatusChecker(
                apiKey = endpoint.apiKey,
                baseUrl = endpoint.baseUrl
            )

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

            LlmProvider.OLLAMA -> OllamaProviderStatusChecker(
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
    
    internal fun runInteractiveMode(
        llm: LlmRuntimeConfig,
        config: AgentConfig,
        memoryRuntimeConfig: MemoryRuntimeConfig,
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
    
        val agentScope = agentScope("neopsyke-agent")
        val dashboardStore = DashboardStateStore()
        val interlocutorResolver = ai.neopsyke.agent.config.DefaultInterlocutorResolver()
        val sensoryInput = AsyncSignalSource(
            includeStdin = true,
            emitStdinClosedSignal = false,
            stdinMode = ai.neopsyke.agent.cortex.sensory.AsyncSignalSource.StdinMode.CONTROL_ONLY,
            prompt = { print("control> ") },
            scope = agentScope
        )
        val sensoryCortex = SensoryCortex(
            config = config,
            source = sensoryInput,
            interlocutorResolver = interlocutorResolver
        )
        val secretProvider = EnvActionSecretProvider(System.getenv())
        val telegramConfig = config.nativeIntegrations.telegram
        val telegramBotToken = secretProvider.read(SecretHandle(telegramConfig.botTokenHandle))
        val telegramWebhookSecret = secretProvider.read(SecretHandle(telegramConfig.webhookSecretHandle))
        if (telegramConfig.enabled) {
            logger.info {
                "Telegram integration config enabled=true mode=${telegramConfig.mode.name.lowercase()} bot_token_present=${!telegramBotToken.isNullOrBlank()} webhook_secret_present=${!telegramWebhookSecret.isNullOrBlank()} owner_chat_id=${telegramConfig.ownerChatId.ifBlank { "unset" }} owner_user_id=${telegramConfig.ownerUserId.ifBlank { "unset" }} require_direct_chat=${telegramConfig.requireDirectChat} drop_unauthorized_messages=${telegramConfig.dropUnauthorizedMessages}"
            }
            if (telegramBotToken.isNullOrBlank()) {
                logger.warn {
                    "Telegram integration is enabled but bot token handle '${telegramConfig.botTokenHandle}' resolved blank; ingress/egress will not start."
                }
            }
            if (telegramConfig.mode == TelegramIngressMode.WEBHOOK && telegramWebhookSecret.isNullOrBlank()) {
                logger.warn {
                    "Telegram webhook mode is enabled but webhook secret handle '${telegramConfig.webhookSecretHandle}' resolved blank; inbound webhook auth will fail closed."
                }
            }
        }
        val telegramSink = if (telegramConfig.enabled && !telegramBotToken.isNullOrBlank()) {
            TelegramBotApiClient(botToken = telegramBotToken)
        } else {
            null
        }
        val conversationOutput = RoutedConversationOutputGateway(
            fallbackOutput = {},
            telegramSink = telegramSink,
        )
        val chatBridge = ChatRuntimeBridge(
            store = dashboardStore,
            sensoryInput = sensoryInput,
            interlocutorResolver = interlocutorResolver
        )
        val telegramUpdateProcessor = if (telegramConfig.enabled) {
            TelegramUpdateProcessor(
                store = dashboardStore,
                sensoryInput = sensoryInput,
                config = telegramConfig,
                interlocutorResolver = interlocutorResolver,
            )
        } else {
            null
        }
        val telegramWebhookBridge = if (
            telegramConfig.enabled &&
            telegramConfig.mode == TelegramIngressMode.WEBHOOK
        ) {
            TelegramWebhookBridge(
                store = dashboardStore,
                sensoryInput = sensoryInput,
                config = telegramConfig,
                webhookSecret = telegramWebhookSecret,
                interlocutorResolver = interlocutorResolver,
            )
        } else {
            null
        }
        val telegramPollingBridge = if (
            telegramConfig.enabled &&
            telegramConfig.mode == TelegramIngressMode.POLLING &&
            telegramSink != null &&
            telegramUpdateProcessor != null
        ) {
            TelegramPollingBridge.create(
                scope = agentScope,
                config = telegramConfig,
                apiClient = telegramSink,
                processor = telegramUpdateProcessor,
            ).also { it.start() }
        } else {
            if (telegramConfig.enabled && telegramConfig.mode == TelegramIngressMode.POLLING && telegramSink == null) {
                logger.warn { "Telegram polling mode was requested but the polling bridge did not start because the bot token is unavailable." }
            }
            null
        }
        val googleConfig = config.nativeIntegrations.googleWorkspace
        val googleClientId = secretProvider.read(SecretHandle(googleConfig.oauthClientIdHandle))
        val googleClientSecret = secretProvider.read(SecretHandle(googleConfig.oauthClientSecretHandle))
        val googleStateSigningSecret = secretProvider.read(SecretHandle(googleConfig.oauthStateSigningSecretHandle))
        val googleTokenEncryptionSecret = secretProvider.read(SecretHandle(googleConfig.oauthTokenEncryptionSecretHandle))
        val googleCredentialStore = if (
            googleConfig.enabled &&
            !googleTokenEncryptionSecret.isNullOrBlank()
        ) {
            GoogleWorkspaceCredentialStore(
                rootDir = Paths.get(googleConfig.tokenStoreDir),
                encryptionSecret = googleTokenEncryptionSecret,
            )
        } else {
            null
        }
        val googleApiClient = if (
            googleConfig.enabled &&
            !googleClientId.isNullOrBlank() &&
            !googleClientSecret.isNullOrBlank() &&
            googleCredentialStore != null
        ) {
            GoogleWorkspaceApiClient(
                clientId = googleClientId,
                clientSecret = googleClientSecret,
                tokenBaseUrl = googleConfig.tokenBaseUrl,
                credentialStore = googleCredentialStore,
            )
        } else {
            null
        }
        val googleOAuthBridge = if (
            googleConfig.enabled &&
            !googleClientId.isNullOrBlank() &&
            !googleClientSecret.isNullOrBlank() &&
            !googleStateSigningSecret.isNullOrBlank() &&
            !googleTokenEncryptionSecret.isNullOrBlank() &&
            googleApiClient != null &&
            googleCredentialStore != null
        ) {
            GoogleWorkspaceOAuthBridge(
                config = googleConfig,
                clientId = googleClientId,
                clientSecret = googleClientSecret,
                stateCodec = OAuthStateCodec(signingSecret = googleStateSigningSecret),
                pendingAuthorizationStore = OAuthPendingAuthorizationStore(
                    rootDir = Paths.get(googleConfig.tokenStoreDir).resolve("pending"),
                    encryptionSecret = googleTokenEncryptionSecret,
                ),
                credentialStore = googleCredentialStore,
                apiClient = googleApiClient,
            )
        } else {
            null
        }
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
        val idInnerVoiceFileSink = if (innerVoiceStore != null) {
            resolveIdInnerVoiceFilePath()?.let { path ->
                try {
                    IdInnerVoiceFileSink(path).also {
                        logger.info { "Id inner-voice file sink enabled at ${it.path}" }
                    }
                } catch (ex: Exception) {
                    logger.warn(ex) { "Failed to initialize Id inner-voice file sink at $path; continuing without it." }
                    null
                }
            }
        } else {
            null
        }
        val innerVoiceSink = if (innerVoiceStore != null) {
            InnerVoiceSink(
                dashboardStore = dashboardStore,
                innerVoiceStore = innerVoiceStore,
                config = config.innerVoice,
                idFileSink = idInnerVoiceFileSink
            )
        } else {
            null
        }
        try {
            InstrumentationBus(
                sinks = listOfNotNull(
                    StructuredLogSink(),
                    dashboardStore,
                    ScratchpadDumpSink(scope = agentScope),
                    innerVoiceSink
                ),
                criticalSinks = listOfNotNull(sidecarSink),
                scope = agentScope
            ).use { instrumentation ->
                val actionAuthorizationPolicy = createActionAuthorizationPolicy(config)
                createActionControlStoreIfEnabled(config).use { actionControlStore ->
                    val dashboardServer = if (dashboardEnabled) {
                        try {
                            DashboardServer(
                                store = dashboardStore,
                                chatBridge = chatBridge,
                                telegramWebhookBridge = telegramWebhookBridge,
                                googleOAuthBridge = googleOAuthBridge,
                                innerVoiceStore = innerVoiceStore,
                                idInnerVoiceFilePath = idInnerVoiceFileSink?.path,
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
                                "website_fetch_enabled" to config.builtinTools.websiteFetch.enabled,
                                "website_fetch_call_timeout_ms" to config.builtinTools.websiteFetch.callTimeoutMs,
                                "website_fetch_max_chars" to config.builtinTools.websiteFetch.maxChars,
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
                    val callObserversByProvider = mutableMapOf<String, ai.neopsyke.llm.ChatCallObserver?>()
                    fun callObserverForProvider(provider: String): ai.neopsyke.llm.ChatCallObserver? {
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
                            delegate = AdaptiveStructuredOutputChatClient(
                                delegate = maybeCacheWrap(createChatClient(
                                    endpoint = llm.planner,
                                    callObserver = callObserverForProvider(llm.planner.providerLabel)
                                )),
                                provider = llm.planner.providerLabel
                            ),
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
                                            val fetchTool = createFetchTool(config)
                                            val webSearchRuntime = createWebSearchRuntime(
                                                llm = llm,
                                                callObserver = webSearchCallObserver,
                                                instrumentation = instrumentation,
                                                maxRawResponseChars = config.maxActionPayloadChars,
                                                tokenBudgetGate = tokenBudgetGate
                                            )

                                            webSearchRuntime.use { runtime ->
                                                val activeFetchTool = fetchTool
                                                    val earlyMemoryStartup =
                                                        resolveInteractiveMemoryStartup(config, memoryRuntimeConfig)
                                                    val hippocampus = earlyMemoryStartup.hippocampus
                                                    val logbook = createLogbookIfEnabled(config)
                                                    val longTermMemoryAdvisor = LlmLongTermMemoryAdvisor(
                                                        modelClient = longTermMemoryClient,
                                                        config = config,
                                                        modelTokenWeight = llm.modelCatalog.tokenWeightFor(llm.memoryAdvisor),
                                                        modelContextWindow = llm.modelCatalog.contextWindowFor(llm.memoryAdvisor),
                                                        instrumentation = instrumentation
                                                    )
                                                    val logbookSummarizer = createLogbookSummarizer(config, longTermMemoryClient)
                                                    val webSearchActionHandler = WebSearchActionHandler(runtime.engine)
                                                    val goalManager = if (config.goals.enabled) {
                                                        ai.neopsyke.agent.goal.GoalManager(
                                                            config = config.goals,
                                                            store = ai.neopsyke.agent.goal.GoalStore(config.goals.workspaceRoot),
                                                            planner = ai.neopsyke.agent.goal.LlmGoalPlanner(plannerClient, config),
                                                            verifier = ai.neopsyke.agent.goal.LlmGoalStepVerifier(plannerClient, config),
                                                            instrumentation = instrumentation,
                                                            cueEmitter = sensoryInput::offerGoalRuntimeCue,
                                                        ).also { it.start(agentScope) }
                                                    } else {
                                                        null
                                                    }
                                                    dashboardServer?.goalManager = goalManager
                                                    var plannerNoopCount = 0
                                                    var plannerOutputRepairedCount = 0
                                                    val assembly = EgoAssembler.assemble(
                                                        config = config,
                                                        plannerFactory = { motorCortex ->
                                                            LlmEgoPlanner(
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
                                                        },
                                                        superegoFactory = { registry ->
                                                            Superego(
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
                                                                authorizationPolicy = actionAuthorizationPolicy,
                                                                instrumentation = instrumentation
                                                            )
                                                        },
                                                        sensoryCortex = sensoryCortex,
                                                        instrumentation = instrumentation,
                                                        hippocampus = hippocampus,
                                                        metaReasoner = LlmMetaReasoner(
                                                            modelClient = metaReasonerClient,
                                                            config = config,
                                                            modelTokenWeight = llm.modelCatalog.tokenWeightFor(llm.metaReasoner),
                                                            modelContextWindow = llm.modelCatalog.contextWindowFor(llm.metaReasoner),
                                                            fallbackModelClient = metaReasonerFallbackClient,
                                                            instrumentation = instrumentation
                                                        ),
                                                        longTermMemoryAdvisor = longTermMemoryAdvisor,
                                                        scratchpadFinalizer = if (
                                                            config.memory.scratchpad.enabled &&
                                                                config.memory.scratchpad.finalPassRewriteEnabled
                                                        ) {
                                                            LlmScratchpadFinalizer(
                                                                modelClient = plannerClient,
                                                                config = config,
                                                                instrumentation = instrumentation
                                                            )
                                                        } else {
                                                            NoopScratchpadFinalizer
                                                        },
                                                        logbook = logbook,
                                                        logbookSummarizer = logbookSummarizer,
                                                        webSearchActionHandler = webSearchActionHandler,
                                                        fetchTool = activeFetchTool,
                                                        goalsGateway = goalManager
                                                            ?: ai.neopsyke.agent.goal.NoopGoalsGateway,
                                                        actionControlServiceFactory = { motorCortex ->
                                                            actionControlStore?.let { store ->
                                                                ai.neopsyke.agent.actioncontrol.DefaultActionControlService(
                                                                    config = config.actionControl,
                                                                    store = store,
                                                                    executeCommittedAction = { action, authorization ->
                                                                        motorCortex.execute(action, config.searchResultCount, authorization)
                                                                    },
                                                                    actionRegistry = motorCortex.actionRegistry(),
                                                                )
                                                            } ?: ai.neopsyke.agent.actioncontrol.LegacyCompatibleActionControlService { action, authorization ->
                                                                motorCortex.execute(action, config.searchResultCount, authorization)
                                                            }
                                                        },
                                                        output = {},
                                                        conversationOutput = conversationOutput,
                                                    )
                                                    assembly.actionRegistry.loadWarnings.forEach { warning ->
                                                        instrumentation.emit(AgentEvents.warning(warning))
                                                    }
                                                    assembly.use { assembled ->
                                                        val egoDispatcher = Executors.newSingleThreadExecutor { Thread(it, "neopsyke-ego") }.asCoroutineDispatcher()
                                                        try { runBlocking(egoDispatcher) {
                                                        val registry = assembled.actionRegistry
                                                        val motorCortex = assembled.motorCortex
                                                        val ego = assembled.ego
                                                        dashboardServer?.actionControlService = assembled.actionControlService
                                                        val actionStatuses = motorCortex.startupSmokeTest()
                                                        instrumentation.emit(AgentEvents.actionCapabilities(actionStatuses))
                                                        actionStatuses.filterNot { it.available }.forEach { status ->
                                                            instrumentation.emit(
                                                                AgentEvents.warning(
                                                                    "Action ${status.actionType.id} unavailable at startup: ${status.detail}"
                                                                )
                                                            )
                                                        }
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
                                                        if (cliOptions?.hasClearMemoryRequest == true) {
                                                            executeLongTermMemoryClear(
                                                                cliOptions = cliOptions,
                                                                hippocampus = hippocampus,
                                                                logbook = logbook,
                                                                instrumentation = instrumentation
                                                            )
                                                        }
                                                        val shutdownGuard = ShutdownCloseGuard("interactive-runtime").apply {
                                                            register(hippocampus)
                                                            register(logbook)
                                                            register(actionControlStore)
                                                            register(activeFetchTool)
                                                        }
                                                        shutdownGuard.register(
                                                            createActionControlAutonomousWorker(
                                                                scope = this,
                                                                ego = ego,
                                                                config = config,
                                                                instrumentation = instrumentation,
                                                            )
                                                        )
                                                        val idConfig = ai.neopsyke.config.IdRuntimeConfigLoader.load()
                                                        val idModule = if (idConfig.enabled) {
                                                           ai.neopsyke.agent.id.Id(
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
                                                                shutdownGuard.register(id)
                                                            }
                                                        } else {
                                                            null
                                                        }
                                                        try {
                                                            ego.runInteractive()
                                                        } finally {
                                                            shutdownGuard.close()
                                                        }
                                                        } } finally {
                                                            goalManager?.stop()
                                                            egoDispatcher.close()
                                                        }
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
            }
        } finally {
            telegramPollingBridge?.close()
            idInnerVoiceFileSink?.close()
            innerVoiceStore?.close()
            sensoryInput.close()
            agentScope.cancel()
        }
    }

    internal fun runFreudLiveMode(
        llm: LlmRuntimeConfig,
        config: AgentConfig,
        memoryRuntimeConfig: MemoryRuntimeConfig,
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

        val agentScope = agentScope("neopsyke-freud-live")
        val sensoryInput = AsyncSignalSource(
            includeStdin = false,
            emitStdinClosedSignal = false,
            scope = agentScope
        )
        val interlocutorResolver = ai.neopsyke.agent.config.DefaultInterlocutorResolver()
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
                    ScratchpadDumpSink(scope = agentScope),
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

                    val callObserversByProvider = mutableMapOf<String, ai.neopsyke.llm.ChatCallObserver?>()
                    fun callObserverForProvider(provider: String): ai.neopsyke.llm.ChatCallObserver? {
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
                            delegate = AdaptiveStructuredOutputChatClient(
                                delegate = maybeCacheWrap(createChatClient(
                                    endpoint = llm.planner,
                                    callObserver = callObserverForProvider(llm.planner.providerLabel)
                                )),
                                provider = llm.planner.providerLabel
                            ),
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
                                        val actionAuthorizationPolicy = createActionAuthorizationPolicy(config)
                                        val actionControlStore = createActionControlStoreIfEnabled(config)
                                        val fetchTool = createFetchTool(config)
                                        val webSearchRuntime = createWebSearchRuntime(
                                            llm = llm,
                                            callObserver = callObserverForProvider(llm.webSearch.providerLabel),
                                            instrumentation = instrumentation,
                                            maxRawResponseChars = config.maxActionPayloadChars,
                                            tokenBudgetGate = tokenBudgetGate
                                        )

                                        webSearchRuntime.use { runtime ->
                                            val activeFetchTool = fetchTool
                                                val earlyMemoryStartup2 =
                                                    resolveInteractiveMemoryStartup(config, memoryRuntimeConfig)
                                                val hippocampus = earlyMemoryStartup2.hippocampus
                                                val logbook = createLogbookIfEnabled(config)
                                                val longTermMemoryAdvisor = LlmLongTermMemoryAdvisor(
                                                    modelClient = longTermMemoryClient,
                                                    config = config,
                                                    modelTokenWeight = llm.modelCatalog.tokenWeightFor(llm.memoryAdvisor),
                                                    modelContextWindow = llm.modelCatalog.contextWindowFor(llm.memoryAdvisor),
                                                    instrumentation = instrumentation
                                                )
                                                val logbookSummarizer = createLogbookSummarizer(config, longTermMemoryClient)
                                                val webSearchActionHandler = WebSearchActionHandler(runtime.engine)
                                                val goalManager = if (config.goals.enabled) {
                                                    ai.neopsyke.agent.goal.GoalManager(
                                                        config = config.goals,
                                                        store = ai.neopsyke.agent.goal.GoalStore(config.goals.workspaceRoot),
                                                        planner = ai.neopsyke.agent.goal.LlmGoalPlanner(plannerClient, config),
                                                        verifier = ai.neopsyke.agent.goal.LlmGoalStepVerifier(plannerClient, config),
                                                        instrumentation = instrumentation,
                                                        cueEmitter = sensoryInput::offerGoalRuntimeCue,
                                                    ).also { it.start(agentScope) }
                                                } else {
                                                    null
                                                }
                                                val assembly = EgoAssembler.assemble(
                                                    config = config,
                                                    plannerFactory = { motorCortex ->
                                                        LlmEgoPlanner(
                                                            modelClient = plannerClient,
                                                            actionVerifierModelClient = actionVerifierClient,
                                                            actionVerifierContextWindow = llm.modelCatalog.contextWindowFor(llm.actionVerifier),
                                                            config = config,
                                                            actionPayloadRepair = motorCortex::repairPlannerPayload,
                                                            instrumentation = instrumentation,
                                                            onPlannerNoop = { metrics.recordPlannerNoop() },
                                                            onPlannerOutputRepaired = { metrics.recordPlannerOutputRepaired() }
                                                        )
                                                    },
                                                    superegoFactory = { registry ->
                                                        Superego(
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
                                                            authorizationPolicy = actionAuthorizationPolicy,
                                                            instrumentation = instrumentation
                                                        )
                                                    },
                                                    sensoryCortex = sensoryCortex,
                                                    instrumentation = instrumentation,
                                                    hippocampus = hippocampus,
                                                    metaReasoner = LlmMetaReasoner(
                                                        modelClient = metaReasonerClient,
                                                        config = config,
                                                        modelTokenWeight = llm.modelCatalog.tokenWeightFor(llm.metaReasoner),
                                                        modelContextWindow = llm.modelCatalog.contextWindowFor(llm.metaReasoner),
                                                        fallbackModelClient = metaReasonerFallbackClient,
                                                        instrumentation = instrumentation
                                                    ),
                                                    longTermMemoryAdvisor = longTermMemoryAdvisor,
                                                    scratchpadFinalizer = if (
                                                        config.memory.scratchpad.enabled &&
                                                            config.memory.scratchpad.finalPassRewriteEnabled
                                                    ) {
                                                        LlmScratchpadFinalizer(
                                                            modelClient = plannerClient,
                                                            config = config,
                                                            instrumentation = instrumentation
                                                        )
                                                    } else {
                                                        NoopScratchpadFinalizer
                                                    },
                                                    logbook = logbook,
                                                    logbookSummarizer = logbookSummarizer,
                                                    webSearchActionHandler = webSearchActionHandler,
                                                    fetchTool = activeFetchTool,
                                                    goalsGateway = goalManager
                                                        ?: ai.neopsyke.agent.goal.NoopGoalsGateway,
                                                    actionControlServiceFactory = { motorCortex ->
                                                        actionControlStore?.let { store ->
                                                            ai.neopsyke.agent.actioncontrol.DefaultActionControlService(
                                                                config = config.actionControl,
                                                                store = store,
                                                                executeCommittedAction = { action, authorization ->
                                                                    motorCortex.execute(action, config.searchResultCount, authorization)
                                                                },
                                                                actionRegistry = motorCortex.actionRegistry(),
                                                            )
                                                        } ?: ai.neopsyke.agent.actioncontrol.LegacyCompatibleActionControlService { action, authorization ->
                                                            motorCortex.execute(action, config.searchResultCount, authorization)
                                                        }
                                                    },
                                                    output = liveOutput,
                                                )
                                                assembly.actionRegistry.loadWarnings.forEach { warning ->
                                                    instrumentation.emit(AgentEvents.warning(warning))
                                                }
                                                assembly.use { assembled ->
                                                    val egoDispatcher = Executors.newSingleThreadExecutor { Thread(it, "neopsyke-freud-live-ego") }.asCoroutineDispatcher()
                                                    try { runBlocking(egoDispatcher) {
                                                    val registry = assembled.actionRegistry
                                                    val motorCortex = assembled.motorCortex
                                                    val ego = assembled.ego
                                                    val shutdownGuard = ShutdownCloseGuard("freud-live-runtime").apply {
                                                        register(hippocampus)
                                                        register(logbook)
                                                        register(actionControlStore)
                                                        register(activeFetchTool)
                                                    }
                                                    shutdownGuard.register(
                                                        createActionControlAutonomousWorker(
                                                            scope = this,
                                                            ego = ego,
                                                            config = config,
                                                            instrumentation = instrumentation,
                                                        )
                                                    )
                                                    val actionStatuses = motorCortex.startupSmokeTest()
                                                    instrumentation.emit(AgentEvents.actionCapabilities(actionStatuses))
                                                    instrumentation.emit(
                                                        AgentEvent(
                                                            type = "memory_status",
                                                            data = mapOf(
                                                                "available" to hippocampus.enabled,
                                                                "detail" to earlyMemoryStartup2.detail
                                                            )
                                                        )
                                                    )

                                                    sensoryInput.submitInput(
                                                        content = stdinContent,
                                                        source = "freud-live",
                                                        conversationContext = freudLiveConversationContext(),
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
                                                        shutdownGuard.close()
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

                                                    } } finally {
                                                        goalManager?.stop()
                                                        egoDispatcher.close()
                                                    }
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
        callObserver: ai.neopsyke.llm.ChatCallObserver?,
        instrumentation: ai.neopsyke.instrumentation.AgentInstrumentation,
        maxRawResponseChars: Int,
        tokenBudgetGate: LlmTokenBudgetGate? = null,
    ): WebSearchRuntime {
        val webSearch = llm.webSearch
        return try {
            when (webSearch.provider) {
                LlmProvider.ANTHROPIC -> WebSearchRuntime(
                    engine = UnavailableWebSearchEngine(
                        "Web search provider 'anthropic' is not implemented. Use groq, mistral, or google for web_search."
                    )
                )

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

                LlmProvider.OLLAMA -> WebSearchRuntime(
                    engine = UnavailableWebSearchEngine(
                        "Web search provider 'ollama' is not implemented. Use groq, mistral, or google for web_search."
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
        instrumentation: ai.neopsyke.instrumentation.AgentInstrumentation,
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
        callObserver: ai.neopsyke.llm.ChatCallObserver? = null,
    ): ChatModelClient {
        return when (endpoint.provider) {
            LlmProvider.ANTHROPIC -> AnthropicChatClient(
                apiKey = endpoint.apiKey,
                baseUrl = endpoint.baseUrl,
                modelName = endpoint.model,
                callObserver = callObserver
            )

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

            LlmProvider.OLLAMA -> OllamaChatClient(
                apiKey = endpoint.apiKey,
                baseUrl = endpoint.baseUrl,
                modelName = endpoint.model,
                callObserver = callObserver
            )
        }
    }
    
    private fun resolveLlmCacheConfig(): Pair<LlmCacheMode, java.nio.file.Path?> {
        val mode = LlmCacheMode.parse(System.getenv("NEOPSYKE_LLM_CACHE_MODE"))
        val file = System.getenv("NEOPSYKE_LLM_CACHE_FILE")?.trim()?.ifBlank { null }
            ?.let { java.nio.file.Path.of(it) }
        return mode to file
    }

    private fun createFetchTool(config: AgentConfig): FetchTool {
        val fetchConfig = config.builtinTools.websiteFetch
        if (!fetchConfig.enabled) {
            val reason = "Built-in website fetch disabled by configuration."
            logger.info { reason }
            return DisabledFetchTool(reason)
        }
        logger.info { "Using native JVM fetch tool (OkHttp + Jsoup). No external process." }
        return NativeFetchTool(
            callTimeoutMs = fetchConfig.callTimeoutMs,
            maxChars = fetchConfig.maxChars
        )
    }
    
    private fun createHippocampus(config: AgentConfig, runtimeConfig: MemoryRuntimeConfig): Hippocampus {
        return when (runtimeConfig.mode) {
            MemoryMode.OFF -> NoopHippocampus
            MemoryMode.DEFAULT -> createDefaultProviderBackedHippocampus(config, runtimeConfig)
            MemoryMode.EXTERNAL -> createExternalProviderBackedHippocampus(config, runtimeConfig)
        }
    }

    private fun createDefaultProviderBackedHippocampus(
        config: AgentConfig,
        runtimeConfig: MemoryRuntimeConfig,
    ): Hippocampus {
        val provider = runtimeConfig.defaultProvider
        val baseUrl = provider.baseUrl.trim().trimEnd('/')
        if (baseUrl.isBlank()) {
            logger.warn { "Default memory provider baseUrl is blank; disabling long-term vector memory for this run." }
            return NoopHippocampus
        }
        val managedProcess = if (isHttpMemoryProviderHealthy(baseUrl, provider.healthTimeoutMs)) {
            null
        } else {
            DefaultMemoryProviderInstaller().ensureInstalled(provider)
            val command = resolveProviderCommand(provider.command)
                ?: throw IllegalStateException("Default memory provider command is unavailable: ${provider.command}")
            ManagedHttpMemoryProviderProcess(
                command = command,
                baseUrl = baseUrl,
                startupTimeoutMs = provider.startupTimeoutMs,
                healthTimeoutMs = provider.healthTimeoutMs
            )
        }
        return ProviderBackedHippocampus(
            namespace = provider.namespace,
            client = HttpMemoryProviderClient(
                providerName = provider.provider,
                baseUrl = baseUrl,
                callTimeoutMs = config.memory.mcpMemoryCallTimeoutMs,
                managedProcess = managedProcess
            )
        )
    }

    private fun createExternalProviderBackedHippocampus(
        config: AgentConfig,
        runtimeConfig: MemoryRuntimeConfig,
    ): Hippocampus {
        val provider = runtimeConfig.externalProvider
        val transport = provider.transport.trim().lowercase()
        if (transport != "http") {
            // TODO(memory-provider): add transport adapters for MCP/direct external providers.
            throw IllegalStateException(
                "memory=external currently supports only transport=http. " +
                    "Configured provider=${provider.provider} transport=${provider.transport}."
            )
        }
        val baseUrl = provider.baseUrl.trim().trimEnd('/')
        if (baseUrl.isBlank()) {
            throw IllegalStateException(
                "memory=external requires a non-blank baseUrl for provider=${provider.provider}."
            )
        }
        if (!isHttpMemoryProviderHealthy(baseUrl, config.memory.mcpMemoryCallTimeoutMs)) {
            throw IllegalStateException(
                "External memory provider health check failed for provider=${provider.provider} baseUrl=$baseUrl."
            )
        }
        return ProviderBackedHippocampus(
            namespace = provider.namespace,
            client = HttpMemoryProviderClient(
                providerName = provider.provider,
                baseUrl = baseUrl,
                callTimeoutMs = config.memory.mcpMemoryCallTimeoutMs,
            )
        )
    }

    private fun resolveInteractiveMemoryStartup(
        config: AgentConfig,
        runtimeConfig: MemoryRuntimeConfig,
    ): InteractiveMemoryStartup {
        val hippocampus = try {
            createHippocampus(config = config, runtimeConfig = runtimeConfig)
        } catch (ex: Exception) {
            val detail = ex.message?.takeIf { it.isNotBlank() }
                ?: "Memory provider startup failed; long-term memory disabled for this run."
            logger.warn(ex) { "Memory provider startup failed for interactive mode: $detail" }
            return InteractiveMemoryStartup(
                hippocampus = NoopHippocampus,
                detail = detail
            )
        }
        if (!hippocampus.enabled) {
            return InteractiveMemoryStartup(
                hippocampus = NoopHippocampus,
                detail = "Long-term vector memory disabled (mode=${runtimeConfig.mode.name.lowercase()})."
            )
        }
        return InteractiveMemoryStartup(
            hippocampus = hippocampus,
            detail = "Provider: ${hippocampus.providerName} (${runtimeConfig.mode.name.lowercase()})"
        )
    }

    private fun resolveProviderCommand(command: String): List<String>? {
        val parsed = McpStdioClient.parseCommand(command)
        if (parsed.isEmpty()) {
            return null
        }
        return parsed.takeIf { isExecutableAvailable(it.first()) }
    }

    private fun isHttpMemoryProviderHealthy(baseUrl: String, timeoutMs: Long): Boolean {
        return try {
            val request = okhttp3.Request.Builder()
                .url("${baseUrl.trimEnd('/')}/v1/health")
                .get()
                .build()
            okhttp3.OkHttpClient.Builder()
                .callTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build()
                .newCall(request)
                .execute()
                .use { response -> response.isSuccessful }
        } catch (_: Exception) {
            false
        }
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
    
    private fun createLogbookIfEnabled(config: AgentConfig): Logbook? {
        if (!config.logbook.enabled) return null
        return try {
            SqliteLogbook(config.logbook)
        } catch (ex: Exception) {
            logger.warn(ex) { "Episodic logbook initialization failed; continuing without." }
            null
        }
    }

    private fun createActionAuthorizationPolicy(config: AgentConfig): ai.neopsyke.agent.actioncontrol.ActionAuthorizationPolicy =
        ai.neopsyke.agent.actioncontrol.ConfiguredActionAuthorizationPolicy(
            ai.neopsyke.agent.actioncontrol.ActionSecurityPolicyLoader.load(
                Paths.get(config.actionControl.policyPath)
            )
        )

    private fun createActionControlStoreIfEnabled(config: AgentConfig): ai.neopsyke.agent.actioncontrol.ActionControlStore? {
        if (!config.actionControl.enabled) {
            return null
        }
        return try {
            ai.neopsyke.agent.actioncontrol.SqliteActionControlStore(config.actionControl.dbPath)
        } catch (ex: Exception) {
            logger.warn(ex) { "Action-control store initialization failed; continuing with legacy action control." }
            null
        }
    }

    private fun createActionControlAutonomousWorker(
        scope: CoroutineScope,
        ego: Ego,
        config: AgentConfig,
        instrumentation: ai.neopsyke.instrumentation.AgentInstrumentation,
    ): AutoCloseable? {
        if (!config.actionControl.enabled || !config.actionControl.autonomousWorkerEnabled) {
            return null
        }
        return ai.neopsyke.agent.actioncontrol.ActionControlAutonomousWorker(
            scope = scope,
            config = config.actionControl,
            processBatch = { limit -> ego.processAutonomousStagedActions(limit) },
            instrumentation = instrumentation,
        )
    }

    private fun createLogbookSummarizer(
        config: AgentConfig,
        memoryClient: ai.neopsyke.llm.ChatModelClient,
    ): ai.neopsyke.agent.memory.longterm.LogbookSummarizer {
        if (!config.logbook.useLlmSummarizer) {
            return DeterministicLogbookSummarizer(config.logbook)
        }
        return ai.neopsyke.agent.memory.longterm.LlmLogbookSummarizer(
            modelClient = memoryClient,
            config = config,
        )
    }

    private fun executeLongTermMemoryClear(
        cliOptions: AppCliOptions,
        hippocampus: Hippocampus,
        logbook: ai.neopsyke.agent.memory.longterm.Logbook?,
        instrumentation: ai.neopsyke.instrumentation.AgentInstrumentation,
    ) {
        val clearVector = cliOptions.clearMemoryAll || cliOptions.clearMemoryVector
        val clearEpisodic = cliOptions.clearMemoryAll || cliOptions.clearMemoryEpisodic
        val clearLessons = cliOptions.clearMemoryLessons && !clearVector

        if (clearVector) {
            if (hippocampus.enabled) {
                output.info("Clearing vector/hippocampus memory...")
                try {
                    val admin = hippocampus as? HippocampusAdmin
                    val deleted = admin?.reset(ResetRequest(clearAll = true))?.deletedCount ?: 0
                    output.info("Vector memory cleared ($deleted observations removed).")
                    logger.info { "CLI --clear-memory: vector/hippocampus cleared, $deleted observations removed." }
                    instrumentation.emit(
                        ai.neopsyke.instrumentation.AgentEvents.warning(
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
                    val admin = hippocampus as? HippocampusAdmin
                    val deleted = admin?.forget(ForgetRequest(tagMarkers = setOf("kind:lesson")))?.deletedCount ?: 0
                    output.info("Lessons cleared ($deleted observations removed).")
                    logger.info { "CLI --clear-memory: lessons cleared, $deleted observations removed." }
                    instrumentation.emit(
                        ai.neopsyke.instrumentation.AgentEvents.warning(
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
                        ai.neopsyke.instrumentation.AgentEvents.warning(
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

    // Ensure managed resources are closed on JVM shutdown (Ctrl-C / SIGTERM) as well as normal flow.
    internal class ShutdownCloseGuard(
        threadLabel: String,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        private val resources = CopyOnWriteArrayList<AutoCloseable>()
        private val hook = Thread(
            { closeInternal(removeHook = false) },
            "neopsyke-$threadLabel-shutdown"
        )

        init {
            Runtime.getRuntime().addShutdownHook(hook)
        }

        fun register(value: Any?) {
            val closeable = value as? AutoCloseable ?: return
            if (closed.get()) {
                closeQuietly(closeable)
                return
            }
            resources.add(closeable)
            if (closed.get() && resources.remove(closeable)) {
                closeQuietly(closeable)
            }
        }

        override fun close() {
            closeInternal(removeHook = true)
        }

        private fun closeInternal(removeHook: Boolean) {
            if (!closed.compareAndSet(false, true)) return
            if (removeHook) {
                try {
                    Runtime.getRuntime().removeShutdownHook(hook)
                } catch (_: IllegalStateException) {
                    // JVM shutdown already in progress; let the active hook own cleanup.
                }
            }
            val snapshot = resources.toList().asReversed()
            resources.clear()
            snapshot.forEach(::closeQuietly)
        }
    }

    private data class InteractiveMemoryStartup(
        val hippocampus: Hippocampus,
        val detail: String,
    )
    
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

    private fun dumpEndOfRunMetrics(metrics: ai.neopsyke.metrics.MetricsRuntime) {
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
