package psyke.config

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import psyke.agent.core.AgentConfig
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

data class AgentRuntimeSettings(
    val agentConfig: AgentConfig,
    val dashboardEnabled: Boolean,
    val dashboardPort: Int,
    val evalMaxRawResponseChars: Int,
    val evalDefaultStage: String? = null,
)

private data class AgentRuntimeYamlConfig(
    val app: AgentRuntimeYamlApp? = AgentRuntimeYamlApp(),
    val eval: AgentRuntimeYamlEval? = AgentRuntimeYamlEval(),
    val agent: AgentRuntimeYamlAgent? = AgentRuntimeYamlAgent(),
)

private data class AgentRuntimeYamlApp(
    val dashboardEnabled: Boolean? = null,
    val dashboardPort: Int? = null,
)

private data class AgentRuntimeYamlEval(
    val maxRawResponseChars: Int? = null,
    val defaultStage: String? = null,
)

private data class AgentRuntimeYamlAgent(
    val maxLoopStepsPerInput: Int? = null,
    val loopDelayMs: Int? = null,
    val maxThoughtPasses: Int? = null,
    val maxPendingThoughts: Int? = null,
    val maxPendingActions: Int? = null,
    val maxPendingInputs: Int? = null,
    val maxInputChars: Int? = null,
    val maxShortTermContextChars: Int? = null,
    val maxShortTermContextPromptTokens: Int? = null,
    val maxThoughtChars: Int? = null,
    val maxActionPayloadChars: Int? = null,
    val maxActionSummaryChars: Int? = null,
    val maxPromptTokens: Int? = null,
    val maxCompletionTokens: Int? = null,
    val llmRetryAttempts: Int? = null,
    val superegoMaxCompletionTokens: Int? = null,
    val searchResultCount: Int? = null,
    val mcpCallTimeoutMs: Long? = null,
    val mcpFetchMaxChars: Int? = null,
    val mcpMemoryCallTimeoutMs: Long? = null,
    val longTermMemoryRecallMaxItems: Int? = null,
    val longTermMemoryRecallMaxChars: Int? = null,
    val deliberationPressureAssessmentMinStep: Int? = null,
    val deliberationPressureAssessmentEverySteps: Int? = null,
    val deliberationPressureAssessmentThreshold: Double? = null,
    val metaReasonerCooldownSteps: Int? = null,
    val metaReasonerMaxTokens: Int? = null,
    val longTermMemoryAssessEverySteps: Int? = null,
    val longTermMemoryAssessCooldownSteps: Int? = null,
    val longTermMemoryMinConfidence: Double? = null,
    val longTermMemoryMaxTokens: Int? = null,
    val longTermMemoryMaxSummaryChars: Int? = null,
    val forcedTerminalPressureThreshold: Double? = null,
    val forcedTerminalStaleStreakThreshold: Int? = null,
    val longTermMemoryForceAssessOnAllowedAction: Boolean? = null,
    val longTermMemoryParseFallbackDisableAfter: Int? = null,
)

object AgentRuntimeSettingsLoader {
    private const val DEFAULT_DASHBOARD_PORT: Int = 8787
    private val mapper = ObjectMapper(YAMLFactory())
        .registerKotlinModule()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)

    fun load(
        env: Map<String, String> = System.getenv(),
        defaultPath: Path = Paths.get("agent-runtime.yaml"),
    ): AgentRuntimeSettings {
        val defaults = AgentConfig()
        val yaml = readYaml(resolveConfigPath(env, defaultPath)) ?: AgentRuntimeYamlConfig()
        val appYaml = yaml.app ?: AgentRuntimeYamlApp()
        val evalYaml = yaml.eval ?: AgentRuntimeYamlEval()
        val agentYaml = yaml.agent ?: AgentRuntimeYamlAgent()

        val mcpCallTimeoutMs = readPositiveLong(
            env = env["MCP_CALL_TIMEOUT_MS"],
            yaml = agentYaml.mcpCallTimeoutMs,
            fallback = defaults.mcpCallTimeoutMs
        )

        val agentConfig = AgentConfig(
            maxLoopStepsPerInput = readPositiveInt(env["EGO_MAX_LOOP_STEPS"], agentYaml.maxLoopStepsPerInput, defaults.maxLoopStepsPerInput),
            loopDelayMs = readNonNegativeInt(env["EGO_LOOP_DELAY_MS"], agentYaml.loopDelayMs, defaults.loopDelayMs),
            maxThoughtPasses = readPositiveInt(env["EGO_MAX_THOUGHT_PASSES"], agentYaml.maxThoughtPasses, defaults.maxThoughtPasses),
            maxPendingThoughts = readPositiveInt(null, agentYaml.maxPendingThoughts, defaults.maxPendingThoughts),
            maxPendingActions = readPositiveInt(null, agentYaml.maxPendingActions, defaults.maxPendingActions),
            maxPendingInputs = readPositiveInt(null, agentYaml.maxPendingInputs, defaults.maxPendingInputs),
            maxInputChars = readPositiveInt(null, agentYaml.maxInputChars, defaults.maxInputChars),
            maxShortTermContextChars = readPositiveInt(
                env["EGO_SHORT_TERM_CONTEXT_MAX_CHARS"],
                agentYaml.maxShortTermContextChars,
                defaults.maxShortTermContextChars
            ),
            maxShortTermContextPromptTokens = readPositiveInt(
                env["EGO_SHORT_TERM_CONTEXT_MAX_PROMPT_TOKENS"],
                agentYaml.maxShortTermContextPromptTokens,
                defaults.maxShortTermContextPromptTokens
            ),
            maxThoughtChars = readPositiveInt(null, agentYaml.maxThoughtChars, defaults.maxThoughtChars),
            maxActionPayloadChars = readPositiveInt(
                env["EGO_MAX_ACTION_PAYLOAD_CHARS"],
                agentYaml.maxActionPayloadChars,
                defaults.maxActionPayloadChars
            ),
            maxActionSummaryChars = readPositiveInt(null, agentYaml.maxActionSummaryChars, defaults.maxActionSummaryChars),
            maxPromptTokens = readPositiveInt(env["EGO_MAX_PROMPT_TOKENS"], agentYaml.maxPromptTokens, defaults.maxPromptTokens),
            maxCompletionTokens = readPositiveInt(
                env["EGO_MAX_COMPLETION_TOKENS"],
                agentYaml.maxCompletionTokens,
                defaults.maxCompletionTokens
            ),
            llmRetryAttempts = readPositiveInt(env["EGO_LLM_RETRY_ATTEMPTS"], agentYaml.llmRetryAttempts, defaults.llmRetryAttempts),
            superegoMaxCompletionTokens = readPositiveInt(
                env["EGO_SUPEREGO_MAX_COMPLETION_TOKENS"],
                agentYaml.superegoMaxCompletionTokens,
                defaults.superegoMaxCompletionTokens
            ),
            searchResultCount = readPositiveInt(env["EGO_SEARCH_RESULT_COUNT"], agentYaml.searchResultCount, defaults.searchResultCount),
            mcpCallTimeoutMs = mcpCallTimeoutMs,
            mcpFetchMaxChars = readPositiveInt(env["MCP_FETCH_MAX_CHARS"], agentYaml.mcpFetchMaxChars, defaults.mcpFetchMaxChars),
            mcpMemoryCallTimeoutMs = readPositiveLong(
                env = env["MCP_MEMORY_CALL_TIMEOUT_MS"],
                yaml = agentYaml.mcpMemoryCallTimeoutMs,
                fallback = mcpCallTimeoutMs
            ),
            longTermMemoryRecallMaxItems = readPositiveInt(
                env["EGO_LONG_TERM_MEMORY_RECALL_MAX_ITEMS"],
                agentYaml.longTermMemoryRecallMaxItems,
                defaults.longTermMemoryRecallMaxItems
            ),
            longTermMemoryRecallMaxChars = readPositiveInt(
                env["EGO_LONG_TERM_MEMORY_RECALL_MAX_CHARS"],
                agentYaml.longTermMemoryRecallMaxChars,
                defaults.longTermMemoryRecallMaxChars
            ),
            deliberationPressureAssessmentMinStep = readPositiveInt(
                env["EGO_PRESSURE_MIN_STEP"],
                agentYaml.deliberationPressureAssessmentMinStep,
                defaults.deliberationPressureAssessmentMinStep
            ),
            deliberationPressureAssessmentEverySteps = readPositiveInt(
                env["EGO_PRESSURE_ASSESS_EVERY_STEPS"],
                agentYaml.deliberationPressureAssessmentEverySteps,
                defaults.deliberationPressureAssessmentEverySteps
            ),
            deliberationPressureAssessmentThreshold = readProbability(
                env["EGO_PRESSURE_ASSESS_THRESHOLD"],
                agentYaml.deliberationPressureAssessmentThreshold,
                defaults.deliberationPressureAssessmentThreshold
            ),
            metaReasonerCooldownSteps = readPositiveInt(
                env["EGO_META_REASONER_COOLDOWN_STEPS"],
                agentYaml.metaReasonerCooldownSteps,
                defaults.metaReasonerCooldownSteps
            ),
            metaReasonerMaxTokens = readPositiveInt(
                env["EGO_META_REASONER_MAX_TOKENS"],
                agentYaml.metaReasonerMaxTokens,
                defaults.metaReasonerMaxTokens
            ),
            longTermMemoryAssessEverySteps = readPositiveInt(
                env["EGO_LONG_TERM_MEMORY_ASSESS_EVERY_STEPS"],
                agentYaml.longTermMemoryAssessEverySteps,
                defaults.longTermMemoryAssessEverySteps
            ),
            longTermMemoryAssessCooldownSteps = readPositiveInt(
                env["EGO_LONG_TERM_MEMORY_ASSESS_COOLDOWN_STEPS"],
                agentYaml.longTermMemoryAssessCooldownSteps,
                defaults.longTermMemoryAssessCooldownSteps
            ),
            longTermMemoryMinConfidence = readProbability(
                env["EGO_LONG_TERM_MEMORY_MIN_CONFIDENCE"],
                agentYaml.longTermMemoryMinConfidence,
                defaults.longTermMemoryMinConfidence
            ),
            longTermMemoryMaxTokens = readPositiveInt(
                env["EGO_LONG_TERM_MEMORY_MAX_TOKENS"],
                agentYaml.longTermMemoryMaxTokens,
                defaults.longTermMemoryMaxTokens
            ),
            longTermMemoryMaxSummaryChars = readPositiveInt(
                env["EGO_LONG_TERM_MEMORY_MAX_SUMMARY_CHARS"],
                agentYaml.longTermMemoryMaxSummaryChars,
                defaults.longTermMemoryMaxSummaryChars
            ),
            forcedTerminalPressureThreshold = readProbability(
                env["EGO_FORCE_TERMINAL_PRESSURE_THRESHOLD"],
                agentYaml.forcedTerminalPressureThreshold,
                defaults.forcedTerminalPressureThreshold
            ),
            forcedTerminalStaleStreakThreshold = readPositiveInt(
                env["EGO_FORCE_TERMINAL_STALE_STREAK_THRESHOLD"],
                agentYaml.forcedTerminalStaleStreakThreshold,
                defaults.forcedTerminalStaleStreakThreshold
            ),
            longTermMemoryForceAssessOnAllowedAction = readBoolean(
                env["EGO_LONG_TERM_MEMORY_FORCE_ASSESS_ON_ALLOWED_ACTION"],
                agentYaml.longTermMemoryForceAssessOnAllowedAction,
                defaults.longTermMemoryForceAssessOnAllowedAction
            ),
            longTermMemoryParseFallbackDisableAfter = readPositiveInt(
                env["EGO_LONG_TERM_MEMORY_PARSE_FALLBACK_DISABLE_AFTER"],
                agentYaml.longTermMemoryParseFallbackDisableAfter,
                defaults.longTermMemoryParseFallbackDisableAfter
            )
        )

        return AgentRuntimeSettings(
            agentConfig = agentConfig,
            dashboardEnabled = readBoolean(
                env = env["PSYKE_DASHBOARD_ENABLED"],
                yaml = appYaml.dashboardEnabled,
                fallback = true
            ),
            dashboardPort = readPositiveInt(
                env = env["PSYKE_DASHBOARD_PORT"],
                yaml = appYaml.dashboardPort,
                fallback = DEFAULT_DASHBOARD_PORT
            ),
            evalMaxRawResponseChars = readPositiveInt(
                env = env["PSYKE_EVAL_MAX_RAW_RESPONSE_CHARS"],
                yaml = evalYaml.maxRawResponseChars,
                fallback = Int.MAX_VALUE
            ),
            evalDefaultStage = firstNonBlank(env["PSYKE_EVAL_STAGE"], evalYaml.defaultStage)
        )
    }

    private fun resolveConfigPath(env: Map<String, String>, defaultPath: Path): Path {
        val configured = env["PSYKE_AGENT_CONFIG_FILE"]?.trim().orEmpty()
        if (configured.isBlank()) {
            return defaultPath
        }
        return Paths.get(configured)
    }

    private fun readYaml(path: Path): AgentRuntimeYamlConfig? {
        if (!Files.exists(path)) {
            return null
        }
        Files.newBufferedReader(path).use { reader ->
            return mapper.readValue<AgentRuntimeYamlConfig>(reader)
        }
    }

    private fun readPositiveInt(env: String?, yaml: Int?, fallback: Int): Int =
        env?.toIntOrNull()?.takeIf { it > 0 } ?: yaml?.takeIf { it > 0 } ?: fallback

    private fun readNonNegativeInt(env: String?, yaml: Int?, fallback: Int): Int =
        env?.toIntOrNull()?.takeIf { it >= 0 } ?: yaml?.takeIf { it >= 0 } ?: fallback

    private fun readPositiveLong(env: String?, yaml: Long?, fallback: Long): Long =
        env?.toLongOrNull()?.takeIf { it > 0 } ?: yaml?.takeIf { it > 0 } ?: fallback

    private fun readProbability(env: String?, yaml: Double?, fallback: Double): Double =
        env?.toDoubleOrNull()?.takeIf { it in 0.0..1.0 } ?: yaml?.takeIf { it in 0.0..1.0 } ?: fallback

    private fun readBoolean(env: String?, yaml: Boolean?, fallback: Boolean): Boolean =
        parseBoolean(env) ?: yaml ?: fallback

    private fun parseBoolean(raw: String?): Boolean? =
        when (raw?.trim()?.lowercase()) {
            "1", "true", "yes" -> true
            "0", "false", "no" -> false
            else -> null
        }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }?.trim()
}
