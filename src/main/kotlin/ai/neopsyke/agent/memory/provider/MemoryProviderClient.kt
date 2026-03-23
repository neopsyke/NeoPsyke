package ai.neopsyke.agent.memory.provider

import ai.neopsyke.agent.memory.longterm.*

/**
 * Technical provider SPI below Hippocampus.
 *
 * This is intentionally transport-agnostic. memory-core/Hippocampus should
 * depend on provider behavior, not on whether the implementation is reached via
 * HTTP, MCP, or a future direct/in-process adapter.
 *
 * Intended adapter directions:
 * - HTTP: default NeoPsyke pgvector provider path
 * - MCP: optional compatibility/tooling path
 * - direct/in-process: future contributor extension point if needed
 */
interface MemoryProviderClient : AutoCloseable {
    val providerName: String
    val capabilities: Set<MemoryCapability>

    fun health(): MemoryHealth

    fun recall(request: RecallRequest, namespace: String): RecallResult

    fun imprint(request: ImprintRequest, namespace: String): ImprintResult

    override fun close() {}
}

/**
 * Optional operational/admin extension beside the core provider SPI.
 *
 * Not every provider needs to implement this in v1, but NeoPsyke still uses
 * these operations for CLI/eval hygiene such as clearing memory before runs.
 */
interface MemoryProviderAdminClient {
    fun stats(): MemoryStatsResult = MemoryStatsResult()

    fun forget(request: ForgetRequest, namespace: String): ForgetResult =
        ForgetResult(deletedCount = 0, detail = "unsupported")

    fun reset(request: ResetRequest, namespace: String): ResetResult =
        ResetResult(deletedCount = 0, detail = "unsupported")
}
