package ai.neopsyke.session

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import mu.KotlinLogging
import ai.neopsyke.instrumentation.AgentInstrumentation
import ai.neopsyke.instrumentation.NoopAgentInstrumentation
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

enum class SessionRecordingMode {
    RECORD,
    REPLAY,
    OFF;

    companion object {
        fun parse(value: String?): SessionRecordingMode =
            when (value?.trim()?.lowercase()) {
                "record" -> RECORD
                "replay" -> REPLAY
                else -> OFF
            }
    }
}

/**
 * Central coordinator for session recording and replay.
 *
 * Manages per-channel [RecordReplayChannel] instances and writes a
 * session manifest on close. Each channel has its own JSONL file and
 * diverges independently.
 *
 * Environment variables:
 * - `NEOPSYKE_SESSION_RECORDING_MODE`: record / replay / off
 * - `NEOPSYKE_SESSION_RECORDING_DIR`: path to session directory
 */
class SessionRecordingManager(
    val mode: SessionRecordingMode,
    val sessionDir: Path,
    private val instrumentation: AgentInstrumentation = NoopAgentInstrumentation,
) : Closeable {

    val signals: RecordReplayChannel = createChannel(CHANNEL_SIGNALS, SIGNALS_FILE)

    private val channels: List<RecordReplayChannel> = listOf(signals)

    init {
        if (mode != SessionRecordingMode.OFF) {
            Files.createDirectories(sessionDir)
            logger.info { "Session recording manager initialized: mode=$mode dir=$sessionDir" }
        }
    }

    /**
     * Write a session manifest summarizing the recording.
     */
    fun writeManifest(gitSha: String? = null) {
        if (mode != SessionRecordingMode.RECORD) return
        val manifest = buildMap<String, Any?> {
            put("version", MANIFEST_VERSION)
            put("mode", mode.name)
            put("session_dir", sessionDir.toString())
            put("git_sha", gitSha)
            put("channels", channels.associate { ch ->
                ch.channelName to mapOf(
                    "entry_count" to ch.totalSequenceCalls,
                    "passthrough" to ch.passthroughMode,
                )
            })
        }
        val manifestPath = sessionDir.resolve(MANIFEST_FILE)
        Files.newBufferedWriter(manifestPath).use { w ->
            w.write(manifestMapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest))
        }
        logger.info { "Session manifest written to $manifestPath" }
    }

    override fun close() {
        channels.forEach { it.close() }
        if (mode == SessionRecordingMode.RECORD) {
            writeManifest()
        }
        logger.info { "Session recording manager closed: mode=$mode" }
    }

    private fun createChannel(name: String, fileName: String): RecordReplayChannel =
        RecordReplayChannel(
            channelName = name,
            mode = mode,
            file = sessionDir.resolve(fileName),
            instrumentation = instrumentation,
        )

    companion object {
        const val MANIFEST_VERSION: Int = 1

        const val CHANNEL_SIGNALS: String = "signals"
        const val SIGNALS_FILE: String = "signals.jsonl"
        const val MANIFEST_FILE: String = "session-manifest.json"

        /** Environment variable names. */
        const val ENV_SESSION_RECORDING_MODE: String = "NEOPSYKE_SESSION_RECORDING_MODE"
        const val ENV_SESSION_RECORDING_DIR: String = "NEOPSYKE_SESSION_RECORDING_DIR"

        private val manifestMapper = jacksonObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)

        /**
         * Resolve session recording configuration from environment variables.
         * Returns `null` if mode is OFF or env vars are not set.
         */
        fun fromEnvironment(
            instrumentation: AgentInstrumentation = NoopAgentInstrumentation,
        ): SessionRecordingManager? {
            val mode = SessionRecordingMode.parse(System.getenv(ENV_SESSION_RECORDING_MODE))
            if (mode == SessionRecordingMode.OFF) return null
            val dir = System.getenv(ENV_SESSION_RECORDING_DIR)
            if (dir.isNullOrBlank()) {
                logger.warn { "$ENV_SESSION_RECORDING_MODE=$mode but $ENV_SESSION_RECORDING_DIR is not set" }
                return null
            }
            return SessionRecordingManager(
                mode = mode,
                sessionDir = Path.of(dir),
                instrumentation = instrumentation,
            )
        }
    }
}
