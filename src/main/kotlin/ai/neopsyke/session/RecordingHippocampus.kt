package ai.neopsyke.session

import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import mu.KotlinLogging
import ai.neopsyke.agent.memory.longterm.ConsolidationRequest
import ai.neopsyke.agent.memory.longterm.ConsolidationResult
import ai.neopsyke.agent.memory.longterm.Hippocampus
import ai.neopsyke.agent.memory.longterm.ImprintRequest
import ai.neopsyke.agent.memory.longterm.ImprintResult
import ai.neopsyke.agent.memory.longterm.MemoryCapability
import ai.neopsyke.agent.memory.longterm.MemoryHealth
import ai.neopsyke.agent.memory.longterm.MemoryItem
import ai.neopsyke.agent.memory.longterm.MemoryKind
import ai.neopsyke.agent.memory.longterm.RecallRequest
import ai.neopsyke.agent.memory.longterm.RecallResult

private val logger = KotlinLogging.logger {}
private val mapper = jacksonObjectMapper()

/**
 * Wraps a [Hippocampus] to record or replay `recall()` results.
 *
 * - **RECORD**: delegates to the real hippocampus, records each recall result.
 * - **REPLAY**: returns cached results when the recall request hash matches,
 *   switches to passthrough on divergence.
 *
 * Only `recall()` is recorded. `imprint()` and `consolidate()` always
 * delegate to the real hippocampus — they are write operations that don't
 * affect replay determinism.
 *
 * Hash strategy: SHA-256 of `cue + intent + limits.maxItems + limits.maxChars`.
 */
class RecordingHippocampus(
    private val delegate: Hippocampus,
    private val channel: RecordReplayChannel,
) : Hippocampus {

    override val providerName: String get() = delegate.providerName
    override val capabilities: Set<MemoryCapability> get() = delegate.capabilities
    override val enabled: Boolean get() = delegate.enabled

    override fun health(): MemoryHealth = delegate.health()

    override fun recall(request: RecallRequest): RecallResult {
        return when (channel.mode) {
            SessionRecordingMode.RECORD -> recordRecall(request)
            SessionRecordingMode.REPLAY -> replayRecall(request)
            SessionRecordingMode.OFF -> delegate.recall(request)
        }
    }

    override fun imprint(request: ImprintRequest): ImprintResult =
        delegate.imprint(request)

    override fun consolidate(request: ConsolidationRequest): ConsolidationResult =
        delegate.consolidate(request)

    override fun close() = delegate.close()

    private fun recordRecall(request: RecallRequest): RecallResult {
        val result = delegate.recall(request)
        val seq = channel.nextSequenceIndex()
        val hash = hashRecallRequest(request)
        channel.recordEntry(
            SessionRecordEntry(
                seq = seq,
                hash = hash,
                channel = SessionRecordingManager.CHANNEL_MEMORY_RECALL,
                data = serializeRecallResult(result),
            )
        )
        return result
    }

    private fun replayRecall(request: RecallRequest): RecallResult {
        if (channel.passthroughMode) {
            return delegate.recall(request)
        }
        val seq = channel.nextSequenceIndex()
        if (seq >= channel.entryCount) {
            logger.info { "Memory recall channel exhausted at seq=$seq, switching to live" }
            return delegate.recall(request)
        }
        val hash = hashRecallRequest(request)
        val data = channel.replayOrDiverge(seq, hash)
        if (data == null) {
            logger.info { "Memory recall channel diverged at seq=$seq, switching to live" }
            return delegate.recall(request)
        }
        val dataObj = data as? ObjectNode ?: run {
            logger.info { "Memory recall channel: unexpected data node type at seq=$seq, switching to live" }
            return delegate.recall(request)
        }
        return deserializeRecallResult(dataObj)
    }

    companion object {
        private fun hashRecallRequest(request: RecallRequest): String =
            RecordReplayChannel.hashContent(
                request.cue,
                request.intent.name,
                request.limits.maxItems.toString(),
                request.limits.maxChars.toString(),
            )

        private fun serializeRecallResult(result: RecallResult): ObjectNode {
            val node = mapper.createObjectNode()
            node.put("provider", result.provider)
            node.put("rendered_text", result.renderedText)
            node.put("hit_count", result.hitCount)
            node.put("truncated", result.truncated)
            val items = mapper.createArrayNode()
            result.items.forEach { item ->
                val itemNode = mapper.createObjectNode()
                itemNode.put("id", item.id)
                itemNode.put("kind", item.kind.name)
                itemNode.put("summary", item.summary)
                if (item.content != null) itemNode.put("content", item.content)
                if (item.score != null) itemNode.put("score", item.score)
                if (item.confidence != null) itemNode.put("confidence", item.confidence)
                if (item.timestamp != null) itemNode.put("timestamp", item.timestamp.toString())
                if (item.tags.isNotEmpty()) {
                    val tagsArr = mapper.createArrayNode()
                    item.tags.forEach { tagsArr.add(it) }
                    itemNode.set<ObjectNode>("tags", tagsArr)
                }
                if (item.eventType != null) itemNode.put("event_type", item.eventType.name)
                if (item.actionType != null) itemNode.put("action_type", item.actionType)
                items.add(itemNode)
            }
            node.set<ObjectNode>("items", items)
            return node
        }

        internal fun deserializeRecallResult(node: ObjectNode): RecallResult {
            val provider = node.path("provider").asText()
            val renderedText = node.path("rendered_text").asText()
            val hitCount = node.path("hit_count").asInt()
            val truncated = node.path("truncated").asBoolean()
            val items = node.path("items").mapNotNull { itemNode ->
                if (itemNode.isObject) {
                    MemoryItem(
                        id = itemNode.path("id").asText().ifEmpty { "" },
                        kind = try {
                            MemoryKind.valueOf(itemNode.path("kind").asText())
                        } catch (_: Exception) {
                            MemoryKind.NARRATIVE
                        },
                        summary = itemNode.path("summary").asText(),
                        content = if (itemNode.has("content")) itemNode.path("content").asText() else null,
                        score = if (itemNode.has("score")) itemNode.path("score").asDouble() else null,
                        confidence = if (itemNode.has("confidence")) itemNode.path("confidence").asDouble() else null,
                        timestamp = if (itemNode.has("timestamp")) try {
                            java.time.Instant.parse(itemNode.path("timestamp").asText())
                        } catch (_: Exception) { null } else null,
                        tags = itemNode.path("tags").mapNotNull { it.asText() },
                        eventType = if (itemNode.has("event_type")) try {
                            ai.neopsyke.agent.memory.longterm.MemoryEventType.valueOf(itemNode.path("event_type").asText())
                        } catch (_: Exception) { null } else null,
                        actionType = if (itemNode.has("action_type")) itemNode.path("action_type").asText() else null,
                    )
                } else null
            }
            return RecallResult(
                provider = provider,
                items = items,
                renderedText = renderedText,
                hitCount = hitCount,
                truncated = truncated,
            )
        }
    }
}
