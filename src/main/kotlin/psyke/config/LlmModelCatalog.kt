package psyke.config

enum class LlmModelTier(val wireValue: String) {
    MEDIUM_HIGH("medium_high"),
    MEDIUM("medium"),
    LIGHT("light");

    companion object {
        fun parse(raw: String?): LlmModelTier? {
            val normalized = raw?.trim()?.lowercase().orEmpty()
            if (normalized.isBlank()) return null
            return entries.firstOrNull { it.wireValue == normalized }
        }
    }
}

data class LlmModelProfile(
    val model: String,
    val tier: LlmModelTier,
    val tokenWeight: Double = DEFAULT_TOKEN_WEIGHT,
    val inputCostPerMillionTokensUsd: Double? = null,
    val outputCostPerMillionTokensUsd: Double? = null,
    val contextWindow: Int? = null,
) {
    fun normalizedModel(): String = model.trim().lowercase()

    companion object {
        const val DEFAULT_TOKEN_WEIGHT: Double = 1.0
    }
}

data class LlmModelCatalog(
    val byProvider: Map<LlmProvider, List<LlmModelProfile>> = emptyMap(),
) {
    fun profiles(provider: LlmProvider): List<LlmModelProfile> =
        byProvider[provider].orEmpty()

    fun profileFor(endpoint: LlmEndpointConfig): LlmModelProfile? {
        val normalized = endpoint.model.trim().lowercase()
        if (normalized.isBlank()) return null
        return profiles(endpoint.provider).firstOrNull { it.normalizedModel() == normalized }
    }

    fun tokenWeightFor(endpoint: LlmEndpointConfig): Double =
        profileFor(endpoint)?.tokenWeight ?: LlmModelProfile.DEFAULT_TOKEN_WEIGHT

    fun contextWindowFor(endpoint: LlmEndpointConfig): Int? =
        profileFor(endpoint)?.contextWindow

    fun cheapestProfileForProvider(
        provider: LlmProvider,
        excludingModel: String? = null,
    ): LlmModelProfile? {
        val excluded = excludingModel?.trim()?.lowercase().orEmpty()
        return profiles(provider)
            .asSequence()
            .filter { profile ->
                excluded.isBlank() || profile.normalizedModel() != excluded
            }
            .minWithOrNull(
                compareBy<LlmModelProfile>(
                    { effectiveCost(profile = it) },
                    { it.tokenWeight },
                    { tierRank(it.tier) }
                )
            )
    }

    fun merge(overrides: Map<LlmProvider, List<LlmModelProfile>>): LlmModelCatalog {
        if (overrides.isEmpty()) return this
        val merged = byProvider.toMutableMap()
        overrides.forEach { (provider, models) ->
            if (models.isNotEmpty()) {
                merged[provider] = models
            }
        }
        return copy(byProvider = merged.toMap())
    }

    companion object {
        private fun effectiveCost(profile: LlmModelProfile): Double {
            val input = profile.inputCostPerMillionTokensUsd
            val output = profile.outputCostPerMillionTokensUsd
            return when {
                input != null && output != null -> input + output
                input != null -> input
                output != null -> output
                else -> profile.tokenWeight
            }
        }

        private fun tierRank(tier: LlmModelTier): Int =
            when (tier) {
                LlmModelTier.LIGHT -> 0
                LlmModelTier.MEDIUM -> 1
                LlmModelTier.MEDIUM_HIGH -> 2
            }

        fun defaults(): LlmModelCatalog =
            LlmModelCatalog(
                byProvider = mapOf(
                    LlmProvider.OPENAI to listOf(
                        LlmModelProfile("gpt-5", LlmModelTier.MEDIUM_HIGH, tokenWeight = 2.60, inputCostPerMillionTokensUsd = 1.25, outputCostPerMillionTokensUsd = 10.0, contextWindow = 128_000),
                        LlmModelProfile("gpt-5-mini", LlmModelTier.MEDIUM, tokenWeight = 1.35, inputCostPerMillionTokensUsd = 0.25, outputCostPerMillionTokensUsd = 2.0, contextWindow = 128_000),
                        LlmModelProfile("gpt-5-nano", LlmModelTier.LIGHT, tokenWeight = 0.85, inputCostPerMillionTokensUsd = 0.05, outputCostPerMillionTokensUsd = 0.4, contextWindow = 8_192),
                        LlmModelProfile("gpt-4.1-mini", LlmModelTier.MEDIUM, tokenWeight = 1.20, inputCostPerMillionTokensUsd = 0.40, outputCostPerMillionTokensUsd = 1.60, contextWindow = 1_048_576),
                        LlmModelProfile("gpt-4o-mini", LlmModelTier.LIGHT, tokenWeight = 1.00, inputCostPerMillionTokensUsd = 0.15, outputCostPerMillionTokensUsd = 0.60, contextWindow = 128_000),
                    ),
                    LlmProvider.GOOGLE to listOf(
                        LlmModelProfile("gemini-2.5-pro", LlmModelTier.MEDIUM_HIGH, tokenWeight = 2.30, inputCostPerMillionTokensUsd = 1.25, outputCostPerMillionTokensUsd = 10.0, contextWindow = 1_048_576),
                        LlmModelProfile("gemini-2.5-flash", LlmModelTier.MEDIUM, tokenWeight = 1.20, inputCostPerMillionTokensUsd = 0.30, outputCostPerMillionTokensUsd = 2.50, contextWindow = 1_048_576),
                        LlmModelProfile("gemini-2.5-flash-lite", LlmModelTier.LIGHT, tokenWeight = 0.90, inputCostPerMillionTokensUsd = 0.10, outputCostPerMillionTokensUsd = 0.40, contextWindow = 1_048_576),
                        LlmModelProfile("gemini-2.0-flash", LlmModelTier.MEDIUM, tokenWeight = 1.05, inputCostPerMillionTokensUsd = 0.10, outputCostPerMillionTokensUsd = 0.40, contextWindow = 1_048_576),
                        LlmModelProfile("gemini-2.0-flash-lite", LlmModelTier.LIGHT, tokenWeight = 0.80, inputCostPerMillionTokensUsd = 0.075, outputCostPerMillionTokensUsd = 0.30, contextWindow = 1_048_576),
                    ),
                    LlmProvider.GROQ to listOf(
                        LlmModelProfile("openai/gpt-oss-120b", LlmModelTier.MEDIUM_HIGH, tokenWeight = 1.15, inputCostPerMillionTokensUsd = 0.15, outputCostPerMillionTokensUsd = 0.75, contextWindow = 131_072),
                        LlmModelProfile("openai/gpt-oss-20b", LlmModelTier.MEDIUM, tokenWeight = 0.75, inputCostPerMillionTokensUsd = 0.10, outputCostPerMillionTokensUsd = 0.50, contextWindow = 131_072),
                        LlmModelProfile("llama-3.3-70b-versatile", LlmModelTier.MEDIUM, tokenWeight = 1.45, inputCostPerMillionTokensUsd = 0.59, outputCostPerMillionTokensUsd = 0.79, contextWindow = 32_768),
                        LlmModelProfile("qwen/qwen3-32b", LlmModelTier.MEDIUM, tokenWeight = 0.95, inputCostPerMillionTokensUsd = 0.29, outputCostPerMillionTokensUsd = 0.59, contextWindow = 32_768),
                        LlmModelProfile("llama-3.1-8b-instant", LlmModelTier.LIGHT, tokenWeight = 0.60, inputCostPerMillionTokensUsd = 0.05, outputCostPerMillionTokensUsd = 0.08, contextWindow = 131_072),
                    ),
                    LlmProvider.MISTRAL to listOf(
                        LlmModelProfile("mistral-large-2512", LlmModelTier.MEDIUM_HIGH, tokenWeight = 1.45, inputCostPerMillionTokensUsd = 0.5, outputCostPerMillionTokensUsd = 1.5, contextWindow = 131_072),
                        LlmModelProfile("mistral-medium-2508", LlmModelTier.MEDIUM, tokenWeight = 1.30, inputCostPerMillionTokensUsd = 0.4, outputCostPerMillionTokensUsd = 2.0, contextWindow = 131_072),
                        LlmModelProfile("mistral-small-2506", LlmModelTier.LIGHT, tokenWeight = 1.00, inputCostPerMillionTokensUsd = 0.1, outputCostPerMillionTokensUsd = 0.3, contextWindow = 131_072),
                        LlmModelProfile("ministral-14b-2512", LlmModelTier.LIGHT, tokenWeight = 0.95, inputCostPerMillionTokensUsd = 0.2, outputCostPerMillionTokensUsd = 0.2, contextWindow = 131_072),
                        LlmModelProfile("ministral-8b-2512", LlmModelTier.LIGHT, tokenWeight = 0.85, inputCostPerMillionTokensUsd = 0.15, outputCostPerMillionTokensUsd = 0.15, contextWindow = 131_072),
                    )
                )
            )
    }
}
