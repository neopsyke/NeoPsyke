package ai.neopsyke.memory.pgvector.tools

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
import ai.neopsyke.memory.pgvector.db.FactWriteRequest
import ai.neopsyke.memory.pgvector.db.MemoryRepository
import ai.neopsyke.memory.pgvector.db.MemoryWriteMode
import ai.neopsyke.memory.pgvector.embedding.Embedder
import ai.neopsyke.memory.pgvector.metrics.MemoryProviderMetrics
import java.time.Instant

private val logger = KotlinLogging.logger {}
private val jackson = jacksonObjectMapper()

private const val REMEMBER_TOOL = "remember"
private const val CREATE_MEMORY_TOOL = "create_memory"

fun registerRememberTool(
    server: Server,
    repository: MemoryRepository,
    embedder: Embedder,
    defaultNamespace: String,
    defaultFactSubject: String,
    metrics: MemoryProviderMetrics? = null,
) {
    server.addTool(
        name = REMEMBER_TOOL,
        description = "Store a memory for later recall. Supports write_mode: append, dedupe_if_similar, upsert_fact.",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                put("text", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("The memory text to store"))
                })
                put("namespace", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Optional memory namespace/tenant. Defaults to provider namespace."))
                })
                put("write_mode", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("append|dedupe_if_similar|upsert_fact"))
                })
                put("fact_subject", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Required for upsert_fact if no subject alias is provided."))
                })
                put("fact_key", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Required for upsert_fact; e.g. profile.name"))
                })
                put("fact_value", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Optional for upsert_fact. Defaults to text/content."))
                })
                put("fact_versioned_at", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Optional ISO-8601 timestamp for fact versioning."))
                })
            },
            required = listOf("text")
        )
    ) { request ->
        val startNs = metrics?.startTimer() ?: 0L
        val result = handleRemember(request, repository, embedder, defaultNamespace, defaultFactSubject)
        metrics?.recordToolInvocation(REMEMBER_TOOL, startNs)
        if (result.isError == true) metrics?.recordToolError(REMEMBER_TOOL)
        result
    }
}

fun registerCreateMemoryTool(
    server: Server,
    repository: MemoryRepository,
    embedder: Embedder,
    defaultNamespace: String,
    defaultFactSubject: String,
    metrics: MemoryProviderMetrics? = null,
) {
    server.addTool(
        name = CREATE_MEMORY_TOOL,
        description = "Store a memory with metadata (source, confidence, tags). Supports write_mode: append, dedupe_if_similar, upsert_fact.",
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
                put("namespace", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Optional memory namespace/tenant. Defaults to provider namespace."))
                })
                put("write_mode", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("append|dedupe_if_similar|upsert_fact"))
                })
                put("fact_subject", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Required for upsert_fact if no subject alias is provided."))
                })
                put("fact_key", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Required for upsert_fact; e.g. profile.name"))
                })
                put("fact_value", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Optional for upsert_fact. Defaults to content/text."))
                })
                put("fact_versioned_at", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Optional ISO-8601 timestamp for fact versioning."))
                })
            },
            required = listOf("content")
        )
    ) { request ->
        val startNs = metrics?.startTimer() ?: 0L
        val result = handleCreateMemory(request, repository, embedder, defaultNamespace, defaultFactSubject)
        metrics?.recordToolInvocation(CREATE_MEMORY_TOOL, startNs)
        if (result.isError == true) metrics?.recordToolError(CREATE_MEMORY_TOOL)
        result
    }
}

private fun handleRemember(
    request: CallToolRequest,
    repository: MemoryRepository,
    embedder: Embedder,
    defaultNamespace: String,
    defaultFactSubject: String,
): CallToolResult {
    val args = request.arguments
    val namespace = resolveNamespace(args, defaultNamespace)
    val text = args["text"]?.jsonPrimitive?.contentOrNull
        ?: args["memory"]?.jsonPrimitive?.contentOrNull
        ?: args["content"]?.jsonPrimitive?.contentOrNull
    if (text.isNullOrBlank()) {
        return errorResult("Missing required argument: text")
    }

    val writeMode = resolveWriteMode(args)
    val tagsFromText = extractInlineTags(text)
    val cleanedText = removeInlineTags(text)
    val factResult = resolveFactRequest(
        args = args,
        writeMode = writeMode,
        defaultFactSubject = defaultFactSubject,
        defaultFactValue = cleanedText
    )
    if (factResult.error != null) {
        return errorResult(factResult.error)
    }

    return storeMemory(
        namespace = namespace,
        content = cleanedText,
        source = "remember",
        confidence = 0.5,
        tags = tagsFromText,
        writeMode = writeMode,
        fact = factResult.fact,
        repository = repository,
        embedder = embedder,
    )
}

private fun handleCreateMemory(
    request: CallToolRequest,
    repository: MemoryRepository,
    embedder: Embedder,
    defaultNamespace: String,
    defaultFactSubject: String,
): CallToolResult {
    val args = request.arguments
    val namespace = resolveNamespace(args, defaultNamespace)
    val content = args["content"]?.jsonPrimitive?.contentOrNull
        ?: args["text"]?.jsonPrimitive?.contentOrNull
        ?: args["memory"]?.jsonPrimitive?.contentOrNull
    if (content.isNullOrBlank()) {
        return errorResult("Missing required argument: content")
    }

    val source = args["source"]?.jsonPrimitive?.contentOrNull ?: "create_memory"
    val confidence = args["confidence"]?.jsonPrimitive?.doubleOrNull ?: 0.5
    val writeMode = resolveWriteMode(args)
    val tagsFromText = extractInlineTags(content)
    val cleanedContent = removeInlineTags(content)
    val factResult = resolveFactRequest(
        args = args,
        writeMode = writeMode,
        defaultFactSubject = defaultFactSubject,
        defaultFactValue = cleanedContent
    )
    if (factResult.error != null) {
        return errorResult(factResult.error)
    }

    return storeMemory(
        namespace = namespace,
        content = cleanedContent,
        source = source,
        confidence = confidence.coerceIn(0.0, 1.0),
        tags = tagsFromText,
        writeMode = writeMode,
        fact = factResult.fact,
        repository = repository,
        embedder = embedder,
    )
}

private fun storeMemory(
    namespace: String,
    content: String,
    source: String,
    confidence: Double,
    tags: List<String>,
    writeMode: MemoryWriteMode,
    fact: FactWriteRequest?,
    repository: MemoryRepository,
    embedder: Embedder,
): CallToolResult {
    return try {
        val embedding = embedder.embed(content)
        val fingerprint = buildFingerprint(content, writeMode, fact)
        val writeResult = repository.writeMemory(
            namespace = namespace,
            content = content,
            embedding = embedding,
            source = source,
            confidence = confidence,
            tags = tags,
            fingerprint = fingerprint,
            writeMode = writeMode,
            fact = fact
        )
        val response = linkedMapOf<String, Any?>(
            "status" to "ok",
            "id" to writeResult.id,
            "namespace" to namespace,
            "write_mode" to writeMode.name.lowercase(),
            "write_result" to writeResult.status
        )
        if (writeResult.supersedesMemoryId != null) {
            response["supersedes_memory_id"] = writeResult.supersedesMemoryId
        }
        if (writeResult.semanticSimilarity != null) {
            response["semantic_similarity"] = String.format(java.util.Locale.US, "%.4f", writeResult.semanticSimilarity)
        }
        CallToolResult(content = listOf(TextContent(text = jackson.writeValueAsString(response))))
    } catch (ex: Exception) {
        logger.warn(ex) {
            "Memory store failed namespace=$namespace source=$source tags_count=${tags.size} write_mode=${writeMode.name.lowercase()}"
        }
        errorResult("Failed to store memory: ${ex.message}")
    }
}

private fun buildFingerprint(content: String, writeMode: MemoryWriteMode, fact: FactWriteRequest?): String {
    if (writeMode == MemoryWriteMode.UPSERT_FACT && fact != null) {
        return normalizeFingerprint("${fact.subject}|${fact.key}|${fact.value}")
    }
    return normalizeFingerprint(content)
}

private fun resolveFactRequest(
    args: Map<String, kotlinx.serialization.json.JsonElement>,
    writeMode: MemoryWriteMode,
    defaultFactSubject: String,
    defaultFactValue: String,
): FactResolution {
    if (writeMode != MemoryWriteMode.UPSERT_FACT) {
        return FactResolution(fact = null, error = null)
    }

    val keyRaw = args["fact_key"]?.jsonPrimitive?.contentOrNull
        ?: args["key"]?.jsonPrimitive?.contentOrNull
    val key = sanitizeFactKey(keyRaw)
        ?: return FactResolution(fact = null, error = "write_mode=upsert_fact requires fact_key.")

    val subjectRaw = args["fact_subject"]?.jsonPrimitive?.contentOrNull
        ?: args["subject"]?.jsonPrimitive?.contentOrNull
        ?: defaultFactSubject
    val subject = sanitizeNamespace(subjectRaw, fallback = defaultFactSubject)

    val value = args["fact_value"]?.jsonPrimitive?.contentOrNull
        ?: args["value"]?.jsonPrimitive?.contentOrNull
        ?: defaultFactValue
    if (value.isBlank()) {
        return FactResolution(fact = null, error = "write_mode=upsert_fact requires non-empty fact_value or content.")
    }

    val versionedAtRaw = args["fact_versioned_at"]?.jsonPrimitive?.contentOrNull
        ?: args["versioned_at"]?.jsonPrimitive?.contentOrNull
    val versionedAt = if (versionedAtRaw.isNullOrBlank()) {
        null
    } else {
        try {
            Instant.parse(versionedAtRaw)
        } catch (_: Exception) {
            return FactResolution(
                fact = null,
                error = "Invalid fact_versioned_at. Use ISO-8601 format, e.g. 2026-03-03T12:00:00Z."
            )
        }
    }

    return FactResolution(
        fact = FactWriteRequest(
            subject = subject,
            key = key,
            value = value.trim(),
            versionedAt = versionedAt
        ),
        error = null
    )
}

private data class FactResolution(
    val fact: FactWriteRequest?,
    val error: String?,
)

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
