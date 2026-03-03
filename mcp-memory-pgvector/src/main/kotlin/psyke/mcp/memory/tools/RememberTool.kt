package psyke.mcp.memory.tools

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import psyke.mcp.memory.db.MemoryRepository
import psyke.mcp.memory.embedding.Embedder

private val logger = KotlinLogging.logger {}
private val jackson = jacksonObjectMapper()

fun registerRememberTool(
    server: Server,
    repository: MemoryRepository,
    embedder: Embedder,
) {
    server.addTool(
        name = "remember",
        description = "Store a memory for later recall. The text will be embedded and stored for semantic search.",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                put("text", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("The memory text to store"))
                })
            },
            required = listOf("text")
        )
    ) { request ->
        handleRemember(request, repository, embedder)
    }
}

fun registerCreateMemoryTool(
    server: Server,
    repository: MemoryRepository,
    embedder: Embedder,
) {
    server.addTool(
        name = "create_memory",
        description = "Store a memory with metadata (source, confidence, tags).",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                put("content", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("The memory content to store"))
                })
                put("source", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Origin of this memory"))
                })
                put("confidence", buildJsonObject {
                    put("type", JsonPrimitive("number"))
                    put("description", JsonPrimitive("Confidence score 0.0-1.0"))
                })
            },
            required = listOf("content")
        )
    ) { request ->
        handleCreateMemory(request, repository, embedder)
    }
}

private fun handleRemember(
    request: CallToolRequest,
    repository: MemoryRepository,
    embedder: Embedder,
): CallToolResult {
    val args = request.arguments
    val text = args["text"]?.jsonPrimitive?.contentOrNull
        ?: args["memory"]?.jsonPrimitive?.contentOrNull
        ?: args["content"]?.jsonPrimitive?.contentOrNull
    if (text.isNullOrBlank()) {
        return errorResult("Missing required argument: text")
    }

    val tagsFromText = extractInlineTags(text)
    val cleanedText = removeInlineTags(text)

    return storeMemory(
        content = cleanedText,
        source = "remember",
        confidence = 0.5,
        tags = tagsFromText,
        repository = repository,
        embedder = embedder,
    )
}

private fun handleCreateMemory(
    request: CallToolRequest,
    repository: MemoryRepository,
    embedder: Embedder,
): CallToolResult {
    val args = request.arguments
    val content = args["content"]?.jsonPrimitive?.contentOrNull
        ?: args["text"]?.jsonPrimitive?.contentOrNull
        ?: args["memory"]?.jsonPrimitive?.contentOrNull
    if (content.isNullOrBlank()) {
        return errorResult("Missing required argument: content")
    }

    val source = args["source"]?.jsonPrimitive?.contentOrNull ?: "create_memory"
    val confidence = args["confidence"]?.jsonPrimitive?.doubleOrNull ?: 0.5

    val tagsFromText = extractInlineTags(content)
    val cleanedContent = removeInlineTags(content)

    return storeMemory(
        content = cleanedContent,
        source = source,
        confidence = confidence.coerceIn(0.0, 1.0),
        tags = tagsFromText,
        repository = repository,
        embedder = embedder,
    )
}

private fun storeMemory(
    content: String,
    source: String,
    confidence: Double,
    tags: List<String>,
    repository: MemoryRepository,
    embedder: Embedder,
): CallToolResult {
    return try {
        val embedding = embedder.embed(content)
        val fingerprint = normalizeFingerprint(content)
        val id = repository.insertMemory(
            content = content,
            embedding = embedding,
            source = source,
            confidence = confidence,
            tags = tags,
            fingerprint = fingerprint,
        )
        val response = mapOf("status" to "ok", "id" to id)
        CallToolResult(content = listOf(TextContent(text = jackson.writeValueAsString(response))))
    } catch (ex: Exception) {
        logger.warn(ex) { "Memory store failed" }
        errorResult("Failed to store memory: ${ex.message}")
    }
}

/**
 * Extracts inline tags from text like " tags=foo,bar,baz".
 * This matches the format McpHippocampus uses when enriching imprint text.
 */
internal fun extractInlineTags(text: String): List<String> {
    val match = Regex("""tags=([^\s)]+)""").find(text) ?: return emptyList()
    return match.groupValues[1].split(",").map { it.trim() }.filter { it.isNotBlank() }
}

internal fun removeInlineTags(text: String): String =
    text.replace(Regex("""\s*tags=[^\s)]+"""), "").trim()

internal fun normalizeFingerprint(text: String): String =
    text.lowercase().replace(Regex("\\s+"), " ").trim()
