package psyke.config

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import psyke.llm.GroqChatClient
import psyke.llm.MistralChatClient
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

enum class LlmProvider(val id: String) {
    GROQ("groq"),
    MISTRAL("mistral");

    companion object {
        fun parse(raw: String?): LlmProvider? =
            entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) }
    }
}

data class LlmRuntimeConfig(
    val provider: LlmProvider,
    val apiKey: String,
    val apiKeyEnvVar: String,
    val baseUrl: String,
    val egoModel: String,
    val superegoModel: String,
    val metaReasonerModel: String,
    val memoryConsolidationModel: String,
    val webSearchModel: String,
) {
    val providerLabel: String
        get() = provider.id
}

private data class LlmRuntimeYamlModels(
    val ego: String? = null,
    val superego: String? = null,
    @JsonProperty("meta_reasoner")
    val metaReasoner: String? = null,
    @JsonProperty("memory_consolidation")
    val memoryConsolidation: String? = null,
    @JsonProperty("web_search")
    val webSearch: String? = null,
)

private data class LlmRuntimeYamlConfig(
    val provider: String? = null,
    @JsonProperty("base_url")
    val baseUrl: String? = null,
    @JsonProperty("api_key_env")
    val apiKeyEnvVar: String? = null,
    val models: LlmRuntimeYamlModels = LlmRuntimeYamlModels(),
)

private data class ProviderDefaults(
    val baseUrl: String,
    val apiKeyEnvVar: String,
    val defaultModel: String,
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
        val yaml = readYaml(filePath) ?: LlmRuntimeYamlConfig(provider = LlmProvider.GROQ.id)

        val provider = LlmProvider.parse(
            firstNonBlank(env["LLM_PROVIDER"], yaml.provider, LlmProvider.GROQ.id)
        ) ?: return null
        val defaults = provider.defaults()
        val providerPrefix = provider.id.uppercase()

        val baseUrl = firstNonBlank(
            env["LLM_BASE_URL"],
            env["${providerPrefix}_BASE_URL"],
            yaml.baseUrl,
            defaults.baseUrl
        ) ?: defaults.baseUrl

        val apiKeyEnvVar = firstNonBlank(
            env["LLM_API_KEY_ENV"],
            yaml.apiKeyEnvVar,
            defaults.apiKeyEnvVar
        ) ?: defaults.apiKeyEnvVar

        val apiKey = firstNonBlank(
            env["LLM_API_KEY"],
            env[apiKeyEnvVar],
            env[defaults.apiKeyEnvVar]
        ).orEmpty()

        val egoModel = firstNonBlank(
            env["LLM_EGO_MODEL"],
            env["${providerPrefix}_EGO_MODEL"],
            yaml.models.ego,
            defaults.defaultModel
        ) ?: defaults.defaultModel

        val superegoModel = firstNonBlank(
            env["LLM_SUPEREGO_MODEL"],
            env["${providerPrefix}_SUPEREGO_MODEL"],
            yaml.models.superego,
            egoModel
        ) ?: egoModel

        val metaReasonerModel = firstNonBlank(
            env["LLM_META_REASONER_MODEL"],
            env["${providerPrefix}_META_REASONER_MODEL"],
            yaml.models.metaReasoner,
            egoModel
        ) ?: egoModel

        val memoryConsolidationModel = firstNonBlank(
            env["LLM_MEMORY_CONSOLIDATION_MODEL"],
            env["${providerPrefix}_MEMORY_CONSOLIDATION_MODEL"],
            yaml.models.memoryConsolidation,
            egoModel
        ) ?: egoModel

        val webSearchModel = firstNonBlank(
            env["LLM_WEBSEARCH_MODEL"],
            env["${providerPrefix}_WEBSEARCH_MODEL"],
            yaml.models.webSearch,
            egoModel
        ) ?: egoModel

        return LlmRuntimeConfig(
            provider = provider,
            apiKey = apiKey,
            apiKeyEnvVar = apiKeyEnvVar,
            baseUrl = baseUrl,
            egoModel = egoModel,
            superegoModel = superegoModel,
            metaReasonerModel = metaReasonerModel,
            memoryConsolidationModel = memoryConsolidationModel,
            webSearchModel = webSearchModel
        )
    }

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

    private fun LlmProvider.defaults(): ProviderDefaults =
        when (this) {
            LlmProvider.GROQ -> ProviderDefaults(
                baseUrl = "https://api.groq.com/openai/v1",
                apiKeyEnvVar = "GROQ_API_KEY",
                defaultModel = GroqChatClient.DEFAULT_MODEL
            )

            LlmProvider.MISTRAL -> ProviderDefaults(
                baseUrl = "https://api.mistral.ai/v1",
                apiKeyEnvVar = "MISTRAL_API_KEY",
                defaultModel = MistralChatClient.DEFAULT_MODEL
            )
        }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }?.trim()
}
