package psyke.config

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import psyke.llm.GroqChatClient
import psyke.llm.MistralChatClient
import psyke.llm.OpenAiChatClient
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

enum class LlmProvider(val id: String) {
    GROQ("groq"),
    MISTRAL("mistral"),
    GOOGLE("google"),
    OPENAI("openai");

    companion object {
        fun parse(raw: String?): LlmProvider? =
            entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) }
    }
}

data class LlmEndpointConfig(
    val provider: LlmProvider,
    val apiKey: String,
    val apiKeyEnvVar: String,
    val baseUrl: String,
    val model: String,
) {
    val providerLabel: String
        get() = provider.id
}

data class LlmCognitiveRolesConfig(
    val planner: LlmEndpointConfig,
    val actionVerifier: LlmEndpointConfig,
    val superego: LlmEndpointConfig,
    val metaReasoner: LlmEndpointConfig,
    val memoryAdvisor: LlmEndpointConfig,
    val superegoPrimary: LlmEndpointConfig? = null,
    val superegoEscalation: LlmEndpointConfig? = null,
)

data class LlmRuntimeConfig(
    val cognitiveRoles: LlmCognitiveRolesConfig,
    val webSearch: LlmEndpointConfig,
    val modelCatalog: LlmModelCatalog = LlmModelCatalog.defaults(),
) {
    val planner: LlmEndpointConfig
        get() = cognitiveRoles.planner

    val actionVerifier: LlmEndpointConfig
        get() = cognitiveRoles.actionVerifier

    val superego: LlmEndpointConfig
        get() = cognitiveRoles.superego

    val metaReasoner: LlmEndpointConfig
        get() = cognitiveRoles.metaReasoner

    val memoryAdvisor: LlmEndpointConfig
        get() = cognitiveRoles.memoryAdvisor

    // Legacy aliases retained for existing call sites.
    val provider: LlmProvider
        get() = planner.provider

    val apiKey: String
        get() = planner.apiKey

    val apiKeyEnvVar: String
        get() = planner.apiKeyEnvVar

    val baseUrl: String
        get() = planner.baseUrl

    val egoModel: String
        get() = planner.model

    val superegoModel: String
        get() = superego.model

    val metaReasonerModel: String
        get() = metaReasoner.model

    val memoryConsolidationModel: String
        get() = memoryAdvisor.model

    val superegoPrimary: LlmEndpointConfig?
        get() = cognitiveRoles.superegoPrimary

    val superegoEscalation: LlmEndpointConfig?
        get() = cognitiveRoles.superegoEscalation

    val webSearchProvider: LlmProvider
        get() = webSearch.provider

    val webSearchApiKey: String
        get() = webSearch.apiKey

    val webSearchApiKeyEnvVar: String
        get() = webSearch.apiKeyEnvVar

    val webSearchBaseUrl: String
        get() = webSearch.baseUrl

    val webSearchModel: String
        get() = webSearch.model

    val providerLabel: String
        get() = provider.id

    val webSearchProviderLabel: String
        get() = webSearchProvider.id
}

private data class LlmRuntimeYamlModels(
    val ego: String? = null,
    val superego: String? = null,
    @param:JsonProperty("meta_reasoner")
    val metaReasoner: String? = null,
    @param:JsonProperty("memory_consolidation")
    val memoryConsolidation: String? = null,
    @param:JsonProperty("web_search")
    val webSearch: String? = null,
)

private data class LlmRuntimeYamlRole(
    val provider: String? = null,
    val model: String? = null,
)

private data class LlmRuntimeYamlCognitiveRoles(
    val planner: LlmRuntimeYamlRole? = null,
    @param:JsonProperty("action_verifier")
    val actionVerifier: LlmRuntimeYamlRole? = null,
    val superego: LlmRuntimeYamlRole? = null,
    @param:JsonProperty("superego_primary")
    val superegoPrimary: LlmRuntimeYamlRole? = null,
    @param:JsonProperty("superego_escalation")
    val superegoEscalation: LlmRuntimeYamlRole? = null,
    @param:JsonProperty("meta_reasoner")
    val metaReasoner: LlmRuntimeYamlRole? = null,
    @param:JsonProperty("memory_advisor")
    val memoryAdvisor: LlmRuntimeYamlRole? = null,
)

private data class LlmRuntimeYamlProvider(
    @param:JsonProperty("base_url")
    val baseUrl: String? = null,
    @param:JsonProperty("api_key_env")
    val apiKeyEnvVar: String? = null,
)

private data class LlmRuntimeYamlProviders(
    val groq: LlmRuntimeYamlProvider? = null,
    val mistral: LlmRuntimeYamlProvider? = null,
    val google: LlmRuntimeYamlProvider? = null,
    val openai: LlmRuntimeYamlProvider? = null,
)

private data class LlmRuntimeYamlConfig(
    val provider: String? = null,
    @param:JsonProperty("base_url")
    val baseUrl: String? = null,
    @param:JsonProperty("api_key_env")
    val apiKeyEnvVar: String? = null,
    @param:JsonProperty("web_search")
    val webSearch: LlmRuntimeYamlWebSearch? = null,
    @param:JsonProperty("cognitive_roles")
    val cognitiveRoles: LlmRuntimeYamlCognitiveRoles? = null,
    val providers: LlmRuntimeYamlProviders? = null,
    val models: LlmRuntimeYamlModels = LlmRuntimeYamlModels(),
    @param:JsonProperty("model_catalog")
    val modelCatalog: LlmRuntimeYamlModelCatalog? = null,
)

private data class LlmRuntimeYamlWebSearch(
    val provider: String? = null,
    @param:JsonProperty("base_url")
    val baseUrl: String? = null,
    @param:JsonProperty("api_key_env")
    val apiKeyEnvVar: String? = null,
    val model: String? = null,
)

private data class LlmRuntimeYamlModelProfile(
    val model: String? = null,
    val tier: String? = null,
    @param:JsonProperty("token_weight")
    val tokenWeight: Double? = null,
    @param:JsonProperty("input_cost_per_million_tokens_usd")
    val inputCostPerMillionTokensUsd: Double? = null,
    @param:JsonProperty("output_cost_per_million_tokens_usd")
    val outputCostPerMillionTokensUsd: Double? = null,
    @param:JsonProperty("context_window")
    val contextWindow: Int? = null,
)

private data class LlmRuntimeYamlModelCatalog(
    val groq: List<LlmRuntimeYamlModelProfile>? = null,
    val mistral: List<LlmRuntimeYamlModelProfile>? = null,
    val google: List<LlmRuntimeYamlModelProfile>? = null,
    val openai: List<LlmRuntimeYamlModelProfile>? = null,
)

private data class ProviderDefaults(
    val baseUrl: String,
    val apiKeyEnvVar: String,
    val defaultModel: String,
    val defaultWebSearchModel: String,
)

private data class ProviderRuntimeSettings(
    val baseUrl: String,
    val apiKeyEnvVar: String,
    val apiKey: String,
)

object LlmRuntimeConfigLoader {
    private val mapper = ObjectMapper(YAMLFactory())
        .registerKotlinModule()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun load(
        env: Map<String, String> = System.getenv(),
        defaultPath: Path = Paths.get("llm-runtime.yaml"),
    ): LlmRuntimeConfig? {
        val filePath = resolveConfigPath(env, defaultPath)
        val yaml = readYaml(filePath) ?: LlmRuntimeYamlConfig()

        val fallbackProvider = LlmProvider.parse(yaml.provider) ?: LlmProvider.GROQ

        val planner = resolveRoleEndpoint(
            env = env,
            yaml = yaml,
            fallbackProvider = fallbackProvider,
            role = yaml.cognitiveRoles?.planner,
            legacyModel = yaml.models.ego
        ) ?: return null

        val actionVerifier = resolveRoleEndpoint(
            env = env,
            yaml = yaml,
            fallbackProvider = fallbackProvider,
            role = yaml.cognitiveRoles?.actionVerifier,
            legacyModel = planner.model
        ) ?: return null

        val superego = resolveRoleEndpoint(
            env = env,
            yaml = yaml,
            fallbackProvider = fallbackProvider,
            role = yaml.cognitiveRoles?.superego ?: yaml.cognitiveRoles?.superegoPrimary,
            legacyModel = yaml.models.superego ?: planner.model
        ) ?: return null

        val metaReasoner = resolveRoleEndpoint(
            env = env,
            yaml = yaml,
            fallbackProvider = fallbackProvider,
            role = yaml.cognitiveRoles?.metaReasoner,
            legacyModel = yaml.models.metaReasoner ?: planner.model
        ) ?: return null

        val memoryAdvisor = resolveRoleEndpoint(
            env = env,
            yaml = yaml,
            fallbackProvider = fallbackProvider,
            role = yaml.cognitiveRoles?.memoryAdvisor,
            legacyModel = yaml.models.memoryConsolidation ?: planner.model
        ) ?: return null

        val superegoPrimary = yaml.cognitiveRoles?.superegoPrimary?.let { role ->
            resolveRoleEndpoint(
                env = env,
                yaml = yaml,
                fallbackProvider = fallbackProvider,
                role = role,
                legacyModel = superego.model
            )
        }

        val superegoEscalation = yaml.cognitiveRoles?.superegoEscalation?.let { role ->
            resolveRoleEndpoint(
                env = env,
                yaml = yaml,
                fallbackProvider = fallbackProvider,
                role = role,
                legacyModel = superego.model
            )
        }

        val webSearchProvider = LlmProvider.parse(yaml.webSearch?.provider) ?: fallbackProvider
        val webSearchProviderSettings = resolveProviderSettings(
            env = env,
            yaml = yaml,
            fallbackProvider = fallbackProvider,
            provider = webSearchProvider
        )
        val webSearchModel = firstNonBlank(
            yaml.webSearch?.model,
            yaml.models.webSearch,
            if (webSearchProvider == planner.provider) planner.model else null,
            webSearchProvider.defaults().defaultWebSearchModel
        ) ?: webSearchProvider.defaults().defaultWebSearchModel

        val webSearch = LlmEndpointConfig(
            provider = webSearchProvider,
            apiKey = env[resolveWebSearchApiKeyEnvVar(yaml, webSearchProviderSettings.apiKeyEnvVar)]?.trim().orEmpty(),
            apiKeyEnvVar = resolveWebSearchApiKeyEnvVar(yaml, webSearchProviderSettings.apiKeyEnvVar),
            baseUrl = firstNonBlank(yaml.webSearch?.baseUrl, webSearchProviderSettings.baseUrl) ?: webSearchProviderSettings.baseUrl,
            model = webSearchModel
        )

        return LlmRuntimeConfig(
            cognitiveRoles = LlmCognitiveRolesConfig(
                planner = planner,
                actionVerifier = actionVerifier,
                superego = superego,
                metaReasoner = metaReasoner,
                memoryAdvisor = memoryAdvisor,
                superegoPrimary = superegoPrimary,
                superegoEscalation = superegoEscalation
            ),
            webSearch = webSearch,
            modelCatalog = resolveModelCatalog(yaml.modelCatalog)
        )
    }

    private fun resolveRoleEndpoint(
        env: Map<String, String>,
        yaml: LlmRuntimeYamlConfig,
        fallbackProvider: LlmProvider,
        role: LlmRuntimeYamlRole?,
        legacyModel: String?,
    ): LlmEndpointConfig? {
        val provider = LlmProvider.parse(role?.provider) ?: fallbackProvider
        val providerSettings = resolveProviderSettings(
            env = env,
            yaml = yaml,
            fallbackProvider = fallbackProvider,
            provider = provider
        )
        val model = firstNonBlank(role?.model, legacyModel, provider.defaults().defaultModel)
            ?: provider.defaults().defaultModel
        return LlmEndpointConfig(
            provider = provider,
            apiKey = providerSettings.apiKey,
            apiKeyEnvVar = providerSettings.apiKeyEnvVar,
            baseUrl = providerSettings.baseUrl,
            model = model
        )
    }

    private fun resolveProviderSettings(
        env: Map<String, String>,
        yaml: LlmRuntimeYamlConfig,
        fallbackProvider: LlmProvider,
        provider: LlmProvider,
    ): ProviderRuntimeSettings {
        val defaults = provider.defaults()
        val providerYaml = yaml.providers.forProvider(provider)
        val usesLegacyTopLevel = provider == fallbackProvider

        val baseUrl = firstNonBlank(
            providerYaml?.baseUrl,
            if (usesLegacyTopLevel) yaml.baseUrl else null,
            defaults.baseUrl
        ) ?: defaults.baseUrl

        val apiKeyEnvVar = firstNonBlank(
            providerYaml?.apiKeyEnvVar,
            if (usesLegacyTopLevel) yaml.apiKeyEnvVar else null,
            defaults.apiKeyEnvVar
        ) ?: defaults.apiKeyEnvVar

        return ProviderRuntimeSettings(
            baseUrl = baseUrl,
            apiKeyEnvVar = apiKeyEnvVar,
            apiKey = env[apiKeyEnvVar]?.trim().orEmpty()
        )
    }

    private fun resolveWebSearchApiKeyEnvVar(
        yaml: LlmRuntimeYamlConfig,
        providerApiKeyEnvVar: String,
    ): String =
        firstNonBlank(yaml.webSearch?.apiKeyEnvVar, providerApiKeyEnvVar) ?: providerApiKeyEnvVar

    private fun resolveConfigPath(env: Map<String, String>, defaultPath: Path): Path {
        val configured = env["PSYKE_LLM_CONFIG_FILE"]?.trim().orEmpty()
        if (configured.isBlank()) {
            return defaultPath
        }
        return Paths.get(configured)
    }

    private fun readYaml(path: Path): LlmRuntimeYamlConfig? {
        if (!Files.exists(path)) {
            return null
        }
        Files.newBufferedReader(path).use { reader ->
            return mapper.readValue<LlmRuntimeYamlConfig>(reader)
        }
    }

    private fun LlmRuntimeYamlProviders?.forProvider(provider: LlmProvider): LlmRuntimeYamlProvider? =
        when (provider) {
            LlmProvider.GROQ -> this?.groq
            LlmProvider.MISTRAL -> this?.mistral
            LlmProvider.GOOGLE -> this?.google
            LlmProvider.OPENAI -> this?.openai
        }

    private fun resolveModelCatalog(yamlCatalog: LlmRuntimeYamlModelCatalog?): LlmModelCatalog {
        val defaults = LlmModelCatalog.defaults()
        if (yamlCatalog == null) return defaults
        val overrides = linkedMapOf(
            LlmProvider.GROQ to parseModelProfiles(yamlCatalog.groq),
            LlmProvider.MISTRAL to parseModelProfiles(yamlCatalog.mistral),
            LlmProvider.GOOGLE to parseModelProfiles(yamlCatalog.google),
            LlmProvider.OPENAI to parseModelProfiles(yamlCatalog.openai),
        ).filterValues { it.isNotEmpty() }
        return defaults.merge(overrides)
    }

    private fun parseModelProfiles(entries: List<LlmRuntimeYamlModelProfile>?): List<LlmModelProfile> =
        entries.orEmpty()
            .mapNotNull { entry ->
                val model = firstNonBlank(entry.model) ?: return@mapNotNull null
                val tier = LlmModelTier.parse(entry.tier) ?: LlmModelTier.LIGHT
                val weight = entry.tokenWeight
                    ?.takeIf { it > 0.0 }
                    ?.coerceIn(MODEL_WEIGHT_MIN, MODEL_WEIGHT_MAX)
                    ?: LlmModelProfile.DEFAULT_TOKEN_WEIGHT
                LlmModelProfile(
                    model = model,
                    tier = tier,
                    tokenWeight = weight,
                    inputCostPerMillionTokensUsd = entry.inputCostPerMillionTokensUsd?.takeIf { it >= 0.0 },
                    outputCostPerMillionTokensUsd = entry.outputCostPerMillionTokensUsd?.takeIf { it >= 0.0 },
                    contextWindow = entry.contextWindow?.takeIf { it > 0 }
                )
            }
            .distinctBy { it.normalizedModel() }

    private fun LlmProvider.defaults(): ProviderDefaults =
        when (this) {
            LlmProvider.GROQ -> ProviderDefaults(
                baseUrl = "https://api.groq.com/openai/v1",
                apiKeyEnvVar = "GROQ_API_KEY",
                defaultModel = GroqChatClient.DEFAULT_MODEL,
                defaultWebSearchModel = "groq/compound-mini"
            )

            LlmProvider.MISTRAL -> ProviderDefaults(
                baseUrl = "https://api.mistral.ai/v1",
                apiKeyEnvVar = "MISTRAL_API_KEY",
                defaultModel = MistralChatClient.DEFAULT_MODEL,
                defaultWebSearchModel = MistralChatClient.DEFAULT_MODEL
            )

            LlmProvider.GOOGLE -> ProviderDefaults(
                baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai/",
                apiKeyEnvVar = "GOOGLE_API_KEY",
                defaultModel = "gemini-3.1-pro-preview",
                defaultWebSearchModel = "gemini-3.1-flash-lite-preview"
            )

            LlmProvider.OPENAI -> ProviderDefaults(
                baseUrl = "https://api.openai.com/v1",
                apiKeyEnvVar = "OPENAI_API_KEY",
                defaultModel = OpenAiChatClient.DEFAULT_MODEL,
                defaultWebSearchModel = OpenAiChatClient.DEFAULT_MODEL
            )
        }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }?.trim()

    private const val MODEL_WEIGHT_MIN: Double = 0.25
    private const val MODEL_WEIGHT_MAX: Double = 4.0
}
