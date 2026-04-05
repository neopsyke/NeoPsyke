package ai.neopsyke.agent.config

data class ApprovalRuntimeConfig(
    val enabled: Boolean = true,
    val ttlMs: Long = 5 * 60 * 1000L,
    val clarificationTurns: Int = 2,
    val defaultChannel: String = DEFAULT_CHANNEL_DASHBOARD,
    val channelPriority: List<String> = listOf(DEFAULT_CHANNEL_DASHBOARD, DEFAULT_CHANNEL_TELEGRAM),
    val dashboardRequiresLiveSubscriber: Boolean = true,
    val telegramStartupAckEnabled: Boolean = true,
) {
    companion object {
        const val DEFAULT_CHANNEL_DASHBOARD: String = "dashboard"
        const val DEFAULT_CHANNEL_TELEGRAM: String = "telegram"
    }
}
