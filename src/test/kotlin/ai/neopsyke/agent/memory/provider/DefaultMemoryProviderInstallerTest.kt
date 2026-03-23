package ai.neopsyke.agent.memory.provider

import ai.neopsyke.config.DefaultMemoryProviderConfig
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultMemoryProviderInstallerTest {
    @Test
    fun `installer downloads managed provider jar and writes install manifest`() {
        val server = MockWebServer()
        val jarBytes = "provider-jar".encodeToByteArray()
        val jarSha = sha256(jarBytes)
        server.start()

        val releaseJson = """
            {
              "tag_name": "v0.1.0",
              "assets": [
                {
                  "name": "neopsyke-pgvector-memory-0.1.0-all.jar",
                  "browser_download_url": "${server.url("/downloads/provider.jar")}",
                  "digest": "sha256:$jarSha"
                }
              ]
            }
        """.trimIndent()
        server.enqueue(MockResponse().setBody(releaseJson))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(jarBytes)))

        val tempDir = Files.createTempDirectory("memory-provider-installer-test")
        try {
            val jarPath = tempDir.resolve(".neopsyke/providers/neopsyke-pgvector-memory/current/neopsyke-pgvector-memory-all.jar")
            val config = DefaultMemoryProviderConfig(
                command = "java -jar $jarPath --transport=http --port=7841",
                releaseApiUrl = server.url("/release").toString(),
                downloadTimeoutMs = 5_000,
            )

            DefaultMemoryProviderInstaller().ensureInstalled(config)

            assertTrue(Files.exists(jarPath))
            assertEquals(jarSha, sha256(Files.readAllBytes(jarPath)))
            val manifest = jarPath.parent.resolve("provider-install.json")
            assertTrue(Files.exists(manifest))
            val manifestText = Files.readString(manifest)
            assertTrue(manifestText.contains("\"tag\":\"v0.1.0\""))
            assertTrue(manifestText.contains("\"provider\":\"neopsyke-pgvector-memory\""))
        } finally {
            server.shutdown()
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
