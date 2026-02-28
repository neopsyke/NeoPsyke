package psyke.agent

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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import mu.KotlinLogging
import java.io.IOException
import java.net.URI
import java.util.Locale
import kotlin.concurrent.thread

private val logger = KotlinLogging.logger {}

interface McpTimeTool {
    fun getCurrentTime(payload: String): String

    fun healthCheck(): ToolHealthStatus = ToolHealthStatus(
        available = true,
        detail = "health check not implemented"
    )
}

interface McpFetchTool {
    fun fetch(payload: String): String

    fun healthCheck(): ToolHealthStatus = ToolHealthStatus(
        available = true,
        detail = "health check not implemented"
    )
}

class SdkMcpTimeTool(
    command: List<String>,
    private val callTimeoutMs: Long,
) : McpTimeTool, AutoCloseable {
    private val clientHolder = LazyMcpClientHolder(command, serverLabel = "time")

    override fun getCurrentTime(payload: String): String {
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

        val content = TextSecurity.clamp(result.content.ifBlank { "No time data returned." }, 500)
        return if (result.isError) {
            "MCP time tool returned an error: $content"
        } else {
            "MCP time result: $content"
        }
    }

    override fun close() {
        clientHolder.close()
    }

    override fun healthCheck(): ToolHealthStatus {
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

class SdkMcpFetchTool(
    command: List<String>,
    private val callTimeoutMs: Long,
    private val maxChars: Int,
) : McpFetchTool, AutoCloseable {
    private val clientHolder = LazyMcpClientHolder(command, serverLabel = "fetch")

    override fun fetch(payload: String): String {
        val parsed = try {
            mapper.readValue<FetchPayload>(payload)
        } catch (_: Exception) {
            return "MCP fetch payload is invalid. Expected JSON like {\"url\":\"https://example.com\",\"max_chars\":1200}."
        }

        val url = parsed.url?.trim().orEmpty()
        if (url.isEmpty()) {
            return "MCP fetch payload is missing url."
        }
        if (!isFetchUrlAllowed(url)) {
            return "MCP fetch blocked URL by safety policy. Only public HTTPS URLs are allowed."
        }

        val requestedMaxChars = parsed.maxChars ?: maxChars
        val safeMaxChars = requestedMaxChars.coerceIn(256, maxChars)

        val result = try {
            clientHolder.callTool(
                toolName = "fetch",
                arguments = mapOf("url" to url),
                timeoutMs = callTimeoutMs
            )
        } catch (ex: Exception) {
            logger.warn(ex) { "MCP fetch tool call failed for url=${TextSecurity.preview(url, 120)}." }
            return "MCP fetch unavailable: ${ex.message ?: "tool call failed"}"
        }

        val content = TextSecurity.clamp(result.content, safeMaxChars)
        val preview = TextSecurity.preview(content, 240)
        return if (result.isError) {
            "MCP fetch tool returned an error for $url: ${TextSecurity.clamp(content.ifBlank { "empty error" }, 240)}"
        } else {
            "MCP fetch completed for $url. Extracted ${content.length} chars. Preview: $preview"
        }
    }

    override fun close() {
        clientHolder.close()
    }

    override fun healthCheck(): ToolHealthStatus {
        return try {
            val toolNames = clientHolder.listTools(callTimeoutMs)
            if ("fetch" in toolNames) {
                ToolHealthStatus(
                    available = true,
                    detail = "MCP fetch server reachable and tool fetch is registered."
                )
            } else {
                ToolHealthStatus(
                    available = false,
                    detail = "MCP fetch server reachable but fetch tool is missing."
                )
            }
        } catch (ex: Exception) {
            ToolHealthStatus(
                available = false,
                detail = "MCP fetch server health check failed: ${ex.message ?: "unknown error"}"
            )
        }
    }

    private fun isFetchUrlAllowed(rawUrl: String): Boolean {
        val uri = try {
            URI(rawUrl)
        } catch (_: Exception) {
            return false
        }

        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        if (scheme != "https") {
            return false
        }

        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        if (host.isBlank()) {
            return false
        }

        if (host == "localhost" || host.endsWith(".local")) {
            return false
        }

        if (host == "::1" || host.startsWith("127.")) {
            return false
        }

        if (host.startsWith("10.") || host.startsWith("192.168.") || host.startsWith("169.254.")) {
            return false
        }

        if (host.startsWith("172.")) {
            val secondOctet = host.split('.').getOrNull(1)?.toIntOrNull()
            if (secondOctet != null && secondOctet in 16..31) {
                return false
            }
        }

        if (host.contains(":") && (host.startsWith("fc") || host.startsWith("fd"))) {
            return false
        }

        return true
    }
}

class LazyMcpClientHolder(
    private val command: List<String>,
    private val serverLabel: String,
) : AutoCloseable {
    @Volatile
    private var client: McpStdioClient? = null

    fun callTool(toolName: String, arguments: Map<String, Any>, timeoutMs: Long): McpToolCallResult {
        val activeClient = ensureClient()
        return activeClient.callTool(toolName = toolName, arguments = arguments, timeoutMs = timeoutMs)
    }

    fun listTools(timeoutMs: Long): Set<String> = ensureClient().listTools(timeoutMs)

    private fun ensureClient(): McpStdioClient {
        client?.let { return it }
        return synchronized(this) {
            client ?: McpStdioClient.start(command, serverLabel).also { created ->
                client = created
            }
        }
    }

    override fun close() {
        synchronized(this) {
            client?.close()
            client = null
        }
    }
}

class McpStdioClient private constructor(
    private val process: Process,
    private val client: Client,
    private val serverLabel: String,
) : AutoCloseable {
    fun listTools(timeoutMs: Long): Set<String> {
        val result = try {
            runBlocking {
                withTimeout(timeoutMs) {
                    client.listTools(ListToolsRequest())
                }
            }
        } catch (ex: TimeoutCancellationException) {
            throw IOException("MCP $serverLabel list-tools timed out after ${timeoutMs}ms", ex)
        }
        return result?.tools.orEmpty().map { it.name }.toSet()
    }

    fun callTool(toolName: String, arguments: Map<String, Any>, timeoutMs: Long): McpToolCallResult {
        val result = try {
            runBlocking {
                withTimeout(timeoutMs) {
                    client.callTool(toolName, arguments)
                }
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
                process.waitFor(250, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
            if (process.isAlive) {
                process.destroyForcibly()
            }
        }
    }

    companion object {
        fun start(command: List<String>, serverLabel: String): McpStdioClient {
            require(command.isNotEmpty()) { "MCP command cannot be empty." }
            val process = ProcessBuilder(command).start()

            // Drain stderr continuously to avoid deadlocks if the server logs.
            thread(name = "mcp-$serverLabel-stderr", isDaemon = true) {
                process.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        logger.debug { "mcp-$serverLabel stderr: $line" }
                    }
                }
            }

            val transport = StdioClientTransport(
                input = process.inputStream.asSource().buffered(),
                output = process.outputStream.asSink().buffered()
            )
            val client = Client(
                clientInfo = Implementation(name = "psyke", version = "0.1.0"),
                options = ClientOptions()
            )
            runBlocking {
                client.connect(transport)
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
    }
}

data class McpToolCallResult(
    val isError: Boolean,
    val content: String,
)

data class ToolHealthStatus(
    val available: Boolean,
    val detail: String,
)

private data class TimePayload(
    val timezone: String? = null,
)

private data class FetchPayload(
    val url: String? = null,
    @JsonProperty("max_chars")
    val maxChars: Int? = null,
)

private val mapper = jacksonObjectMapper()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
