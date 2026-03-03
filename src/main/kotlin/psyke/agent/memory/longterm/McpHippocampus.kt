package psyke.agent.memory.longterm

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import mu.KotlinLogging
import psyke.agent.support.TextSecurity
import psyke.agent.tools.mcp.LazyMcpClientHolder
import psyke.agent.tools.mcp.McpToolCallResult
import java.io.IOException
import java.util.Locale
import kotlin.math.max

private val logger = KotlinLogging.logger {}

class McpHippocampus(
    command: List<String>,
    private val callTimeoutMs: Long,
    private val defaultMaxItems: Int = 4,
    private val defaultMaxChars: Int = 1200,
) : Hippocampus {
    override val providerName: String = "mcp_memory"

    private val clientHolder = LazyMcpClientHolder(command, serverLabel = "memory")

    @Volatile
    private var searchToolName: String? = null
    @Volatile
    private var imprintToolName: String? = null

    override fun recall(query: MemoryRecallQuery): MemoryRecall {
        val cue = query.cue.trim()
        if (cue.isEmpty()) {
            return MemoryRecall(provider = providerName, text = "")
        }

        val toolName = resolveSearchToolName()
            ?: throw IOException("MCP memory server does not expose a search-like tool.")

        val maxItems = query.maxItems.coerceIn(1, max(1, defaultMaxItems))
        val maxChars = query.maxChars.coerceIn(256, max(256, defaultMaxChars))
        val result = callWithFallbackArguments(
            toolName = toolName,
            argumentCandidates = buildArgumentCandidates(
                toolName = toolName,
                query = query.copy(cue = cue),
                maxItems = maxItems
            )
        )

        if (result.isError) {
            throw IOException(
                "MCP memory tool $toolName returned an error: ${TextSecurity.preview(result.content, 200)}"
            )
        }

        val normalized = normalizeResultText(result.content, maxChars, cue)
        return MemoryRecall(
            provider = providerName,
            text = normalized.text,
            hitCount = normalized.hitCount,
            truncated = normalized.truncated
        )
    }

    override fun close() {
        clientHolder.close()
    }

    @Suppress("UNCHECKED_CAST")
    override fun fetchServerMetrics(): Map<String, Any>? {
        return try {
            val tools = clientHolder.listTools(callTimeoutMs)
            if (METRICS_TOOL_NAME !in tools) return null
            val result = clientHolder.callTool(
                toolName = METRICS_TOOL_NAME,
                arguments = emptyMap(),
                timeoutMs = callTimeoutMs
            )
            if (result.isError) return null
            mapper.readValue(result.content, Map::class.java) as? Map<String, Any>
        } catch (ex: Exception) {
            logger.debug(ex) { "Failed to fetch server-side memory metrics." }
            null
        }
    }

    override fun purgeTaggedObservations(tagMarkers: List<String>): Int {
        val normalizedMarkers = tagMarkers
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (normalizedMarkers.isEmpty()) {
            return 0
        }

        val toolNames = try {
            clientHolder.listTools(callTimeoutMs)
        } catch (ex: Exception) {
            logger.warn(ex) { "MCP memory purge failed while listing tools." }
            return 0
        }

        if ("read_graph" !in toolNames || "delete_observations" !in toolNames) {
            logger.warn {
                "MCP memory purge skipped; required tools missing. has_read_graph=${"read_graph" in toolNames} has_delete_observations=${"delete_observations" in toolNames}"
            }
            return 0
        }

        val readResult = try {
            callWithFallbackArguments(
                toolName = "read_graph",
                argumentCandidates = listOf(emptyMap<String, Any>())
            )
        } catch (ex: Exception) {
            logger.warn(ex) { "MCP memory purge failed while reading graph." }
            return 0
        }

        if (readResult.isError) {
            logger.warn {
                "MCP memory purge read_graph returned error: ${TextSecurity.preview(readResult.content, 220)}"
            }
            return 0
        }

        val deletions = buildObservationDeletions(readResult.content, normalizedMarkers)
        if (deletions.isEmpty()) {
            return 0
        }

        val deleteResult = try {
            callWithFallbackArguments(
                toolName = "delete_observations",
                argumentCandidates = listOf(
                    mapOf("deletions" to deletions)
                )
            )
        } catch (ex: Exception) {
            logger.warn(ex) { "MCP memory purge failed while deleting observations." }
            return 0
        }

        if (deleteResult.isError) {
            logger.warn {
                "MCP memory purge delete_observations returned error: ${TextSecurity.preview(deleteResult.content, 220)}"
            }
            return 0
        }

        return deletions.sumOf { deletion ->
            @Suppress("UNCHECKED_CAST")
            (deletion["observations"] as? List<String>)?.size ?: 0
        }
    }

    override fun imprint(imprint: MemoryImprint): Boolean {
        val summary = TextSecurity.clamp(imprint.summary.trim(), 600)
        if (summary.isBlank()) {
            return false
        }

        val toolNames = try {
            clientHolder.listTools(callTimeoutMs)
        } catch (ex: Exception) {
            logger.warn(ex) { "MCP memory imprint failed while listing tools." }
            return false
        }

        if ("add_observations" in toolNames) {
            val graphSaved = imprintViaGraphObservations(toolNames, summary, imprint)
            if (graphSaved) {
                return true
            }
            logger.warn {
                "MCP graph memory imprint failed via add_observations; falling back to generic write tools."
            }
        }

        val toolName = resolveImprintToolName(toolNames) ?: return false
        return try {
            val result = callWithFallbackArguments(
                toolName = toolName,
                argumentCandidates = buildImprintArgumentCandidates(toolName, summary, imprint)
            )
            if (result.isError) {
                logger.warn {
                    "MCP memory imprint tool=$toolName returned error: ${TextSecurity.preview(result.content, 220)}"
                }
                false
            } else {
                true
            }
        } catch (ex: Exception) {
            logger.warn(ex) { "MCP memory imprint call failed for tool=$toolName." }
            false
        }
    }

    private fun resolveSearchToolName(): String? {
        searchToolName?.let { return it }
        val availableTools = clientHolder.listTools(callTimeoutMs)
        val selected = preferredSearchTools.firstOrNull { it in availableTools }
            ?: availableTools.firstOrNull { candidate ->
                val lower = candidate.lowercase()
                lower.contains("search") || lower.contains("recall") || lower.contains("memory")
            }
        searchToolName = selected
        return selected
    }

    private fun resolveImprintToolName(toolNames: Set<String>): String? {
        imprintToolName?.let { return it }
        val selected = preferredImprintTools.firstOrNull { it in toolNames }
            ?: toolNames.firstOrNull { candidate ->
                val lower = candidate.lowercase()
                val looksWrite = lower.contains("remember") ||
                    lower.contains("imprint") ||
                    lower.contains("add_memory") ||
                    lower.contains("create_memory") ||
                    lower.contains("write_memory")
                val isUnsafe = lower.contains("delete") || lower.contains("remove") || lower.contains("clear")
                looksWrite && !isUnsafe
            }
        imprintToolName = selected
        return selected
    }

    private fun buildArgumentCandidates(
        toolName: String,
        query: MemoryRecallQuery,
        maxItems: Int,
    ): List<Map<String, Any>> {
        val context = buildContext(query)
        val baseCandidates = when (toolName) {
            "search_nodes" -> listOf(
                mapOf("query" to query.cue),
                mapOf("query" to query.cue, "limit" to maxItems)
            )

            else -> listOf(
                mapOf("query" to query.cue, "limit" to maxItems),
                mapOf("query" to query.cue),
                mapOf("q" to query.cue, "top_k" to maxItems),
                mapOf("text" to query.cue)
            )
        }

        if (context.isBlank()) {
            return baseCandidates
        }

        return baseCandidates + baseCandidates.map { candidate ->
            candidate + ("context" to context)
        }
    }

    private fun buildImprintArgumentCandidates(
        toolName: String,
        summary: String,
        imprint: MemoryImprint,
    ): List<Map<String, Any>> {
        val tagsText = if (imprint.tags.isEmpty()) "" else " tags=${imprint.tags.joinToString(",")}"
        val enriched = "$summary$tagsText"
        return when (toolName) {
            "remember" -> listOf(
                mapOf("text" to enriched, "write_mode" to "dedupe_if_similar"),
                mapOf("memory" to enriched, "write_mode" to "dedupe_if_similar"),
                mapOf("content" to enriched, "write_mode" to "dedupe_if_similar")
            )

            "create_memory", "add_memory", "write_memory", "imprint_memory" -> listOf(
                mapOf(
                    "content" to enriched,
                    "source" to imprint.source,
                    "confidence" to imprint.confidence,
                    "write_mode" to "dedupe_if_similar"
                ),
                mapOf(
                    "text" to enriched,
                    "metadata" to mapOf("source" to imprint.source, "confidence" to imprint.confidence),
                    "write_mode" to "dedupe_if_similar"
                ),
                mapOf("memory" to enriched, "write_mode" to "dedupe_if_similar")
            )

            else -> listOf(
                mapOf("content" to enriched, "write_mode" to "dedupe_if_similar"),
                mapOf("text" to enriched, "write_mode" to "dedupe_if_similar"),
                mapOf("memory" to enriched, "write_mode" to "dedupe_if_similar")
            )
        }
    }

    private fun imprintViaGraphObservations(
        toolNames: Set<String>,
        summary: String,
        imprint: MemoryImprint,
    ): Boolean {
        val entityName = "psyke_long_term_memory"
        if ("create_entities" in toolNames) {
            tryCreateMemoryEntity(entityName)
        }
        val tagsText = if (imprint.tags.isEmpty()) "" else " tags=${imprint.tags.joinToString(",")}"
        val observation = TextSecurity.clamp(
            "$summary (source=${imprint.source}; confidence=${String.format(Locale.ROOT, "%.2f", imprint.confidence)}$tagsText)",
            700
        )
        val candidates = listOf(
            mapOf(
                "observations" to listOf(
                    mapOf(
                        "entityName" to entityName,
                        "contents" to listOf(observation)
                    )
                )
            ),
            mapOf(
                "entity_name" to entityName,
                "observations" to listOf(observation)
            ),
            mapOf(
                "entity" to entityName,
                "observation" to observation
            )
        )
        return try {
            val result = callWithFallbackArguments(
                toolName = "add_observations",
                argumentCandidates = candidates
            )
            if (result.isError) {
                logger.warn {
                    "MCP graph memory add_observations returned error: ${TextSecurity.preview(result.content, 220)}"
                }
                false
            } else {
                true
            }
        } catch (ex: Exception) {
            logger.warn(ex) { "MCP graph memory add_observations imprint failed." }
            false
        }
    }

    private fun tryCreateMemoryEntity(entityName: String) {
        val candidates = listOf(
            mapOf(
                "entities" to listOf(
                    mapOf(
                        "name" to entityName,
                        "entityType" to "memory_log",
                        "observations" to emptyList<String>()
                    )
                )
            ),
            mapOf(
                "name" to entityName,
                "entity_type" to "memory_log"
            )
        )
        try {
            callWithFallbackArguments(toolName = "create_entities", argumentCandidates = candidates)
        } catch (_: Exception) {
            // Best effort: entity may already exist or server may reject one argument variant.
        }
    }

    private fun callWithFallbackArguments(
        toolName: String,
        argumentCandidates: List<Map<String, Any>>,
    ): McpToolCallResult {
        var lastResult: McpToolCallResult? = null
        var lastError: Exception? = null

        for (arguments in argumentCandidates) {
            try {
                val result = clientHolder.callTool(
                    toolName = toolName,
                    arguments = arguments,
                    timeoutMs = callTimeoutMs
                )
                if (!result.isError) {
                    return result
                }
                logger.debug {
                    "MCP memory tool=$toolName attempt failed with args=${arguments.keys} error=${TextSecurity.preview(result.content, 180)}"
                }
                lastResult = result
            } catch (ex: Exception) {
                lastError = ex
                logger.debug(ex) {
                    "MCP memory search call failed for tool=$toolName with args=${arguments.keys}."
                }
            }
        }

        if (lastResult != null) {
            return lastResult
        }
        throw IOException(
            "MCP memory search call failed for tool=$toolName.",
            lastError
        )
    }

    private fun buildContext(query: MemoryRecallQuery): String {
        val dialogue = query.recentDialogue
            .takeLast(6)
            .joinToString(separator = "\n") { turn ->
                "${turn.role.name.lowercase()}: ${TextSecurity.preview(turn.content, 120)}"
            }
        val summary = query.shortTermContextSummary
            .lineSequence()
            .take(10)
            .joinToString(separator = "\n")
            .trim()
        return listOfNotNull(
            dialogue.ifBlank { null }?.let { "recent_dialogue:\n$it" },
            summary.ifBlank { null }?.let { "short_term_context_summary:\n$it" }
        ).joinToString(separator = "\n\n")
    }

    internal fun buildObservationDeletions(rawGraph: String, tagMarkers: List<String>): List<Map<String, Any>> {
        if (tagMarkers.isEmpty()) {
            return emptyList()
        }
        val root = try {
            mapper.readTree(rawGraph)
        } catch (_: Exception) {
            return emptyList()
        }
        val entities = root.get("entities")?.takeIf { it.isArray } ?: return emptyList()
        val loweredMarkers = tagMarkers.map { it.lowercase() }

        return entities.mapNotNull { entity ->
            val entityName = entity.get("name")?.asText()?.trim().orEmpty()
            if (entityName.isBlank()) {
                return@mapNotNull null
            }
            val observations = entity.get("observations")
                ?.takeIf { it.isArray }
                ?.mapNotNull { it.asText().trim().ifBlank { null } }
                .orEmpty()
            if (observations.isEmpty()) {
                return@mapNotNull null
            }
            val matched = observations.filter { observation ->
                val lowered = observation.lowercase()
                loweredMarkers.any { marker -> lowered.contains(marker) }
            }
            if (matched.isEmpty()) {
                return@mapNotNull null
            }
            mapOf(
                "entityName" to entityName,
                "observations" to matched
            )
        }
    }

    internal fun normalizeResultText(raw: String, maxChars: Int, queryHint: String = ""): NormalizedRecall {
        val trimmedRaw = raw.trim()
        if (trimmedRaw.isEmpty()) {
            return NormalizedRecall(text = "", hitCount = 0, truncated = false)
        }

        val root = try {
            mapper.readTree(trimmedRaw)
        } catch (_: Exception) {
            null
        }

        if (root != null) {
            val structured = parseStructuredRecall(root, maxChars, queryHint)
            if (structured != null) {
                return structured
            }
            if (isEmptyStructuredPayload(root)) {
                logger.debug { "MCP memory recall returned empty structured payload." }
                return NormalizedRecall(text = "", hitCount = 0, truncated = false)
            }
        }

        val clamped = TextSecurity.clamp(trimmedRaw, maxChars)
        return NormalizedRecall(
            text = clamped,
            hitCount = clamped.lineSequence().count { it.trimStart().startsWith("-") },
            truncated = clamped.length < trimmedRaw.length
        )
    }

    private fun parseStructuredRecall(root: JsonNode, maxChars: Int, queryHint: String): NormalizedRecall? {
        val candidates = extractResultNodes(root)
        if (candidates.isEmpty()) {
            return if (isEmptyStructuredPayload(root)) {
                NormalizedRecall(text = "", hitCount = 0, truncated = false)
            } else {
                null
            }
        }

        val lines = candidates
            .mapNotNull { candidate ->
                renderCandidateLine(candidate, queryHint)?.let { TextSecurity.preview(it, 220) }
            }
            .filter { it.isNotBlank() }
        if (lines.isEmpty()) {
            return if (isEmptyStructuredPayload(root)) {
                NormalizedRecall(text = "", hitCount = 0, truncated = false)
            } else {
                null
            }
        }

        val rendered = lines.joinToString(separator = "\n") { "- $it" }
        val clamped = TextSecurity.clamp(rendered, maxChars)
        return NormalizedRecall(
            text = clamped,
            hitCount = lines.size,
            truncated = clamped.length < rendered.length
        )
    }

    private fun extractResultNodes(root: JsonNode): List<JsonNode> {
        if (root.isArray) {
            return root.toList()
        }

        if (root.isObject) {
            val arrayFields = listOf("results", "items", "memories", "nodes", "observations", "entities", "relations")
            val firstArrayField = arrayFields.firstNotNullOfOrNull { field ->
                root.get(field)?.takeIf { it.isArray }
            }
            if (firstArrayField != null) {
                return firstArrayField.toList()
            }
            return listOf(root)
        }

        return emptyList()
    }

    private fun isEmptyStructuredPayload(root: JsonNode): Boolean {
        if (root.isArray) {
            return root.size() == 0
        }
        if (!root.isObject) {
            return false
        }

        val fields = root.fields().asSequence().toList()
        if (fields.isEmpty()) {
            return true
        }

        val hasNonEmptySignal = fields.any { (_, value) ->
            when {
                value.isTextual -> value.asText().isNotBlank()
                value.isNumber || value.isBoolean -> true
                value.isArray -> value.size() > 0
                value.isObject -> value.fields().asSequence().any()
                else -> false
            }
        }
        if (hasNonEmptySignal) {
            return false
        }

        return fields.all { (_, value) -> value.isArray || value.isObject || value.isNull }
    }

    private fun renderCandidateLine(node: JsonNode, queryHint: String): String? {
        if (node.isTextual) {
            return node.asText().trim().ifBlank { null }
        }

        if (!node.isObject) {
            return node.toString()
        }

        val observations = node.get("observations")
        if (observations?.isArray == true) {
            val observationItems = observations
                .mapNotNull { it.asText().trim().ifBlank { null } }
            if (observationItems.isEmpty()) {
                return null
            }
            val loweredHint = queryHint.trim().lowercase()
            val prioritized = if (loweredHint.isNotBlank()) {
                observationItems.filter { it.lowercase().contains(loweredHint) }
            } else {
                emptyList()
            }
            val selected = (if (prioritized.isNotEmpty()) prioritized else observationItems.asReversed())
                .take(3)
            val observationText = selected.joinToString(separator = " | ").trim()
            if (observationText.isNotBlank()) {
                return observationText
            }
        }

        val keyOrder = listOf(
            "name",
            "title",
            "entity",
            "observation",
            "content",
            "text",
            "memory",
            "summary",
            "description",
            "value"
        )
        keyOrder.forEach { key ->
            node.get(key)?.asText()?.trim()?.let { text ->
                if (text.isNotBlank()) {
                    return text
                }
            }
        }

        val relationSource = node.get("from")?.asText()?.trim().orEmpty()
        val relationTarget = node.get("to")?.asText()?.trim().orEmpty()
        val relationType = node.get("type")?.asText()?.trim().orEmpty()
        if (relationSource.isNotBlank() || relationTarget.isNotBlank() || relationType.isNotBlank()) {
            return listOf(relationSource, relationType, relationTarget)
                .filter { it.isNotBlank() }
                .joinToString(separator = " ")
                .ifBlank { null }
        }

        return null
    }

    internal data class NormalizedRecall(
        val text: String,
        val hitCount: Int,
        val truncated: Boolean,
    )

    private companion object {
        val mapper = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

        val preferredSearchTools = listOf(
            "search_memory",
            "search_memories",
            "search_nodes",
            "recall_memory",
            "recall",
            "query_memory",
            "query_memories",
            "search"
        )

        val preferredImprintTools = listOf(
            "remember",
            "create_memory",
            "add_memory",
            "write_memory",
            "imprint_memory"
        )

        const val METRICS_TOOL_NAME = "get_memory_metrics"
    }
}
