package ai.neopsyke.config

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeDefaultsConfigLoaderTest {
    @Test
    fun `loadOrCreate creates runtime defaults file with metrics db`() {
        val tempDir = Files.createTempDirectory("runtime-defaults-test")
        val configPath = tempDir.resolve("runtime-defaults.yaml")

        val loaded = RuntimeDefaultsConfigLoader.loadOrCreate(
            env = mapOf("NEOPSYKE_RUNTIME_DEFAULTS_FILE" to configPath.toString()),
            cwd = tempDir
        )

        assertEquals(".neopsyke/metrics.db", loaded.metricsDb)
        assertTrue(Files.exists(configPath))
        val resolvedDb = RuntimeDefaultsConfigLoader.resolveMetricsDbPath(
            env = mapOf("NEOPSYKE_RUNTIME_DEFAULTS_FILE" to configPath.toString()),
            cwd = tempDir
        )
        assertEquals(tempDir.resolve(".neopsyke").resolve("metrics.db").toAbsolutePath().normalize(), resolvedDb)
    }

    @Test
    fun `NEOPSYKE_METRICS_DB override wins over defaults config`() {
        val tempDir = Files.createTempDirectory("runtime-defaults-override-test")
        val configPath = tempDir.resolve("runtime-defaults.yaml")
        Files.writeString(
            configPath,
            """
            metrics_db: .neopsyke/ignored.db
            """.trimIndent()
        )

        val overridden = RuntimeDefaultsConfigLoader.resolveMetricsDbPath(
            env = mapOf(
                "NEOPSYKE_RUNTIME_DEFAULTS_FILE" to configPath.toString(),
                "NEOPSYKE_METRICS_DB" to "./custom/metrics.db"
            ),
            cwd = tempDir
        )
        assertEquals(tempDir.resolve("custom").resolve("metrics.db").toAbsolutePath().normalize(), overridden)
    }
}
