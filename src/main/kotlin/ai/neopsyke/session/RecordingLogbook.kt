package ai.neopsyke.session

import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import mu.KotlinLogging
import ai.neopsyke.agent.memory.longterm.Logbook
import ai.neopsyke.agent.memory.longterm.LogbookEntry
import ai.neopsyke.agent.memory.longterm.LogbookQuery
import ai.neopsyke.agent.memory.longterm.LogbookRecall
import ai.neopsyke.agent.memory.longterm.MemoryEventType
import java.time.Instant

private val logger = KotlinLogging.logger {}
private val mapper = jacksonObjectMapper()

/**
 * Wraps a [Logbook] to record or replay `query()` results.
 *
 * - **RECORD**: delegates to the real logbook, records each query result.
 * - **REPLAY**: returns cached results when the query hash matches,
 *   switches to passthrough on divergence.
 *
 * Only `query()` is recorded. `record()` (writes) always delegates to the
 * real logbook — writes don't affect replay determinism for the current
 * query sequence.
 *
 * Hash strategy: SHA-256 of `keywordSearch + maxResults + eventTypes + sessionId`.
 */
class RecordingLogbook(
    private val delegate: Logbook,
    private val channel: RecordReplayChannel,
) : Logbook {

    override fun record(entry: LogbookEntry): Long = delegate.record(entry)

    override fun query(query: LogbookQuery): LogbookRecall {
        return when (channel.mode) {
            SessionRecordingMode.RECORD -> recordQuery(query)
            SessionRecordingMode.REPLAY -> replayQuery(query)
            SessionRecordingMode.OFF -> delegate.query(query)
        }
    }

    override fun pruneOlderThan(retentionDays: Int): Int = delegate.pruneOlderThan(retentionDays)
    override fun clearAll(): Int = delegate.clearAll()
    override fun close() = delegate.close()

    private fun recordQuery(query: LogbookQuery): LogbookRecall {
        val result = delegate.query(query)
        val seq = channel.nextSequenceIndex()
        val hash = hashQuery(query)
        channel.recordEntry(
            SessionRecordEntry(
                seq = seq,
                hash = hash,
                channel = SessionRecordingManager.CHANNEL_LOGBOOK_RECALL,
                data = serializeRecall(result),
            )
        )
        return result
    }

    private fun replayQuery(query: LogbookQuery): LogbookRecall {
        if (channel.passthroughMode) {
            return delegate.query(query)
        }
        val seq = channel.nextSequenceIndex()
        if (seq >= channel.entryCount) {
            logger.info { "Logbook recall channel exhausted at seq=$seq, switching to live" }
            return delegate.query(query)
        }
        val hash = hashQuery(query)
        val data = channel.replayOrDiverge(seq, hash)
        if (data == null) {
            logger.info { "Logbook recall channel diverged at seq=$seq, switching to live" }
            return delegate.query(query)
        }
        return deserializeRecall(data as ObjectNode)
    }

    companion object {
        private fun hashQuery(query: LogbookQuery): String =
            RecordReplayChannel.hashContent(
                query.keywordSearch.orEmpty(),
                query.maxResults.toString(),
                query.eventTypes?.joinToString(",") { it.name }.orEmpty(),
                query.sessionId.orEmpty(),
            )

        private fun serializeRecall(recall: LogbookRecall): ObjectNode {
            val node = mapper.createObjectNode()
            node.put("total_matched", recall.totalMatched)
            node.put("truncated", recall.truncated)
            val entries = mapper.createArrayNode()
            recall.entries.forEach { entry ->
                val e = mapper.createObjectNode()
                e.put("id", entry.id)
                e.put("ts", entry.ts.toString())
                e.put("event_type", entry.eventType.name)
                e.put("summary", entry.summary)
                if (entry.keywords.isNotEmpty()) {
                    val kw = mapper.createArrayNode()
                    entry.keywords.forEach { kw.add(it) }
                    e.set<ObjectNode>("keywords", kw)
                }
                if (entry.actionType != null) e.put("action_type", entry.actionType)
                if (entry.runId != null) e.put("run_id", entry.runId)
                if (entry.sessionId != null) e.put("session_id", entry.sessionId)
                if (entry.interlocutorId != null) e.put("interlocutor_id", entry.interlocutorId)
                entries.add(e)
            }
            node.set<ObjectNode>("entries", entries)
            return node
        }

        internal fun deserializeRecall(node: ObjectNode): LogbookRecall {
            val totalMatched = node.path("total_matched").asInt()
            val truncated = node.path("truncated").asBoolean()
            val entries = node.path("entries").mapNotNull { e ->
                if (e.isObject) {
                    LogbookEntry(
                        id = e.path("id").asLong(),
                        ts = try {
                            Instant.parse(e.path("ts").asText())
                        } catch (_: Exception) {
                            Instant.EPOCH
                        },
                        eventType = try {
                            MemoryEventType.valueOf(e.path("event_type").asText())
                        } catch (_: Exception) {
                            MemoryEventType.INPUT_RECEIVED
                        },
                        summary = e.path("summary").asText(),
                        keywords = e.path("keywords").mapNotNull { it.asText() },
                        actionType = if (e.has("action_type")) e.path("action_type").asText() else null,
                        runId = if (e.has("run_id")) e.path("run_id").asText() else null,
                        sessionId = if (e.has("session_id")) e.path("session_id").asText() else null,
                        interlocutorId = if (e.has("interlocutor_id")) e.path("interlocutor_id").asText() else null,
                    )
                } else null
            }
            return LogbookRecall(
                entries = entries,
                totalMatched = totalMatched,
                truncated = truncated,
            )
        }
    }
}
