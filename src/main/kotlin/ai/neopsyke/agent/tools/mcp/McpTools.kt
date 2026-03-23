package ai.neopsyke.agent.tools.mcp

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.modelcontextprotocol.kotlin.sdk.CallToolResultBase
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import mu.KotlinLogging
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import ai.neopsyke.agent.support.PromptInjectionDefense
import ai.neopsyke.agent.support.TextSecurity
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.concurrent.thread

private val logger = KotlinLogging.logger {}

interface McpTimeTool {
    suspend fun getCurrentTime(payload: String): String

    suspend fun healthCheck(): ToolHealthStatus = ToolHealthStatus(
        available = true,
        detail = "health check not implemented"
    )
}

interface FetchTool {
    suspend fun fetch(payload: String): String

    suspend fun fetchWithOutcome(payload: String): FetchOutcome =
        FetchOutcome(message = fetch(payload))

    suspend fun healthCheck(): ToolHealthStatus = ToolHealthStatus(
        available = true,
        detail = "health check not implemented"
    )
}

class SdkMcpTimeTool(
    command: List<String>,
    private val callTimeoutMs: Long,
    scope: CoroutineScope? = null,
) : McpTimeTool, AutoCloseable {
    private val clientHolder = LazyMcpClientHolder(command, serverLabel = "time", scope = scope)

    override suspend fun getCurrentTime(payload: String): String {
        val parsed = try {
            mapper.readValue<TimePayload>(payload)
        } catch (_: Exception) {
            TimePayload()
        }

        val arguments = buildMap<String, Any> {
            val timezone = parsed.timezone?.trim().orEmpty()
            if (timezone.isNotEmpty()) {
                put("timezone", timezone)
            }
        }

        val result = try {
            clientHolder.callTool(
                toolName = "get_current_time",
                arguments = arguments,
                timeoutMs = callTimeoutMs
            )
        } catch (ex: Exception) {
            logger.warn(ex) { "MCP time tool call failed." }
            return "MCP time unavailable: ${ex.message ?: "tool call failed"}"
        }

        val content = PromptInjectionDefense.sanitizeExternalText(
            result.content.ifBlank { "No time data returned." },
            500
        )
        return if (result.isError) {
            "MCP time tool returned an error: $content"
        } else {
            "MCP time result: $content"
        }
    }

    override fun close() {
        clientHolder.close()
    }

    override suspend fun healthCheck(): ToolHealthStatus {
        return try {
            val toolNames = clientHolder.listTools(callTimeoutMs)
            if ("get_current_time" in toolNames) {
                ToolHealthStatus(
                    available = true,
                    detail = "MCP time server reachable and tool get_current_time is registered."
                )
            } else {
                ToolHealthStatus(
                    available = false,
                    detail = "MCP time server reachable but get_current_time tool is missing."
                )
            }
        } catch (ex: Exception) {
            ToolHealthStatus(
                available = false,
                detail = "MCP time server health check failed: ${ex.message ?: "unknown error"}"
            )
        }
    }
}

class LazyMcpClientHolder(
    private val command: List<String>,
    private val serverLabel: String,
    private val scope: CoroutineScope? = null,
) : AutoCloseable {
    @Volatile
    private var client: McpStdioClient? = null
    private val mutex = Mutex()

    suspend fun callTool(toolName: String, arguments: Map<String, Any>, timeoutMs: Long): McpToolCallResult {
        val activeClient = ensureClient()
        return activeClient.callTool(toolName = toolName, arguments = arguments, timeoutMs = timeoutMs)
    }

    suspend fun listTools(timeoutMs: Long): Set<String> = ensureClient().listTools(timeoutMs)

    suspend fun listToolDescriptors(timeoutMs: Long): List<McpToolDescriptor> =
        ensureClient().listToolDescriptors(timeoutMs)

    private suspend fun ensureClient(): McpStdioClient {
        client?.let { return it }
        return mutex.withLock {
            client ?: McpStdioClient.start(command, serverLabel, scope).also { created ->
                client = created
            }
        }
    }

    override fun close() {
        // close() must remain synchronous for AutoCloseable contract
        runBlocking {
            mutex.withLock {
                client?.close()
                client = null
            }
        }
    }
}

class McpStdioClient private constructor(
    private val process: Process,
    private val client: Client,
    private val serverLabel: String,
) : AutoCloseable {
    suspend fun listTools(timeoutMs: Long): Set<String> {
        return listToolDescriptors(timeoutMs)
            .map { it.name }
            .toSet()
    }

    suspend fun listToolDescriptors(timeoutMs: Long): List<McpToolDescriptor> {
        val result = try {
            withTimeout(timeoutMs) {
                client.listTools(ListToolsRequest())
            }
        } catch (ex: TimeoutCancellationException) {
            throw IOException("MCP $serverLabel list-tools timed out after ${timeoutMs}ms", ex)
        }
        return result?.tools.orEmpty().map { tool ->
            McpToolDescriptor(
                name = tool.name,
                description = tool.description.orEmpty(),
                inputSchema = tool.inputSchema.toString(),
            )
        }
    }

    suspend fun callTool(toolName: String, arguments: Map<String, Any>, timeoutMs: Long): McpToolCallResult {
        val encodedArguments = encodeMcpArguments(arguments)
        val result = try {
            withTimeout(timeoutMs) {
                client.callTool(toolName, encodedArguments)
            }
        } catch (ex: TimeoutCancellationException) {
            throw IOException("MCP $serverLabel tool call timed out after ${timeoutMs}ms", ex)
        }
        val callResult = result ?: throw IOException("MCP $serverLabel returned an empty tool result.")

        return McpToolCallResult(
            isError = callResult.isError == true,
            content = extractResultText(callResult)
        )
    }

    override fun close() {
        try {
            runBlocking {
                client.close()
            }
        } catch (ex: Exception) {
            logger.debug(ex) { "Error while closing MCP client for $serverLabel." }
        } finally {
            if (process.isAlive) {
                process.destroy()
                process.waitFor(PROCESS_SHUTDOWN_GRACE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
            if (process.isAlive) {
                process.destroyForcibly()
            }
        }
    }

    companion object {
        suspend fun start(command: List<String>, serverLabel: String, scope: CoroutineScope? = null): McpStdioClient {
            require(command.isNotEmpty()) { "MCP command cannot be empty." }
            var lastError: Exception? = null
            for (attempt in 1..START_MAX_ATTEMPTS) {
                try {
                    return startOnce(command = command, serverLabel = serverLabel, scope = scope)
                } catch (ex: Exception) {
                    lastError = ex
                    if (attempt < START_MAX_ATTEMPTS) {
                        logger.warn(ex) {
                            "MCP $serverLabel connect failed; retrying (attempt $attempt/$START_MAX_ATTEMPTS)."
                        }
                        delay(START_RETRY_DELAY_MS)
                    } else {
                        logger.warn(ex) {
                            "MCP $serverLabel connect failed after $START_MAX_ATTEMPTS attempts."
                        }
                    }
                }
            }

            throw IOException(
                "MCP $serverLabel connect failed after $START_MAX_ATTEMPTS attempts.",
                lastError
            )
        }

        private suspend fun startOnce(command: List<String>, serverLabel: String, scope: CoroutineScope? = null): McpStdioClient {
            require(command.isNotEmpty()) { "MCP command cannot be empty." }
            val processBuilder = ProcessBuilder(command)
            NpmCommandIsolation.apply(processBuilder, command, serverLabel)
            val process = processBuilder.start()

            // Drain stderr continuously to avoid deadlocks if the server logs.
            if (scope != null) {
                scope.launch(Dispatchers.IO + CoroutineName("mcp-$serverLabel-stderr")) {
                    process.errorStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            logger.debug { "mcp-$serverLabel stderr: $line" }
                        }
                    }
                }
            } else {
                thread(name = "mcp-$serverLabel-stderr", isDaemon = true) {
                    process.errorStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            logger.debug { "mcp-$serverLabel stderr: $line" }
                        }
                    }
                }
            }

            val transport = StdioClientTransport(
                input = process.inputStream.asSource().buffered(),
                output = process.outputStream.asSink().buffered()
            )
            val client = Client(
                clientInfo = Implementation(name = "neopsyke", version = "0.1.0"),
                options = ClientOptions()
            )
            try {
                client.connect(transport)
            } catch (ex: Exception) {
                if (process.isAlive) {
                    process.destroy()
                    process.waitFor(PROCESS_SHUTDOWN_GRACE_MS, TimeUnit.MILLISECONDS)
                }
                if (process.isAlive) {
                    process.destroyForcibly()
                }
                throw ex
            }
            return McpStdioClient(
                process = process,
                client = client,
                serverLabel = serverLabel
            )
        }

        fun parseCommand(command: String): List<String> {
            val trimmed = command.trim()
            if (trimmed.isEmpty()) {
                return emptyList()
            }
            val pattern = Regex("""[^\s\"']+|\"([^\"\\]|\\.)*\"|'([^'\\]|\\.)*'""")
            return pattern.findAll(trimmed)
                .map { match ->
                    val token = match.value
                    when {
                        token.startsWith('"') && token.endsWith('"') && token.length >= 2 -> {
                            token.substring(1, token.length - 1).replace("\\\"", "\"")
                        }

                        token.startsWith("'") && token.endsWith("'") && token.length >= 2 -> {
                            token.substring(1, token.length - 1)
                        }

                        else -> token
                    }
                }
                .toList()
        }

        private fun extractResultText(result: CallToolResultBase): String {
            val text = result.content
                .mapNotNull { item -> (item as? TextContent)?.text?.trim() }
                .filter { it.isNotBlank() }
                .joinToString(separator = "\n")
            if (text.isNotBlank()) {
                return text
            }

            val structured = result.structuredContent.toString()
            if (structured != "{}") {
                return structured
            }

            return ""
        }

        private const val START_MAX_ATTEMPTS: Int = 2
        private const val START_RETRY_DELAY_MS: Long = 200
        private const val PROCESS_SHUTDOWN_GRACE_MS: Long = 250
    }
}

internal fun encodeMcpArguments(arguments: Map<String, Any?>): Map<String, Any> =
    arguments.mapValues { (_, value) -> toMcpJsonElement(value) }

private fun toMcpJsonElement(value: Any?): JsonElement =
    when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(
            value.entries
                .filter { it.key != null }
                .associate { (key, nestedValue) -> key.toString() to toMcpJsonElement(nestedValue) }
        )

        is Iterable<*> -> JsonArray(value.map { nested -> toMcpJsonElement(nested) })
        is Array<*> -> JsonArray(value.map { nested -> toMcpJsonElement(nested) })
        else -> JsonPrimitive(value.toString())
    }

private object NpmCommandIsolation {
    private const val PUBLIC_REGISTRY = "https://registry.npmjs.org/"

    fun apply(processBuilder: ProcessBuilder, command: List<String>, serverLabel: String) {
        val executable = normalizeExecutableName(command.firstOrNull().orEmpty())
        if (executable != "npm" && executable != "npx") {
            return
        }

        val workspaceRoot = Paths.get(System.getProperty("user.dir"))
        val npmDir = workspaceRoot.resolve(".neopsyke").resolve("npm")
        val userConfig = npmDir.resolve("mcp-user.npmrc")
        val globalConfig = npmDir.resolve("mcp-global.npmrc")
        val cacheDir = npmDir.resolve("cache")
        val npxCacheDir = npmDir.resolve("npx-cache")

        try {
            Files.createDirectories(npmDir)
            Files.createDirectories(cacheDir)
            Files.createDirectories(npxCacheDir)
            writeUserConfig(userConfig)
            writeGlobalConfig(globalConfig)
        } catch (ex: Exception) {
            logger.warn(ex) { "Failed to prepare isolated npm config for MCP $serverLabel." }
            return
        }

        val env = processBuilder.environment()
        val removed = env.keys
            .filter { key ->
                val normalized = key.lowercase(Locale.ROOT)
                normalized.startsWith("npm_config_") ||
                    normalized == "npm_token" ||
                    normalized == "node_auth_token"
            }
            .toList()
        removed.forEach { key -> env.remove(key) }

        setEnv(env, "NPM_CONFIG_USERCONFIG", userConfig.toString())
        setEnv(env, "NPM_CONFIG_GLOBALCONFIG", globalConfig.toString())
        setEnv(env, "NPM_CONFIG_REGISTRY", PUBLIC_REGISTRY)
        setEnv(env, "NPM_CONFIG_ALWAYS_AUTH", "false")
        setEnv(env, "NPM_CONFIG_CACHE", cacheDir.toString())
        setEnv(env, "NPX_CACHE", npxCacheDir.toString())

        logger.info {
            "mcp-$serverLabel using isolated npm configuration at $userConfig (registry=$PUBLIC_REGISTRY, removed_env=${removed.size})"
        }
    }

    private fun writeUserConfig(path: Path) {
        val content = buildString {
            appendLine("registry=$PUBLIC_REGISTRY")
            appendLine("always-auth=false")
            appendLine("fund=false")
            appendLine("update-notifier=false")
            appendLine("audit=false")
        }
        Files.writeString(
            path,
            content,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        )
    }

    private fun writeGlobalConfig(path: Path) {
        Files.writeString(
            path,
            "",
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        )
    }

    private fun setEnv(env: MutableMap<String, String>, key: String, value: String) {
        env[key] = value
        env[key.lowercase(Locale.ROOT)] = value
    }

    private fun normalizeExecutableName(executable: String): String {
        val trimmed = executable.trim()
        if (trimmed.isBlank()) {
            return ""
        }
        val base = trimmed
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .lowercase(Locale.ROOT)
        return base.removeSuffix(".cmd").removeSuffix(".exe").removeSuffix(".bat")
    }
}

// ---------------------------------------------------------------------------
// Native JVM fetch — OkHttp + Jsoup, no subprocess, no npm, no Python.
// ---------------------------------------------------------------------------

class NativeFetchTool(
    private val callTimeoutMs: Long,
    private val maxChars: Int,
) : FetchTool {
    private val httpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .connectTimeout(5_000, TimeUnit.MILLISECONDS)
        .readTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    override suspend fun fetch(payload: String): String = fetchWithOutcome(payload).message

    override suspend fun fetchWithOutcome(payload: String): FetchOutcome = withContext(Dispatchers.IO) {
        val parsed = try {
            mapper.readValue<FetchPayload>(payload)
        } catch (_: Exception) {
            return@withContext FetchOutcome(
                message = "Fetch payload is invalid. Expected JSON like {\"url\":\"https://example.com\",\"max_chars\":1200}.",
                errorCategory = FetchErrorCategory.MALFORMED_REQUEST
            )
        }

        val url = parsed.url?.trim().orEmpty()
        if (url.isEmpty()) {
            return@withContext FetchOutcome(
                message = "Fetch payload is missing url.",
                errorCategory = FetchErrorCategory.MALFORMED_REQUEST
            )
        }
        if (!isFetchUrlAllowed(url)) {
            return@withContext FetchOutcome(
                message = "Fetch blocked URL by safety policy. Only public HTTPS URLs are allowed.",
                errorCategory = FetchErrorCategory.MALFORMED_REQUEST
            )
        }

        val requestedMaxChars = parsed.maxChars ?: maxChars
        val safeMaxChars = requestedMaxChars.coerceIn(256, maxChars)

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,text/plain;q=0.8,*/*;q=0.7")
            .get()
            .build()

        val response = try {
            httpClient.newCall(request).execute()
        } catch (ex: SocketTimeoutException) {
            return@withContext FetchOutcome(
                message = "Fetch timed out for $url: ${ex.message}",
                errorCategory = FetchErrorCategory.RETRYABLE
            )
        } catch (ex: UnknownHostException) {
            return@withContext FetchOutcome(
                message = "DNS resolution failed for $url: ${ex.message}",
                errorCategory = FetchErrorCategory.NON_RETRYABLE
            )
        } catch (ex: SSLException) {
            return@withContext FetchOutcome(
                message = "SSL error fetching $url: ${ex.message}",
                errorCategory = FetchErrorCategory.NON_RETRYABLE
            )
        } catch (ex: IOException) {
            return@withContext FetchOutcome(
                message = "Network error fetching $url: ${ex.message}",
                errorCategory = FetchErrorCategory.RETRYABLE
            )
        }

        response.use { resp ->
            val code = resp.code
            if (code in NON_RETRYABLE_HTTP_CODES) {
                return@withContext FetchOutcome(
                    message = "HTTP $code ${resp.message} for $url",
                    errorCategory = FetchErrorCategory.NON_RETRYABLE
                )
            }
            if (!resp.isSuccessful) {
                val retryable = code in 500..599 || code == 429
                return@withContext FetchOutcome(
                    message = "HTTP $code ${resp.message} for $url",
                    errorCategory = if (retryable) FetchErrorCategory.RETRYABLE else FetchErrorCategory.NON_RETRYABLE
                )
            }

            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) {
                return@withContext FetchOutcome(
                    message = "Fetch completed for $url but returned empty body.",
                    errorCategory = FetchErrorCategory.NONE
                )
            }

            val contentType = resp.header("Content-Type").orEmpty().lowercase(Locale.ROOT)
            val text = if ("html" in contentType || "xhtml" in contentType) {
                htmlToReadableText(body, url)
            } else {
                body
            }

            val clamped = PromptInjectionDefense.sanitizeExternalText(text, safeMaxChars)
            val injectionScan = PromptInjectionDefense.scan(text)
            val preview = TextSecurity.preview(clamped, 240)
            val promptInjectionSignals =
                if (injectionScan.suspicious) injectionScan.signalIds.sorted().joinToString(",") else "none"
            FetchOutcome(
                message = "Fetch completed for $url. Extracted ${clamped.length} chars. Preview: $preview. prompt_injection_signals=$promptInjectionSignals",
                errorCategory = FetchErrorCategory.NONE
            )
        }
    }

    override suspend fun healthCheck(): ToolHealthStatus = ToolHealthStatus(
        available = true,
        detail = "Native JVM fetch tool (OkHttp + Jsoup). No external process required."
    )

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (compatible; neopsyke-agent/1.0; +https://neopsyke.ai)"

        private val NON_RETRYABLE_HTTP_CODES = setOf(400, 401, 403, 404, 410, 451)

        internal fun htmlToReadableText(html: String, baseUri: String): String {
            val doc = Jsoup.parse(html, baseUri)
            doc.select("script, style, noscript, svg, [hidden]").remove()
            return buildString { renderNode(doc.body() ?: return@buildString, this) }
                .replace(Regex("\n{3,}"), "\n\n")
                .trim()
        }

        private val BLOCK_TAGS = setOf(
            "p", "div", "section", "article", "main", "aside",
            "h1", "h2", "h3", "h4", "h5", "h6",
            "ul", "ol", "li", "tr", "blockquote", "pre", "figure", "figcaption",
            "details", "summary", "dl", "dt", "dd",
        )

        private fun renderNode(node: Node, out: StringBuilder) {
            when (node) {
                is TextNode -> {
                    val text = node.wholeText.replace(Regex("[ \\t]+"), " ")
                    if (text.isNotBlank()) out.append(text)
                }

                is Element -> {
                    val tag = node.tagName()
                    val isBlock = tag in BLOCK_TAGS

                    if (isBlock && out.isNotEmpty() && !out.endsWith('\n')) out.append('\n')
                    when {
                        tag.matches(Regex("h[1-6]")) -> out.append("#".repeat(tag[1].digitToInt())).append(' ')
                        tag == "li" -> out.append("- ")
                        tag == "br" -> { out.append('\n'); return }
                        tag == "pre" -> {
                            out.append("```\n")
                            out.append(node.wholeText())
                            out.append("\n```\n")
                            return
                        }
                    }
                    for (child in node.childNodes()) renderNode(child, out)
                    if (isBlock) out.append('\n')
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Shared URL safety gate.
// ---------------------------------------------------------------------------

internal fun isFetchUrlAllowed(rawUrl: String): Boolean {
    val uri = try {
        URI(rawUrl)
    } catch (_: Exception) {
        return false
    }

    val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
    if (scheme != "https") return false

    val host = uri.host?.lowercase(Locale.ROOT) ?: return false
    if (host.isBlank()) return false
    if (host == "localhost" || host.endsWith(".local")) return false
    if (host == "::1" || host.startsWith("127.")) return false
    if (host.startsWith("10.") || host.startsWith("192.168.") || host.startsWith("169.254.")) return false

    if (host.startsWith("172.")) {
        val secondOctet = host.split('.').getOrNull(1)?.toIntOrNull()
        if (secondOctet != null && secondOctet in 16..31) return false
    }

    if (host.contains(":") && (host.startsWith("fc") || host.startsWith("fd"))) return false

    return true
}

data class McpToolCallResult(
    val isError: Boolean,
    val content: String,
)

data class ToolHealthStatus(
    val available: Boolean,
    val detail: String,
)

data class McpToolDescriptor(
    val name: String,
    val description: String,
    val inputSchema: String,
)

enum class FetchErrorCategory {
    NONE,
    MALFORMED_REQUEST,
    NON_RETRYABLE,
    RETRYABLE,
}

data class FetchOutcome(
    val message: String,
    val errorCategory: FetchErrorCategory = FetchErrorCategory.NONE,
)

private data class TimePayload(
    val timezone: String? = null,
)

private data class FetchPayload(
    val url: String? = null,
    @param:JsonProperty("max_chars")
    val maxChars: Int? = null,
)

private val mapper = jacksonObjectMapper()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
