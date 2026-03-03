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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import psyke.mcp.memory.db.MemoryRepository
import psyke.mcp.memory.embedding.Embedder

private val logger = KotlinLogging.logger {}
private val jackson = jacksonObjectMapper()

/**
 * Graph-compatible tools mapped to the flat memories table.
 * These exist so McpHippocampus can use its preferred graph imprint/purge path
 * without needing separate entity/observation tables.
 */
fun registerGraphCompatTools(
    server: Server,
    repository: MemoryRepository,
    embedder: Embedder,
) {
    registerAddObservationsTool(server, repository, embedder)
    registerReadGraphTool(server, repository)
    registerDeleteObservationsTool(server, repository)
    registerCreateEntitiesTool(server)
}

private fun registerAddObservationsTool(
    server: Server,
    repository: MemoryRepository,
    embedder: Embedder,
) {
    server.addTool(
        name = "add_observations",
        description = "Add observations to an entity (stored as tagged memories).",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                put("observations", buildJsonObject {
                    put("type", JsonPrimitive("array"))
                    put("description", JsonPrimitive("List of observation groups"))
                })
            },
            required = listOf("observations")
        )
    ) { request ->
        handleAddObservations(request, repository, embedder)
    }
}

private fun registerReadGraphTool(
    server: Server,
    repository: MemoryRepository,
) {
    server.addTool(
        name = "read_graph",
        description = "Read all stored memories as a graph structure.",
        inputSchema = Tool.Input(
            properties = buildJsonObject {},
        )
    ) { _ ->
        handleReadGraph(repository)
    }
}

private fun registerDeleteObservationsTool(
    server: Server,
    repository: MemoryRepository,
) {
    server.addTool(
        name = "delete_observations",
        description = "Delete observations (memories) matching entity name and content.",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                put("deletions", buildJsonObject {
                    put("type", JsonPrimitive("array"))
                    put("description", JsonPrimitive("List of deletion groups"))
                })
            },
            required = listOf("deletions")
        )
    ) { request ->
        handleDeleteObservations(request, repository)
    }
}

private fun registerCreateEntitiesTool(server: Server) {
    server.addTool(
        name = "create_entities",
        description = "Create named entities (no-op: entity concept is mapped to source field).",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                put("entities", buildJsonObject {
                    put("type", JsonPrimitive("array"))
                    put("description", JsonPrimitive("List of entities to create"))
                })
            },
            required = listOf("entities")
        )
    ) { _ ->
        // No-op: entities are implicit via the source field on memories.
        CallToolResult(
            content = listOf(TextContent(text = jackson.writeValueAsString(mapOf("status" to "ok"))))
        )
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
): CallToolResult {
    val args = request.arguments
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
                    repository.insertMemory(
                        content = content, // keep original (with metadata) for purge matching
                        embedding = embedding,
                        source = entityName,
                        confidence = extractConfidence(content),
                        tags = tagsFromText,
                        fingerprint = normalizeFingerprint(cleanedContent),
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
                repository.insertMemory(
                    content = content,
                    embedding = embedding,
                    source = entityName,
                    confidence = extractConfidence(content),
                    tags = tagsFromText,
                    fingerprint = normalizeFingerprint(cleanedContent),
                )
                stored++
            }
        }
    } catch (ex: Exception) {
        logger.warn(ex) { "add_observations failed" }
        return errorResult("add_observations failed: ${ex.message}")
    }

    val response = mapOf("status" to "ok", "stored" to stored)
    return CallToolResult(content = listOf(TextContent(text = jackson.writeValueAsString(response))))
}

private fun handleReadGraph(repository: MemoryRepository): CallToolResult {
    return try {
        val grouped = repository.readAllGroupedBySource()
        val entities = grouped.map { (source, contents) ->
            mapOf(
                "name" to source,
                "entityType" to "memory_source",
                "observations" to contents,
            )
        }
        val response = mapOf(
            "entities" to entities,
            "relations" to emptyList<Any>(),
        )
        CallToolResult(content = listOf(TextContent(text = jackson.writeValueAsString(response))))
    } catch (ex: Exception) {
        logger.warn(ex) { "read_graph failed" }
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
): CallToolResult {
    val args = request.arguments
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
                deleted += repository.deleteByEntityAndContents(entityName, observations)
            }
        }
    } catch (ex: Exception) {
        logger.warn(ex) { "delete_observations failed" }
        return errorResult("delete_observations failed: ${ex.message}")
    }

    val response = mapOf("status" to "ok", "deleted" to deleted)
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
