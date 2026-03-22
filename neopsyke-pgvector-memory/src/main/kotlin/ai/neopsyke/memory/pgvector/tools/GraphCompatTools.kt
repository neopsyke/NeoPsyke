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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import ai.neopsyke.memory.pgvector.db.MemoryRepository
import ai.neopsyke.memory.pgvector.db.MemoryWriteMode
import ai.neopsyke.memory.pgvector.embedding.Embedder
import ai.neopsyke.memory.pgvector.metrics.MemoryProviderMetrics

private val logger = KotlinLogging.logger {}
private val jackson = jacksonObjectMapper()

private const val ADD_OBSERVATIONS_TOOL = "add_observations"
private const val READ_GRAPH_TOOL = "read_graph"
private const val DELETE_OBSERVATIONS_TOOL = "delete_observations"
private const val CREATE_ENTITIES_TOOL = "create_entities"

/**
 * Graph-compatible tools mapped to the flat memories table.
 * These exist so McpHippocampus can use its preferred graph imprint/purge path
 * without needing separate entity/observation tables.
 */
fun registerGraphCompatTools(
    server: Server,
    repository: MemoryRepository,
    embedder: Embedder,
    defaultNamespace: String,
    metrics: MemoryProviderMetrics? = null,
) {
    registerAddObservationsTool(server, repository, embedder, defaultNamespace, metrics)
    registerReadGraphTool(server, repository, defaultNamespace, metrics)
    registerDeleteObservationsTool(server, repository, defaultNamespace, metrics)
    registerCreateEntitiesTool(server, defaultNamespace, metrics)
}

private fun registerAddObservationsTool(
    server: Server,
    repository: MemoryRepository,
    embedder: Embedder,
    defaultNamespace: String,
    metrics: MemoryProviderMetrics?,
) {
    server.addTool(
        name = ADD_OBSERVATIONS_TOOL,
        description = "Add observations to an entity (stored as tagged memories).",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                put("observations", buildJsonObject {
                    put("type", JsonPrimitive("array"))
                    put("description", JsonPrimitive("List of observation groups"))
                })
                put("namespace", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Optional memory namespace/tenant. Defaults to provider namespace."))
                })
            },
            required = listOf("observations")
        )
    ) { request ->
        val startNs = metrics?.startTimer() ?: 0L
        val result = handleAddObservations(request, repository, embedder, defaultNamespace)
        metrics?.recordToolInvocation(ADD_OBSERVATIONS_TOOL, startNs)
        if (result.isError == true) metrics?.recordToolError(ADD_OBSERVATIONS_TOOL)
        result
    }
}

private fun registerReadGraphTool(
    server: Server,
    repository: MemoryRepository,
    defaultNamespace: String,
    metrics: MemoryProviderMetrics?,
) {
    server.addTool(
        name = READ_GRAPH_TOOL,
        description = "Read all stored memories as a graph structure.",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                put("namespace", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Optional memory namespace/tenant. Defaults to provider namespace."))
                })
            },
        )
    ) { request ->
        val startNs = metrics?.startTimer() ?: 0L
        val result = handleReadGraph(request, repository, defaultNamespace)
        metrics?.recordToolInvocation(READ_GRAPH_TOOL, startNs)
        if (result.isError == true) metrics?.recordToolError(READ_GRAPH_TOOL)
        result
    }
}

private fun registerDeleteObservationsTool(
    server: Server,
    repository: MemoryRepository,
    defaultNamespace: String,
    metrics: MemoryProviderMetrics?,
) {
    server.addTool(
        name = DELETE_OBSERVATIONS_TOOL,
        description = "Delete observations (memories) matching entity name and content.",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                put("deletions", buildJsonObject {
                    put("type", JsonPrimitive("array"))
                    put("description", JsonPrimitive("List of deletion groups"))
                })
                put("namespace", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Optional memory namespace/tenant. Defaults to provider namespace."))
                })
            },
            required = listOf("deletions")
        )
    ) { request ->
        val startNs = metrics?.startTimer() ?: 0L
        val result = handleDeleteObservations(request, repository, defaultNamespace)
        metrics?.recordToolInvocation(DELETE_OBSERVATIONS_TOOL, startNs)
        if (result.isError == true) metrics?.recordToolError(DELETE_OBSERVATIONS_TOOL)
        result
    }
}

private fun registerCreateEntitiesTool(
    server: Server,
    defaultNamespace: String,
    metrics: MemoryProviderMetrics?,
) {
    server.addTool(
        name = CREATE_ENTITIES_TOOL,
        description = "Create named entities (no-op: entity concept is mapped to source field).",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                put("entities", buildJsonObject {
                    put("type", JsonPrimitive("array"))
                    put("description", JsonPrimitive("List of entities to create"))
                })
                put("namespace", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Optional memory namespace/tenant. Defaults to provider namespace."))
                })
            },
            required = listOf("entities")
        )
    ) { request ->
        val startNs = metrics?.startTimer() ?: 0L
        val namespace = resolveNamespace(request.arguments, defaultNamespace)
        // No-op: entities are implicit via the source field on memories.
        val result = CallToolResult(
            content = listOf(TextContent(text = jackson.writeValueAsString(mapOf("status" to "ok", "namespace" to namespace))))
        )
        metrics?.recordToolInvocation(CREATE_ENTITIES_TOOL, startNs)
        result
    }
}

/**
 * Handles add_observations in three argument formats that McpHippocampus sends:
 *
 * Format 1: {observations: [{entityName: "x", contents: ["obs1", "obs2"]}]}
 * Format 2: {entity_name: "x", observations: ["obs1"]}
 * Format 3: {entity: "x", observation: "obs1"}
 */
private fun handleAddObservations(
    request: CallToolRequest,
    repository: MemoryRepository,
    embedder: Embedder,
    defaultNamespace: String,
): CallToolResult {
    val args = request.arguments
    val namespace = resolveNamespace(args, defaultNamespace)
    var stored = 0

    try {
        // Format 1: {observations: [{entityName, contents}]}
        val observationsArray = args["observations"]?.jsonArray
        if (observationsArray != null) {
            for (group in observationsArray) {
                val obj = group.jsonObject
                val entityName = obj["entityName"]?.jsonPrimitive?.contentOrNull
                    ?: obj["entity_name"]?.jsonPrimitive?.contentOrNull
                    ?: "psyke_long_term_memory"

                val contents = obj["contents"]?.jsonArray?.map { it.jsonPrimitive.content }
                    ?: obj["observations"]?.jsonArray?.map { it.jsonPrimitive.content }
                    ?: continue

                for (content in contents) {
                    if (content.isBlank()) continue
                    val tagsFromText = extractInlineTags(content)
                    val cleanedContent = removeInlineTags(content)
                    val embedding = embedder.embed(cleanedContent)
                    repository.writeMemory(
                        namespace = namespace,
                        content = content, // keep original (with metadata) for purge matching
                        embedding = embedding,
                        source = entityName,
                        confidence = extractConfidence(content),
                        tags = tagsFromText,
                        fingerprint = normalizeFingerprint(cleanedContent),
                        writeMode = MemoryWriteMode.DEDUPE_IF_SIMILAR
                    )
                    stored++
                }
            }
        } else {
            // Format 2: {entity_name, observations: [string]}
            val entityName = args["entity_name"]?.jsonPrimitive?.contentOrNull
                ?: args["entity"]?.jsonPrimitive?.contentOrNull
                ?: "psyke_long_term_memory"

            val singleObs = args["observation"]?.jsonPrimitive?.contentOrNull
            val obsArray = args["observations"]?.jsonArray?.map { it.jsonPrimitive.content }

            val contents = when {
                obsArray != null -> obsArray
                singleObs != null -> listOf(singleObs)
                else -> emptyList()
            }

            for (content in contents) {
                if (content.isBlank()) continue
                val tagsFromText = extractInlineTags(content)
                val cleanedContent = removeInlineTags(content)
                val embedding = embedder.embed(cleanedContent)
                repository.writeMemory(
                    namespace = namespace,
                    content = content,
                    embedding = embedding,
                    source = entityName,
                    confidence = extractConfidence(content),
                    tags = tagsFromText,
                    fingerprint = normalizeFingerprint(cleanedContent),
                    writeMode = MemoryWriteMode.DEDUPE_IF_SIMILAR
                )
                stored++
            }
        }
    } catch (ex: Exception) {
        logger.warn(ex) { "add_observations failed namespace=$namespace" }
        return errorResult("add_observations failed: ${ex.message}")
    }

    val response = mapOf("status" to "ok", "stored" to stored, "namespace" to namespace)
    return CallToolResult(content = listOf(TextContent(text = jackson.writeValueAsString(response))))
}

private fun handleReadGraph(
    request: CallToolRequest,
    repository: MemoryRepository,
    defaultNamespace: String,
): CallToolResult {
    val namespace = resolveNamespace(request.arguments, defaultNamespace)
    return try {
        val grouped = repository.readAllGroupedBySource(namespace)
        val entities = grouped.map { (source, contents) ->
            mapOf(
                "name" to source,
                "entityType" to "memory_source",
                "observations" to contents,
            )
        }
        val response = mapOf(
            "namespace" to namespace,
            "entities" to entities,
            "relations" to emptyList<Any>(),
        )
        CallToolResult(content = listOf(TextContent(text = jackson.writeValueAsString(response))))
    } catch (ex: Exception) {
        logger.warn(ex) { "read_graph failed namespace=$namespace" }
        errorResult("read_graph failed: ${ex.message}")
    }
}

/**
 * Handles delete_observations in the format McpHippocampus sends:
 * {deletions: [{entityName: "x", observations: ["obs1", "obs2"]}]}
 */
private fun handleDeleteObservations(
    request: CallToolRequest,
    repository: MemoryRepository,
    defaultNamespace: String,
): CallToolResult {
    val args = request.arguments
    val namespace = resolveNamespace(args, defaultNamespace)
    var deleted = 0

    try {
        val deletionsArray = args["deletions"]?.jsonArray
        if (deletionsArray != null) {
            for (group in deletionsArray) {
                val obj = group.jsonObject
                val entityName = obj["entityName"]?.jsonPrimitive?.contentOrNull
                    ?: obj["entity_name"]?.jsonPrimitive?.contentOrNull
                    ?: continue
                val observations = obj["observations"]?.jsonArray?.map { it.jsonPrimitive.content }
                    ?: continue
                deleted += repository.deleteByEntityAndContents(namespace, entityName, observations)
            }
        }
    } catch (ex: Exception) {
        logger.warn(ex) { "delete_observations failed namespace=$namespace" }
        return errorResult("delete_observations failed: ${ex.message}")
    }

    val response = mapOf("status" to "ok", "deleted" to deleted, "namespace" to namespace)
    return CallToolResult(content = listOf(TextContent(text = jackson.writeValueAsString(response))))
}

/**
 * Extract confidence from observation text formatted by McpHippocampus:
 * "summary (source=x; confidence=0.85 tags=a,b)"
 */
private fun extractConfidence(text: String): Double {
    val match = Regex("""confidence=([0-9.]+)""").find(text)
    return match?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.5
}
