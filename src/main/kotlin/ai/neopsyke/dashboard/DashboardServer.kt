package ai.neopsyke.dashboard

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.selects.select
import mu.KotlinLogging
import ai.neopsyke.agent.cortex.motor.actions.control.ActionControlDecisionResult
import ai.neopsyke.agent.cortex.motor.actions.control.ActionControlService
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.ConversationSecurityContexts
import ai.neopsyke.agent.model.StagedAction
import ai.neopsyke.agent.model.StagedActionStatus
import ai.neopsyke.agent.durablework.DurableWorkRuntime
import ai.neopsyke.integrations.google.GoogleWorkspaceOAuthBridge
import ai.neopsyke.integrations.telegram.TelegramWebhookBridge
import ai.neopsyke.metrics.LlmCallStatsReport
import java.nio.file.Files
import java.nio.file.Path
import ai.neopsyke.metrics.MetricsQueryProvider
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}
private const val SSE_HEARTBEAT_TIMEOUT_MS: Long = 5_000L
private const val LLM_STATS_CACHE_TTL_MS: Long = 10_000L
private const val LLM_STATS_WARMUP_STATUS: Int = 202
private const val LLM_STATS_MAX_PARALLEL_REFRESH: Int = 1
private const val SNAPSHOT_DEFAULT_EVENTS_LIMIT: Int = 300
private const val SNAPSHOT_MAX_EVENTS_LIMIT: Int = 1_000
private const val RESPONSE_STATUS_ATTRIBUTE: String = "dashboard.response_status"

class DashboardServer(
    private val store: DashboardStateStore,
    private val chatBridge: ChatRuntimeBridge? = null,
    private val telegramWebhookBridge: TelegramWebhookBridge? = null,
    private val googleOAuthBridge: GoogleWorkspaceOAuthBridge? = null,
    private val innerVoiceStore: InnerVoiceStore? = null,
    private val idInnerVoiceFilePath: Path? = null,
    @Volatile var metricsQueryProvider: MetricsQueryProvider? = null,
    @Volatile var durableWorkRuntime: DurableWorkRuntime? = null,
    @Volatile var actionControlService: ActionControlService? = null,
    @Volatile var actionControlMutationHandler: ((String, ActionControlDecisionResult) -> Unit)? = null,
    port: Int,
    host: String = "127.0.0.1",
) : Closeable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress(host, port), 0)
    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
    private val llmStatsRefreshExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val llmStatsRefreshSemaphore = Semaphore(LLM_STATS_MAX_PARALLEL_REFRESH)
    private val llmStatsCache = ConcurrentHashMap<String, LlmStatsCacheEntry>()
    private val llmStatsRefreshInFlight = ConcurrentHashMap.newKeySet<String>()
    private val mapper = jacksonObjectMapper()
    val url: String = "http://$host:$port/"

    init {
        server.executor = executor
        server.createContext("/") { exchange ->
            withRequestGuard(exchange, "root_page") {
                if (exchange.requestURI.path != "/") {
                    respondText(exchange, 404, "Not found", "text/plain; charset=utf-8")
                    return@withRequestGuard
                }
                respondText(exchange, 200, DashboardAssets.shellHtml, "text/html; charset=utf-8")
            }
        }
        server.createContext("/dashboard") { exchange ->
            withRequestGuard(exchange, "observability_page") {
                if (exchange.requestURI.path != "/dashboard") {
                    respondText(exchange, 404, "Not found", "text/plain; charset=utf-8")
                    return@withRequestGuard
                }
                respondText(exchange, 200, DashboardAssets.shellHtml, "text/html; charset=utf-8")
            }
        }
        server.createContext("/metrics") { exchange ->
            withRequestGuard(exchange, "metrics_page") {
                if (exchange.requestURI.path != "/metrics") {
                    respondText(exchange, 404, "Not found", "text/plain; charset=utf-8")
                    return@withRequestGuard
                }
                respondText(exchange, 200, DashboardAssets.shellHtml, "text/html; charset=utf-8")
            }
        }
        server.createContext("/goals") { exchange ->
            withRequestGuard(exchange, "goals_page") {
                if (exchange.requestURI.path != "/goals") {
                    respondText(exchange, 404, "Not found", "text/plain; charset=utf-8")
                    return@withRequestGuard
                }
                respondText(exchange, 200, DashboardAssets.shellHtml, "text/html; charset=utf-8")
            }
        }
        server.createContext("/action-control") { exchange ->
            withRequestGuard(exchange, "action_control_page") {
                if (exchange.requestURI.path != "/action-control") {
                    respondText(exchange, 404, "Not found", "text/plain; charset=utf-8")
                    return@withRequestGuard
                }
                respondText(exchange, 200, DashboardAssets.shellHtml, "text/html; charset=utf-8")
            }
        }
        server.createContext("/__dashboard/assets") { exchange ->
            withRequestGuard(exchange, "dashboard_asset") {
                handleDashboardAsset(exchange)
            }
        }
        server.createContext("/__dashboard") { exchange ->
            withRequestGuard(exchange, "embedded_dashboard_page") {
                handleEmbeddedDashboardPage(exchange)
            }
        }
        server.createContext("/api/goals") { exchange ->
            withRequestGuard(exchange, "goals_api") {
                handleGoalsApi(exchange)
            }
        }
        server.createContext("/api/goals/events") { exchange ->
            withRequestGuard(exchange, "goals_events_sse") {
                if (exchange.requestMethod != "GET") {
                    respondText(exchange, 405, "Method not allowed", "text/plain; charset=utf-8")
                    return@withRequestGuard
                }
                handleGoalsSse(exchange)
            }
        }
        server.createContext("/api/obs/llm-stats") { exchange ->
            withRequestGuard(exchange, "obs_llm_stats") {
                if (exchange.requestMethod != "GET") {
                    respondText(exchange, 405, "Method not allowed", "text/plain; charset=utf-8")
                    return@withRequestGuard
                }
                handleLlmStatsApi(exchange)
            }
        }
        server.createContext("/api/obs/snapshot") { exchange ->
            withRequestGuard(exchange, "obs_snapshot") {
                if (exchange.requestMethod != "GET") {
                    respondText(exchange, 405, "Method not allowed", "text/plain; charset=utf-8")
                    return@withRequestGuard
                }
                val query = exchange.requestURI.query
                val eventsLimit = parseQueryParam(query, "events_limit")
                    ?.toIntOrNull()
                    ?.coerceIn(0, SNAPSHOT_MAX_EVENTS_LIMIT)
                    ?: SNAPSHOT_DEFAULT_EVENTS_LIMIT
                val includeHeavyEvents = parseBooleanQueryParam(query, "include_heavy_events") ?: false
                respondText(
                    exchange,
                    200,
                    store.snapshotJson(eventsLimit = eventsLimit, includeHeavyEvents = includeHeavyEvents),
                    "application/json; charset=utf-8"
                )
            }
        }
        server.createContext("/api/obs/events") { exchange ->
            withRequestGuard(exchange, "obs_events_sse") {
                if (exchange.requestMethod != "GET") {
                    respondText(exchange, 405, "Method not allowed", "text/plain; charset=utf-8")
                    return@withRequestGuard
                }
                handleSse(exchange)
            }
        }
        server.createContext("/api/obs/threads") { exchange ->
            withRequestGuard(exchange, "obs_threads") {
                if (exchange.requestMethod != "GET") {
                    respondText(exchange, 405, "Method not allowed", "text/plain; charset=utf-8")
                    return@withRequestGuard
                }
                handleThreadApi(exchange)
            }
        }
        server.createContext("/api/dashboard/events") { exchange ->
            withRequestGuard(exchange, "dashboard_events_sse") {
                if (exchange.requestMethod != "GET") {
                    respondText(exchange, 405, "Method not allowed", "text/plain; charset=utf-8")
                    return@withRequestGuard
                }
                handleDashboardEventsSse(exchange)
            }
        }
        server.createContext("/api/obs/scratchpad") { exchange ->
            withRequestGuard(exchange, "obs_scratchpad") {
                if (exchange.requestMethod != "GET") {
                    respondText(exchange, 405, "Method not allowed", "text/plain; charset=utf-8")
                    return@withRequestGuard
                }
                handleScratchpadApi(exchange)
            }
        }
        server.createContext("/api/chat/sessions") { exchange ->
            withRequestGuard(exchange, "chat_api") {
                handleChatApi(exchange)
            }
        }
        telegramWebhookBridge?.let { bridge ->
            server.createContext(bridge.webhookPath()) { exchange ->
                withRequestGuard(exchange, "telegram_webhook") {
                    handleTelegramWebhook(exchange, bridge)
                }
            }
        }
        googleOAuthBridge?.let { bridge ->
            server.createContext(bridge.startPath()) { exchange ->
                withRequestGuard(exchange, "google_oauth_start") {
                    handleGoogleOAuthStart(exchange, bridge)
                }
            }
            server.createContext(bridge.callbackPath()) { exchange ->
                withRequestGuard(exchange, "google_oauth_callback") {
                    handleGoogleOAuthCallback(exchange, bridge)
                }
            }
        }
        server.createContext("/api/action-control") { exchange ->
            withRequestGuard(exchange, "action_control_api") {
                handleActionControlApi(exchange)
            }
        }
        server.createContext("/api/id-thinking/history") { exchange ->
            withRequestGuard(exchange, "id_thinking_history") {
                if (exchange.requestMethod != "GET") {
                    respondText(exchange, 405, "Method not allowed", "text/plain; charset=utf-8")
                    return@withRequestGuard
                }
                handleIdThinkingHistory(exchange)
            }
        }
        server.createContext("/api/id-thinking") { exchange ->
            withRequestGuard(exchange, "id_thinking_sse") {
                val path = exchange.requestURI.path
                if (path == "/api/id-thinking/history") {
                    // Already handled by more specific context above
                    return@withRequestGuard
                }
                if (exchange.requestMethod != "GET") {
                    respondText(exchange, 405, "Method not allowed", "text/plain; charset=utf-8")
                    return@withRequestGuard
                }
                handleIdThinkingSse(exchange)
            }
        }
        server.createContext("/health") { exchange ->
            withRequestGuard(exchange, "health") {
                respondText(exchange, 200, "ok", "text/plain; charset=utf-8")
            }
        }
    }

    fun start() {
        server.start()
        logger.info { "Dashboard server started at $url" }
    }

    override fun close() {
        try {
            server.stop(0)
        } finally {
            llmStatsRefreshExecutor.shutdownNow()
            try {
                llmStatsRefreshExecutor.awaitTermination(1, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            executor.shutdownNow()
            try {
                executor.awaitTermination(1, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        logger.info { "Dashboard server stopped." }
    }

    private fun handleSse(exchange: HttpExchange) {
        handleSubscriptionSse(
            exchange = exchange,
            subscription = store.subscribe(),
            expectedDisconnectMessage = "Observability SSE client disconnected.",
            unexpectedDisconnectMessage = "Observability SSE stream terminated unexpectedly.",
            streamName = "obs_events_sse"
        )
    }

    private fun handleDashboardAsset(exchange: HttpExchange) {
        if (exchange.requestMethod.uppercase() != "GET") {
            respondText(exchange, 405, "Method not allowed", "text/plain; charset=utf-8")
            return
        }
        val fileName = exchange.requestURI.path.removePrefix("/__dashboard/assets/").trim()
        if (fileName.isEmpty() || fileName.contains("..") || fileName.contains("/")) {
            respondText(exchange, 400, "Bad request", "text/plain; charset=utf-8")
            return
        }
        val resourcePath = "/dashboard/assets/$fileName"
        val stream = DashboardAssets::class.java.getResourceAsStream(resourcePath)
        if (stream == null) {
            respondText(exchange, 404, "Not found", "text/plain; charset=utf-8")
            return
        }
        val bytes = stream.use { it.readBytes() }
        val contentType = when {
            fileName.endsWith(".png") -> "image/png"
            fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") -> "image/jpeg"
            fileName.endsWith(".svg") -> "image/svg+xml"
            fileName.endsWith(".ico") -> "image/x-icon"
            else -> "application/octet-stream"
        }
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.responseHeaders.add("Cache-Control", "public, max-age=86400")
        recordResponseStatus(exchange, 200)
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun handleEmbeddedDashboardPage(exchange: HttpExchange) {
        if (exchange.requestMethod.uppercase() != "GET") {
            respondText(exchange, 405, "Method not allowed", "text/plain; charset=utf-8")
            return
        }
        val path = exchange.requestURI.path.removePrefix("/__dashboard").trim()
        val asset = when (path) {
            "/conversations" -> DashboardAssets.conversationsHtml
            "/observability" -> DashboardAssets.observabilityHtml
            "/metrics" -> DashboardAssets.metricsHtml
            "/goals" -> DashboardAssets.goalsHtml
            "/action-control" -> DashboardAssets.actionControlHtml
            else -> null
        }
        if (asset == null) {
            respondText(exchange, 404, "Not found", "text/plain; charset=utf-8")
            return
        }
        respondText(exchange, 200, asset, "text/html; charset=utf-8")
    }

    private fun handleGoalsSse(exchange: HttpExchange) {
        handleSubscriptionSse(
            exchange = exchange,
            subscription = store.subscribe { event -> event.type.startsWith("goal_") },
            expectedDisconnectMessage = "Goals SSE client disconnected.",
            unexpectedDisconnectMessage = "Goals SSE stream terminated unexpectedly.",
            streamName = "goals_events_sse"
        )
    }

    private fun handleActionControlSse(exchange: HttpExchange) {
        handleSubscriptionSse(
            exchange = exchange,
            subscription = store.subscribeActionControl(),
            expectedDisconnectMessage = "Action-control SSE client disconnected.",
            unexpectedDisconnectMessage = "Action-control SSE stream terminated unexpectedly.",
            streamName = "action_control_events_sse",
            eventName = "action-control",
        )
    }

    private fun handleDashboardEventsSse(exchange: HttpExchange) {
        val agentSubscription = store.subscribe()
        val actionControlSubscription = store.subscribeActionControl()
        val idThinkingSubscription = innerVoiceStore?.subscribeIdGlobal()

        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.responseHeaders.add("Cache-Control", "no-cache")
        exchange.responseHeaders.add("Connection", "keep-alive")
        recordResponseStatus(exchange, 200)
        exchange.sendResponseHeaders(200, 0)

        val output = exchange.responseBody.bufferedWriter(StandardCharsets.UTF_8)
        val agentChannel = agentSubscription.asReceiveChannel()
        val actionControlChannel = actionControlSubscription.asReceiveChannel()
        val idThinkingChannel = idThinkingSubscription?.asReceiveChannel()
        try {
            output.write("event: ready\n")
            output.write("data: {\"status\":\"connected\"}\n\n")
            output.flush()
            runBlocking {
                while (true) {
                    val nextEvent = withTimeoutOrNull(SSE_HEARTBEAT_TIMEOUT_MS) {
                        select<NamedSseEvent> {
                            agentChannel.onReceiveCatching { result ->
                                NamedSseEvent(eventName = "agent", payload = result.getOrThrow())
                            }
                            actionControlChannel.onReceiveCatching { result ->
                                NamedSseEvent(eventName = "action-control", payload = result.getOrThrow())
                            }
                            if (idThinkingChannel != null) {
                                idThinkingChannel.onReceiveCatching { result ->
                                    NamedSseEvent(eventName = "id-thinking", payload = result.getOrThrow())
                                }
                            }
                        }
                    }
                    if (nextEvent != null) {
                        output.write("event: ${nextEvent.eventName}\n")
                        output.write("data: ${nextEvent.payload}\n\n")
                        output.flush()
                    } else {
                        output.write(": heartbeat\n\n")
                        output.flush()
                    }
                }
            }
        } catch (ex: Exception) {
            if (isExpectedClientDisconnect(ex)) {
                logger.debug { "Dashboard multiplexed SSE client disconnected." }
            } else {
                logger.warn(ex) { "Dashboard multiplexed SSE stream terminated unexpectedly." }
            }
        } finally {
            agentSubscription.close()
            actionControlSubscription.close()
            idThinkingSubscription?.close()
            closeStreamQuietly(output, streamName = "dashboard_events_sse")
        }
    }

    private fun handleSubscriptionSse(
        exchange: HttpExchange,
        subscription: DashboardFlowSubscription,
        expectedDisconnectMessage: String,
        unexpectedDisconnectMessage: String,
        streamName: String,
        eventName: String = "agent",
    ) {
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.responseHeaders.add("Cache-Control", "no-cache")
        exchange.responseHeaders.add("Connection", "keep-alive")
        recordResponseStatus(exchange, 200)
        exchange.sendResponseHeaders(200, 0)

        val output = exchange.responseBody.bufferedWriter(StandardCharsets.UTF_8)
        try {
            output.write("event: ready\n")
            output.write("data: {\"status\":\"connected\"}\n\n")
            output.flush()
            runBlocking {
                while (true) {
                    val payload = withTimeoutOrNull(SSE_HEARTBEAT_TIMEOUT_MS) {
                        subscription.receive()
                    }
                    if (payload != null) {
                        output.write("event: $eventName\n")
                        output.write("data: $payload\n\n")
                        output.flush()
                    } else {
                        output.write(": heartbeat\n\n")
                        output.flush()
                    }
                }
            }
        } catch (ex: Exception) {
            if (isExpectedClientDisconnect(ex)) {
                logger.debug { expectedDisconnectMessage }
            } else {
                logger.warn(ex) { unexpectedDisconnectMessage }
            }
        } finally {
            subscription.close()
            closeStreamQuietly(output, streamName = streamName)
        }
    }

    private fun handleScratchpadApi(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        if (path == "/api/obs/scratchpad") {
            respondText(exchange, 200, store.scratchpadIndexJson(), "application/json; charset=utf-8")
            return
        }
        val rootIdRaw = path.removePrefix("/api/obs/scratchpad/").trim()
        if (rootIdRaw.isBlank() || rootIdRaw == path) {
            respondText(exchange, 404, "Not found", "text/plain; charset=utf-8")
            return
        }
        val rootId = rootIdRaw
        val version = parseQueryParam(exchange.requestURI.query, "version")?.toLongOrNull()
        val snapshot = store.scratchpadSnapshotJson(rootInputId = rootId, version = version)
        if (snapshot == null) {
            respondText(exchange, 404, "Scratchpad snapshot not found", "text/plain; charset=utf-8")
            return
        }
        respondText(exchange, 200, snapshot, "application/json; charset=utf-8")
    }

    private fun handleThreadApi(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        if (path == "/api/obs/threads") {
            val query = exchange.requestURI.query
            val includeTerminal = parseBooleanQueryParam(query, "include_terminal") ?: false
            val limit = parseQueryParam(query, "limit")?.toIntOrNull() ?: 100
            respondText(
                exchange,
                200,
                store.threadIndexJson(includeTerminal = includeTerminal, limit = limit),
                "application/json; charset=utf-8"
            )
            return
        }
        val threadId = path.removePrefix("/api/obs/threads/").trim().takeIf { it.isNotBlank() }
        if (threadId == null) {
            respondText(exchange, 404, "Not found", "text/plain; charset=utf-8")
            return
        }
        val snapshot = store.threadSnapshotJson(threadId)
        if (snapshot == null) {
            respondText(exchange, 404, "Cognitive thread not found", "text/plain; charset=utf-8")
            return
        }
        respondText(exchange, 200, snapshot, "application/json; charset=utf-8")
    }

    private fun handleChatApi(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val basePath = "/api/chat/sessions"
        if (!(path == basePath || path.startsWith("$basePath/"))) {
            respondText(exchange, 404, "Not found", "text/plain; charset=utf-8")
            return
        }
        if (chatBridge == null) {
            respondText(exchange, 503, "Chat API unavailable", "text/plain; charset=utf-8")
            return
        }
        val suffix = path.removePrefix(basePath).trim()
        if (suffix.isBlank()) {
            when (exchange.requestMethod.uppercase()) {
                "GET" -> respondText(exchange, 200, chatBridge.listSessionsJson(), "application/json; charset=utf-8")
                "POST" -> handleCreateChatSession(exchange, chatBridge)
                else -> respondText(exchange, 405, "Method not allowed", "text/plain; charset=utf-8")
            }
            return
        }

        val normalizedSuffix = suffix.removePrefix("/")
        if (normalizedSuffix.isBlank()) {
            respondText(exchange, 404, "Not found", "text/plain; charset=utf-8")
            return
        }
        val parts = normalizedSuffix.split("/")
        val sessionId = parts.first().trim()
        if (sessionId.isBlank()) {
            respondText(exchange, 400, "Invalid session id", "text/plain; charset=utf-8")
            return
        }
        if (parts.size == 1) {
            if (exchange.requestMethod.uppercase() != "GET") {
                respondText(exchange, 405, "Method not allowed", "text/plain; charset=utf-8")
                return
            }
            val payload = chatBridge.sessionJson(sessionId)
            if (payload == null) {
                respondText(exchange, 404, "Session not found", "text/plain; charset=utf-8")
                return
            }
            respondText(exchange, 200, payload, "application/json; charset=utf-8")
            return
        }

        val action = parts.getOrNull(1)?.trim().orEmpty()
        when (action) {
            "messages" -> {
                if (exchange.requestMethod.uppercase() != "POST") {
                    respondText(exchange, 405, "Method not allowed", "text/plain; charset=utf-8")
                    return
                }
                handleSubmitChatMessage(exchange, chatBridge, sessionId)
            }
            "events" -> {
                if (exchange.requestMethod.uppercase() != "GET") {
                    respondText(exchange, 405, "Method not allowed", "text/plain; charset=utf-8")
                    return
                }
                handleChatSse(exchange, chatBridge, sessionId)
            }
            "stream" -> {
                if (exchange.requestMethod.uppercase() != "GET") {
                    respondText(exchange, 405, "Method not allowed", "text/plain; charset=utf-8")
                    return
                }
                handleConversationStreamSse(exchange, chatBridge, sessionId)
            }
            "thinking" -> {
                if (exchange.requestMethod.uppercase() != "GET") {
                    respondText(exchange, 405, "Method not allowed", "text/plain; charset=utf-8")
                    return
                }
                handleThinkingSse(exchange, sessionId)
            }
            else -> respondText(exchange, 404, "Not found", "text/plain; charset=utf-8")
        }
    }

    private fun handleActionControlApi(exchange: HttpExchange) {
        val service = actionControlService ?: run {
            respondText(exchange, 503, """{"error":"Action control unavailable"}""", "application/json; charset=utf-8")
            return
        }
        val path = exchange.requestURI.path
        val basePath = "/api/action-control"
        if (!(path == basePath || path.startsWith("$basePath/"))) {
            respondText(exchange, 404, "Not found", "text/plain; charset=utf-8")
            return
        }
        val suffix = path.removePrefix(basePath).trim()
        if (suffix.isBlank()) {
            respondText(
                exchange,
                200,
                mapper.writeValueAsString(
                    mapOf(
                        "staged_path" to "/api/action-control/staged",
                        "receipts_path" to "/api/action-control/receipts"
                    )
                ),
                "application/json; charset=utf-8"
            )
            return
        }
        val parts = suffix.removePrefix("/").split("/").filter { it.isNotBlank() }
        if (parts.isEmpty()) {
            respondText(exchange, 404, "Not found", "text/plain; charset=utf-8")
            return
        }
        when (parts.first()) {
            "events" -> {
                if (exchange.requestMethod.uppercase() != "GET") {
                    respondText(exchange, 405, "Method not allowed", "text/plain; charset=utf-8")
                    return
                }
                handleActionControlSse(exchange)
            }
            "staged" -> handleStagedActionApi(exchange, service, parts.drop(1))
            "receipts" -> handleActionReceiptApi(exchange, service, parts.drop(1))
            "ledger" -> handleActionLedgerApi(exchange, service, parts.drop(1))
            else -> respondText(exchange, 404, "Not found", "text/plain; charset=utf-8")
        }
    }

    private fun handleTelegramWebhook(exchange: HttpExchange, bridge: TelegramWebhookBridge) {
        val result = bridge.handleWebhook(
            requestMethod = exchange.requestMethod,
            secretTokenHeader = exchange.requestHeaders.getFirst(TELEGRAM_SECRET_HEADER),
            requestBody = readRawBody(exchange),
        )
        respondText(
            exchange,
            result.statusCode,
            mapper.writeValueAsString(
                mapOf(
                    "accepted" to result.accepted,
                    "detail" to result.detail,
                )
            ),
            "application/json; charset=utf-8"
        )
    }

    private fun handleGoogleOAuthStart(exchange: HttpExchange, bridge: GoogleWorkspaceOAuthBridge) {
        if (exchange.requestMethod.uppercase() != "GET") {
            respondText(exchange, 405, "Method not allowed", "text/plain; charset=utf-8")
            return
        }
        val result = bridge.startAuthorization()
        respondText(
            exchange,
            result.statusCode,
            mapper.writeValueAsString(
                mapOf(
                    "ok" to result.ok,
                    "detail" to result.detail,
                    "authorization_url" to result.authorizationUrl,
                )
            ),
            "application/json; charset=utf-8"
        )
    }

    private fun handleGoogleOAuthCallback(exchange: HttpExchange, bridge: GoogleWorkspaceOAuthBridge) {
        if (exchange.requestMethod.uppercase() != "GET") {
            respondText(exchange, 405, "Method not allowed", "text/plain; charset=utf-8")
            return
        }
        val result = bridge.completeAuthorization(
            code = parseQueryParam(exchange.requestURI.query, "code"),
            stateToken = parseQueryParam(exchange.requestURI.query, "state"),
        )
        respondText(
            exchange,
            result.statusCode,
            mapper.writeValueAsString(
                mapOf(
                    "ok" to result.ok,
                    "detail" to result.detail,
                )
            ),
            "application/json; charset=utf-8"
        )
    }

    private fun handleStagedActionApi(
        exchange: HttpExchange,
        service: ActionControlService,
        suffixParts: List<String>,
    ) {
        if (suffixParts.isEmpty()) {
            if (exchange.requestMethod.uppercase() != "GET") {
                respondText(exchange, 405, "Method not allowed", "text/plain; charset=utf-8")
                return
            }
            val limit = parseQueryParam(exchange.requestURI.query, "limit")?.toIntOrNull() ?: 50
            val includeTerminal = parseBooleanQueryParam(exchange.requestURI.query, "include_terminal") ?: false
            respondText(
                exchange,
                200,
                mapper.writeValueAsString(service.stagedActions(limit, includeTerminal = includeTerminal)),
                "application/json; charset=utf-8"
            )
            return
        }
        val stagedActionId = suffixParts.first()
        if (suffixParts.size == 1) {
            if (exchange.requestMethod.uppercase() != "GET") {
                respondText(exchange, 405, "Method not allowed", "text/plain; charset=utf-8")
                return
            }
            val staged = service.stagedAction(stagedActionId)
            if (staged == null) {
                respondText(exchange, 404, """{"error":"Staged action not found"}""", "application/json; charset=utf-8")
                return
            }
            respondText(exchange, 200, mapper.writeValueAsString(staged), "application/json; charset=utf-8")
            return
        }
        val action = suffixParts.getOrNull(1).orEmpty()
        if (exchange.requestMethod.uppercase() != "POST" ||
            (action != "authorize" && action != "deny")
        ) {
            respondText(exchange, 404, "Not found", "text/plain; charset=utf-8")
            return
        }
        val requestBody = readJsonBody(exchange)
        val result = runBlocking {
            when (action) {
                "authorize" -> service.authorizeStagedAction(
                    stagedActionId = stagedActionId,
                    grantedBy = ConversationSecurityContexts.ownerDirect(
                        provider = "webapp",
                        channelId = "dashboard-action-control",
                    )
                )

                "deny" -> service.denyStagedAction(
                    stagedActionId = stagedActionId,
                    deniedBy = ConversationSecurityContexts.ownerDirect(
                        provider = "webapp",
                        channelId = "dashboard-action-control",
                    ),
                    reason = requestBody?.get("reason")?.toString()?.trim().takeUnless { it.isNullOrBlank() }
                        ?: "Denied from dashboard.",
                    reasonCode = requestBody?.get("reason_code")?.toString()?.trim().takeUnless { it.isNullOrBlank() }
                )
                else -> error("Unsupported staged action control verb: $action")
            }
        }
        handleActionControlDecisionSideEffects(mutation = action, result = result)
        val statusCode = when (result) {
            is ActionControlDecisionResult.Refused -> 409
            is ActionControlDecisionResult.Staged,
            is ActionControlDecisionResult.Cancelled,
            is ActionControlDecisionResult.Executed -> 200
        }
        respondText(exchange, statusCode, mapper.writeValueAsString(result), "application/json; charset=utf-8")
    }

    private fun handleActionReceiptApi(
        exchange: HttpExchange,
        service: ActionControlService,
        suffixParts: List<String>,
    ) {
        if (exchange.requestMethod.uppercase() != "GET") {
            respondText(exchange, 405, "Method not allowed", "text/plain; charset=utf-8")
            return
        }
        if (suffixParts.isEmpty()) {
            val limit = parseQueryParam(exchange.requestURI.query, "limit")?.toIntOrNull() ?: 50
            respondText(
                exchange,
                200,
                mapper.writeValueAsString(service.receipts(limit)),
                "application/json; charset=utf-8"
            )
            return
        }
        val receipt = service.receipt(suffixParts.first())
        if (receipt == null) {
            respondText(exchange, 404, """{"error":"Action receipt not found"}""", "application/json; charset=utf-8")
            return
        }
        respondText(exchange, 200, mapper.writeValueAsString(receipt), "application/json; charset=utf-8")
    }

    private fun handleActionLedgerApi(
        exchange: HttpExchange,
        service: ActionControlService,
        suffixParts: List<String>,
    ) {
        if (exchange.requestMethod.uppercase() != "GET") {
            respondText(exchange, 405, "Method not allowed", "text/plain; charset=utf-8")
            return
        }
        if (suffixParts.isEmpty()) {
            val limit = parseQueryParam(exchange.requestURI.query, "limit")?.toIntOrNull() ?: 100
            respondText(
                exchange,
                200,
                mapper.writeValueAsString(service.ledgerEntries(limit)),
                "application/json; charset=utf-8"
            )
            return
        }
        val entry = service.ledgerEntry(suffixParts.first())
        if (entry == null) {
            respondText(exchange, 404, """{"error":"Action ledger entry not found"}""", "application/json; charset=utf-8")
            return
        }
        respondText(exchange, 200, mapper.writeValueAsString(entry), "application/json; charset=utf-8")
    }

    private fun handleCreateChatSession(exchange: HttpExchange, bridge: ChatRuntimeBridge) {
        val body = readJsonBody(exchange)
        val title = body?.get("title")?.toString()
        val session = bridge.createSession(title = title)
        val payload = mapOf(
            "session_id" to session.sessionId,
            "title" to session.title,
            "created_at_ms" to session.createdAtMs,
            "updated_at_ms" to session.updatedAtMs,
            "message_count" to session.messageCount
        )
        respondText(exchange, 201, mapper.writeValueAsString(payload), "application/json; charset=utf-8")
    }

    private fun handleSubmitChatMessage(
        exchange: HttpExchange,
        bridge: ChatRuntimeBridge,
        sessionId: String,
    ) {
        val body = readJsonBody(exchange) ?: run {
            respondText(exchange, 400, "Invalid JSON body", "text/plain; charset=utf-8")
            return
        }
        val content = body["content"]?.toString().orEmpty()
        val result = bridge.submitMessage(sessionId = sessionId, content = content)
        if (!result.recorded) {
            val statusCode = if (result.detail.contains("Unknown session", ignoreCase = true)) 404 else 400
            val payload = mapOf(
                "accepted" to false,
                "recorded" to false,
                "enqueued" to false,
                "detail" to result.detail
            )
            respondText(exchange, statusCode, mapper.writeValueAsString(payload), "application/json; charset=utf-8")
            return
        }
        val payload = mapOf(
            "accepted" to result.enqueued,
            "recorded" to result.recorded,
            "enqueued" to result.enqueued,
            "detail" to result.detail,
            "message" to result.message?.let { store.chatMessagePayload(it) },
        )
        respondText(exchange, 202, mapper.writeValueAsString(payload), "application/json; charset=utf-8")
    }

    private fun handleChatSse(
        exchange: HttpExchange,
        bridge: ChatRuntimeBridge,
        sessionId: String,
    ) {
        val subscription = bridge.subscribe(sessionId)
        if (subscription == null) {
            respondText(exchange, 404, "Session not found", "text/plain; charset=utf-8")
            return
        }

        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.responseHeaders.add("Cache-Control", "no-cache")
        exchange.responseHeaders.add("Connection", "keep-alive")
        recordResponseStatus(exchange, 200)
        exchange.sendResponseHeaders(200, 0)

        val output = exchange.responseBody.bufferedWriter(StandardCharsets.UTF_8)
        try {
            output.write("event: ready\n")
            output.write("data: {\"status\":\"connected\",\"session_id\":\"$sessionId\"}\n\n")
            output.flush()
            runBlocking {
                while (true) {
                    val payload = withTimeoutOrNull(SSE_HEARTBEAT_TIMEOUT_MS) {
                        subscription.receive()
                    }
                    if (payload != null) {
                        output.write("event: chat\n")
                        output.write("data: $payload\n\n")
                        output.flush()
                    } else {
                        output.write(": heartbeat\n\n")
                        output.flush()
                    }
                }
            }
        } catch (ex: Exception) {
            if (isExpectedClientDisconnect(ex)) {
                logger.debug { "Chat SSE client disconnected for session=$sessionId." }
            } else {
                logger.warn(ex) { "Chat SSE stream terminated unexpectedly for session=$sessionId." }
            }
        } finally {
            subscription.close()
            closeStreamQuietly(output, streamName = "chat_events_sse", context = "session=$sessionId")
        }
    }

    private fun handleThinkingSse(exchange: HttpExchange, sessionId: String) {
        val voiceStore = innerVoiceStore
        if (voiceStore == null) {
            respondText(exchange, 503, "Inner voice not available", "text/plain; charset=utf-8")
            return
        }
        val subscription = voiceStore.subscribe(sessionId)
        if (subscription == null) {
            respondText(exchange, 404, "Session not found", "text/plain; charset=utf-8")
            return
        }

        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.responseHeaders.add("Cache-Control", "no-cache")
        exchange.responseHeaders.add("Connection", "keep-alive")
        recordResponseStatus(exchange, 200)
        exchange.sendResponseHeaders(200, 0)

        val output = exchange.responseBody.bufferedWriter(StandardCharsets.UTF_8)
        try {
            output.write("event: ready\n")
            output.write("data: {\"status\":\"connected\",\"session_id\":\"$sessionId\"}\n\n")
            output.flush()
            runBlocking {
                while (true) {
                    val payload = withTimeoutOrNull(SSE_HEARTBEAT_TIMEOUT_MS) {
                        subscription.receive()
                    }
                    if (payload != null) {
                        output.write("event: thinking\n")
                        output.write("data: $payload\n\n")
                        output.flush()
                    } else {
                        output.write(": heartbeat\n\n")
                        output.flush()
                    }
                }
            }
        } catch (ex: Exception) {
            if (isExpectedClientDisconnect(ex)) {
                logger.debug { "Thinking SSE client disconnected for session=$sessionId." }
            } else {
                logger.warn(ex) { "Thinking SSE stream terminated unexpectedly for session=$sessionId." }
            }
        } finally {
            subscription.close()
            closeStreamQuietly(output, streamName = "thinking_sse", context = "session=$sessionId")
        }
    }

    private fun handleConversationStreamSse(
        exchange: HttpExchange,
        bridge: ChatRuntimeBridge,
        sessionId: String,
    ) {
        val chatSubscription = bridge.subscribe(sessionId)
        if (chatSubscription == null) {
            respondText(exchange, 404, "Session not found", "text/plain; charset=utf-8")
            return
        }
        val thinkingSubscription = innerVoiceStore?.subscribe(sessionId)

        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.responseHeaders.add("Cache-Control", "no-cache")
        exchange.responseHeaders.add("Connection", "keep-alive")
        recordResponseStatus(exchange, 200)
        exchange.sendResponseHeaders(200, 0)

        val output = exchange.responseBody.bufferedWriter(StandardCharsets.UTF_8)
        val chatChannel = chatSubscription.asReceiveChannel()
        val thinkingChannel = thinkingSubscription?.asReceiveChannel()
        try {
            output.write("event: ready\n")
            output.write("data: {\"status\":\"connected\",\"session_id\":\"$sessionId\"}\n\n")
            output.flush()
            runBlocking {
                while (true) {
                    val nextEvent = withTimeoutOrNull(SSE_HEARTBEAT_TIMEOUT_MS) {
                        select<NamedSseEvent> {
                            chatChannel.onReceiveCatching { result ->
                                NamedSseEvent(eventName = "chat", payload = result.getOrThrow())
                            }
                            if (thinkingChannel != null) {
                                thinkingChannel.onReceiveCatching { result ->
                                    NamedSseEvent(eventName = "thinking", payload = result.getOrThrow())
                                }
                            }
                        }
                    }
                    if (nextEvent != null) {
                        output.write("event: ${nextEvent.eventName}\n")
                        output.write("data: ${nextEvent.payload}\n\n")
                        output.flush()
                    } else {
                        output.write(": heartbeat\n\n")
                        output.flush()
                    }
                }
            }
        } catch (ex: Exception) {
            if (isExpectedClientDisconnect(ex)) {
                logger.debug { "Conversation SSE client disconnected for session=$sessionId." }
            } else {
                logger.warn(ex) { "Conversation SSE stream terminated unexpectedly for session=$sessionId." }
            }
        } finally {
            chatSubscription.close()
            thinkingSubscription?.close()
            closeStreamQuietly(output, streamName = "conversation_stream_sse", context = "session=$sessionId")
        }
    }

    private fun handleIdThinkingSse(exchange: HttpExchange) {
        val voiceStore = innerVoiceStore
        if (voiceStore == null) {
            respondText(exchange, 503, "Id inner voice not available", "text/plain; charset=utf-8")
            return
        }
        val subscription = voiceStore.subscribeIdGlobal()

        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.responseHeaders.add("Cache-Control", "no-cache")
        exchange.responseHeaders.add("Connection", "keep-alive")
        exchange.sendResponseHeaders(200, 0)

        val output = exchange.responseBody.bufferedWriter(StandardCharsets.UTF_8)
        try {
            output.write("event: ready\n")
            output.write("data: {\"status\":\"connected\"}\n\n")
            output.flush()
            runBlocking {
                while (true) {
                    val payload = withTimeoutOrNull(SSE_HEARTBEAT_TIMEOUT_MS) {
                        subscription.receive()
                    }
                    if (payload != null) {
                        output.write("event: id-thinking\n")
                        output.write("data: $payload\n\n")
                        output.flush()
                    } else {
                        output.write(": heartbeat\n\n")
                        output.flush()
                    }
                }
            }
        } catch (ex: Exception) {
            if (isExpectedClientDisconnect(ex)) {
                logger.debug { "Id thinking SSE client disconnected." }
            } else {
                logger.warn(ex) { "Id thinking SSE stream terminated unexpectedly." }
            }
        } finally {
            subscription.close()
            closeStreamQuietly(output, streamName = "id_thinking_sse")
        }
    }

    private data class NamedSseEvent(
        val eventName: String,
        val payload: String,
    )

    private fun handleIdThinkingHistory(exchange: HttpExchange) {
        val filePath = idInnerVoiceFilePath
        if (filePath == null || !Files.exists(filePath)) {
            respondText(exchange, 200, "[]", "application/json; charset=utf-8")
            return
        }
        try {
            val events = Files.readAllLines(filePath, StandardCharsets.UTF_8)
                .filter { it.isNotBlank() }
                .map { line ->
                    // Parse and re-serialize to ensure consistent JSON format
                    mapper.readValue<Map<String, Any?>>(line)
                }
            respondText(exchange, 200, mapper.writeValueAsString(events), "application/json; charset=utf-8")
        } catch (ex: Exception) {
            logger.warn(ex) { "Failed to read Id inner-voice history from $filePath" }
            respondText(exchange, 200, "[]", "application/json; charset=utf-8")
        }
    }

    private fun handleGoalsApi(exchange: HttpExchange) {
        val pm = durableWorkRuntime
        if (pm == null) {
            respondText(exchange, 503, "[]", "application/json; charset=utf-8")
            return
        }
        val path = exchange.requestURI.path
        val basePath = "/api/goals"
        if (exchange.requestMethod != "GET") {
            respondText(exchange, 405, "Method not allowed", "text/plain; charset=utf-8")
            return
        }
        val suffix = path.removePrefix(basePath).removePrefix("/").trim()
        if (suffix.isBlank()) {
            val summaries = pm.allWorkItems().map { summary ->
                val state = pm.workItemStatus(summary.workItemId)
                val projection = pm.workItemProjection(summary.workItemId)
                val allSteps = state?.workItem?.plan?.steps ?: emptyList()
                val totalSteps = allSteps.size
                val doneSteps = allSteps.count { step ->
                    step.status == ai.neopsyke.agent.durablework.StepStatus.DONE
                }
                val steps = allSteps.map { step ->
                    mapOf(
                        "id" to step.id,
                        "description" to step.description,
                        "status" to step.status.name,
                    )
                }
                mapOf(
                    "goalId" to summary.workItemId,
                    "workItemId" to summary.workItemId,
                    "title" to summary.title,
                    "status" to summary.status.name,
                    "health" to (projection?.health?.name ?: summary.health.name),
                    "priority" to summary.priority.name,
                    "deliveryPolicy" to (projection?.deliveryPolicy?.name ?: summary.deliveryPolicy.name),
                    "totalSteps" to totalSteps,
                    "doneSteps" to doneSteps,
                    "nextWakeAt" to projection?.nextWakeAt?.toString(),
                    "lastSuccessfulActivation" to projection?.lastSuccessfulActivation?.toString(),
                    "lastMeaningfulChange" to projection?.lastMeaningfulChange?.toString(),
                    "currentBlocker" to projection?.currentBlocker,
                    "failureCountInWindow" to (projection?.failureCountInWindow ?: 0),
                    "latestArtifactSummary" to projection?.latestArtifactSummary,
                    "lastWorkedAt" to summary.lastWorkedAt?.toString(),
                    "createdAtMs" to (state?.workItem?.createdAt?.toEpochMilli() ?: 0),
                    "whyActive" to projection?.explanation?.whyActive,
                    "whyBlocked" to projection?.explanation?.whyBlocked,
                    "whyStalled" to projection?.explanation?.whyStalled,
                    "whyQuiet" to projection?.explanation?.whyQuiet,
                    "whyNotified" to projection?.explanation?.whyNotified,
                    "whySkippedOrDeferred" to projection?.explanation?.whySkippedOrDeferred,
                    "steps" to steps,
                )
            }
            respondText(exchange, 200, mapper.writeValueAsString(summaries), "application/json; charset=utf-8")
        } else {
            val state = pm.workItemStatus(suffix)
            if (state == null) {
                respondText(exchange, 404, """{"error":"Goal not found"}""", "application/json; charset=utf-8")
                return
            }
            val projection = pm.workItemProjection(suffix)
            val steps = state.workItem.plan.steps.map { step ->
                mapOf(
                    "id" to step.id,
                    "description" to step.description,
                    "status" to step.status.name,
                    "attempts" to step.attempts,
                    "maxAttempts" to step.maxAttempts,
                    "requires" to step.requires,
                    "produces" to step.produces,
                )
            }
            val detail = mapOf(
                "goalId" to state.id,
                "workItemId" to state.id,
                "title" to state.workItem.title,
                "status" to state.workItem.status.name,
                "health" to (projection?.health?.name ?: state.workItem.health.name),
                "priority" to state.workItem.priority.name,
                "deliveryPolicy" to (projection?.deliveryPolicy?.name ?: state.workItem.deliveryPolicy.name),
                "instruction" to state.workItem.instruction,
                "completionCriteria" to state.workItem.completionCriteria,
                "nextWakeAt" to projection?.nextWakeAt?.toString(),
                "lastSuccessfulActivation" to projection?.lastSuccessfulActivation?.toString(),
                "lastMeaningfulChange" to projection?.lastMeaningfulChange?.toString(),
                "currentBlocker" to projection?.currentBlocker,
                "failureCountInWindow" to (projection?.failureCountInWindow ?: 0),
                "latestArtifactSummary" to projection?.latestArtifactSummary,
                "whyActive" to projection?.explanation?.whyActive,
                "whyBlocked" to projection?.explanation?.whyBlocked,
                "whyStalled" to projection?.explanation?.whyStalled,
                "whyQuiet" to projection?.explanation?.whyQuiet,
                "whyNotified" to projection?.explanation?.whyNotified,
                "whySkippedOrDeferred" to projection?.explanation?.whySkippedOrDeferred,
                "createdAt" to state.workItem.createdAt.toString(),
                "lastWorkedAt" to state.workItem.lastWorkedAt?.toString(),
                "steps" to steps,
                "producedKeys" to state.producedKeys.toList(),
                "eventCount" to state.eventCount,
            )
            respondText(exchange, 200, mapper.writeValueAsString(detail), "application/json; charset=utf-8")
        }
    }

    private fun handleLlmStatsApi(exchange: HttpExchange) {
        val provider = metricsQueryProvider
        if (provider == null) {
            respondText(exchange, 503, """{"error":"Metrics query not available"}""", "application/json; charset=utf-8")
            return
        }
        try {
            val query = parseLlmStatsQuery(exchange.requestURI.query)
            val cached = llmStatsCache[query.cacheKey]
            val nowMs = System.currentTimeMillis()
            val cacheAgeMs = cached?.let { nowMs - it.generatedAtMs } ?: Long.MAX_VALUE
            val isFresh = cached != null && cacheAgeMs <= LLM_STATS_CACHE_TTL_MS

            if (!isFresh) {
                triggerLlmStatsRefresh(provider = provider, query = query)
            }
            val refreshInFlight = llmStatsRefreshInFlight.contains(query.cacheKey)
            if (cached != null) {
                val payload = llmStatsPayload(
                    report = cached.report,
                    query = query,
                    generatedAtMs = cached.generatedAtMs,
                    stale = !isFresh,
                    refreshInFlight = refreshInFlight,
                    warmup = false
                )
                respondText(exchange, 200, payload, "application/json; charset=utf-8")
                return
            }

            val payload = llmStatsPayload(
                report = EMPTY_LLM_STATS_REPORT,
                query = query,
                generatedAtMs = nowMs,
                stale = true,
                refreshInFlight = refreshInFlight,
                warmup = true
            )
            respondText(exchange, LLM_STATS_WARMUP_STATUS, payload, "application/json; charset=utf-8")
        } catch (ex: Exception) {
            logger.error(ex) {
                "Failed to build LLM stats response for request=${exchange.requestMethod} ${exchange.requestURI.path}?${exchange.requestURI.query.orEmpty()}"
            }
            respondText(exchange, 500, """{"error":"Failed to query LLM stats"}""", "application/json; charset=utf-8")
        }
    }

    private fun triggerLlmStatsRefresh(provider: MetricsQueryProvider, query: LlmStatsQuery) {
        if (!llmStatsRefreshInFlight.add(query.cacheKey)) {
            return
        }
        if (!llmStatsRefreshSemaphore.tryAcquire()) {
            llmStatsRefreshInFlight.remove(query.cacheKey)
            logger.debug {
                "Skipping LLM stats refresh because refresh gate is saturated query=${query.cacheKey}"
            }
            return
        }
        llmStatsRefreshExecutor.execute {
            try {
                val report = provider.llmCallStats(runOnly = query.runOnly, timeframeMs = query.timeframeMs)
                llmStatsCache[query.cacheKey] = LlmStatsCacheEntry(
                    report = report,
                    generatedAtMs = System.currentTimeMillis()
                )
            } catch (ex: Exception) {
                logger.warn(ex) {
                    "LLM stats refresh failed for scope=${query.scope} timeframeMs=${query.timeframeMs}"
                }
            } finally {
                llmStatsRefreshSemaphore.release()
                llmStatsRefreshInFlight.remove(query.cacheKey)
            }
        }
    }

    private fun parseLlmStatsQuery(queryString: String?): LlmStatsQuery {
        val requestedScope = parseQueryParam(queryString, "scope")?.lowercase()
        val scope = if (requestedScope == "all") "all" else "run"
        val timeframeMs = parseQueryParam(queryString, "timeframe")?.toLongOrNull()
        val normalizedTimeframeMs = timeframeMs?.takeIf { it > 0L }
        return LlmStatsQuery(
            scope = scope,
            runOnly = scope != "all",
            timeframeMs = normalizedTimeframeMs,
            cacheKey = "$scope:${normalizedTimeframeMs ?: 0L}"
        )
    }

    private fun llmStatsPayload(
        report: LlmCallStatsReport,
        query: LlmStatsQuery,
        generatedAtMs: Long,
        stale: Boolean,
        refreshInFlight: Boolean,
        warmup: Boolean,
    ): String {
        val payload = mapOf(
            "byModel" to report.byModel,
            "byRole" to report.byRole,
            "errorBreakdown" to report.errorBreakdown,
            "generated_at_ms" to generatedAtMs,
            "scope" to query.scope,
            "timeframe_ms" to query.timeframeMs,
            "stale" to stale,
            "refresh_in_flight" to refreshInFlight,
            "warmup" to warmup
        )
        return mapper.writeValueAsString(payload)
    }

    private fun parseQueryParam(query: String?, key: String): String? {
        if (query.isNullOrBlank()) return null
        return query
            .split("&")
            .mapNotNull { part ->
                val (k, v) = part.split("=", limit = 2).let { tokens ->
                    when (tokens.size) {
                        2 -> tokens[0] to tokens[1]
                        1 -> tokens[0] to ""
                        else -> return@mapNotNull null
                    }
                }
                if (k == key) v else null
            }
            .firstOrNull()
    }

    private fun parseBooleanQueryParam(query: String?, key: String): Boolean? {
        val raw = parseQueryParam(query, key)?.trim()?.lowercase() ?: return null
        return when (raw) {
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off" -> false
            else -> null
        }
    }

    private fun readJsonBody(exchange: HttpExchange): Map<String, Any?>? {
        return try {
            val bytes = readRawBody(exchange).toByteArray(StandardCharsets.UTF_8)
            if (bytes.isEmpty()) {
                emptyMap()
            } else {
                mapper.readValue<Map<String, Any?>>(bytes)
            }
        } catch (ex: Exception) {
            logger.warn(ex) {
                "Failed to parse JSON request body for request=${exchange.requestMethod} ${exchange.requestURI.path}"
            }
            null
        }
    }

    private fun readRawBody(exchange: HttpExchange): String =
        exchange.requestBody.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }

    private inline fun withRequestGuard(
        exchange: HttpExchange,
        handlerName: String,
        block: () -> Unit,
    ) {
        val accessLogging = shouldLogAccess(exchange.requestURI.path)
        val startedAtNs = if (accessLogging) System.nanoTime() else 0L
        if (accessLogging) {
            logger.info {
                "Dashboard request started handler=$handlerName method=${exchange.requestMethod} path=${exchange.requestURI.path} query=${exchange.requestURI.query.orEmpty().ifBlank { "-" }}"
            }
        }
        try {
            block()
        } catch (ex: Exception) {
            val requestSummary = "${exchange.requestMethod} ${exchange.requestURI.path}?${exchange.requestURI.query.orEmpty()}"
            if (isExpectedClientDisconnect(ex)) {
                logger.debug {
                    "Dashboard request terminated after client disconnect handler=$handlerName request=$requestSummary"
                }
                closeResponseBodyQuietly(exchange, handlerName)
                return
            }
            logger.error(ex) {
                "Dashboard request handler failed handler=$handlerName request=$requestSummary"
            }
            writeFallbackInternalError(exchange, handlerName)
        } finally {
            if (accessLogging) {
                val elapsedMs = (System.nanoTime() - startedAtNs) / 1_000_000L
                val status = exchange.getAttribute(RESPONSE_STATUS_ATTRIBUTE) as? Int ?: -1
                logger.info {
                    "Dashboard request finished handler=$handlerName method=${exchange.requestMethod} path=${exchange.requestURI.path} status=$status duration_ms=$elapsedMs"
                }
            }
        }
    }

    private fun writeFallbackInternalError(exchange: HttpExchange, handlerName: String) {
        if (exchange.responseCode != -1) {
            logger.debug {
                "Skipping fallback error response because headers were already sent handler=$handlerName request=${exchange.requestMethod} ${exchange.requestURI.path}"
            }
            closeResponseBodyQuietly(exchange, handlerName)
            return
        }
        runCatching {
            respondText(
                exchange = exchange,
                status = 500,
                body = """{"error":"Internal dashboard error","handler":"$handlerName"}""",
                contentType = "application/json; charset=utf-8"
            )
        }.onFailure { writeEx ->
            logger.warn(writeEx) {
                "Failed to write fallback error response for handler=$handlerName request=${exchange.requestMethod} ${exchange.requestURI.path}"
            }
            closeResponseBodyQuietly(exchange, handlerName)
        }
    }

    private fun closeResponseBodyQuietly(exchange: HttpExchange, handlerName: String) {
        runCatching { exchange.responseBody.close() }
            .onFailure { closeEx ->
                if (isExpectedClientDisconnect(closeEx)) {
                    logger.debug {
                        "Response stream already disconnected during cleanup for handler=$handlerName request=${exchange.requestMethod} ${exchange.requestURI.path}"
                    }
                } else {
                    logger.warn(closeEx) {
                        "Failed to close response body during cleanup for handler=$handlerName request=${exchange.requestMethod} ${exchange.requestURI.path}"
                    }
                }
            }
    }

    private fun closeStreamQuietly(
        closeable: Closeable,
        streamName: String,
        context: String? = null,
    ) {
        runCatching { closeable.close() }
            .onFailure { closeEx ->
                val contextSuffix = if (context.isNullOrBlank()) "" else " $context"
                if (isExpectedClientDisconnect(closeEx)) {
                    logger.debug { "Client disconnect detected while closing $streamName stream.$contextSuffix" }
                } else {
                    logger.warn(closeEx) { "Failed to close $streamName stream.$contextSuffix" }
                }
            }
    }

    private fun isExpectedClientDisconnect(ex: Throwable?): Boolean {
        var current = ex
        while (current != null) {
            if (current is IOException) {
                val message = current.message?.lowercase().orEmpty()
                if (
                    message.contains("broken pipe") ||
                    message.contains("connection reset") ||
                    message.contains("stream closed") ||
                    message.contains("forcibly closed") ||
                    message.contains("insufficient bytes written")
                ) {
                    return true
                }
            }
            current = current.cause
        }
        return false
    }

    private fun respondText(
        exchange: HttpExchange,
        status: Int,
        body: String,
        contentType: String,
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.responseHeaders.add("Cache-Control", "no-store, no-cache, must-revalidate")
        exchange.responseHeaders.add("Pragma", "no-cache")
        recordResponseStatus(exchange, status)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { output ->
            output.write(bytes)
        }
    }

    private fun handleActionControlDecisionSideEffects(
        mutation: String,
        result: ActionControlDecisionResult,
    ) {
        actionControlMutationHandler?.invoke(mutation, result)
        when (result) {
            is ActionControlDecisionResult.Executed -> {
                if (actionControlMutationHandler == null) {
                    maybeAppendDashboardApprovalMessage(result)
                }
                maybeClearRootInputSession(result.stagedAction)
                store.publishActionControlUpdate(
                    type = "action_control_state_changed",
                    data = actionControlUpdatePayload(
                        mutation = mutation,
                        stagedAction = result.stagedAction,
                        detail = result.receipt.statusSummary,
                    ) + mapOf(
                        "execution_status" to result.receipt.executionStatus.name,
                        "receipt_id" to result.receipt.id,
                        "authorization_id" to result.authorization.id,
                    )
                )
            }
            is ActionControlDecisionResult.Cancelled -> {
                maybeClearRootInputSession(result.stagedAction)
                store.publishActionControlUpdate(
                    type = "action_control_state_changed",
                    data = actionControlUpdatePayload(
                        mutation = mutation,
                        stagedAction = result.stagedAction,
                        detail = result.ledgerEntry.summary,
                    ) + mapOf(
                        "ledger_id" to result.ledgerEntry.id,
                    )
                )
            }
            is ActionControlDecisionResult.Refused -> {
                store.publishActionControlUpdate(
                    type = "action_control_request_refused",
                    data = mapOf(
                        "mutation" to mutation,
                        "reason" to result.reason,
                        "reason_code" to result.reasonCode,
                    )
                )
            }
            is ActionControlDecisionResult.Staged -> {
                store.publishActionControlUpdate(
                    type = "action_control_state_changed",
                    data = actionControlUpdatePayload(
                        mutation = mutation,
                        stagedAction = result.stagedAction,
                        detail = result.authorizationDecision.reason,
                    )
                )
            }
        }
    }

    private fun maybeAppendDashboardApprovalMessage(result: ActionControlDecisionResult.Executed) {
        val sessionId = result.stagedAction.rootInputId
            ?.let { store.resolveSessionForRootInput(it) }
            ?: result.stagedAction.conversationContext.sessionId
        if (sessionId.isBlank()) {
            return
        }
        val content = if (result.executedAction.type == ActionType.CONTACT_USER) {
            result.executedAction.payload.trim()
        } else {
            buildDashboardApprovalSummary(result)
        }
        if (content.isBlank()) {
            return
        }
        store.addAssistantMessage(
            sessionId = sessionId,
            content = content,
            source = "dashboard-action-control",
        )
    }

    private fun buildDashboardApprovalSummary(result: ActionControlDecisionResult.Executed): String {
        val actionType = result.executedAction.type.id
        val summary = result.receipt.statusSummary.trim()
        return when (result.stagedAction.status) {
            StagedActionStatus.WAITING_EXTERNAL ->
                "Dashboard-approved action '$actionType' is waiting on external completion: $summary"
            StagedActionStatus.FAILED ->
                "Dashboard-approved action '$actionType' failed: $summary"
            else ->
                "Dashboard-approved action '$actionType' completed: $summary"
        }
    }

    private fun maybeClearRootInputSession(stagedAction: StagedAction) {
        val rootInputId = stagedAction.rootInputId ?: return
        if (stagedAction.status in TERMINAL_STAGED_ACTION_STATUSES) {
            store.clearSessionForRootInput(rootInputId)
        }
    }

    private fun actionControlUpdatePayload(
        mutation: String,
        stagedAction: StagedAction,
        detail: String?,
    ): Map<String, Any?> =
        mapOf(
            "mutation" to mutation,
            "staged_action_id" to stagedAction.id,
            "status" to stagedAction.status.name,
            "action_type" to stagedAction.actionType.id,
            "root_input_id" to stagedAction.rootInputId,
            "session_id" to stagedAction.conversationContext.sessionId,
            "detail" to detail,
        )

    private fun shouldLogAccess(path: String): Boolean =
        path.startsWith("/api/chat/") || path.startsWith("/api/action-control")

    private fun recordResponseStatus(exchange: HttpExchange, status: Int) {
        exchange.setAttribute(RESPONSE_STATUS_ATTRIBUTE, status)
    }

    private data class LlmStatsQuery(
        val scope: String,
        val runOnly: Boolean,
        val timeframeMs: Long?,
        val cacheKey: String,
    )

    private data class LlmStatsCacheEntry(
        val report: LlmCallStatsReport,
        val generatedAtMs: Long,
    )

    private companion object {
        private const val TELEGRAM_SECRET_HEADER: String = "X-Telegram-Bot-Api-Secret-Token"
        val TERMINAL_STAGED_ACTION_STATUSES: Set<StagedActionStatus> = setOf(
            StagedActionStatus.COMPLETED,
            StagedActionStatus.CANCELLED,
            StagedActionStatus.FAILED,
        )
        val EMPTY_LLM_STATS_REPORT = LlmCallStatsReport(
            byModel = emptyMap(),
            byRole = emptyMap(),
            errorBreakdown = emptyMap(),
        )
    }
}
