package ai.neopsyke.agent.memory.provider

import ai.neopsyke.config.DefaultMemoryProviderConfig
import ai.neopsyke.agent.cortex.motor.actions.mcp.McpStdioClient
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import mu.KotlinLogging
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

private val installerLogger = KotlinLogging.logger {}

class DefaultMemoryProviderInstaller(
    private val httpClientFactory: (Long) -> OkHttpClient = { timeoutMs ->
        OkHttpClient.Builder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
    }
) {
    private val mapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun ensureInstalled(config: DefaultMemoryProviderConfig) {
        if (!config.bootstrapEnabled) {
            return
        }
        val jarPath = extractJarPath(config.command)
            ?: throw IllegalStateException(
                "Default memory provider bootstrap requires a java -jar command. command=${config.command}"
            )
        val release = fetchReleaseMetadata(config)
        val jarAsset = release.assets.firstOrNull { it.name.endsWith("-all.jar") }
            ?: throw IOException("Provider release ${release.tagName} does not include an -all.jar asset.")
        val expectedSha256 = jarAsset.digest?.removePrefix("sha256:")
            ?: resolveChecksumFromAsset(config, release, jarAsset.name)
        if (Files.exists(jarPath) && sha256(jarPath).equals(expectedSha256, ignoreCase = true)) {
            return
        }

        Files.createDirectories(jarPath.parent)
        val versionDir = jarPath.parent.parent.resolve(release.tagName)
        Files.createDirectories(versionDir)
        val versionJarPath = versionDir.resolve(jarAsset.name)

        downloadTo(config, jarAsset.browserDownloadUrl, versionJarPath)
        verifyChecksum(versionJarPath, expectedSha256)

        Files.copy(versionJarPath, jarPath, StandardCopyOption.REPLACE_EXISTING)
        Files.writeString(
            jarPath.parent.resolve("provider-install.json"),
            mapper.writeValueAsString(
                mapOf(
                    "provider" to config.provider,
                    "tag" to release.tagName,
                    "asset" to jarAsset.name,
                    "jarPath" to jarPath.toAbsolutePath().toString(),
                    "sha256" to expectedSha256,
                )
            )
        )
        installerLogger.info {
            "Installed ${config.provider} ${release.tagName} to ${jarPath.toAbsolutePath()}"
        }
    }

    private fun fetchReleaseMetadata(config: DefaultMemoryProviderConfig): GitHubRelease {
        val request = Request.Builder()
            .url(config.releaseApiUrl)
            .header("Accept", "application/vnd.github+json")
            .get()
            .build()
        return httpClientFactory(config.downloadTimeoutMs).newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Provider release metadata request failed with ${response.code}.")
            }
            mapper.readValue(response.body?.string().orEmpty(), GitHubRelease::class.java)
        }
    }

    private fun resolveChecksumFromAsset(
        config: DefaultMemoryProviderConfig,
        release: GitHubRelease,
        assetName: String,
    ): String {
        val checksumAsset = release.assets.firstOrNull { it.name == "SHA256SUMS" }
            ?: throw IOException("Provider release ${release.tagName} is missing SHA256SUMS.")
        val temp = Files.createTempFile("neopsyke-memory-sha256sums", ".txt")
        try {
            downloadTo(config, checksumAsset.browserDownloadUrl, temp)
            val line = Files.readAllLines(temp).firstOrNull { it.contains(assetName) }
                ?: throw IOException("SHA256SUMS does not contain an entry for $assetName.")
            return line.substringBefore(' ').trim()
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun downloadTo(config: DefaultMemoryProviderConfig, url: String, target: Path) {
        val temp = target.resolveSibling("${target.fileName}.tmp")
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/octet-stream")
            .get()
            .build()
        httpClientFactory(config.downloadTimeoutMs).newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Provider artifact download failed with ${response.code} for $url")
            }
            response.body?.byteStream()?.use { input ->
                Files.newOutputStream(temp).use { output -> input.copyTo(output) }
            } ?: throw IOException("Provider artifact download returned no body for $url")
        }
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun verifyChecksum(path: Path, expectedSha256: String) {
        val actual = sha256(path)
        if (!actual.equals(expectedSha256, ignoreCase = true)) {
            Files.deleteIfExists(path)
            throw IOException("Checksum verification failed for $path expected=$expectedSha256 actual=$actual")
        }
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun extractJarPath(command: String): Path? {
        val parsed = McpStdioClient.parseCommand(command)
        val jarIndex = parsed.indexOf("-jar")
        if (jarIndex < 0 || jarIndex + 1 >= parsed.size) {
            return null
        }
        return Path.of(parsed[jarIndex + 1])
    }

    data class GitHubRelease(
        @JsonProperty("tag_name")
        val tagName: String,
        val assets: List<GitHubReleaseAsset>,
    )

    data class GitHubReleaseAsset(
        val name: String,
        @JsonProperty("browser_download_url")
        val browserDownloadUrl: String,
        val digest: String? = null,
    )
}
