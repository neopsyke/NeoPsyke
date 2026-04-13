package ai.neopsyke.config

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDate

enum class LlmProvider(val id: String) {
    ANTHROPIC("anthropic"),
    GROQ("groq"),
    MISTRAL("mistral"),
    GOOGLE("google"),
    OPENAI("openai"),
    OLLAMA("ollama");

    companion object {
        fun parse(raw: String?): LlmProvider? =
            entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) }
    }

    fun requiresApiKey(): Boolean =
        this != OLLAMA
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
    val superego: LlmEndpointConfig,
    val metaReasoner: LlmEndpointConfig,
    val metaReasonerFallback: LlmEndpointConfig? = null,
    val memoryAdvisor: LlmEndpointConfig,
    val approvalInterpreter: LlmEndpointConfig,
    val superegoPrimary: LlmEndpointConfig? = null,
    val superegoEscalation: LlmEndpointConfig? = null,
    val plannerLanes: Map<String, LlmEndpointConfig> = emptyMap(),
)

data class LlmRuntimeConfig(
    val cognitiveRoles: LlmCognitiveRolesConfig,
    val webSearch: LlmEndpointConfig,
    val modelCatalog: LlmModelCatalog = LlmModelCatalog(),
) {
    val planner: LlmEndpointConfig
        get() = cognitiveRoles.planner

    val superego: LlmEndpointConfig
        get() = cognitiveRoles.superego

    val metaReasoner: LlmEndpointConfig
        get() = cognitiveRoles.metaReasoner

    val metaReasonerFallback: LlmEndpointConfig?
        get() = cognitiveRoles.metaReasonerFallback

    val memoryAdvisor: LlmEndpointConfig
        get() = cognitiveRoles.memoryAdvisor

    val approvalInterpreter: LlmEndpointConfig
        get() = cognitiveRoles.approvalInterpreter

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

private data class LlmRuntimeYamlRole(
    val provider: String? = null,
    val model: String? = null,
)

private data class LlmRuntimeYamlPlannerRole(
    val provider: String? = null,
    val model: String? = null,
    val lanes: Map<String, LlmRuntimeYamlRole>? = null,
) {
    fun toBaseRole(): LlmRuntimeYamlRole = LlmRuntimeYamlRole(provider = provider, model = model)
}

private data class LlmRuntimeYamlCognitiveRoles(
    val planner: LlmRuntimeYamlPlannerRole? = null,
    val superego: LlmRuntimeYamlRole? = null,
    @param:JsonProperty("superego_primary")
    val superegoPrimary: LlmRuntimeYamlRole? = null,
    @param:JsonProperty("superego_escalation")
    val superegoEscalation: LlmRuntimeYamlRole? = null,
    @param:JsonProperty("meta_reasoner")
    val metaReasoner: LlmRuntimeYamlRole? = null,
    @param:JsonProperty("meta_reasoner_fallback")
    val metaReasonerFallback: LlmRuntimeYamlRole? = null,
    @param:JsonProperty("memory_advisor")
    val memoryAdvisor: LlmRuntimeYamlRole? = null,
    @param:JsonProperty("approval_interpreter")
    val approvalInterpreter: LlmRuntimeYamlRole? = null,
)

private data class LlmRuntimeYamlProvider(
    @param:JsonProperty("base_url")
    val baseUrl: String? = null,
    @param:JsonProperty("api_key_env")
    val apiKeyEnvVar: String? = null,
    @param:JsonProperty("default_model")
    val defaultModel: String? = null,
    @param:JsonProperty("default_web_search_model")
    val defaultWebSearchModel: String? = null,
)

private data class LlmRuntimeYamlProviders(
    val anthropic: LlmRuntimeYamlProvider? = null,
    val groq: LlmRuntimeYamlProvider? = null,
    val mistral: LlmRuntimeYamlProvider? = null,
    val google: LlmRuntimeYamlProvider? = null,
    val openai: LlmRuntimeYamlProvider? = null,
    val ollama: LlmRuntimeYamlProvider? = null,
)

private data class LlmRuntimeYamlConfig(
    @param:JsonProperty("web_search")
    val webSearch: LlmRuntimeYamlWebSearch? = null,
    @param:JsonProperty("cognitive_roles")
    val cognitiveRoles: LlmRuntimeYamlCognitiveRoles? = null,
    val providers: LlmRuntimeYamlProviders? = null,
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
    @param:JsonProperty("reasoning_overhead_multiplier")
    val reasoningOverheadMultiplier: Double? = null,
    @param:JsonProperty("metadata_updated_at")
    val metadataUpdatedAt: String? = null,
)

private data class LlmRuntimeYamlModelCatalog(
    val anthropic: List<LlmRuntimeYamlModelProfile>? = null,
    val groq: List<LlmRuntimeYamlModelProfile>? = null,
    val mistral: List<LlmRuntimeYamlModelProfile>? = null,
    val google: List<LlmRuntimeYamlModelProfile>? = null,
    val openai: List<LlmRuntimeYamlModelProfile>? = null,
    val ollama: List<LlmRuntimeYamlModelProfile>? = null,
)

private data class ProviderRuntimeSettings(
    val baseUrl: String,
    val apiKeyEnvVar: String,
    val apiKey: String,
    val defaultModel: String? = null,
    val defaultWebSearchModel: String? = null,
)

object LlmRuntimeConfigLoader {
    private val mapper = ObjectMapper(YAMLFactory())
        .registerKotlinModule()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun load(
        env: Map<String, String> = System.getenv(),
        defaultPath: Path = Paths.get("llm-runtime.yaml"),
    ): LlmRuntimeConfig {
        val yaml = YamlConfigSources.loadYamlConfig<LlmRuntimeYamlConfig>(
            mapper = mapper,
            env = env,
            envKey = "NEOPSYKE_LLM_CONFIG_FILE",
            defaultPath = defaultPath,
            bundledResourceName = "llm-runtime.yaml",
        ) ?: throw IllegalStateException("Missing bundled or external llm-runtime.yaml configuration.")
        validate(yaml)

        val planner = resolveRoleEndpoint(
            env = env,
            yaml = yaml,
            roleName = "cognitive_roles.planner",
            role = yaml.cognitiveRoles?.planner?.toBaseRole()
        )

        val superego = resolveRoleEndpoint(
            env = env,
            yaml = yaml,
            role = yaml.cognitiveRoles?.superego ?: yaml.cognitiveRoles?.superegoPrimary,
            roleName = if (yaml.cognitiveRoles?.superego != null) "cognitive_roles.superego" else "cognitive_roles.superego_primary"
        )

        val metaReasoner = resolveRoleEndpoint(
            env = env,
            yaml = yaml,
            roleName = "cognitive_roles.meta_reasoner",
            role = yaml.cognitiveRoles?.metaReasoner,
        )

        val memoryAdvisor = resolveRoleEndpoint(
            env = env,
            yaml = yaml,
            roleName = "cognitive_roles.memory_advisor",
            role = yaml.cognitiveRoles?.memoryAdvisor,
        )

        val approvalInterpreter = resolveRoleEndpoint(
            env = env,
            yaml = yaml,
            roleName = "cognitive_roles.approval_interpreter",
            role = yaml.cognitiveRoles?.approvalInterpreter,
        )

        val metaReasonerFallback = yaml.cognitiveRoles?.metaReasonerFallback?.let { role ->
            resolveRoleEndpoint(
                env = env,
                yaml = yaml,
                role = role,
                roleName = "cognitive_roles.meta_reasoner_fallback"
            )
        }

        val superegoPrimary = yaml.cognitiveRoles?.superegoPrimary?.let { role ->
            resolveRoleEndpoint(
                env = env,
                yaml = yaml,
                role = role,
                roleName = "cognitive_roles.superego_primary"
            )
        }

        val superegoEscalation = yaml.cognitiveRoles?.superegoEscalation?.let { role ->
            resolveRoleEndpoint(
                env = env,
                yaml = yaml,
                role = role,
                roleName = "cognitive_roles.superego_escalation"
            )
        }

        val webSearchYaml = yaml.webSearch
            ?: throw IllegalStateException("llm-runtime.yaml is missing required section: web_search")
        val webSearchProvider = parseProvider(
            raw = webSearchYaml.provider,
            fieldName = "web_search.provider"
        )
        val webSearchProviderSettings = resolveProviderSettings(
            env = env,
            yaml = yaml,
            provider = webSearchProvider,
        )
        val webSearchModel = firstNonBlank(webSearchYaml.model, webSearchProviderSettings.defaultWebSearchModel)
            ?: throw IllegalStateException(
                "llm-runtime.yaml is missing web_search.model and providers.${webSearchProvider.id}.default_web_search_model"
            )

        val webSearch = LlmEndpointConfig(
            provider = webSearchProvider,
            apiKey = env[resolveWebSearchApiKeyEnvVar(webSearchYaml, webSearchProviderSettings.apiKeyEnvVar)]?.trim().orEmpty(),
            apiKeyEnvVar = resolveWebSearchApiKeyEnvVar(webSearchYaml, webSearchProviderSettings.apiKeyEnvVar),
            baseUrl = firstNonBlank(webSearchYaml.baseUrl, webSearchProviderSettings.baseUrl) ?: webSearchProviderSettings.baseUrl,
            model = webSearchModel
        )

        val plannerLanes = resolvePlannerLanes(
            env = env,
            yaml = yaml,
            plannerEndpoint = planner,
            lanes = yaml.cognitiveRoles?.planner?.lanes,
        )

        return LlmRuntimeConfig(
            cognitiveRoles = LlmCognitiveRolesConfig(
                planner = planner,
                superego = superego,
                metaReasoner = metaReasoner,
                metaReasonerFallback = metaReasonerFallback,
                memoryAdvisor = memoryAdvisor,
                approvalInterpreter = approvalInterpreter,
                superegoPrimary = superegoPrimary,
                superegoEscalation = superegoEscalation,
                plannerLanes = plannerLanes,
            ),
            webSearch = webSearch,
            modelCatalog = resolveModelCatalog(yaml.modelCatalog)
        )
    }

    private fun validate(yaml: LlmRuntimeYamlConfig) {
        val providers = yaml.providers
            ?: throw IllegalStateException("llm-runtime.yaml is missing required section: providers")
        val roles = yaml.cognitiveRoles
            ?: throw IllegalStateException("llm-runtime.yaml is missing required section: cognitive_roles")

        requireRole(name = "cognitive_roles.planner", role = roles.planner?.toBaseRole())
        requireRole(name = "cognitive_roles.meta_reasoner", role = roles.metaReasoner)
        requireRole(name = "cognitive_roles.memory_advisor", role = roles.memoryAdvisor)
        requireRole(name = "cognitive_roles.approval_interpreter", role = roles.approvalInterpreter)
        if (roles.superego == null && roles.superegoPrimary == null) {
            throw IllegalStateException(
                "llm-runtime.yaml must define either cognitive_roles.superego or cognitive_roles.superego_primary"
            )
        }
        validateRoleProvider("cognitive_roles.planner", roles.planner?.toBaseRole(), providers)
        for ((laneKey, laneRole) in roles.planner?.lanes.orEmpty()) {
            validatePlannerLaneRole("cognitive_roles.planner.lanes.$laneKey", laneRole, providers)
        }
        validateRoleProvider("cognitive_roles.superego", roles.superego, providers)
        validateRoleProvider("cognitive_roles.superego_primary", roles.superegoPrimary, providers)
        validateRoleProvider("cognitive_roles.superego_escalation", roles.superegoEscalation, providers)
        validateRoleProvider("cognitive_roles.meta_reasoner", roles.metaReasoner, providers)
        validateRoleProvider("cognitive_roles.meta_reasoner_fallback", roles.metaReasonerFallback, providers)
        validateRoleProvider("cognitive_roles.memory_advisor", roles.memoryAdvisor, providers)
        validateRoleProvider("cognitive_roles.approval_interpreter", roles.approvalInterpreter, providers)

        val webSearch = yaml.webSearch
            ?: throw IllegalStateException("llm-runtime.yaml is missing required section: web_search")
        val webSearchProvider = parseProvider(raw = webSearch.provider, fieldName = "web_search.provider")
        validateProviderBlock(provider = webSearchProvider, providers = providers)
    }

    private fun requireRole(name: String, role: LlmRuntimeYamlRole?) {
        if (role == null) {
            throw IllegalStateException("llm-runtime.yaml is missing required role: $name")
        }
        if (role.provider.isNullOrBlank()) {
            throw IllegalStateException("llm-runtime.yaml is missing required field: $name.provider")
        }
        if (role.model.isNullOrBlank()) {
            throw IllegalStateException("llm-runtime.yaml is missing required field: $name.model")
        }
    }

    private fun validateRoleProvider(
        name: String,
        role: LlmRuntimeYamlRole?,
        providers: LlmRuntimeYamlProviders,
    ) {
        if (role == null) return
        val provider = parseProvider(raw = role.provider, fieldName = "$name.provider")
        validateProviderBlock(provider = provider, providers = providers)
    }

    private fun validateProviderBlock(
        provider: LlmProvider,
        providers: LlmRuntimeYamlProviders,
    ) {
        val providerYaml = providers.forProvider(provider)
            ?: throw IllegalStateException(
                "llm-runtime.yaml references provider '${provider.id}' but providers.${provider.id} is not configured"
            )
        if (providerYaml.baseUrl.isNullOrBlank()) {
            throw IllegalStateException("llm-runtime.yaml is missing required field: providers.${provider.id}.base_url")
        }
        if (providerYaml.apiKeyEnvVar.isNullOrBlank()) {
            throw IllegalStateException("llm-runtime.yaml is missing required field: providers.${provider.id}.api_key_env")
        }
    }

    private fun parseProvider(raw: String?, fieldName: String): LlmProvider =
        LlmProvider.parse(raw)
            ?: throw IllegalStateException(
                "Invalid $fieldName value '$raw'. Supported providers: ${LlmProvider.entries.joinToString(", ") { it.id }}"
            )

    private fun resolveRoleEndpoint(
        env: Map<String, String>,
        yaml: LlmRuntimeYamlConfig,
        roleName: String,
        role: LlmRuntimeYamlRole?,
    ): LlmEndpointConfig {
        val roleConfig = role ?: throw IllegalStateException("llm-runtime.yaml is missing required role: $roleName")
        val provider = parseProvider(raw = roleConfig.provider, fieldName = "$roleName.provider")
        val providerSettings = resolveProviderSettings(
            env = env,
            yaml = yaml,
            provider = provider
        )
        val model = firstNonBlank(roleConfig.model, providerSettings.defaultModel)
            ?: throw IllegalStateException("llm-runtime.yaml is missing required field: $roleName.model")
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
        provider: LlmProvider,
    ): ProviderRuntimeSettings {
        val providerYaml = yaml.providers?.forProvider(provider)
            ?: throw IllegalStateException(
                "llm-runtime.yaml references provider '${provider.id}' but providers.${provider.id} is not configured"
            )
        val baseUrl = firstNonBlank(providerYaml.baseUrl)
            ?: throw IllegalStateException("llm-runtime.yaml is missing required field: providers.${provider.id}.base_url")
        val apiKeyEnvVar = firstNonBlank(providerYaml.apiKeyEnvVar)
            ?: throw IllegalStateException("llm-runtime.yaml is missing required field: providers.${provider.id}.api_key_env")

        return ProviderRuntimeSettings(
            baseUrl = baseUrl,
            apiKeyEnvVar = apiKeyEnvVar,
            apiKey = env[apiKeyEnvVar]?.trim().orEmpty(),
            defaultModel = firstNonBlank(providerYaml.defaultModel),
            defaultWebSearchModel = firstNonBlank(providerYaml.defaultWebSearchModel, providerYaml.defaultModel),
        )
    }

    private fun resolvePlannerLanes(
        env: Map<String, String>,
        yaml: LlmRuntimeYamlConfig,
        plannerEndpoint: LlmEndpointConfig,
        lanes: Map<String, LlmRuntimeYamlRole>?,
    ): Map<String, LlmEndpointConfig> {
        if (lanes.isNullOrEmpty()) return emptyMap()
        return lanes.mapValues { (laneKey, role) ->
            val provider = LlmProvider.parse(role.provider)
            val model = role.model?.trim()?.ifBlank { null }
            if (provider == null && model == null) {
                plannerEndpoint
            } else {
                val effectiveProvider = provider ?: plannerEndpoint.provider
                val providerSettings = resolveProviderSettings(env = env, yaml = yaml, provider = effectiveProvider)
                val effectiveModel = model
                    ?: firstNonBlank(providerSettings.defaultModel)
                    ?: plannerEndpoint.model
                LlmEndpointConfig(
                    provider = effectiveProvider,
                    apiKey = providerSettings.apiKey,
                    apiKeyEnvVar = providerSettings.apiKeyEnvVar,
                    baseUrl = providerSettings.baseUrl,
                    model = effectiveModel,
                )
            }
        }
    }

    private fun validatePlannerLaneRole(
        name: String,
        role: LlmRuntimeYamlRole,
        providers: LlmRuntimeYamlProviders,
    ) {
        if (!role.provider.isNullOrBlank()) {
            validateRoleProvider(name, role, providers)
        }
    }

    private fun resolveWebSearchApiKeyEnvVar(
        webSearch: LlmRuntimeYamlWebSearch,
        providerApiKeyEnvVar: String,
    ): String =
        firstNonBlank(webSearch.apiKeyEnvVar, providerApiKeyEnvVar) ?: providerApiKeyEnvVar

    private fun LlmRuntimeYamlProviders?.forProvider(provider: LlmProvider): LlmRuntimeYamlProvider? =
        when (provider) {
            LlmProvider.ANTHROPIC -> this?.anthropic
            LlmProvider.GROQ -> this?.groq
            LlmProvider.MISTRAL -> this?.mistral
            LlmProvider.GOOGLE -> this?.google
            LlmProvider.OPENAI -> this?.openai
            LlmProvider.OLLAMA -> this?.ollama
        }

    private fun resolveModelCatalog(yamlCatalog: LlmRuntimeYamlModelCatalog?): LlmModelCatalog {
        if (yamlCatalog == null) return LlmModelCatalog()
        val overrides = linkedMapOf(
            LlmProvider.ANTHROPIC to parseModelProfiles(yamlCatalog.anthropic),
            LlmProvider.GROQ to parseModelProfiles(yamlCatalog.groq),
            LlmProvider.MISTRAL to parseModelProfiles(yamlCatalog.mistral),
            LlmProvider.GOOGLE to parseModelProfiles(yamlCatalog.google),
            LlmProvider.OPENAI to parseModelProfiles(yamlCatalog.openai),
            LlmProvider.OLLAMA to parseModelProfiles(yamlCatalog.ollama),
        ).filterValues { it.isNotEmpty() }
        return LlmModelCatalog(byProvider = overrides)
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
                val reasoningOverhead = entry.reasoningOverheadMultiplier
                    ?.takeIf { it >= 1.0 }
                    ?.coerceAtMost(5.0)
                    ?: LlmModelProfile.DEFAULT_REASONING_OVERHEAD
                LlmModelProfile(
                    model = model,
                    tier = tier,
                    tokenWeight = weight,
                    inputCostPerMillionTokensUsd = entry.inputCostPerMillionTokensUsd?.takeIf { it >= 0.0 },
                    outputCostPerMillionTokensUsd = entry.outputCostPerMillionTokensUsd?.takeIf { it >= 0.0 },
                    contextWindow = entry.contextWindow?.takeIf { it > 0 },
                    reasoningOverheadMultiplier = reasoningOverhead,
                    metadataUpdatedAt = entry.metadataUpdatedAt
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { LocalDate.parse(it).toString() }
                )
            }
            .distinctBy { it.normalizedModel() }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }?.trim()

    private const val MODEL_WEIGHT_MIN: Double = 0.25
    private const val MODEL_WEIGHT_MAX: Double = 4.0
}
