package ai.neopsyke.llm

internal data class ProviderErrorDetails(
    val summary: String,
    val providerErrorType: String? = null,
    val providerErrorCode: String? = null,
    val failedGenerationPreview: String? = null,
    val errorBodyPreview: String? = null,
)

internal object ProviderErrorDetailsExtractor {
    private val messageRegex = Regex(
        pattern = """"message"\s*:\s*"((?:\\.|[^"])*)"""",
        options = setOf(RegexOption.DOT_MATCHES_ALL)
    )
    private val typeRegex = Regex(
        pattern = """"type"\s*:\s*"((?:\\.|[^"])*)"""",
        options = setOf(RegexOption.DOT_MATCHES_ALL)
    )
    private val codeRegex = Regex(
        pattern = """"code"\s*:\s*"((?:\\.|[^"])*)"""",
        options = setOf(RegexOption.DOT_MATCHES_ALL)
    )

    fun fromRaw(raw: String): ProviderErrorDetails {
        val normalized = normalize(raw)
        val summary = extract(messageRegex, raw)
            ?.takeIf { it.isNotBlank() }
            ?: normalized.take(MAX_SUMMARY_CHARS)
        return ProviderErrorDetails(
            summary = summary,
            providerErrorType = extract(typeRegex, raw),
            providerErrorCode = extract(codeRegex, raw),
            failedGenerationPreview = StructuredOutputFailureClassifier.failedGenerationPreview(
                IllegalStateException(raw)
            ).ifBlank { null },
            errorBodyPreview = normalized.take(MAX_BODY_PREVIEW_CHARS)
        )
    }

    private fun extract(regex: Regex, raw: String): String? =
        regex.find(raw)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::decodeJsonString)
            ?.trim()
            ?.ifBlank { null }

    private fun normalize(raw: String): String =
        raw.replace(Regex("\\s+"), " ").trim()

    private fun decodeJsonString(raw: String): String =
        raw
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\n", " ")
            .replace("\\r", " ")
            .replace("\\t", " ")

    private const val MAX_SUMMARY_CHARS: Int = 240
    private const val MAX_BODY_PREVIEW_CHARS: Int = 2_000
}
