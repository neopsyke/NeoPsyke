package ai.neopsyke.agent.memory.provider

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import mu.KotlinLogging
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ai.neopsyke.agent.memory.longterm.*
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

private val httpProviderLogger = KotlinLogging.logger {}
private val jsonMediaType = "application/json".toMediaType()

class HttpMemoryProviderClient(
    private val baseUrl: String,
    private val callTimeoutMs: Long,
    private val managedProcess: ManagedHttpMemoryProviderProcess? = null,
) : MemoryProviderClient, MemoryProviderAdminClient {
    override val providerName: String = "neopsyke_pgvector_http"
    override val capabilities: Set<MemoryCapability> = setOf(
        MemoryCapability.SEMANTIC_RECALL,
        MemoryCapability.NARRATIVE_IMPRINT,
        MemoryCapability.FACT_IMPRINT,
        MemoryCapability.RELATION_IMPRINT,
    )

    private val mapper = ObjectMapper()
        .registerKotlinModule()
        .registerModule(JavaTimeModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private val client = OkHttpClient.Builder()
        .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    override fun health(): MemoryHealth =
        get("/health", MemoryHealth::class.java)

    override fun recall(request: RecallRequest, namespace: String): RecallResult {
        val payload = linkedMapOf<String, Any?>(
            "namespace" to namespace,
            "cue" to request.cue,
            "intent" to request.intent.name,
            "maxItems" to request.maxItems,
            "maxChars" to request.maxChars,
            "sessionId" to request.context.sessionId,
            "interlocutorId" to request.context.interlocutorId,
            "activeGoalIds" to request.context.activeGoalIds,
        )
        return post("/v1/recall", payload, RecallResult::class.java)
    }

    override fun imprint(request: ImprintRequest, namespace: String): ImprintResult {
        val payload = when (request) {
            is NarrativeImprint -> linkedMapOf(
                "type" to "narrative",
                "namespace" to namespace,
                "summary" to request.summary,
                "kind" to request.kind.name,
                "confidence" to request.confidence,
                "tags" to request.tags,
                "source" to request.source,
            )

            is FactImprint -> linkedMapOf(
                "type" to "fact",
                "namespace" to namespace,
                "subject" to request.subject,
                "predicate" to request.predicate,
                "obj" to request.obj,
                "confidence" to request.confidence,
                "tags" to request.tags,
                "source" to request.source,
            )

            is RelationImprint -> linkedMapOf(
                "type" to "relation",
                "namespace" to namespace,
                "from" to request.from,
                "relation" to request.relation,
                "to" to request.to,
                "confidence" to request.confidence,
                "tags" to request.tags,
                "source" to request.source,
            )

            is EpisodeImprint -> linkedMapOf(
                "type" to "episode",
                "namespace" to namespace,
                "summary" to request.summary,
                "eventType" to request.eventType.name,
                "occurredAt" to request.occurredAt.toString(),
                "actionType" to request.actionType,
                "runId" to request.runId,
                "details" to request.details,
                "metadata" to request.metadata,
                "confidence" to request.confidence,
                "tags" to request.tags,
                "source" to request.source,
            )
        }
        return post("/v1/imprint", payload, ImprintResult::class.java)
    }

    override fun stats(): MemoryStatsResult {
        val type = object : TypeReference<Map<String, Any?>>() {}
        val stats = get("/metrics", type)
        return MemoryStatsResult(stats = stats)
    }

    override fun forget(request: ForgetRequest, namespace: String): ForgetResult {
        val payload = linkedMapOf(
            "namespace" to namespace,
            "tagMarkers" to request.tagMarkers.toList(),
            "ids" to request.ids.toList(),
        )
        return post("/v1/admin/forget", payload, ForgetResult::class.java)
    }

    override fun reset(request: ResetRequest, namespace: String): ResetResult {
        val payload = linkedMapOf(
            "namespace" to namespace,
            "clearAll" to request.clearAll,
        )
        return post("/v1/admin/reset", payload, ResetResult::class.java)
    }

    override fun close() {
        managedProcess?.close()
    }

    private fun <T> get(path: String, type: Class<T>): T {
        val request = Request.Builder().url("$baseUrl$path").get().build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP memory provider GET $path failed with ${response.code}.")
            }
            val body = response.body?.string().orEmpty()
            mapper.readValue(body, type)
        }
    }

    private fun <T> get(path: String, type: TypeReference<T>): T {
        val request = Request.Builder().url("$baseUrl$path").get().build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP memory provider GET $path failed with ${response.code}.")
            }
            val body = response.body?.string().orEmpty()
            mapper.readValue(body, type)
        }
    }

    private fun <T> post(path: String, payload: Any, type: Class<T>): T {
        val json = mapper.writeValueAsString(payload)
        val request = Request.Builder()
            .url("$baseUrl$path")
            .post(json.toRequestBody(jsonMediaType))
            .build()
        return client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                httpProviderLogger.warn { "HTTP memory provider POST $path failed status=${response.code} body=${body.take(220)}" }
                throw IOException("HTTP memory provider POST $path failed with ${response.code}.")
            }
            mapper.readValue(body, type)
        }
    }
}
