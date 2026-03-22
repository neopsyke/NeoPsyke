package ai.neopsyke.memory.pgvector

import ai.neopsyke.memory.pgvector.db.MemoryRepository
import ai.neopsyke.memory.pgvector.embedding.Embedder
import ai.neopsyke.memory.pgvector.metrics.MemoryProviderMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProviderMemoryServiceTest {
    private val config = PgvectorMemoryProviderConfig.fromEnv(
        mapOf("EMBEDDING_API_KEY" to "test-key")
    )

    private val repository = MemoryRepository(config, MemoryProviderMetrics())
    private val embedder = object : Embedder {
        override val dimensions: Int = 2
        override fun embed(text: String): FloatArray = floatArrayOf(0.1f, 0.2f)
    }

    private val service = ProviderMemoryService(
        config = config,
        repository = repository,
        embedder = embedder,
    )

    @Test
    fun `imprint rejects unsupported types as bad request`() {
        val ex = assertFailsWith<ProviderBadRequestException> {
            service.imprint(ProviderImprintRequest(type = "unknown"))
        }
        assertEquals("unsupported_type", ex.errorCode)
    }

    @Test
    fun `narrative imprint rejects blank summary`() {
        val ex = assertFailsWith<ProviderBadRequestException> {
            service.imprint(ProviderImprintRequest(type = "narrative", summary = "   "))
        }
        assertEquals("blank_summary", ex.errorCode)
    }

    @Test
    fun `reset requires clear all true`() {
        val ex = assertFailsWith<ProviderBadRequestException> {
            service.reset(ProviderResetRequest(clearAll = false))
        }
        assertEquals("clear_all_false", ex.errorCode)
    }

    @Test
    fun `forget requires criteria`() {
        val ex = assertFailsWith<ProviderBadRequestException> {
            service.forget(ProviderForgetRequest())
        }
        assertEquals("missing_delete_criteria", ex.errorCode)
    }

    @Test
    fun `forget by id is explicitly unsupported in v1`() {
        val ex = assertFailsWith<ProviderBadRequestException> {
            service.forget(ProviderForgetRequest(ids = listOf("123")))
        }
        assertEquals("forget_by_id_not_supported", ex.errorCode)
    }
}
