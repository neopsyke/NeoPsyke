package ai.neopsyke.llm

import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

private val adaptiveStructuredOutputLogger = KotlinLogging.logger {}

internal enum class StructuredOutputMode {
    STRICT_JSON_SCHEMA,
    RELAXED_JSON_SCHEMA,
    PROMPT_ONLY_JSON,
    ;

    fun telemetryValue(): String =
        when (this) {
            STRICT_JSON_SCHEMA -> "strict"
            RELAXED_JSON_SCHEMA -> "relaxed"
            PROMPT_ONLY_JSON -> "prompt-only"
        }
}

internal enum class StructuredOutputFailureKind {
    COMPATIBILITY,
    OTHER,
}

class StructuredOutputCompatibilityFailureException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class AdaptiveStructuredOutputChatClient(
    private val delegate: ChatModelClient,
    private val provider: String,
) : ChatModelClient {
    override val modelName: String
        get() = delegate.modelName

    private val stickyModes = ConcurrentHashMap<StickyModeKey, StructuredOutputMode>()

    override fun chat(messages: List<ChatMessage>, options: ChatRequestOptions): ChatCompletion {
        val requestedFormat = options.responseFormat as? ChatResponseFormat.JsonSchema
            ?: return delegate.chat(messages, options)
        val policy = StructuredOutputExecutionPolicyResolver.resolve(
            provider = provider,
            modelName = modelName,
            metadata = options.metadata,
            requestedFormat = requestedFormat
        )
        val stickyKey = policy.stickyKey()
        var mode = stickyKey?.let { stickyModes[it] } ?: policy.initialMode
        while (true) {
            val attempt = policy.buildAttempt(
                mode = mode,
                messages = messages,
                options = options,
                requestedFormat = requestedFormat
            )
            try {
                val completion = delegate.chat(attempt.messages, attempt.options)
                if (stickyKey != null && mode != policy.initialMode) {
                    stickyModes[stickyKey] = mode
                }
                return completion
            } catch (ex: Exception) {
                if (StructuredOutputFailureClassifier.classify(ex) != StructuredOutputFailureKind.COMPATIBILITY) {
                    throw ex
                }

                val failure = StructuredOutputCompatibilityFailureException(
                    message = buildCompatibilityFailureMessage(
                        provider = provider,
                        modelName = modelName,
                        metadata = options.metadata,
                        mode = mode,
                        error = ex
                    ),
                    cause = ex
                )
                val nextMode = policy.nextMode(mode)
                if (nextMode == null) {
                    throw failure
                }

                adaptiveStructuredOutputLogger.warn {
                    buildString {
                        append("Structured-output compatibility downgrade ")
                        append("provider=").append(provider)
                        append(" model=").append(modelName)
                        append(" actor=").append(options.metadata.actor.ifBlank { "unknown" })
                        append(" call_site=").append(options.metadata.callSite.ifBlank { "unknown" })
                        append(" schema=").append(requestedFormat.name)
                        append(" requested_mode=").append(policy.initialMode.name.lowercase())
                        append(" current_mode=").append(mode.name.lowercase())
                        append(" next_mode=").append(nextMode.name.lowercase())
                        append(" failed_generation_preview=").append(StructuredOutputFailureClassifier.failedGenerationPreview(ex))
                    }
                }
                mode = nextMode
            }
        }
    }

    override fun close() {
        delegate.close()
    }

    private fun buildCompatibilityFailureMessage(
        provider: String,
        modelName: String,
        metadata: ChatCallMetadata,
        mode: StructuredOutputMode,
        error: Throwable,
    ): String {
        val actor = metadata.actor.ifBlank { "unknown" }
        val callSite = metadata.callSite.ifBlank { "unknown" }
        val failedGenerationPreview = StructuredOutputFailureClassifier.failedGenerationPreview(error)
        return buildString {
            append("Structured-output compatibility failure ")
            append("provider=").append(provider)
            append(" model=").append(modelName)
            append(" actor=").append(actor)
            append(" call_site=").append(callSite)
            append(" mode=").append(mode.name.lowercase())
            if (failedGenerationPreview.isNotBlank()) {
                append(" failed_generation_preview=").append(failedGenerationPreview)
            }
        }
    }
}

internal object StructuredOutputFailureClassifier {
    private val compatibilityMarkers: Set<String> = setOf(
        "tool_use_failed",
        "model called a tool",
        "tool choice is none",
        "json_validate_failed",
        "failed to validate json",
        "failed to generate json",
        "generated json does not match the expected schema",
        "does not match the expected schema",
        "schema validation",
        "jsonschema",
    )
    private val failedGenerationRegex = Regex(
        pattern = """"failed_generation"\s*:\s*"((?:\\.|[^"])*)"""",
        options = setOf(RegexOption.DOT_MATCHES_ALL)
    )

    fun classify(error: Throwable?): StructuredOutputFailureKind {
        var current = error
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (current is StructuredOutputCompatibilityFailureException) {
                return StructuredOutputFailureKind.COMPATIBILITY
            }
            val normalized = current.message.orEmpty().trim().lowercase()
            if (compatibilityMarkers.any { normalized.contains(it) }) {
                return StructuredOutputFailureKind.COMPATIBILITY
            }
            current = current.cause
            depth += 1
        }
        return StructuredOutputFailureKind.OTHER
    }

    fun failedGenerationPreview(error: Throwable?): String {
        var current = error
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            val raw = current.message.orEmpty()
            val match = failedGenerationRegex.find(raw)
            if (match != null) {
                return match.groupValues[1]
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .take(MAX_FAILED_GENERATION_PREVIEW_CHARS)
            }
            current = current.cause
            depth += 1
        }
        return ""
    }

    private const val MAX_CAUSE_DEPTH: Int = 4
    private const val MAX_FAILED_GENERATION_PREVIEW_CHARS: Int = 160
}

private data class StructuredOutputExecutionPlan(
    val initialMode: StructuredOutputMode,
    val stickyEligible: Boolean,
    val promptOnlyInstruction: String?,
    val metadata: ChatCallMetadata,
    val requestedFormat: ChatResponseFormat.JsonSchema,
) {
    fun stickyKey(): StickyModeKey? {
        if (!stickyEligible) return null
        return StickyModeKey(
            actor = metadata.actor.ifBlank { "unknown" },
            callSite = metadata.callSite.ifBlank { "unknown" },
            schemaName = requestedFormat.name
        )
    }

    fun nextMode(current: StructuredOutputMode): StructuredOutputMode? =
        when (current) {
            StructuredOutputMode.STRICT_JSON_SCHEMA ->
                if (!requestedFormat.relaxedSchemaJson.isNullOrBlank()) {
                    StructuredOutputMode.RELAXED_JSON_SCHEMA
                } else {
                    StructuredOutputMode.PROMPT_ONLY_JSON
                }

            StructuredOutputMode.RELAXED_JSON_SCHEMA -> StructuredOutputMode.PROMPT_ONLY_JSON
            StructuredOutputMode.PROMPT_ONLY_JSON -> null
        }

    fun buildAttempt(
        mode: StructuredOutputMode,
        messages: List<ChatMessage>,
        options: ChatRequestOptions,
        requestedFormat: ChatResponseFormat.JsonSchema,
    ): StructuredOutputAttempt {
        val responseFormat = when (mode) {
            StructuredOutputMode.STRICT_JSON_SCHEMA -> requestedFormat
            StructuredOutputMode.RELAXED_JSON_SCHEMA ->
                requestedFormat.copy(
                    schemaJson = requestedFormat.relaxedSchemaJson ?: requestedFormat.schemaJson,
                    strict = false
                )

            StructuredOutputMode.PROMPT_ONLY_JSON -> null
        }
        val attemptMessages = if (mode == StructuredOutputMode.PROMPT_ONLY_JSON) {
            messages + ChatMessage(
                role = ChatRole.SYSTEM,
                content = promptOnlyInstruction ?: DEFAULT_PROMPT_ONLY_INSTRUCTION
            )
        } else {
            messages
        }
        return StructuredOutputAttempt(
            messages = attemptMessages,
            options = options.copy(
                responseFormat = responseFormat,
                metadata = options.metadata.copy(structuredOutputMode = mode.telemetryValue())
            )
        )
    }

    companion object {
        private const val DEFAULT_PROMPT_ONLY_INSTRUCTION: String =
            "Return one raw JSON object only. Never emit tool calls, function-call wrappers, named envelopes, markdown, or code fences."
    }
}

private data class StructuredOutputAttempt(
    val messages: List<ChatMessage>,
    val options: ChatRequestOptions,
)

private data class StickyModeKey(
    val actor: String,
    val callSite: String,
    val schemaName: String,
)

private object StructuredOutputExecutionPolicyResolver {
    private val groqGptOssModelRegex = Regex("""(?i)^openai/gpt-oss-\d+b$""")

    fun resolve(
        provider: String,
        modelName: String,
        metadata: ChatCallMetadata,
        requestedFormat: ChatResponseFormat.JsonSchema,
    ): StructuredOutputExecutionPlan {
        val stickyEligible = provider.equals("groq", ignoreCase = true) &&
            groqGptOssModelRegex.matches(modelName.trim()) &&
            metadata.callSite.equals("thought", ignoreCase = true)
        return StructuredOutputExecutionPlan(
            initialMode = StructuredOutputMode.STRICT_JSON_SCHEMA,
            stickyEligible = stickyEligible,
            promptOnlyInstruction = buildPromptOnlyInstruction(schemaName = requestedFormat.name),
            metadata = metadata,
            requestedFormat = requestedFormat
        )
    }

    private fun buildPromptOnlyInstruction(schemaName: String): String =
        "Return one raw JSON object only for schema=$schemaName. " +
            "Do not emit tool calls, function wrappers, named envelopes, markdown, or code fences."
}
