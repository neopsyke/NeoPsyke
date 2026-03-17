package psyke.config

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import psyke.agent.id.ConvergenceMode
import psyke.agent.id.IdConfig
import psyke.agent.id.NeedConfig
import psyke.agent.id.ResponseCurveConfig
import psyke.agent.model.ActionEffect
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * YAML representation of the Id runtime config.
 * All fields nullable — missing values fall back to [IdConfig] defaults.
 */
private data class IdRuntimeYamlConfig(
    val id: IdRuntimeYamlId? = IdRuntimeYamlId(),
)

private data class IdRuntimeYamlId(
    val enabled: Boolean? = null,
    val pulseIntervalMs: Long? = null,
    val triggerThreshold: Double? = null,
    val thresholdOnUrgency: Boolean? = null,
    val maxConsecutiveDenials: Int? = null,
    val backoffPulses: Int? = null,
    val maxInFlightPulses: Int? = null,
    val maxPendingImpulses: Int? = null,
    val needs: Map<String, IdRuntimeYamlNeed>? = null,
)

private data class IdRuntimeYamlNeed(
    val enabled: Boolean? = null,
    val description: String? = null,
    val growthRate: Double? = null,
    val satisfactionDecay: Double? = null,
    val resetFloor: Double? = null,
    val cooldownPulses: Int? = null,
    val prompt: String? = null,
    val convergence: String? = null,
    val allowEscalation: Boolean? = null,
    val responseCurve: IdRuntimeYamlResponseCurve? = null,
    val satisfactionEffectsAnyOf: List<String>? = null,
    val activityDecay: Map<String, Double>? = null,
)

private data class IdRuntimeYamlResponseCurve(
    val type: String? = null,
    val exponent: Double? = null,
    val steepness: Double? = null,
    val midpoint: Double? = null,
    val scale: Double? = null,
)

object IdRuntimeConfigLoader {
    private val mapper = ObjectMapper(YAMLFactory())
        .registerKotlinModule()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)

    fun load(
        env: Map<String, String> = System.getenv(),
        defaultPath: Path = Paths.get("id-runtime.yaml"),
    ): IdConfig {
        val defaults = IdConfig()
        val defaultNeed = NeedConfig()
        val yaml = readYaml(resolveConfigPath(env, defaultPath))?.id ?: IdRuntimeYamlId()

        val needs = (yaml.needs ?: emptyMap()).mapValues { (_, yamlNeed) ->
            val curveYaml = yamlNeed.responseCurve
            NeedConfig(
                enabled = yamlNeed.enabled ?: defaultNeed.enabled,
                description = yamlNeed.description ?: defaultNeed.description,
                growthRate = yamlNeed.growthRate?.takeIf { it >= 0.0 } ?: defaultNeed.growthRate,
                satisfactionDecay = yamlNeed.satisfactionDecay?.takeIf { it in 0.0..1.0 }
                    ?: defaultNeed.satisfactionDecay,
                resetFloor = yamlNeed.resetFloor?.takeIf { it in 0.0..1.0 } ?: defaultNeed.resetFloor,
                cooldownPulses = yamlNeed.cooldownPulses?.takeIf { it >= 0 } ?: defaultNeed.cooldownPulses,
                prompt = yamlNeed.prompt ?: defaultNeed.prompt,
                convergence = ConvergenceMode.fromYaml(yamlNeed.convergence),
                allowEscalation = yamlNeed.allowEscalation ?: defaultNeed.allowEscalation,
                responseCurve = if (curveYaml != null) {
                    ResponseCurveConfig(
                        type = curveYaml.type ?: "linear",
                        exponent = curveYaml.exponent,
                        steepness = curveYaml.steepness,
                        midpoint = curveYaml.midpoint,
                        scale = curveYaml.scale,
                    )
                } else {
                    defaultNeed.responseCurve
                },
                satisfactionEffectsAnyOf = yamlNeed.satisfactionEffectsAnyOf
                    ?.mapNotNull(ActionEffect::fromRaw)
                    ?.toSet()
                    ?.takeIf { it.isNotEmpty() }
                    ?: defaultNeed.satisfactionEffectsAnyOf,
                activityDecay = yamlNeed.activityDecay ?: defaultNeed.activityDecay,
            )
        }

        return IdConfig(
            enabled = readBoolean(env["PSYKE_ID_ENABLED"], yaml.enabled, defaults.enabled),
            pulseIntervalMs = readPositiveLong(
                env["PSYKE_ID_PULSE_INTERVAL_MS"],
                yaml.pulseIntervalMs,
                defaults.pulseIntervalMs
            ),
            triggerThreshold = readProbability(
                env["PSYKE_ID_TRIGGER_THRESHOLD"],
                yaml.triggerThreshold,
                defaults.triggerThreshold
            ),
            thresholdOnUrgency = readBoolean(
                env["PSYKE_ID_THRESHOLD_ON_URGENCY"],
                yaml.thresholdOnUrgency,
                defaults.thresholdOnUrgency
            ),
            maxConsecutiveDenials = readNonNegativeInt(
                env["PSYKE_ID_MAX_CONSECUTIVE_DENIALS"],
                yaml.maxConsecutiveDenials,
                defaults.maxConsecutiveDenials
            ),
            backoffPulses = readPositiveInt(
                env["PSYKE_ID_BACKOFF_PULSES"],
                yaml.backoffPulses,
                defaults.backoffPulses
            ),
            maxInFlightPulses = readPositiveInt(
                env["PSYKE_ID_MAX_IN_FLIGHT_PULSES"],
                yaml.maxInFlightPulses,
                defaults.maxInFlightPulses
            ),
            maxPendingImpulses = readPositiveInt(
                env["PSYKE_ID_MAX_PENDING_IMPULSES"],
                yaml.maxPendingImpulses,
                defaults.maxPendingImpulses
            ),
            needs = needs,
        )
    }

    private fun resolveConfigPath(env: Map<String, String>, defaultPath: Path): Path {
        val configured = env["PSYKE_ID_CONFIG_FILE"]?.trim().orEmpty()
        return if (configured.isBlank()) defaultPath else Paths.get(configured)
    }

    private fun readYaml(path: Path): IdRuntimeYamlConfig? {
        if (!Files.exists(path)) return null
        val content = Files.readString(path).trim()
        if (content.isEmpty()) return null
        return mapper.readValue<IdRuntimeYamlConfig>(content)
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
}
