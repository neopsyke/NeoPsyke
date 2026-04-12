package ai.neopsyke.llm

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
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

enum class LlmCacheMode {
    RECORD,
    REPLAY,
    OFF;

    companion object {
        fun parse(value: String?): LlmCacheMode =
            when (value?.trim()?.lowercase()) {
                "record" -> RECORD
                "replay" -> REPLAY
                else -> OFF
            }
    }
}

data class LlmCacheEntry(
    @param:JsonProperty("seq") val sequenceIndex: Int,
    @param:JsonProperty("hash") val messagesHash: String,
    @param:JsonProperty("actor") val actor: String = "",
    @param:JsonProperty("call_site") val callSite: String = "",
    @param:JsonProperty("model") val model: String = "",
    @param:JsonProperty("content") val content: String,
    @param:JsonProperty("finish_reason") val finishReason: String? = null,
    @param:JsonProperty("completion_id") val completionId: String? = null,
    @param:JsonProperty("prompt_tokens") val promptTokens: Int? = null,
    @param:JsonProperty("completion_tokens") val completionTokens: Int? = null,
    @param:JsonProperty("total_tokens") val totalTokens: Int? = null,
)

private val cacheMapper = jacksonObjectMapper()
    .setSerializationInclusion(JsonInclude.Include.NON_NULL)

class LlmCacheManager(
    val mode: LlmCacheMode,
    private val cacheFile: Path,
    private val instrumentation: AgentInstrumentation = NoopAgentInstrumentation,
) : Closeable {
    private val sequenceCounter = AtomicInteger(0)
    private val cachedEntries: List<LlmCacheEntry>
    private val writer: BufferedWriter?
    @Volatile
    var passthroughMode: Boolean = false
        private set

    init {
        val normalized = cacheFile.toAbsolutePath().normalize()
        cachedEntries = if (mode == LlmCacheMode.REPLAY) {
            loadEntries(normalized)
        } else {
            emptyList()
        }
        writer = if (mode == LlmCacheMode.RECORD) {
            normalized.parent?.let { Files.createDirectories(it) }
            Files.newBufferedWriter(normalized)
        } else {
            null
        }
        if (mode != LlmCacheMode.OFF) {
            logger.info { "LLM cache manager initialized: mode=$mode file=$normalized entries=${cachedEntries.size}" }
        }
    }

    fun wrapClient(delegate: ChatModelClient): ChatModelClient {
        if (mode == LlmCacheMode.OFF) return delegate
        return CachingChatModelClient(delegate, this)
    }

    fun nextSequenceIndex(): Int = sequenceCounter.getAndIncrement()

    fun lookupCachedEntry(index: Int): LlmCacheEntry? {
        if (index < 0 || index >= cachedEntries.size) return null
        return cachedEntries[index]
    }

    fun switchToPassthrough(
        sequenceIndex: Int,
        actor: String,
        callSite: String,
        expectedHash: String,
        actualHash: String,
    ) {
        passthroughMode = true
        instrumentation.emit(
            AgentEvents.llmCacheDivergence(
                sequenceIndex = sequenceIndex,
                actor = actor,
                callSite = callSite,
                expectedHash = expectedHash,
                actualHash = actualHash
            )
        )
    }

    fun emitCacheHit(sequenceIndex: Int, actor: String, callSite: String) {
        instrumentation.emit(
            AgentEvents.llmCacheHit(
                sequenceIndex = sequenceIndex,
                actor = actor,
                callSite = callSite
            )
        )
    }

    @Synchronized
    fun recordEntry(entry: LlmCacheEntry) {
        val w = writer ?: return
        w.write(cacheMapper.writeValueAsString(entry))
        w.newLine()
        w.flush()
    }

    override fun close() {
        writer?.close()
        val total = sequenceCounter.get()
        logger.info { "LLM cache manager closed: mode=$mode total_calls=$total passthrough=$passthroughMode" }
    }

    companion object {
        private fun loadEntries(path: Path): List<LlmCacheEntry> {
            if (!Files.exists(path)) {
                logger.warn { "LLM cache file not found for replay: $path" }
                return emptyList()
            }
            val entries = mutableListOf<LlmCacheEntry>()
            Files.newBufferedReader(path).useLines { lines ->
                lines.forEachIndexed { lineIndex, line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty()) {
                        try {
                            entries.add(cacheMapper.readValue(trimmed))
                        } catch (ex: Exception) {
                            logger.warn { "Failed to parse LLM cache entry at line $lineIndex: ${ex.message}" }
                        }
                    }
                }
            }
            logger.info { "Loaded ${entries.size} LLM cache entries from $path" }
            return entries
        }

        private val hashDumpCounter = AtomicInteger(0)

        fun hashMessages(messages: List<ChatMessage>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val content = messages.joinToString("\n") { "${it.role.apiValue}:${it.content}" }
            val hashBytes = digest.digest(content.toByteArray(Charsets.UTF_8))
            val hash = hashBytes.joinToString("") { "%02x".format(it) }
            // Debug: dump full hash content to file for diffing
            val dumpDir = System.getenv("NEOPSYKE_LLM_HASH_DUMP_DIR")
            if (!dumpDir.isNullOrBlank()) {
                try {
                    val dir = Path.of(dumpDir)
                    Files.createDirectories(dir)
                    val seq = hashDumpCounter.getAndIncrement()
                    Files.writeString(dir.resolve("hash-$seq-$hash.txt"), content)
                } catch (ex: Exception) {
                    logger.warn { "Failed to dump hash content: ${ex.message}" }
                }
            }
            return hash
        }
    }
}

class CachingChatModelClient(
    private val delegate: ChatModelClient,
    private val manager: LlmCacheManager,
) : ChatModelClient {
    override val modelName: String
        get() = delegate.modelName

    override fun chat(messages: List<ChatMessage>, options: ChatRequestOptions): ChatCompletion {
        val index = manager.nextSequenceIndex()
        val hash = LlmCacheManager.hashMessages(messages)
        val metadata = options.metadata

        if (manager.mode == LlmCacheMode.REPLAY && !manager.passthroughMode) {
            val cached = manager.lookupCachedEntry(index)
            if (cached != null && cached.messagesHash == hash) {
                manager.emitCacheHit(index, metadata.actor, metadata.callSite)
                return ChatCompletion(
                    content = cached.content,
                    model = cached.model.ifBlank { modelName },
                    finishReason = cached.finishReason,
                    id = cached.completionId,
                    usage = ChatUsage(
                        promptTokens = cached.promptTokens,
                        completionTokens = cached.completionTokens,
                        totalTokens = cached.totalTokens
                    )
                )
            }
            val expectedHash = cached?.messagesHash ?: "<index_exhausted>"
            val reason = if (cached == null) "index_exhausted" else "hash_mismatch"
            logger.info {
                "LLM cache divergence at seq=$index reason=$reason actor=${metadata.actor} callSite=${metadata.callSite}"
            }
            manager.switchToPassthrough(
                sequenceIndex = index,
                actor = metadata.actor,
                callSite = metadata.callSite,
                expectedHash = expectedHash,
                actualHash = hash
            )
        }

        val completion = delegate.chat(messages, options)

        if (manager.mode == LlmCacheMode.RECORD) {
            manager.recordEntry(
                LlmCacheEntry(
                    sequenceIndex = index,
                    messagesHash = hash,
                    actor = metadata.actor,
                    callSite = metadata.callSite,
                    model = completion.model,
                    content = completion.content,
                    finishReason = completion.finishReason,
                    completionId = completion.id,
                    promptTokens = completion.usage?.promptTokens,
                    completionTokens = completion.usage?.completionTokens,
                    totalTokens = completion.usage?.totalTokens
                )
            )
        }

        return completion
    }

    override fun close() {
        delegate.close()
    }
}
