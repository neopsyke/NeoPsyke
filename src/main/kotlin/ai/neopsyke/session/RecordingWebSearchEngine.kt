package ai.neopsyke.session

import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import mu.KotlinLogging
import ai.neopsyke.agent.cortex.motor.actions.websearch.WebSearchEngine
import ai.neopsyke.agent.cortex.motor.actions.websearch.WebSearchEngineHealth
import ai.neopsyke.agent.cortex.motor.actions.websearch.WebSearchResult
import ai.neopsyke.agent.cortex.motor.actions.websearch.WebSearchSource

private val logger = KotlinLogging.logger {}
private val mapper = jacksonObjectMapper()

/**
 * Wraps a [WebSearchEngine] to record or replay search results.
 *
 * - **RECORD**: delegates to the real engine, records each search result.
 * - **REPLAY**: returns cached results when the query hash matches,
 *   switches to passthrough on divergence.
 *
 * Hash strategy: SHA-256 of `query + maxResults`.
 */
class RecordingWebSearchEngine(
    private val delegate: WebSearchEngine,
    private val channel: RecordReplayChannel,
) : WebSearchEngine {

    override fun search(query: String, maxResults: Int): WebSearchResult {
        return when (channel.mode) {
            SessionRecordingMode.RECORD -> recordSearch(query, maxResults)
            SessionRecordingMode.REPLAY -> replaySearch(query, maxResults)
            SessionRecordingMode.OFF -> delegate.search(query, maxResults)
        }
    }

    override fun healthCheck(): WebSearchEngineHealth = delegate.healthCheck()

    private fun recordSearch(query: String, maxResults: Int): WebSearchResult {
        val result = delegate.search(query, maxResults)
        val seq = channel.nextSequenceIndex()
        val hash = hashSearchRequest(query, maxResults)
        channel.recordEntry(
            SessionRecordEntry(
                seq = seq,
                hash = hash,
                channel = SessionRecordingManager.CHANNEL_WEB_RESULTS,
                data = serializeSearchResult(result),
            )
        )
        return result
    }

    private fun replaySearch(query: String, maxResults: Int): WebSearchResult {
        if (channel.passthroughMode) {
            return delegate.search(query, maxResults)
        }
        val seq = channel.nextSequenceIndex()
        if (seq >= channel.entryCount) {
            logger.info { "Web search channel exhausted at seq=$seq, switching to live" }
            return delegate.search(query, maxResults)
        }
        val hash = hashSearchRequest(query, maxResults)
        val data = channel.replayOrDiverge(seq, hash)
        if (data == null) {
            logger.info { "Web search channel diverged at seq=$seq, switching to live" }
            return delegate.search(query, maxResults)
        }
        val dataObj = data as? ObjectNode ?: run {
            logger.info { "Web search channel: unexpected data node type at seq=$seq, switching to live" }
            return delegate.search(query, maxResults)
        }
        return deserializeSearchResult(dataObj)
    }

    companion object {
        private fun hashSearchRequest(query: String, maxResults: Int): String =
            RecordReplayChannel.hashContent(query, maxResults.toString())

        private fun serializeSearchResult(result: WebSearchResult): ObjectNode {
            val node = mapper.createObjectNode()
            node.put("summary", result.summary)
            val snippets = mapper.createArrayNode()
            result.snippets.forEach { snippets.add(it) }
            node.set<ObjectNode>("snippets", snippets)
            val sources = mapper.createArrayNode()
            result.sources.forEach { src ->
                val srcNode = mapper.createObjectNode()
                srcNode.put("title", src.title)
                srcNode.put("url", src.url)
                if (src.snippet != null) srcNode.put("snippet", src.snippet)
                sources.add(srcNode)
            }
            node.set<ObjectNode>("sources", sources)
            return node
        }

        internal fun deserializeSearchResult(node: ObjectNode): WebSearchResult {
            val summary = node.path("summary").asText()
            val snippets = node.path("snippets").mapNotNull { it.asText() }
            val sources = node.path("sources").mapNotNull { srcNode ->
                if (srcNode.isObject) {
                    WebSearchSource(
                        title = srcNode.path("title").asText(),
                        url = srcNode.path("url").asText(),
                        snippet = if (srcNode.has("snippet")) srcNode.path("snippet").asText() else null,
                    )
                } else null
            }
            return WebSearchResult(
                summary = summary,
                snippets = snippets,
                sources = sources,
            )
        }
    }
}
