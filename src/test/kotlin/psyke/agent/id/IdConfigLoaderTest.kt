package psyke.agent.id

import psyke.config.IdRuntimeConfigLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdConfigLoaderTest {

    private val epsilon = 1e-9

    // ── YAML loading ────────────────────────────────────────────────────

    @Test
    fun `loads complete YAML config`() {
        val yaml = """
            id:
              enabled: true
              pulse_interval_ms: 5000
              trigger_threshold: 0.6
              threshold_on_urgency: false
              max_consecutive_denials: 3
              backoff_pulses: 5
              max_in_flight_pulses: 15
              max_pending_impulses: 2
              needs:
                learn:
                  description: "curiosity drive"
                  growth_rate: 0.01
                  satisfaction_decay: 0.5
                  reset_floor: 0.1
                  cooldown_pulses: 3
                  prompt: "Learn something!"
                  response_curve:
                    type: logarithmic
                    scale: 8.0
                  satisfaction_effects_any_of:
                    - durable_memory_saved
                  activity_decay:
                    action_executed_web_search: 0.12
        """.trimIndent()
        val config = loadFromYaml(yaml)

        assertTrue(config.enabled)
        assertEquals(5000L, config.pulseIntervalMs)
        assertEquals(0.6, config.triggerThreshold, epsilon)
        assertFalse(config.thresholdOnUrgency)
        assertEquals(3, config.maxConsecutiveDenials)
        assertEquals(5, config.backoffPulses)
        assertEquals(15, config.maxInFlightPulses)
        assertEquals(2, config.maxPendingImpulses)

        assertEquals(1, config.needs.size)
        val learn = config.needs["learn"]!!
        assertEquals("curiosity drive", learn.description)
        assertEquals(0.01, learn.growthRate, epsilon)
        assertEquals(0.5, learn.satisfactionDecay, epsilon)
        assertEquals(0.1, learn.resetFloor, epsilon)
        assertEquals(3, learn.cooldownPulses)
        assertEquals("Learn something!", learn.prompt)
        assertEquals("logarithmic", learn.responseCurve.type)
        assertEquals(8.0, learn.responseCurve.scale)
        assertEquals(setOf(psyke.agent.model.ActionEffect.DURABLE_MEMORY_SAVED), learn.satisfactionEffectsAnyOf)
        assertEquals(0.12, learn.activityDecay["action_executed_web_search"]!!, epsilon)
    }

    @Test
    fun `loads minimal YAML with defaults`() {
        val yaml = """
            id:
              enabled: true
        """.trimIndent()
        val config = loadFromYaml(yaml)

        assertTrue(config.enabled)
        assertEquals(30_000L, config.pulseIntervalMs) // default
        assertEquals(0.7, config.triggerThreshold, epsilon) // default
        assertTrue(config.thresholdOnUrgency) // default
        assertEquals(5, config.maxConsecutiveDenials) // default
        assertEquals(10, config.backoffPulses) // default
        assertEquals(20, config.maxInFlightPulses) // default
        assertEquals(1, config.maxPendingImpulses) // default
        assertTrue(config.needs.isEmpty())
    }

    @Test
    fun `missing file returns defaults`() {
        val config = IdRuntimeConfigLoader.load(
            env = emptyMap(),
            defaultPath = Path.of("/nonexistent/path/id-runtime.yaml"),
        )

        assertFalse(config.enabled) // default is false
        assertEquals(30_000L, config.pulseIntervalMs)
    }

    @Test
    fun `empty YAML returns defaults`() {
        val config = loadFromYaml("")

        assertFalse(config.enabled) // default
        assertEquals(30_000L, config.pulseIntervalMs)
    }

    // ── Env var overrides ───────────────────────────────────────────────

    @Test
    fun `env vars override YAML values`() {
        val yaml = """
            id:
              enabled: false
              pulse_interval_ms: 5000
              trigger_threshold: 0.6
        """.trimIndent()

        val env = mapOf(
            "PSYKE_ID_ENABLED" to "true",
            "PSYKE_ID_PULSE_INTERVAL_MS" to "10000",
            "PSYKE_ID_TRIGGER_THRESHOLD" to "0.8",
        )
        val config = loadFromYaml(yaml, env)

        assertTrue(config.enabled, "Env should override YAML")
        assertEquals(10_000L, config.pulseIntervalMs)
        assertEquals(0.8, config.triggerThreshold, epsilon)
    }

    @Test
    fun `env var boolean parsing accepts various formats`() {
        for (trueVal in listOf("true", "1", "yes", "TRUE", "Yes")) {
            val config = loadFromYaml("", mapOf("PSYKE_ID_ENABLED" to trueVal))
            assertTrue(config.enabled, "Should parse '$trueVal' as true")
        }
        for (falseVal in listOf("false", "0", "no", "FALSE", "No")) {
            val config = loadFromYaml("id:\n  enabled: true", mapOf("PSYKE_ID_ENABLED" to falseVal))
            assertFalse(config.enabled, "Should parse '$falseVal' as false")
        }
    }

    @Test
    fun `invalid env var values fall back to YAML`() {
        val yaml = """
            id:
              pulse_interval_ms: 5000
              trigger_threshold: 0.6
        """.trimIndent()
        val env = mapOf(
            "PSYKE_ID_PULSE_INTERVAL_MS" to "not_a_number",
            "PSYKE_ID_TRIGGER_THRESHOLD" to "2.0",  // out of range [0,1]
        )
        val config = loadFromYaml(yaml, env)

        assertEquals(5000L, config.pulseIntervalMs, "Should fall back to YAML on invalid env")
        assertEquals(0.6, config.triggerThreshold, epsilon, "Should fall back to YAML when out of range")
    }

    @Test
    fun `negative pulse interval in env falls back`() {
        val config = loadFromYaml("", mapOf("PSYKE_ID_PULSE_INTERVAL_MS" to "-1"))
        assertEquals(30_000L, config.pulseIntervalMs, "Negative should fall back to default")
    }

    @Test
    fun `PSYKE_ID_CONFIG_FILE env var overrides default path`() {
        val yaml = """
            id:
              enabled: true
              pulse_interval_ms: 7777
        """.trimIndent()
        val tempFile = Files.createTempFile("id-test-config-", ".yaml")
        try {
            Files.writeString(tempFile, yaml)

            val config = IdRuntimeConfigLoader.load(
                env = mapOf("PSYKE_ID_CONFIG_FILE" to tempFile.toString()),
                defaultPath = Path.of("/nonexistent"),
            )
            assertTrue(config.enabled)
            assertEquals(7777L, config.pulseIntervalMs)
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    // ── Need config details ─────────────────────────────────────────────

    @Test
    fun `loads multiple needs with different response curves`() {
        val yaml = """
            id:
              enabled: true
              needs:
                be-useful:
                  growth_rate: 0.005
                  prompt: "be useful"
                  response_curve:
                    type: power
                    exponent: 3.0
                interact:
                  growth_rate: 0.003
                  prompt: "interact"
                  response_curve:
                    type: sigmoid
                    steepness: 10.0
                    midpoint: 0.5
                learn:
                  growth_rate: 0.008
                  prompt: "learn"
                  response_curve:
                    type: logarithmic
                    scale: 10.0
        """.trimIndent()
        val config = loadFromYaml(yaml)

        assertEquals(3, config.needs.size)
        assertEquals("power", config.needs["be-useful"]!!.responseCurve.type)
        assertEquals(3.0, config.needs["be-useful"]!!.responseCurve.exponent)
        assertEquals("sigmoid", config.needs["interact"]!!.responseCurve.type)
        assertEquals(10.0, config.needs["interact"]!!.responseCurve.steepness)
        assertEquals(0.5, config.needs["interact"]!!.responseCurve.midpoint)
        assertEquals("logarithmic", config.needs["learn"]!!.responseCurve.type)
        assertEquals(10.0, config.needs["learn"]!!.responseCurve.scale)
    }

    @Test
    fun `invalid need values fall back to defaults`() {
        val yaml = """
            id:
              needs:
                test:
                  growth_rate: -0.5
                  satisfaction_decay: 1.5
                  reset_floor: -0.1
                  cooldown_pulses: -3
        """.trimIndent()
        val config = loadFromYaml(yaml)
        val need = config.needs["test"]!!

        // All invalid values should fall back to NeedConfig defaults
        assertEquals(0.005, need.growthRate, epsilon, "Negative growth_rate should fall back")
        assertEquals(0.8, need.satisfactionDecay, epsilon, "Out-of-range satisfaction_decay should fall back")
        assertEquals(0.0, need.resetFloor, epsilon, "Negative reset_floor should fall back")
        assertEquals(5, need.cooldownPulses, "Negative cooldown should fall back")
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun loadFromYaml(yaml: String, env: Map<String, String> = emptyMap()): IdConfig {
        val tempFile = Files.createTempFile("id-test-", ".yaml")
        try {
            Files.writeString(tempFile, yaml)
            return IdRuntimeConfigLoader.load(env = env, defaultPath = tempFile)
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }
}
