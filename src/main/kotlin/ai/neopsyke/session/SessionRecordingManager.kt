package ai.neopsyke.session

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import ai.neopsyke.instrumentation.AgentInstrumentation
import ai.neopsyke.instrumentation.NoopAgentInstrumentation
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
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
 * Context captured from the first recorded signal. Used during replay so the
 * freud-live runtime adopts the original session's identity (source, session ID,
 * interlocutor, security posture) instead of using hardcoded defaults.
 */
data class RecordedContext(
    @param:JsonProperty("source") val source: String,
    @param:JsonProperty("session_id") val sessionId: String,
    @param:JsonProperty("interlocutor_id") val interlocutorId: String,
    @param:JsonProperty("instruction_trust") val instructionTrust: String,
    @param:JsonProperty("channel_surface") val channelSurface: String = "DIRECT",
    @param:JsonProperty("channel_transport") val channelTransport: String = "CHAT",
    @param:JsonProperty("principal_role") val principalRole: String = "OWNER",
    @param:JsonProperty("assignments_enabled") val assignmentsEnabled: Boolean = false,
)

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
    val memoryRecall: RecordReplayChannel = createChannel(CHANNEL_MEMORY_RECALL, MEMORY_RECALL_FILE)
    val webResults: RecordReplayChannel = createChannel(CHANNEL_WEB_RESULTS, WEB_RESULTS_FILE)
    val actionControl: RecordReplayChannel = createChannel(CHANNEL_ACTION_CONTROL, ACTION_CONTROL_FILE)
    val logbookRecall: RecordReplayChannel = createChannel(CHANNEL_LOGBOOK_RECALL, LOGBOOK_RECALL_FILE)
    val approvalFlow: RecordReplayChannel = createChannel(CHANNEL_APPROVAL_FLOW, APPROVAL_FLOW_FILE)

    private val channels: List<RecordReplayChannel> = listOf(
        signals,
        memoryRecall,
        webResults,
        actionControl,
        logbookRecall,
        approvalFlow,
    )

    /**
     * The conversation context from the original recording, loaded from
     * `recording-context.json` during REPLAY. Null during RECORD (until
     * [captureRecordingContext] is called) or when no context file exists.
     */
    val recordedContext: RecordedContext? = if (mode == SessionRecordingMode.REPLAY) {
        loadRecordedContext(sessionDir)
    } else {
        null
    }

    private val contextCaptured = AtomicBoolean(false)

    /**
     * Capture the conversation context from the first signal during
     * recording. Called by [RecordingSignalSource] on the first recorded
     * stimulus. Subsequent calls are ignored (atomic).
     */
    fun captureRecordingContext(context: RecordedContext) {
        if (mode != SessionRecordingMode.RECORD || !contextCaptured.compareAndSet(false, true)) return
        try {
            val path = sessionDir.resolve(RECORDING_CONTEXT_FILE)
            Files.newBufferedWriter(path).use { w ->
                w.write(manifestMapper.writerWithDefaultPrettyPrinter().writeValueAsString(context))
            }
            logger.info { "Recording context captured: source=${context.source} session=${context.sessionId} interlocutor=${context.interlocutorId}" }
        } catch (ex: Exception) {
            logger.warn(ex) { "Failed to write recording context" }
        }
    }

    /**
     * Late-bind the instrumentation bus so session replay/hit events
     * appear in events.jsonl. Call after the [InstrumentationBus] is created.
     */
    fun setInstrumentation(bus: AgentInstrumentation) {
        channels.forEach { it.setInstrumentation(bus) }
    }

    init {
        if (mode != SessionRecordingMode.OFF) {
            Files.createDirectories(sessionDir)
            logger.info { "Session recording manager initialized: mode=$mode dir=$sessionDir" }
            if (recordedContext != null) {
                logger.info { "Loaded recording context: source=${recordedContext.source} session=${recordedContext.sessionId} interlocutor=${recordedContext.interlocutorId}" }
            }
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
        const val CHANNEL_MEMORY_RECALL: String = "memory_recall"
        const val CHANNEL_WEB_RESULTS: String = "web_results"
        const val CHANNEL_ACTION_CONTROL: String = "action_control"
        const val CHANNEL_LOGBOOK_RECALL: String = "logbook_recall"
        const val CHANNEL_APPROVAL_FLOW: String = "approval_flow"
        const val SIGNALS_FILE: String = "signals.jsonl"
        const val MEMORY_RECALL_FILE: String = "memory-recall.jsonl"
        const val WEB_RESULTS_FILE: String = "web-results.jsonl"
        const val ACTION_CONTROL_FILE: String = "action-control.jsonl"
        const val LOGBOOK_RECALL_FILE: String = "logbook-recall.jsonl"
        const val APPROVAL_FLOW_FILE: String = "approval-flow.jsonl"
        const val MANIFEST_FILE: String = "session-manifest.json"
        const val RECORDING_CONTEXT_FILE: String = "recording-context.json"

        /** Environment variable names. */
        const val ENV_SESSION_RECORDING_MODE: String = "NEOPSYKE_SESSION_RECORDING_MODE"
        const val ENV_SESSION_RECORDING_DIR: String = "NEOPSYKE_SESSION_RECORDING_DIR"

        private val manifestMapper = jacksonObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)

        private fun loadRecordedContext(sessionDir: Path): RecordedContext? {
            val path = sessionDir.resolve(RECORDING_CONTEXT_FILE)
            if (!Files.exists(path)) return null
            return try {
                manifestMapper.readValue<RecordedContext>(Files.readString(path))
            } catch (ex: Exception) {
                logger.warn(ex) { "Failed to load recording context from $path" }
                null
            }
        }

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
