package ai.neopsyke.memory.pgvector.tools

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import ai.neopsyke.memory.pgvector.PgvectorMemoryProviderConfig
import ai.neopsyke.memory.pgvector.db.MemoryRepository
import ai.neopsyke.memory.pgvector.embedding.Embedder
import ai.neopsyke.memory.pgvector.metrics.MemoryProviderMetrics

private val logger = KotlinLogging.logger {}
private val jackson = jacksonObjectMapper()

private const val TOOL_NAME = "search_memory"

fun registerSearchMemoryTool(
    server: Server,
    repository: MemoryRepository,
    embedder: Embedder,
    config: PgvectorMemoryProviderConfig,
    metrics: MemoryProviderMetrics? = null,
) {
    server.addTool(
        name = TOOL_NAME,
        description = "Search stored memories using semantic similarity. Returns the most relevant memories matching the query.",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("The search query text"))
                })
                put("limit", buildJsonObject {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive("Maximum number of results to return"))
                })
                put("tags", buildJsonObject {
                    put("type", JsonPrimitive("array"))
                    put("items", buildJsonObject { put("type", JsonPrimitive("string")) })
                    put("description", JsonPrimitive("Optional tag filter: only return memories with at least one matching tag"))
                })
                put("namespace", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Optional memory namespace/tenant. Defaults to provider namespace."))
                })
            },
            required = listOf("query")
        )
    ) { request ->
        val startNs = metrics?.startTimer() ?: 0L
        val result = handleSearchMemory(request, repository, embedder, config)
        metrics?.recordToolInvocation(TOOL_NAME, startNs)
        if (result.isError == true) metrics?.recordToolError(TOOL_NAME)
        result
    }
}

private fun handleSearchMemory(
    request: CallToolRequest,
    repository: MemoryRepository,
    embedder: Embedder,
    config: PgvectorMemoryProviderConfig,
): CallToolResult {
    val args = request.arguments
    val namespace = resolveNamespace(args, config.defaultNamespace)
    val query = args["query"]?.jsonPrimitive?.contentOrNull
        ?: args["q"]?.jsonPrimitive?.contentOrNull
        ?: args["text"]?.jsonPrimitive?.contentOrNull
    if (query.isNullOrBlank()) {
        return errorResult("Missing required argument: query")
    }

    val limit = args["limit"]?.jsonPrimitive?.intOrNull
        ?: args["top_k"]?.jsonPrimitive?.intOrNull
        ?: config.searchDefaultLimit

    val tagFilter = try {
        args["tags"]?.jsonArray?.map { it.jsonPrimitive.content }
    } catch (_: Exception) {
        null
    }

    return try {
        val embedding = embedder.embed(query)
        val results = repository.searchByVector(
            namespace = namespace,
            queryEmbedding = embedding,
            limit = limit.coerceIn(1, 50),
            tagFilter = tagFilter,
        )

        val response = mapOf(
            "results" to results.map { row ->
                mapOf(
                    "content" to row.content,
                    "source" to row.source,
                    "namespace" to row.namespace,
                    "confidence" to row.confidence,
                    "similarity" to String.format(java.util.Locale.US, "%.4f", row.similarity),
                    "score" to String.format(java.util.Locale.US, "%.4f", row.score),
                    "is_active" to row.isActive,
                    "supersedes_memory_id" to row.supersedesMemoryId,
                    "fact_subject" to row.factSubject,
                    "fact_key" to row.factKey,
                    "fact_value" to row.factValue,
                    "versioned_at" to row.versionedAt?.toString(),
                    "tags" to row.tags,
                    "created_at" to row.createdAt.toString(),
                )
            }
        )
        CallToolResult(content = listOf(TextContent(text = jackson.writeValueAsString(response))))
    } catch (ex: Exception) {
        logger.warn(ex) { "search_memory failed namespace=$namespace query_len=${query.length}" }
        errorResult("Search failed: ${ex.message}")
    }
}
