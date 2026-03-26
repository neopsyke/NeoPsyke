package ai.neopsyke.config

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
    val reasoningOverheadMultiplier: Double = DEFAULT_REASONING_OVERHEAD,
    val metadataUpdatedAt: String? = null,
) {
    fun normalizedModel(): String = model.trim().lowercase()

    companion object {
        const val DEFAULT_TOKEN_WEIGHT: Double = 1.0
        const val DEFAULT_REASONING_OVERHEAD: Double = 1.0
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

    fun reasoningOverheadFor(endpoint: LlmEndpointConfig): Double =
        profileFor(endpoint)?.reasoningOverheadMultiplier ?: LlmModelProfile.DEFAULT_REASONING_OVERHEAD

    fun metadataUpdatedAtFor(endpoint: LlmEndpointConfig): String? =
        profileFor(endpoint)?.metadataUpdatedAt

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
    }
}
