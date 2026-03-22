package ai.neopsyke.memory.pgvector

import mu.KotlinLogging
import java.sql.DriverManager
import java.util.Locale

private val bootstrapLogger = KotlinLogging.logger {}

data class LocalPgvectorTarget(
    val host: String,
    val port: Int,
    val database: String,
)

object DockerPgvectorBootstrap {
    private const val DEFAULT_POSTGRES_PORT = 5432
    private const val POLL_INTERVAL_MS = 1_000L

    fun ensureReady(config: PgvectorMemoryProviderConfig) {
        if (config.bootstrapMode == PgvectorBootstrapMode.OFF) {
            return
        }
        val target = parseLocalTarget(config.dbUrl)
        if (isReachable(config)) {
            bootstrapLogger.info { "pgvector database already reachable at ${target.host}:${target.port}/${target.database}" }
            return
        }
        ensureDockerAvailable()
        ensureContainerRunning(config, target)
        waitUntilReachable(config)
    }

    internal fun parseLocalTarget(dbUrl: String): LocalPgvectorTarget {
        val withoutPrefix = dbUrl.removePrefix("jdbc:")
        require(withoutPrefix.startsWith("postgresql://")) {
            "Auto bootstrap only supports PostgreSQL JDBC URLs. Got: $dbUrl"
        }
        val uri = java.net.URI.create(withoutPrefix)
        val host = uri.host?.trim().orEmpty().ifBlank { "localhost" }
        val port = if (uri.port > 0) uri.port else DEFAULT_POSTGRES_PORT
        val database = uri.path.trimStart('/').ifBlank {
            throw IllegalArgumentException("JDBC URL must include a database name when auto bootstrap is enabled.")
        }
        require(isLocalHost(host)) {
            "Auto bootstrap only supports local PostgreSQL URLs. Configure a local host or set PGVECTOR_BOOTSTRAP_MODE=off."
        }
        return LocalPgvectorTarget(host = host, port = port, database = database)
    }

    internal fun buildDockerRunCommand(
        config: PgvectorMemoryProviderConfig,
        target: LocalPgvectorTarget,
    ): List<String> = listOf(
        "docker",
        "run",
        "-d",
        "--name",
        config.bootstrapContainerName,
        "-e",
        "POSTGRES_DB=${target.database}",
        "-e",
        "POSTGRES_USER=${config.dbUser}",
        "-e",
        "POSTGRES_PASSWORD=${config.dbPassword}",
        "-p",
        "${target.port}:5432",
        "-v",
        "${config.bootstrapVolumeName}:/var/lib/postgresql/data",
        config.bootstrapDockerImage,
    )

    private fun ensureDockerAvailable() {
        val result = runCommand(listOf("docker", "version", "--format", "{{.Server.Version}}"), allowFailure = true)
        require(result.exitCode == 0) {
            "PGVECTOR_BOOTSTRAP_MODE=auto but docker is not available or not running. Install/start Docker or set PGVECTOR_BOOTSTRAP_MODE=off."
        }
    }

    private fun ensureContainerRunning(config: PgvectorMemoryProviderConfig, target: LocalPgvectorTarget) {
        val running = runCommand(
            listOf("docker", "ps", "--filter", "name=^/${config.bootstrapContainerName}$", "--format", "{{.Names}}"),
            allowFailure = true,
        ).stdout.lines().any { it.trim() == config.bootstrapContainerName }
        if (running) {
            bootstrapLogger.info { "pgvector container '${config.bootstrapContainerName}' is already running; waiting for readiness." }
            return
        }

        val existing = runCommand(
            listOf("docker", "ps", "-a", "--filter", "name=^/${config.bootstrapContainerName}$", "--format", "{{.Names}}"),
            allowFailure = true,
        ).stdout.lines().any { it.trim() == config.bootstrapContainerName }

        if (existing) {
            bootstrapLogger.info { "Starting existing pgvector container '${config.bootstrapContainerName}'." }
            val start = runCommand(listOf("docker", "start", config.bootstrapContainerName), allowFailure = true)
            require(start.exitCode == 0) {
                "Failed to start existing pgvector container '${config.bootstrapContainerName}': ${start.stderr.ifBlank { start.stdout }}"
            }
            return
        }

        bootstrapLogger.info {
            "Starting Docker-managed pgvector container '${config.bootstrapContainerName}' on ${target.host}:${target.port}/${target.database}."
        }
        val create = runCommand(buildDockerRunCommand(config, target), allowFailure = true)
        require(create.exitCode == 0) {
            "Failed to start pgvector container '${config.bootstrapContainerName}': ${create.stderr.ifBlank { create.stdout }}"
        }
    }

    private fun waitUntilReachable(config: PgvectorMemoryProviderConfig) {
        val deadline = System.currentTimeMillis() + config.bootstrapStartupTimeoutMs
        var lastError: String = "unknown"
        while (System.currentTimeMillis() < deadline) {
            try {
                DriverManager.setLoginTimeout(2)
                DriverManager.getConnection(config.dbUrl, config.dbUser, config.dbPassword).use { connection ->
                    connection.createStatement().use { stmt -> stmt.execute("SELECT 1") }
                }
                bootstrapLogger.info { "pgvector database became reachable for provider startup." }
                return
            } catch (ex: Exception) {
                lastError = ex.message ?: ex::class.java.simpleName
                Thread.sleep(POLL_INTERVAL_MS)
            }
        }
        throw IllegalStateException(
            "Timed out waiting for Docker-managed pgvector to become reachable after ${config.bootstrapStartupTimeoutMs}ms ($lastError)."
        )
    }

    private fun isReachable(config: PgvectorMemoryProviderConfig): Boolean =
        try {
            DriverManager.setLoginTimeout(2)
            DriverManager.getConnection(config.dbUrl, config.dbUser, config.dbPassword).use { connection ->
                connection.createStatement().use { stmt -> stmt.execute("SELECT 1") }
            }
            true
        } catch (_: Exception) {
            false
        }

    private fun isLocalHost(host: String): Boolean {
        val normalized = host.trim().lowercase(Locale.US)
        return normalized == "localhost" || normalized == "127.0.0.1" || normalized == "::1"
    }

    private fun runCommand(command: List<String>, allowFailure: Boolean): ProcessResult {
        val process = ProcessBuilder(command)
            .redirectErrorStream(false)
            .start()
        val stdout = process.inputStream.bufferedReader().readText().trim()
        val stderr = process.errorStream.bufferedReader().readText().trim()
        val exitCode = process.waitFor()
        if (!allowFailure && exitCode != 0) {
            throw IllegalStateException("Command failed (${command.joinToString(" ")}): ${stderr.ifBlank { stdout }}")
        }
        return ProcessResult(stdout = stdout, stderr = stderr, exitCode = exitCode)
    }

    private data class ProcessResult(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
    )
}
