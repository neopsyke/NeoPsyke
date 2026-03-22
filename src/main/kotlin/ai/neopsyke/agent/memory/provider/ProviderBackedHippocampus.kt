package ai.neopsyke.agent.memory.provider

import ai.neopsyke.agent.memory.longterm.*

class ProviderBackedHippocampus(
    private val namespace: String,
    private val client: MemoryProviderClient,
) : Hippocampus, HippocampusAdmin {
    override val providerName: String = client.providerName
    override val capabilities: Set<MemoryCapability> = client.capabilities
    override val adminCapabilities: Set<MemoryAdminCapability> =
        if (client is MemoryProviderAdminClient) {
            setOf(
                MemoryAdminCapability.STATS,
                MemoryAdminCapability.FORGET,
                MemoryAdminCapability.RESET
            )
        } else {
            emptySet()
        }

    override fun health(): MemoryHealth = client.health()

    override fun recall(request: RecallRequest): RecallResult =
        client.recall(request = request, namespace = namespace)

    override fun imprint(request: ImprintRequest): ImprintResult =
        client.imprint(request = request, namespace = namespace)

    override fun stats(): MemoryStatsResult =
        (client as? MemoryProviderAdminClient)?.stats() ?: super.stats()

    override fun forget(request: ForgetRequest): ForgetResult =
        (client as? MemoryProviderAdminClient)?.forget(request = request, namespace = namespace)
            ?: super.forget(request)

    override fun reset(request: ResetRequest): ResetResult =
        (client as? MemoryProviderAdminClient)?.reset(request = request, namespace = namespace)
            ?: super.reset(request)

    override fun close() {
        client.close()
    }
}
