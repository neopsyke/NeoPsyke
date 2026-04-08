package ai.neopsyke

import mu.KotlinLogging
import ai.neopsyke.admin.approvals.ApprovalRuntime
import ai.neopsyke.admin.approvals.DefaultApprovalInterpreter
import ai.neopsyke.admin.approvals.SqliteApprovalStore
import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.config.TelegramIngressMode
import ai.neopsyke.agent.ego.Ego
import ai.neopsyke.agent.ego.EgoAssembler
import ai.neopsyke.agent.ego.planner.HierarchicalEgoPlanner
import ai.neopsyke.agent.ego.planner.LaneId
import ai.neopsyke.agent.ego.planner.input.DirectResponsePlanner
import ai.neopsyke.agent.ego.planner.input.GeneralActionPlanner
import ai.neopsyke.agent.ego.planner.input.GoalCreationPlanner
import ai.neopsyke.agent.ego.planner.input.GoalManagementPlanner
import ai.neopsyke.agent.ego.planner.input.GroundingClassifier
import ai.neopsyke.agent.ego.planner.input.InputIntentRouter
import ai.neopsyke.agent.ego.planner.input.TaskDecompositionPlanner
import ai.neopsyke.agent.ego.planner.lane.DeferredStepPlanner
import ai.neopsyke.agent.ego.planner.lane.FeedbackPlanner
import ai.neopsyke.agent.ego.planner.lane.GoalWorkPlanner
import ai.neopsyke.agent.ego.planner.lane.ImpulsePlanner
import ai.neopsyke.agent.ego.planner.lane.InputPlanner
import ai.neopsyke.agent.ego.planner.runtime.PlannerRuntime
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
import ai.neopsyke.agent.cortex.motor.actions.mcp.McpStdioClient
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.ConversationSecurityContexts
import ai.neopsyke.agent.model.Interlocutor
import ai.neopsyke.agent.cortex.sensory.AsyncSignalSource
import ai.neopsyke.agent.cortex.sensory.SensoryCortex
import ai.neopsyke.agent.memory.longterm.NoopHippocampus
import ai.neopsyke.agent.memory.longterm.ResetRequest
import ai.neopsyke.agent.cortex.motor.actions.fetch.NativeFetchTool
import ai.neopsyke.agent.superego.Superego
import ai.neopsyke.agent.cortex.motor.actions.websearch.WebSearchActionHandler
import ai.neopsyke.agent.cortex.motor.actions.websearch.WebSearchEngine
import ai.neopsyke.agent.cortex.motor.actions.websearch.WebSearchEngineHealth
import ai.neopsyke.agent.cortex.motor.actions.websearch.WebSearchResult
import ai.neopsyke.agent.cortex.motor.actions.EnvActionSecretProvider
import ai.neopsyke.agent.cortex.motor.actions.RoutedConversationOutputGateway
import ai.neopsyke.agent.cortex.motor.actions.SecretHandle
import ai.neopsyke.agent.cortex.motor.actions.control.ActionAuthorizationPolicy
import ai.neopsyke.agent.cortex.motor.actions.control.ActionControlAutonomousWorker
import ai.neopsyke.agent.cortex.motor.actions.control.ActionControlStore
import ai.neopsyke.agent.cortex.motor.actions.control.ActionSecurityPolicyLoader
import ai.neopsyke.agent.cortex.motor.actions.control.ConfiguredActionAuthorizationPolicy
import ai.neopsyke.agent.cortex.motor.actions.control.DefaultActionControlService
import ai.neopsyke.agent.cortex.motor.actions.control.LegacyCompatibleActionControlService
import ai.neopsyke.agent.cortex.motor.actions.control.SqliteActionControlStore
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
import ai.neopsyke.session.RecordingActionControlService
import ai.neopsyke.session.RecordingHippocampus
import ai.neopsyke.session.RecordingLogbook
import ai.neopsyke.session.RecordingSignalSource
import ai.neopsyke.session.RecordingWebSearchEngine
import ai.neopsyke.session.SessionRecordingManager
import ai.neopsyke.session.SessionRecordingMode
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
import ai.neopsyke.agent.cortex.motor.actions.fetch.FetchTool
import ai.neopsyke.agent.cortex.motor.actions.fetch.ToolHealthStatus
import kotlin.system.exitProcess
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
        val resolvedAdvisorKey = llm.memoryAdvisor.apiKey.trim()
        if (resolvedAdvisorKey.isBlank()) {
            val message = "${llm.memoryAdvisor.apiKeyEnvVar} is required for --eval-memory-live (memory_advisor role)."
            output.error(message)
            logger.warn { message }
            return
        }
        if (!checkProviderHealth(endpoint = llm.memoryAdvisor, modeLabel = "eval_memory_live", roleLabel = "memory_advisor")) {
            return
        }
        val resolvedPlannerKey = llm.planner.apiKey.trim()
        if (resolvedPlannerKey.isBlank()) {
            val message = "${llm.planner.apiKeyEnvVar} is required for --eval-memory-live (judge/planner role)."
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
                provider = llm.memoryAdvisor.providerLabel,
                apiKey = llm.memoryAdvisor.apiKey,
                egoModel = llm.memoryAdvisor.model,
                superegoModel = llm.superego.model
            ).use { metrics ->
                val rawResponseHook = LlmRawResponseEventHook(
                    instrumentation = instrumentation,
                    maxRawResponseChars = evalRawResponseCharLimit
                )
                fun callObserverFor(endpoint: LlmEndpointConfig) = combineChatCallObservers(
                    metrics.chatCallObserver(provider = endpoint.providerLabel),
                    LlmCallEventObserver(provider = endpoint.providerLabel, instrumentation = instrumentation),
                    MetricsSnapshotObserver(metricsRuntime = metrics, instrumentation = instrumentation)
                )
                // Memory advisor client — uses the memory_advisor cognitive role.
                UsageTrackingChatClient(
                    delegate = InstrumentedChatModelClient(
                        delegate = createChatClient(
                            endpoint = llm.memoryAdvisor,
                            callObserver = callObserverFor(llm.memoryAdvisor)
                        ),
                        hooks = listOf(rawResponseHook)
                    )
                ).use { advisorClient ->
                // Judge client — uses the planner cognitive role for recall evaluation.
                UsageTrackingChatClient(
                    delegate = InstrumentedChatModelClient(
                        delegate = createChatClient(
                            endpoint = llm.planner,
                            callObserver = callObserverFor(llm.planner)
                        ),
                        hooks = listOf(rawResponseHook)
                    )
                ).use { judgeClient ->
                    logger.info {
                        "Memory live eval role routing: " +
                            "memory_advisor=${llm.memoryAdvisor.providerLabel}/${llm.memoryAdvisor.model}, " +
                            "judge=${llm.planner.providerLabel}/${llm.planner.model}"
                    }
                    val stage = cliOptions.evalStage
                        ?: runtimeSettings.evalDefaultStage
                        ?: java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()
                    hippocampus.use { activeHippocampus ->
                        val report = MemoryLiveEvalRunner(
                            client = judgeClient,
                            longTermMemoryAdvisor = LlmLongTermMemoryAdvisor(
                                modelClient = advisorClient,
                                config = config,
                                modelTokenWeight = llm.modelCatalog.tokenWeightFor(llm.memoryAdvisor),
                                modelContextWindow = llm.modelCatalog.contextWindowFor(llm.memoryAdvisor),
                                modelReasoningOverhead = llm.modelCatalog.reasoningOverheadFor(llm.memoryAdvisor),
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
        val sessionRecordingManager = SessionRecordingManager.fromEnvironment()
        val signalSource: ai.neopsyke.agent.cortex.sensory.SignalSource =
            if (sessionRecordingManager != null && sessionRecordingManager.mode == SessionRecordingMode.RECORD) {
                logger.info { "Session recording enabled for interactive mode" }
                RecordingSignalSource(delegate = sensoryInput, channel = sessionRecordingManager.signals, manager = sessionRecordingManager)
            } else {
                sensoryInput
            }
        val sensoryCortex = SensoryCortex(
            config = config,
            source = signalSource,
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
        val telegramAckTracker = ai.neopsyke.agent.cortex.motor.actions.TelegramStartupAckTracker()
        val dashboardMessageSink = ai.neopsyke.agent.cortex.motor.actions.DashboardMessageSink { sessionId, text, source ->
            dashboardStore.ensureChatSession(sessionId = sessionId, title = "Default")
            dashboardStore.addAssistantMessage(sessionId = sessionId, content = text, source = source)
            ai.neopsyke.agent.cortex.motor.actions.ConversationDeliveryResult(delivered = true, detail = "Dashboard delivery recorded.")
        }
        val dashboardAvailability = ai.neopsyke.agent.cortex.motor.actions.DashboardAvailabilityCheck { sessionId ->
            dashboardStore.hasChatSession(sessionId) &&
                (!config.approvals.dashboardRequiresLiveSubscriber || dashboardStore.hasActiveChatSubscriber(sessionId))
        }
        val contactChannelStatusProvider = ai.neopsyke.agent.cortex.motor.actions.DefaultUserContactChannelStatusProvider(
            dashboardAvailability = dashboardAvailability,
            telegramConfig = telegramConfig,
            telegramSink = telegramSink,
            telegramAckTracker = telegramAckTracker,
        )
        val contactChannelResolver = ai.neopsyke.agent.cortex.motor.actions.DefaultUserContactChannelResolver(
            channelStatusProvider = contactChannelStatusProvider,
            channelPriority = config.approvals.channelPriority,
            defaultChannel = config.approvals.defaultChannel,
        )
        val conversationOutput = RoutedConversationOutputGateway(
            fallbackOutput = {},
            telegramSink = telegramSink,
            dashboardSink = dashboardMessageSink,
        )
        conversationOutput.setChannelResolver(contactChannelResolver)
        val chatBridge = ChatRuntimeBridge(
            store = dashboardStore,
            sensoryInput = sensoryInput,
            policyScope = config.policyScope,
            interlocutorResolver = interlocutorResolver,
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
                sensoryCortex.setInstrumentation(instrumentation)
                sessionRecordingManager?.setInstrumentation(instrumentation)
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
                                        val approvalInterpreterClient = InstrumentedChatModelClient(
                                            delegate = TokenBudgetGuardedChatClient(
                                                delegate = maybeCacheWrap(createChatClient(
                                                    endpoint = llm.approvalInterpreter,
                                                    callObserver = callObserverForProvider(llm.approvalInterpreter.providerLabel)
                                                )),
                                                budgetGate = tokenBudgetGate,
                                                provider = llm.approvalInterpreter.providerLabel,
                                                role = LlmRoleLabels.APPROVAL_INTERPRETER
                                            ),
                                            hooks = listOf(rawResponseHook)
                                        )
                                        logger.info {
                                            "Cognitive role routing: " +
                                                "planner=${llm.planner.providerLabel}/${llm.planner.model}, " +
                                                "superego_primary=${superegoReviewRouting.primaryEndpoint.providerLabel}/${superegoReviewRouting.primaryEndpoint.model}, " +
                                                "superego_escalation=${superegoReviewRouting.escalationEndpoint?.let { "${it.providerLabel}/${it.model}" } ?: "disabled"}, " +
                                                "meta_reasoner=${llm.metaReasoner.providerLabel}/${llm.metaReasoner.model}, " +
                                                "meta_reasoner_fallback=${metaReasonerFallbackEndpoint?.let { "${it.providerLabel}/${it.model}" } ?: "disabled"}, " +
                                                "memory_advisor=${llm.memoryAdvisor.providerLabel}/${llm.memoryAdvisor.model}, " +
                                                "approval_interpreter=${llm.approvalInterpreter.providerLabel}/${llm.approvalInterpreter.model}, " +
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
                                                    val rawHippocampus = earlyMemoryStartup.hippocampus
                                                    val hippocampus = if (sessionRecordingManager != null) {
                                                        RecordingHippocampus(delegate = rawHippocampus, channel = sessionRecordingManager.memoryRecall)
                                                    } else {
                                                        rawHippocampus
                                                    }
                                                    val rawLogbook = createLogbookIfEnabled(config)
                                                    val logbook: Logbook? = if (sessionRecordingManager != null && rawLogbook != null) {
                                                        RecordingLogbook(delegate = rawLogbook, channel = sessionRecordingManager.logbookRecall)
                                                    } else {
                                                        rawLogbook
                                                    }
                                                    val longTermMemoryAdvisor = LlmLongTermMemoryAdvisor(
                                                        modelClient = longTermMemoryClient,
                                                        config = config,
                                                        modelTokenWeight = llm.modelCatalog.tokenWeightFor(llm.memoryAdvisor),
                                                        modelContextWindow = llm.modelCatalog.contextWindowFor(llm.memoryAdvisor),
                                                        modelReasoningOverhead = llm.modelCatalog.reasoningOverheadFor(llm.memoryAdvisor),
                                                        instrumentation = instrumentation
                                                    )
                                                    val logbookSummarizer = createLogbookSummarizer(config, longTermMemoryClient)
                                                    val activeWebSearchEngine = if (sessionRecordingManager != null) {
                                                        RecordingWebSearchEngine(delegate = runtime.engine, channel = sessionRecordingManager.webResults)
                                                    } else {
                                                        runtime.engine
                                                    }
                                                    val webSearchActionHandler = WebSearchActionHandler(activeWebSearchEngine)
                                                    if (cliOptions?.clearGoals == true) {
                                                        executeGoalsClear(config, instrumentation)
                                                    }
                                                    val goalManager = if (config.goals.enabled) {
                                                        ai.neopsyke.agent.goal.GoalManager(
                                                            config = config.goals,
                                                            store = ai.neopsyke.agent.goal.GoalStore(config.goals.workspaceRoot),
                                                            planner = ai.neopsyke.agent.goal.LlmGoalPlanner(plannerClient, config),
                                                            verifier = ai.neopsyke.agent.goal.LlmGoalStepVerifier(plannerClient, config),
                                                            instrumentation = instrumentation,
                                                            cueEmitter = sensoryCortex::offerGoalRuntimeCue,
                                                        ).also { it.start(agentScope) }
                                                    } else {
                                                        null
                                                    }
                                                    dashboardServer?.goalManager = goalManager
                                                    var plannerNoopCount = 0
                                                    var plannerOutputRepairedCount = 0
                                                    val plannerLaneClientResolver = buildPlannerLaneModelClientResolver(
                                                        llm = llm,
                                                        plannerClient = plannerClient,
                                                        createPlannerClient = { endpoint ->
                                                            InstrumentedChatModelClient(
                                                                delegate = TokenBudgetGuardedChatClient(
                                                                    delegate = maybeCacheWrap(
                                                                        createChatClient(
                                                                            endpoint = endpoint,
                                                                            callObserver = callObserverForProvider(endpoint.providerLabel)
                                                                        )
                                                                    ),
                                                                    budgetGate = tokenBudgetGate,
                                                                    provider = endpoint.providerLabel,
                                                                    role = LlmRoleLabels.PLANNER
                                                                ),
                                                                hooks = listOf(rawResponseHook)
                                                            )
                                                        }
                                                    )
                                                    val assembly = EgoAssembler.assemble(
                                                        config = config,
                                                        plannerFactory = { motorCortex ->
                                                            buildHierarchicalPlanner(
                                                                plannerClient = plannerClient,
                                                                config = config,
                                                                instrumentation = instrumentation,
                                                                actionPayloadRepair = motorCortex::repairPlannerPayload,
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
                                                                },
                                                                laneModelClientResolver = plannerLaneClientResolver,
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
                                                            val rawService = actionControlStore?.let { store ->
                                                                DefaultActionControlService(
                                                                    config = config.actionControl,
                                                                    store = store,
                                                                    executeCommittedAction = { action, authorization ->
                                                                        motorCortex.execute(
                                                                            action,
                                                                            config.searchResultCount,
                                                                            authorization
                                                                        )
                                                                    },
                                                                    actionRegistry = motorCortex.actionRegistry(),
                                                                )
                                                            } ?: LegacyCompatibleActionControlService { action, authorization ->
                                                                motorCortex.execute(
                                                                    action,
                                                                    config.searchResultCount,
                                                                    authorization
                                                                )
                                                            }
                                                            if (sessionRecordingManager != null) {
                                                                RecordingActionControlService(delegate = rawService, channel = sessionRecordingManager.actionControl)
                                                            } else {
                                                                rawService
                                                            }
                                                        },
                                                        output = {},
                                                        conversationOutput = conversationOutput,
                                                    )
                                                    assembly.actionRegistry.loadWarnings.forEach { warning ->
                                                        instrumentation.emit(AgentEvents.warning(warning))
                                                    }
                                                    assembly.use { assembled ->
                                                        val approvalRuntime = createApprovalRuntime(
                                                            config = config,
                                                            actionControlService = assembled.actionControlService,
                                                            dashboardStore = dashboardStore,
                                                            telegramConfig = telegramConfig,
                                                            telegramSink = telegramSink,
                                                            telegramAckTracker = telegramAckTracker,
                                                            sessionRecordingManager = sessionRecordingManager,
                                                            forwardNormalInput = { content, source, priority, conversationContext ->
                                                                sensoryInput.submitInput(
                                                                    content = content,
                                                                    source = source,
                                                                    priority = priority,
                                                                    conversationContext = conversationContext,
                                                                )
                                                            },
                                                            onApprovalExecuted = assembled.ego::processExternalApprovalExecuted,
                                                            onApprovalDenied = assembled.ego::processExternalApprovalDenied,
                                                            approvalInterpreterClient = approvalInterpreterClient,
                                                        )
                                                        approvalRuntime?.let { runtime ->
                                                            assembled.ego.setApprovalStagingHook(runtime)
                                                            chatBridge.setApprovalRuntime(runtime)
                                                            telegramUpdateProcessor?.setApprovalRuntime(runtime)
                                                            telegramWebhookBridge?.setApprovalRuntime(runtime)
                                                            dashboardServer?.actionControlMutationHandler =
                                                                runtime::handleLegacyActionControlMutation
                                                            agentScope.launch {
                                                                runtime.sendTelegramStartupAckIfEnabled()
                                                            }
                                                            agentScope.launch {
                                                                while (true) {
                                                                    kotlinx.coroutines.delay(1_000L)
                                                                    runtime.expirePendingRequests()
                                                                }
                                                            }
                                                        }
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
                                                            approvalRuntime?.close()
                                                            egoDispatcher.close()
                                                        }
                                                    }
                                            }
                                        } finally {
                                            approvalInterpreterClient.close()
                                            superegoEscalationClient?.close()
                                            metaReasonerFallbackClient?.close()
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
            sessionRecordingManager?.close()
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

        val sessionRecordingManager = SessionRecordingManager.fromEnvironment()
        val isSessionReplay = sessionRecordingManager?.mode == SessionRecordingMode.REPLAY

        val stdinContent = if (isSessionReplay) {
            // In session replay mode, signals come from the recording — stdin is not needed.
            logger.info { "freud-live: session replay mode — skipping stdin read" }
            ""
        } else {
            System.`in`.bufferedReader().readText().trim()
        }
        if (!isSessionReplay && stdinContent.isBlank()) {
            logger.warn { "No input provided on stdin for freud-live mode." }
            output.error("freud-live: no input on stdin.")
            exitProcess(1)
        }
        if (stdinContent.isNotBlank()) {
            logger.info { "freud-live: read ${stdinContent.length} chars from stdin" }
        }

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
        val signalSource: ai.neopsyke.agent.cortex.sensory.SignalSource =
            if (sessionRecordingManager != null) {
                logger.info { "Session ${sessionRecordingManager.mode.name.lowercase()} enabled for freud-live mode" }
                RecordingSignalSource(delegate = sensoryInput, channel = sessionRecordingManager.signals, manager = sessionRecordingManager)
            } else {
                sensoryInput
            }
        val sensoryCortex = SensoryCortex(
            config = config,
            source = signalSource,
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
                sensoryCortex.setInstrumentation(instrumentation)
                sessionRecordingManager?.setInstrumentation(instrumentation)
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
                                                val rawHippocampus2 = earlyMemoryStartup2.hippocampus
                                                val hippocampus = if (sessionRecordingManager != null) {
                                                    RecordingHippocampus(delegate = rawHippocampus2, channel = sessionRecordingManager.memoryRecall)
                                                } else {
                                                    rawHippocampus2
                                                }
                                                val rawLogbook2 = createLogbookIfEnabled(config)
                                                val logbook: Logbook? = if (sessionRecordingManager != null && rawLogbook2 != null) {
                                                    RecordingLogbook(delegate = rawLogbook2, channel = sessionRecordingManager.logbookRecall)
                                                } else {
                                                    rawLogbook2
                                                }
                                                val longTermMemoryAdvisor = LlmLongTermMemoryAdvisor(
                                                    modelClient = longTermMemoryClient,
                                                    config = config,
                                                    modelTokenWeight = llm.modelCatalog.tokenWeightFor(llm.memoryAdvisor),
                                                    modelContextWindow = llm.modelCatalog.contextWindowFor(llm.memoryAdvisor),
                                                    modelReasoningOverhead = llm.modelCatalog.reasoningOverheadFor(llm.memoryAdvisor),
                                                    instrumentation = instrumentation
                                                )
                                                val logbookSummarizer = createLogbookSummarizer(config, longTermMemoryClient)
                                                val activeWebSearchEngine2 = if (sessionRecordingManager != null) {
                                                    RecordingWebSearchEngine(delegate = runtime.engine, channel = sessionRecordingManager.webResults)
                                                } else {
                                                    runtime.engine
                                                }
                                                val webSearchActionHandler = WebSearchActionHandler(activeWebSearchEngine2)
                                                if (cliOptions.clearGoals) {
                                                    executeGoalsClear(config, instrumentation)
                                                }
                                                val goalManager = if (config.goals.enabled) {
                                                    ai.neopsyke.agent.goal.GoalManager(
                                                        config = config.goals,
                                                        store = ai.neopsyke.agent.goal.GoalStore(config.goals.workspaceRoot),
                                                        planner = ai.neopsyke.agent.goal.LlmGoalPlanner(plannerClient, config),
                                                        verifier = ai.neopsyke.agent.goal.LlmGoalStepVerifier(plannerClient, config),
                                                        instrumentation = instrumentation,
                                                        cueEmitter = sensoryCortex::offerGoalRuntimeCue,
                                                    ).also { it.start(agentScope) }
                                                } else {
                                                    null
                                                }
                                                val plannerLaneClientResolver = buildPlannerLaneModelClientResolver(
                                                    llm = llm,
                                                    plannerClient = plannerClient,
                                                    createPlannerClient = { endpoint ->
                                                        InstrumentedChatModelClient(
                                                            delegate = TokenBudgetGuardedChatClient(
                                                                delegate = maybeCacheWrap(
                                                                    createChatClient(
                                                                        endpoint = endpoint,
                                                                        callObserver = callObserverForProvider(endpoint.providerLabel)
                                                                    )
                                                                ),
                                                                budgetGate = tokenBudgetGate,
                                                                provider = endpoint.providerLabel,
                                                                role = LlmRoleLabels.PLANNER
                                                            ),
                                                            hooks = listOf(rawResponseHook)
                                                        )
                                                    }
                                                )
                                                val assembly = EgoAssembler.assemble(
                                                    config = config,
                                                    plannerFactory = { motorCortex ->
                                                        buildHierarchicalPlanner(
                                                            plannerClient = plannerClient,
                                                            config = config,
                                                            instrumentation = instrumentation,
                                                            actionPayloadRepair = motorCortex::repairPlannerPayload,
                                                            onPlannerNoop = { metrics.recordPlannerNoop() },
                                                            onPlannerOutputRepaired = { metrics.recordPlannerOutputRepaired() },
                                                            laneModelClientResolver = plannerLaneClientResolver,
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
                                                        val rawService = actionControlStore?.let { store ->
                                                            DefaultActionControlService(
                                                                config = config.actionControl,
                                                                store = store,
                                                                executeCommittedAction = { action, authorization ->
                                                                    motorCortex.execute(
                                                                        action,
                                                                        config.searchResultCount,
                                                                        authorization
                                                                    )
                                                                },
                                                                actionRegistry = motorCortex.actionRegistry(),
                                                            )
                                                        } ?: LegacyCompatibleActionControlService { action, authorization ->
                                                            motorCortex.execute(
                                                                action,
                                                                config.searchResultCount,
                                                                authorization
                                                            )
                                                        }
                                                        if (sessionRecordingManager != null) {
                                                            RecordingActionControlService(delegate = rawService, channel = sessionRecordingManager.actionControl)
                                                        } else {
                                                            rawService
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

                                                    if (!isSessionReplay) {
                                                        sensoryInput.submitInput(
                                                            content = stdinContent,
                                                            source = "freud-live",
                                                            conversationContext = freudLiveConversationContext(),
                                                        )
                                                    }

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
                                                    sessionRecordingManager?.close()
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
                    primaryContextWindow = llm.modelCatalog.contextWindowFor(explicitPrimary),
                    primaryReasoningOverhead = llm.modelCatalog.reasoningOverheadFor(explicitPrimary)
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
                primaryContextWindow = llm.modelCatalog.contextWindowFor(explicitPrimary),
                primaryReasoningOverhead = llm.modelCatalog.reasoningOverheadFor(explicitPrimary)
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
                primaryContextWindow = llm.modelCatalog.contextWindowFor(explicitEscalation),
                primaryReasoningOverhead = llm.modelCatalog.reasoningOverheadFor(explicitEscalation)
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
            primaryContextWindow = llm.modelCatalog.contextWindowFor(legacyEndpoint),
            primaryReasoningOverhead = llm.modelCatalog.reasoningOverheadFor(legacyEndpoint)
        )
    }
    
    private fun createChatClient(
        endpoint: LlmEndpointConfig,
        callObserver: ai.neopsyke.llm.ChatCallObserver? = null,
    ): ChatModelClient {
        val raw: ChatModelClient = when (endpoint.provider) {
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
        return AdaptiveStructuredOutputChatClient(
            delegate = raw,
            provider = endpoint.providerLabel
        )
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

    private fun createActionAuthorizationPolicy(config: AgentConfig): ActionAuthorizationPolicy =
        ConfiguredActionAuthorizationPolicy(
            ActionSecurityPolicyLoader.load(
                Paths.get(config.actionControl.policyPath)
            )
        )

    private fun createActionControlStoreIfEnabled(config: AgentConfig): ActionControlStore? {
        if (!config.actionControl.enabled) {
            return null
        }
        return try {
            SqliteActionControlStore(config.actionControl.dbPath)
        } catch (ex: Exception) {
            logger.warn(ex) { "Action-control store initialization failed; continuing with legacy action control." }
            null
        }
    }

    private fun createApprovalRuntime(
        config: AgentConfig,
        actionControlService: ai.neopsyke.agent.cortex.motor.actions.control.ActionControlService,
        dashboardStore: DashboardStateStore,
        telegramConfig: ai.neopsyke.agent.config.TelegramChannelConfig,
        telegramSink: ai.neopsyke.agent.cortex.motor.actions.TelegramMessageSink?,
        telegramAckTracker: ai.neopsyke.agent.cortex.motor.actions.TelegramStartupAckTracker = ai.neopsyke.agent.cortex.motor.actions.TelegramStartupAckTracker(),
        sessionRecordingManager: SessionRecordingManager? = null,
        forwardNormalInput: (String, String, ai.neopsyke.agent.model.InputPriority, ConversationContext) -> Boolean,
        onApprovalExecuted: (ai.neopsyke.agent.cortex.motor.actions.control.ActionControlDecisionResult.Executed) -> Unit,
        onApprovalDenied: (ai.neopsyke.agent.cortex.motor.actions.control.ActionControlDecisionResult.Cancelled) -> Unit,
        approvalInterpreterClient: ChatModelClient? = null,
    ): ApprovalRuntime? {
        if (!config.approvals.enabled || !config.actionControl.enabled) {
            return null
        }
        val store = try {
            SqliteApprovalStore(config.actionControl.dbPath)
        } catch (ex: Exception) {
            logger.warn(ex) { "Approval store initialization failed; approval router disabled." }
            return null
        }
        return ApprovalRuntime(
            config = config,
            store = store,
            actionControlService = actionControlService,
            dashboardStore = dashboardStore,
            telegramConfig = telegramConfig,
            telegramSink = telegramSink,
            interpreter = DefaultApprovalInterpreter(
                config = config,
                llmClient = approvalInterpreterClient,
            ),
            forwardNormalInput = forwardNormalInput,
            onApprovalExecuted = onApprovalExecuted,
            onApprovalDenied = onApprovalDenied,
            sessionRecordingManager = sessionRecordingManager,
            telegramAckTracker = telegramAckTracker,
        )
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
        return ActionControlAutonomousWorker(
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

    private fun executeGoalsClear(
        config: AgentConfig,
        instrumentation: ai.neopsyke.instrumentation.AgentInstrumentation,
    ) {
        val goalsRoot = config.goals.workspaceRoot
        if (!java.nio.file.Files.isDirectory(goalsRoot)) {
            output.info("Goals workspace does not exist; nothing to clear.")
            return
        }
        try {
            var deleted = 0
            java.nio.file.Files.list(goalsRoot).use { stream ->
                stream.filter { java.nio.file.Files.isDirectory(it) }.forEach { goalDir ->
                    java.nio.file.Files.walk(goalDir)
                        .sorted(Comparator.reverseOrder())
                        .forEach { java.nio.file.Files.deleteIfExists(it) }
                    deleted++
                }
            }
            output.info("Goals cleared ($deleted goal(s) removed).")
            logger.info { "CLI --clear-goals: $deleted goal workspace(s) removed from $goalsRoot." }
            instrumentation.emit(
                ai.neopsyke.instrumentation.AgentEvents.warning(
                    "Goals cleared via CLI: $deleted goal(s) removed."
                )
            )
        } catch (ex: Exception) {
            output.error("Failed to clear goals: ${ex.message}")
            logger.warn(ex) { "CLI --clear-goals: failed to clear goals workspace at $goalsRoot." }
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

private fun plannerLaneEndpointTemplates(llm: LlmRuntimeConfig): Map<String, LlmEndpointConfig> {
    val templates = linkedMapOf<String, LlmEndpointConfig>()
    val endpoints = listOfNotNull(
        llm.planner,
        llm.superego,
        llm.metaReasoner,
        llm.metaReasonerFallback,
        llm.memoryAdvisor,
        llm.approvalInterpreter,
        llm.superegoPrimary,
        llm.superegoEscalation,
        llm.webSearch,
    )
    for (endpoint in endpoints) {
        val provider = endpoint.providerLabel.lowercase()
        templates.putIfAbsent(provider, endpoint)
    }
    return templates
}

private fun buildPlannerLaneModelClientResolver(
    llm: LlmRuntimeConfig,
    plannerClient: ChatModelClient,
    createPlannerClient: (LlmEndpointConfig) -> ChatModelClient,
): (LaneId, ai.neopsyke.agent.ego.planner.ResolvedLaneConfig) -> ChatModelClient? {
    val endpointTemplates = plannerLaneEndpointTemplates(llm)
    val plannerProvider = llm.planner.providerLabel.lowercase()
    val plannerModel = llm.planner.model.trim()
    val clientsByKey = mutableMapOf<String, ChatModelClient>()
    val warnedUnknownProviders = mutableSetOf<String>()

    return { laneId, resolved ->
        // Priority 1: per-lane endpoint from llm-runtime.yaml cognitive_roles.planner.lanes
        val laneEndpoint = llm.cognitiveRoles.plannerLanes[laneId.configKey]
        if (laneEndpoint != null) {
            val provider = laneEndpoint.providerLabel.lowercase()
            val model = laneEndpoint.model.trim()
            if (provider == plannerProvider && model == plannerModel) {
                plannerClient
            } else {
                val cacheKey = "$provider::$model"
                clientsByKey.getOrPut(cacheKey) {
                    createPlannerClient(laneEndpoint)
                }
            }
        } else {
            // Priority 2: per-lane provider/model from agent-runtime.yaml planner.lanes
            val requestedProvider = resolved.provider?.trim()?.lowercase()?.ifBlank { null }
            val requestedModel = resolved.model?.trim()?.ifBlank { null }
            if (requestedProvider == null && requestedModel == null) {
                null
            } else {
                val provider = requestedProvider ?: plannerProvider
                val template = endpointTemplates[provider]
                if (template == null) {
                    if (warnedUnknownProviders.add(provider)) {
                        logger.warn {
                            "Lane ${laneId.configKey} requested provider '$provider' but no endpoint template is configured; using default planner client."
                        }
                    }
                    null
                } else {
                    val model = requestedModel ?: template.model
                    if (provider == plannerProvider && model == plannerModel) {
                        plannerClient
                    } else {
                        val cacheKey = "$provider::$model"
                        clientsByKey.getOrPut(cacheKey) {
                            createPlannerClient(template.copy(model = model))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Factory for the HierarchicalEgoPlanner, replacing LlmEgoPlanner.
 */
private fun buildHierarchicalPlanner(
    plannerClient: ai.neopsyke.llm.ChatModelClient,
    config: ai.neopsyke.agent.config.AgentConfig,
    instrumentation: ai.neopsyke.instrumentation.AgentInstrumentation,
    actionPayloadRepair: (ai.neopsyke.agent.model.ActionType, String) -> String,
    onPlannerNoop: () -> Unit = {},
    onPlannerOutputRepaired: () -> Unit = {},
    laneModelClientResolver: ((LaneId, ai.neopsyke.agent.ego.planner.ResolvedLaneConfig) -> ai.neopsyke.llm.ChatModelClient?)? = null,
): Ego.Planner {
    val runtime = PlannerRuntime(
        defaultModelClient = plannerClient,
        config = config,
        instrumentation = instrumentation,
        onPlannerNoop = onPlannerNoop,
        onPlannerOutputRepaired = onPlannerOutputRepaired,
        actionPayloadRepair = actionPayloadRepair,
        laneModelClientResolver = laneModelClientResolver ?: { _, _ -> null },
    )

    val router = InputIntentRouter(runtime, config, instrumentation)
    val groundingClassifier = GroundingClassifier(runtime, config, instrumentation)
    val directResponse = DirectResponsePlanner(runtime, config, instrumentation)
    val generalAction = GeneralActionPlanner(runtime, config, instrumentation)
    val taskDecomp = TaskDecompositionPlanner(runtime, config, instrumentation)
    val goalCreation = GoalCreationPlanner(runtime, config, instrumentation)
    val goalManagement = GoalManagementPlanner(runtime, config, instrumentation)

    val inputPlanner = InputPlanner(
        runtime = runtime,
        config = config,
        instrumentation = instrumentation,
        router = router,
        groundingClassifier = groundingClassifier,
        directResponsePlanner = directResponse,
        generalActionPlanner = generalAction,
        taskDecompositionPlanner = taskDecomp,
        goalCreationPlanner = goalCreation,
        goalManagementPlanner = goalManagement,
    )

    val deferredStepPlanner = DeferredStepPlanner(runtime, config, instrumentation)
    val feedbackPlanner = FeedbackPlanner(runtime, config, instrumentation)
    val goalWorkPlannerLane = GoalWorkPlanner(runtime, config, instrumentation)
    val impulsePlannerLane = ImpulsePlanner(runtime, config, instrumentation)

    return HierarchicalEgoPlanner(
        runtime = runtime,
        instrumentation = instrumentation,
        inputPlanner = inputPlanner,
        deferredStepPlanner = deferredStepPlanner,
        feedbackPlanner = feedbackPlanner,
        goalWorkPlanner = goalWorkPlannerLane,
        impulsePlanner = impulsePlannerLane,
    )
}
