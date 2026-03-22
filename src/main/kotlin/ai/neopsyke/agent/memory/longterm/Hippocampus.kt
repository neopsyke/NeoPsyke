package ai.neopsyke.agent.memory.longterm

import ai.neopsyke.agent.model.DialogueTurn

interface Hippocampus : AutoCloseable {
    val providerName: String
    val capabilities: Set<MemoryCapability>
    val enabled: Boolean
        get() = true

    fun health(): MemoryHealth = MemoryHealth(provider = providerName, available = enabled)

    fun recall(request: RecallRequest): RecallResult

    fun imprint(request: ImprintRequest): ImprintResult = ImprintResult(
        provider = providerName,
        accepted = false,
        storedCount = 0,
        detail = "unsupported"
    )

    /**
     * Reserved for future bounded background integration work:
     * summarization, lesson derivation, dedupe consolidation, fact correction,
     * relation extraction, and similar dream-like reorganization.
     *
     * TODO: wire this into a bounded scheduler or Id-driven internal need.
     */
    fun consolidate(request: ConsolidationRequest): ConsolidationResult =
        ConsolidationResult.unsupported(providerName)

    fun imprint(turn: DialogueTurn): ImprintResult {
        val normalized = turn.content.trim()
        if (normalized.isBlank()) {
            return ImprintResult(
                provider = providerName,
                accepted = false,
                storedCount = 0,
                detail = "blank_turn"
            )
        }
        return imprint(
            NarrativeImprint(
                summary = normalized,
                source = "dialogue_turn_${turn.role.name.lowercase()}"
            )
        )
    }

    override fun close() {}
}

interface HippocampusAdmin {
    val adminCapabilities: Set<MemoryAdminCapability>

    fun stats(): MemoryStatsResult = MemoryStatsResult(stats = emptyMap())

    fun forget(request: ForgetRequest): ForgetResult = ForgetResult(
        deletedCount = 0,
        detail = "unsupported"
    )

    fun reset(request: ResetRequest): ResetResult = ResetResult(
        deletedCount = 0,
        detail = "unsupported"
    )
}

object NoopHippocampus : Hippocampus, HippocampusAdmin {
    override val providerName: String = "none"
    override val capabilities: Set<MemoryCapability> = emptySet()
    override val adminCapabilities: Set<MemoryAdminCapability> = emptySet()
    override val enabled: Boolean = false

    override fun health(): MemoryHealth =
        MemoryHealth(provider = providerName, available = false, detail = "disabled")

    override fun recall(request: RecallRequest): RecallResult =
        RecallResult(
            provider = providerName,
            items = emptyList(),
            renderedText = "",
            hitCount = 0,
            truncated = false
        )
}
