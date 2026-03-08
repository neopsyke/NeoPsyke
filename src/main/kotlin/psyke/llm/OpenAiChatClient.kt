package psyke.llm

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Duration
import kotlin.math.max

private val logger = KotlinLogging.logger {}
private val mapper = jacksonObjectMapper()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    .setSerializationInclusion(JsonInclude.Include.NON_NULL)
private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

class OpenAiChatClient(
    private val apiKey: String = System.getenv("OPENAI_API_KEY") ?: "",
    private val baseUrl: String = DEFAULT_BASE_URL,
    override val modelName: String = DEFAULT_MODEL,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val callObserver: ChatCallObserver? = null,
) : ChatModelClient {
    private var effectiveCallObserver: ChatCallObserver? = callObserver

    init {
        require(apiKey.isNotBlank()) { "OpenAI API key must be provided (set OPENAI_API_KEY)." }
        val binding = bindFailSafeMetricsObserver(
            provider = "openai",
            apiKey = apiKey,
            modelName = modelName,
            primaryObserver = callObserver
        )
        effectiveCallObserver = binding.observer
    }

    override fun chat(messages: List<ChatMessage>, options: ChatRequestOptions): ChatCompletion {
        require(messages.isNotEmpty()) { "At least one chat message is required." }
        val startedAt = System.nanoTime()

        try {
            val completion = executeChatWithAdaptiveParams(messages = messages, options = options)
            observeCall(
                record = ChatCallRecord(
                    model = completion.model,
                    metadata = options.metadata,
                    latencyMs = elapsedMillis(startedAt),
                    promptTokens = completion.usage?.promptTokens,
                    completionTokens = completion.usage?.completionTokens,
                    totalTokens = completion.usage?.totalTokens,
                    status = ChatCallStatus.OK
                )
            )
            return completion
        } catch (ex: Exception) {
            observeCall(
                record = ChatCallRecord(
                    model = modelName,
                    metadata = options.metadata,
                    latencyMs = elapsedMillis(startedAt),
                    status = ChatCallStatus.ERROR,
                    errorCode = ex.toErrorCode(),
                    errorMessage = ex.toErrorMessage()
                )
            )
            throw ex
        }
    }

    private fun executeChatWithAdaptiveParams(
        messages: List<ChatMessage>,
        options: ChatRequestOptions,
    ): ChatCompletion {
        val tuning = OpenAiRequestTuning(
            includeTemperature = options.temperature != null,
            maxTokensField = options.maxTokens?.let {
                if (prefersMaxCompletionTokens(modelName)) {
                    OpenAiMaxTokensField.MAX_COMPLETION_TOKENS
                } else {
                    OpenAiMaxTokensField.MAX_TOKENS
                }
            }
        )
        val attemptedSignatures = mutableSetOf<String>()
        var lastError: Exception? = null

        repeat(MAX_ADAPTIVE_REQUEST_ATTEMPTS) {
            val signature = tuning.signature()
            if (!attemptedSignatures.add(signature)) return@repeat
            try {
                return executeSingleRequest(messages = messages, options = options, tuning = tuning)
            } catch (ex: Exception) {
                lastError = ex
                if (!adaptRequestTuning(ex, tuning)) {
                    throw ex
                }
            }
        }

        throw lastError ?: IOException("OpenAI chat failed after adaptive request retries.")
    }

    private fun executeSingleRequest(
        messages: List<ChatMessage>,
        options: ChatRequestOptions,
        tuning: OpenAiRequestTuning,
    ): ChatCompletion {
        val responseFormatAdaptation = StructuredOutputCompatibility.adapt(
            provider = LlmProvider.OPENAI,
            modelName = modelName,
            responseFormat = options.responseFormat,
            mapper = mapper
        )
        StructuredOutputCompatibility.warningMessageIfNeeded(
            provider = LlmProvider.OPENAI,
            modelName = modelName,
            requestedFormat = options.responseFormat,
            adaptation = responseFormatAdaptation,
            metadata = options.metadata
        )?.let { warning ->
            logger.warn { warning }
        }

        val payload = OpenAiChatCompletionRequest(
            model = modelName,
            messages = messages.map { OpenAiChatMessage(role = it.role.apiValue, content = it.content) },
            temperature = if (tuning.includeTemperature) options.temperature else null,
            maxTokens = if (tuning.maxTokensField == OpenAiMaxTokensField.MAX_TOKENS) options.maxTokens else null,
            maxCompletionTokens = if (tuning.maxTokensField == OpenAiMaxTokensField.MAX_COMPLETION_TOKENS) options.maxTokens else null,
            responseFormat = responseFormatAdaptation.responseFormat.toOpenAiResponseFormat()
        )
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(mapper.writeValueAsString(payload).toRequestBody(jsonMediaType))
            .build()

        logger.debug {
            "Sending OpenAI chat request to $modelName with ${messages.size} message(s), " +
                "temperature_enabled=${tuning.includeTemperature}, max_tokens_field=${tuning.maxTokensField?.wireName ?: "none"}, " +
                "response_format=${if (options.responseFormat == null) "none" else "json_schema"}."
        }

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string()
            if (!response.isSuccessful) {
                throw OpenAiHttpException(
                    endpoint = "chat/completions",
                    statusCode = response.code,
                    responseBody = responseBody
                )
            }
            if (responseBody.isNullOrBlank()) {
                throw IOException("OpenAI chat returned an empty response body.")
            }

            val parsed = mapper.readValue<OpenAiChatCompletionResponse>(responseBody)
            val choices = parsed.choices
            val firstChoice = choices.firstOrNull()
                ?: throw IOException("OpenAI chat returned no choices.")
            val selectedChoice = choices.firstOrNull { choice ->
                extractAssistantContentInfo(choice.message.content).trimmedChars > 0
            }
            if (selectedChoice == null) {
                val contentInfo = extractAssistantContentInfo(firstChoice.message.content)
                val refusalInfo = extractAssistantContentInfo(firstChoice.message.refusal)
                val toolCallCount = if (firstChoice.message.toolCalls?.isArray == true) {
                    firstChoice.message.toolCalls.size()
                } else {
                    0
                }
                throw IOException(
                    "OpenAI chat returned empty message content " +
                        "(finish_reason=${firstChoice.finishReason ?: "none"}, " +
                        "${contentInfo.summary()}, refusal_chars=${refusalInfo.trimmedChars}, " +
                        "tool_calls=$toolCallCount, choices=${choices.size})."
                )
            }
            val content = extractAssistantContentInfo(selectedChoice.message.content).trimmedText

            val usage = parsed.usage?.toChatUsage()
            val resolvedModel = parsed.model ?: modelName
            return ChatCompletion(
                content = content,
                model = resolvedModel,
                finishReason = selectedChoice.finishReason,
                id = parsed.id,
                usage = usage
            )
        }
    }

    private fun adaptRequestTuning(ex: Exception, tuning: OpenAiRequestTuning): Boolean {
        val httpEx = ex as? OpenAiHttpException ?: return false
        if (httpEx.statusCode != 400) return false

        val unsupportedParam = extractUnsupportedParameter(httpEx.responseBody) ?: return false
        val adjusted = when (unsupportedParam) {
            "max_tokens" ->
                if (tuning.maxTokensField == OpenAiMaxTokensField.MAX_TOKENS) {
                    tuning.maxTokensField = OpenAiMaxTokensField.MAX_COMPLETION_TOKENS
                    true
                } else {
                    false
                }

            "max_completion_tokens" ->
                if (tuning.maxTokensField == OpenAiMaxTokensField.MAX_COMPLETION_TOKENS) {
                    tuning.maxTokensField = OpenAiMaxTokensField.MAX_TOKENS
                    true
                } else {
                    false
                }

            "temperature" ->
                if (tuning.includeTemperature) {
                    tuning.includeTemperature = false
                    true
                } else {
                    false
                }

            else -> false
        }
        if (adjusted) {
            logger.warn {
                "OpenAI rejected parameter '$unsupportedParam' for model=$modelName; " +
                    "retrying with temperature_enabled=${tuning.includeTemperature}, " +
                    "max_tokens_field=${tuning.maxTokensField?.wireName ?: "none"}."
            }
        }
        return adjusted
    }

    private fun extractUnsupportedParameter(responseBody: String?): String? {
        if (responseBody.isNullOrBlank()) return null
        return try {
            val root = mapper.readTree(responseBody)
            val error = root.path("error")
            val type = readTextField(error, "type").trim().lowercase()
            val code = readTextField(error, "code").trim().lowercase()
            val message = readTextField(error, "message").trim().lowercase()
            val param = readTextField(error, "param").trim().lowercase()
            val unsupported = code == "unsupported_parameter" ||
                code == "unsupported_value" ||
                message.contains("unsupported parameter") ||
                message.contains("unsupported value") ||
                message.contains("does not support")
            if (type == "invalid_request_error" && unsupported) {
                when {
                    param.isNotBlank() -> param
                    message.contains("max_completion_tokens") -> "max_completion_tokens"
                    message.contains("max_tokens") -> "max_tokens"
                    message.contains("temperature") -> "temperature"
                    else -> null
                }
            } else {
                null
            }
        } catch (_: Exception) {
            val compact = responseBody.lowercase()
            when {
                compact.contains("max_completion_tokens") -> "max_completion_tokens"
                compact.contains("max_tokens") -> "max_tokens"
                compact.contains("temperature") -> "temperature"
                else -> null
            }
        }
    }

    private fun readTextField(node: JsonNode, field: String): String {
        val value = node.get(field) ?: return ""
        return if (value.isTextual) value.asText() else value.toString()
    }

    private fun ChatResponseFormat?.toOpenAiResponseFormat(): OpenAiResponseFormat? =
        when (this) {
            null -> null
            is ChatResponseFormat.JsonSchema -> OpenAiResponseFormat(
                type = "json_schema",
                jsonSchema = OpenAiJsonSchemaFormat(
                    name = name,
                    strict = strict,
                    schema = mapper.readTree(schemaJson)
                )
            )
        }

    private fun prefersMaxCompletionTokens(model: String): Boolean {
        val normalized = model.trim().lowercase()
        return normalized.startsWith("gpt-5") ||
            normalized.startsWith("o1") ||
            normalized.startsWith("o3") ||
            normalized.startsWith("o4")
    }

    override fun close() {
        httpClient.connectionPool.evictAll()
        httpClient.dispatcher.executorService.shutdown()
    }

    private fun observeCall(record: ChatCallRecord) {
        try {
            effectiveCallObserver?.onChatCall(record)
        } catch (ignored: Exception) {
            logger.warn(ignored) { "Failed to persist chat-call metrics; continuing." }
        }
    }

    companion object {
        const val DEFAULT_MODEL = "gpt-4o-mini"
        private const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        private const val MAX_ADAPTIVE_REQUEST_ATTEMPTS = 4

        private fun defaultHttpClient(): OkHttpClient =
            OkHttpClient.Builder()
                .callTimeout(Duration.ofSeconds(30))
                .build()

        private fun elapsedMillis(startedAtNanos: Long): Long =
            max(1L, (System.nanoTime() - startedAtNanos) / 1_000_000L)
    }
}

private enum class OpenAiMaxTokensField(val wireName: String) {
    MAX_TOKENS("max_tokens"),
    MAX_COMPLETION_TOKENS("max_completion_tokens"),
}

private data class OpenAiRequestTuning(
    var includeTemperature: Boolean,
    var maxTokensField: OpenAiMaxTokensField?,
) {
    fun signature(): String = "${if (includeTemperature) "temp" else "no_temp"}:${maxTokensField?.wireName ?: "none"}"
}

private class OpenAiHttpException(
    private val endpoint: String,
    val statusCode: Int,
    val responseBody: String?,
) : IOException("OpenAI $endpoint failed with status $statusCode.${responseBody?.let { " Response: $it" } ?: ""}")

private fun Exception.toErrorCode(): String =
    when (this) {
        is OpenAiHttpException -> "HTTP_$statusCode"
        else -> this::class.simpleName ?: "error"
    }

private fun Exception.toErrorMessage(): String {
    val raw = when (this) {
        is OpenAiHttpException -> responseBody ?: message.orEmpty()
        else -> message.orEmpty()
    }
    return raw.replace(Regex("\\s+"), " ").trim().take(180)
}

private data class OpenAiChatCompletionRequest(
    val model: String,
    val messages: List<OpenAiChatMessage>,
    val temperature: Double? = null,
    @param:JsonProperty("max_tokens")
    val maxTokens: Int? = null,
    @param:JsonProperty("max_completion_tokens")
    val maxCompletionTokens: Int? = null,
    @param:JsonProperty("response_format")
    val responseFormat: OpenAiResponseFormat? = null,
)

private data class OpenAiResponseFormat(
    val type: String,
    @param:JsonProperty("json_schema")
    val jsonSchema: OpenAiJsonSchemaFormat,
)

private data class OpenAiJsonSchemaFormat(
    val name: String,
    val strict: Boolean = true,
    val schema: JsonNode,
)

private data class OpenAiChatMessage(
    val role: String,
    val content: String,
)

private data class OpenAiChatCompletionResponse(
    val id: String?,
    @param:JsonProperty("object")
    val objectType: String? = null,
    val model: String?,
    val choices: List<OpenAiChoice> = emptyList(),
    val usage: OpenAiUsage? = null,
)

private data class OpenAiChoice(
    val index: Int? = null,
    val message: OpenAiResponseMessage,
    @param:JsonProperty("finish_reason")
    val finishReason: String? = null,
)

private data class OpenAiResponseMessage(
    val role: String? = null,
    val content: JsonNode? = null,
    val refusal: JsonNode? = null,
    @param:JsonProperty("tool_calls")
    val toolCalls: JsonNode? = null,
)

private data class OpenAiUsage(
    @param:JsonProperty("prompt_tokens")
    val promptTokens: Int? = null,
    @param:JsonProperty("completion_tokens")
    val completionTokens: Int? = null,
    @param:JsonProperty("total_tokens")
    val totalTokens: Int? = null,
) {
    fun toChatUsage(): ChatUsage =
        ChatUsage(
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = totalTokens
        )
}
