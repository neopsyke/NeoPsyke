package ai.neopsyke.memory.pgvector.tools

import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.TextContent
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import ai.neopsyke.memory.pgvector.db.MemoryWriteMode

private val NON_NAMESPACE_CHARS = Regex("[^A-Za-z0-9._:-]")
private val NAMESPACE_WHITESPACE = Regex("\\s+")
private val NON_FACT_KEY_CHARS = Regex("[^A-Za-z0-9._:-]")
private val FACT_KEY_WHITESPACE = Regex("\\s+")
private const val MAX_NAMESPACE_CHARS = 120
private const val MAX_FACT_KEY_CHARS = 120

fun errorResult(message: String): CallToolResult =
    CallToolResult(
        content = listOf(TextContent(text = message)),
        isError = true,
    )

internal fun resolveNamespace(arguments: Map<String, JsonElement>, defaultNamespace: String): String {
    val candidate = listOf("namespace", "tenant", "workspace", "client")
        .firstNotNullOfOrNull { key -> arguments[key]?.jsonPrimitive?.contentOrNull }
    return sanitizeNamespace(candidate, defaultNamespace)
}

internal fun sanitizeNamespace(raw: String?, fallback: String): String {
    val candidate = raw?.trim().orEmpty()
    if (candidate.isBlank()) {
        return fallback
    }
    val collapsed = candidate.replace(NAMESPACE_WHITESPACE, "_")
    val sanitized = collapsed.replace(NON_NAMESPACE_CHARS, "-")
    val trimmed = sanitized.trim(' ', '-', '_')
    if (trimmed.isBlank()) {
        return fallback
    }
    return trimmed.take(MAX_NAMESPACE_CHARS)
}

internal fun resolveWriteMode(
    arguments: Map<String, JsonElement>,
    fallback: MemoryWriteMode = MemoryWriteMode.APPEND,
): MemoryWriteMode {
    val raw = arguments["write_mode"]?.jsonPrimitive?.contentOrNull
    return MemoryWriteMode.fromRaw(raw) ?: fallback
}

internal fun sanitizeFactKey(raw: String?): String? {
    val candidate = raw?.trim().orEmpty()
    if (candidate.isBlank()) {
        return null
    }
    val collapsed = candidate.replace(FACT_KEY_WHITESPACE, "_")
    val sanitized = collapsed.replace(NON_FACT_KEY_CHARS, "-")
    val trimmed = sanitized.trim(' ', '-', '_')
    if (trimmed.isBlank()) {
        return null
    }
    return trimmed.lowercase().take(MAX_FACT_KEY_CHARS)
}
