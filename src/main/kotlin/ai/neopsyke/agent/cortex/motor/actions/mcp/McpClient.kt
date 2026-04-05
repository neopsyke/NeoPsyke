package ai.neopsyke.agent.cortex.motor.actions.mcp

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
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private val logger = KotlinLogging.logger {}

class LazyMcpClientHolder(
    private val command: List<String>,
    private val environment: Map<String, String> = emptyMap(),
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
            client ?: McpStdioClient.start(command, environment, serverLabel, scope).also { created ->
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
        suspend fun start(
            command: List<String>,
            environment: Map<String, String> = emptyMap(),
            serverLabel: String,
            scope: CoroutineScope? = null,
        ): McpStdioClient {
            require(command.isNotEmpty()) { "MCP command cannot be empty." }
            var lastError: Exception? = null
            for (attempt in 1..START_MAX_ATTEMPTS) {
                try {
                    return startOnce(command = command, environment = environment, serverLabel = serverLabel, scope = scope)
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

        private suspend fun startOnce(
            command: List<String>,
            environment: Map<String, String>,
            serverLabel: String,
            scope: CoroutineScope? = null,
        ): McpStdioClient {
            require(command.isNotEmpty()) { "MCP command cannot be empty." }
            val processBuilder = ProcessBuilder(command)
            if (environment.isNotEmpty()) {
                val env = processBuilder.environment()
                env.clear()
                env.putAll(environment)
            }
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

data class McpToolCallResult(
    val isError: Boolean,
    val content: String,
)

data class McpToolDescriptor(
    val name: String,
    val description: String,
    val inputSchema: String,
)
