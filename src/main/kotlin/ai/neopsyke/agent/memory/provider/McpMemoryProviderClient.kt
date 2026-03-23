package ai.neopsyke.agent.memory.provider

import ai.neopsyke.agent.memory.longterm.*
import ai.neopsyke.agent.memory.longterm.McpHippocampus

/**
 * Transitional MCP-backed provider adapter.
 *
 * This keeps the future provider SPI transport-agnostic while the repo is
 * still migrating away from MCP as the practical default. Once the HTTP path is
 * fully stable, this remains only as an optional adapter.
 */
class McpMemoryProviderClient(
    command: List<String>,
    callTimeoutMs: Long,
    defaultMaxItems: Int,
    defaultMaxChars: Int,
) : MemoryProviderClient, MemoryProviderAdminClient {
    private val delegate = McpHippocampus(
        command = command,
        callTimeoutMs = callTimeoutMs,
        defaultMaxItems = defaultMaxItems,
        defaultMaxChars = defaultMaxChars,
    )

    override val providerName: String
        get() = delegate.providerName

    override val capabilities: Set<MemoryCapability>
        get() = delegate.capabilities

    override fun health(): MemoryHealth = delegate.health()

    override fun recall(request: RecallRequest, namespace: String): RecallResult = delegate.recall(request)

    override fun imprint(request: ImprintRequest, namespace: String): ImprintResult = delegate.imprint(request)

    override fun stats(): MemoryStatsResult = delegate.stats()

    override fun forget(request: ForgetRequest, namespace: String): ForgetResult = delegate.forget(request)

    override fun reset(request: ResetRequest, namespace: String): ResetResult = delegate.reset(request)

    override fun close() {
        delegate.close()
    }
}
