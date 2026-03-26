package ai.neopsyke.session

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import ai.neopsyke.instrumentation.AgentEvents
import ai.neopsyke.instrumentation.AgentInstrumentation
import ai.neopsyke.instrumentation.NoopAgentInstrumentation
import java.io.BufferedWriter
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

/**
 * A generic record/replay channel for session recording.
 *
 * Mirrors the [ai.neopsyke.llm.CachingChatModelClient] pattern: each call
 * gets a monotonic sequence index and a content hash. During REPLAY, if the
 * hash at the current index matches the cached entry the recorded data is
 * returned; on mismatch the channel switches to passthrough permanently.
 *
 * Each channel is independent — divergence in one channel does not affect
 * others.
 */
class RecordReplayChannel(
    val channelName: String,
    val mode: SessionRecordingMode,
    private val file: Path,
    instrumentation: AgentInstrumentation = NoopAgentInstrumentation,
) : Closeable {

    @Volatile
    private var instrumentation: AgentInstrumentation = instrumentation

    private val sequenceCounter = AtomicInteger(0)
    private val cachedEntries: List<SessionRecordEntry>
    private val writer: BufferedWriter?

    @Volatile
    var passthroughMode: Boolean = false
        private set

    fun setInstrumentation(bus: AgentInstrumentation) {
        instrumentation = bus
    }

    init {
        val normalized = file.toAbsolutePath().normalize()
        cachedEntries = if (mode == SessionRecordingMode.REPLAY) {
            loadEntries(normalized)
        } else {
            emptyList()
        }
        writer = if (mode == SessionRecordingMode.RECORD) {
            normalized.parent?.let { Files.createDirectories(it) }
            Files.newBufferedWriter(normalized)
        } else {
            null
        }
        if (mode != SessionRecordingMode.OFF) {
            logger.info { "Session channel '$channelName' initialized: mode=$mode file=$normalized entries=${cachedEntries.size}" }
        }
    }

    fun nextSequenceIndex(): Int = sequenceCounter.getAndIncrement()

    /**
     * Attempt to replay a cached entry at [sequenceIndex] with the given
     * [contextHash]. Returns the cached data node if the hash matches, or
     * `null` if divergence occurred (and switches to passthrough).
     */
    fun replayOrDiverge(sequenceIndex: Int, contextHash: String): JsonNode? {
        if (mode != SessionRecordingMode.REPLAY || passthroughMode) return null

        val cached = lookupCachedEntry(sequenceIndex)
        if (cached != null && cached.hash == contextHash) {
            instrumentation.emit(
                AgentEvents.sessionChannelReplayHit(
                    channel = channelName,
                    sequenceIndex = sequenceIndex,
                )
            )
            return cached.data
        }

        val expectedHash = cached?.hash ?: "<index_exhausted>"
        val reason = if (cached == null) "index_exhausted" else "hash_mismatch"
        logger.info {
            "Session channel '$channelName' divergence at seq=$sequenceIndex reason=$reason"
        }
        passthroughMode = true
        instrumentation.emit(
            AgentEvents.sessionChannelDivergence(
                channel = channelName,
                sequenceIndex = sequenceIndex,
                expectedHash = expectedHash,
                actualHash = contextHash,
            )
        )
        return null
    }

    /**
     * Record an entry during RECORD mode.
     */
    @Synchronized
    fun recordEntry(entry: SessionRecordEntry) {
        val w = writer ?: return
        w.write(entryMapper.writeValueAsString(entry))
        w.newLine()
        w.flush()
    }

    val entryCount: Int get() = cachedEntries.size

    val totalSequenceCalls: Int get() = sequenceCounter.get()

    override fun close() {
        writer?.close()
        val total = sequenceCounter.get()
        logger.info { "Session channel '$channelName' closed: mode=$mode total_calls=$total passthrough=$passthroughMode" }
    }

    private fun lookupCachedEntry(index: Int): SessionRecordEntry? {
        if (index < 0 || index >= cachedEntries.size) return null
        return cachedEntries[index]
    }

    companion object {
        private val entryMapper = jacksonObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)

        fun hashContent(vararg parts: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val content = parts.joinToString("\n")
            val hashBytes = digest.digest(content.toByteArray(Charsets.UTF_8))
            return hashBytes.joinToString("") { "%02x".format(it) }
        }

        private fun loadEntries(path: Path): List<SessionRecordEntry> {
            if (!Files.exists(path)) {
                logger.warn { "Session recording file not found for replay: $path" }
                return emptyList()
            }
            val entries = mutableListOf<SessionRecordEntry>()
            Files.newBufferedReader(path).useLines { lines ->
                lines.forEachIndexed { lineIndex, line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty()) {
                        try {
                            entries.add(entryMapper.readValue(trimmed))
                        } catch (ex: Exception) {
                            logger.warn { "Failed to parse session entry at line $lineIndex in $path: ${ex.message}" }
                        }
                    }
                }
            }
            logger.info { "Loaded ${entries.size} session entries from $path" }
            return entries
        }
    }
}

/**
 * A single entry in a session recording JSONL file.
 */
data class SessionRecordEntry(
    @param:JsonProperty("seq") val seq: Int,
    @param:JsonProperty("hash") val hash: String,
    @param:JsonProperty("channel") val channel: String,
    @param:JsonProperty("data") val data: JsonNode,
)
