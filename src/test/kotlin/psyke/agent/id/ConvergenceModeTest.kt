package psyke.agent.id

import psyke.config.IdRuntimeConfigLoader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class ConvergenceModeTest {

    @Test
    fun `NeedConfig defaults to CONTACT_USER convergence`() {
        val config = NeedConfig()
        assertEquals(ConvergenceMode.CONTACT_USER, config.convergence)
        assertEquals(false, config.allowEscalation)
    }

    @Test
    fun `ConvergenceMode fromYaml parses known values`() {
        assertEquals(ConvergenceMode.INTERNALIZE, ConvergenceMode.fromYaml("internalize"))
        assertEquals(ConvergenceMode.INTERNALIZE, ConvergenceMode.fromYaml("INTERNALIZE"))
        assertEquals(ConvergenceMode.INTERNALIZE, ConvergenceMode.fromYaml(" Internalize "))
        assertEquals(ConvergenceMode.CONTACT_USER, ConvergenceMode.fromYaml("contact_user"))
        assertEquals(ConvergenceMode.CONTACT_USER, ConvergenceMode.fromYaml("CONTACT_USER"))
    }

    @Test
    fun `ConvergenceMode fromYaml defaults to CONTACT_USER for unknown values`() {
        assertEquals(ConvergenceMode.CONTACT_USER, ConvergenceMode.fromYaml(null))
        assertEquals(ConvergenceMode.CONTACT_USER, ConvergenceMode.fromYaml(""))
        assertEquals(ConvergenceMode.CONTACT_USER, ConvergenceMode.fromYaml("unknown"))
    }

    @Test
    fun `YAML loader parses convergence and allowEscalation`() {
        val yaml = """
            id:
              enabled: true
              pulse_interval_ms: 10000
              trigger_threshold: 0.5
              needs:
                test-need:
                  convergence: internalize
                  allow_escalation: true
                  prompt: "test"
                default-need:
                  prompt: "test2"
        """.trimIndent()

        val tmpFile = Files.createTempFile("id-test-", ".yaml")
        try {
            Files.writeString(tmpFile, yaml)
            val config = IdRuntimeConfigLoader.load(
                env = emptyMap(),
                defaultPath = tmpFile,
            )
            val testNeed = config.needs["test-need"]!!
            assertEquals(ConvergenceMode.INTERNALIZE, testNeed.convergence)
            assertEquals(true, testNeed.allowEscalation)

            val defaultNeed = config.needs["default-need"]!!
            assertEquals(ConvergenceMode.CONTACT_USER, defaultNeed.convergence)
            assertEquals(false, defaultNeed.allowEscalation)
        } finally {
            Files.deleteIfExists(tmpFile)
        }
    }

    @Test
    fun `Id exposes needConfig lookup`() {
        val needConfig = NeedConfig(
            enabled = true,
            convergence = ConvergenceMode.INTERNALIZE,
            allowEscalation = true,
            prompt = "test",
        )
        val config = IdConfig(
            enabled = true,
            needs = mapOf("my-need" to needConfig),
        )
        val id = Id(
            config = config,
            instrumentation = psyke.instrumentation.NoopAgentInstrumentation,
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()),
            enqueueImpulse = { false },
            hasPendingWork = { false },
        )

        val result = id.needConfig("my-need")
        assertEquals(ConvergenceMode.INTERNALIZE, result?.convergence)
        assertEquals(true, result?.allowEscalation)
        assertEquals(null, id.needConfig("nonexistent"))
    }
}
