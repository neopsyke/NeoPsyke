package psyke.dashboard

enum class InnerVoiceEventType {
    DELIBERATION,
    INTENTION,
    PLAN,
    PLAN_STEP,
    RECONSIDERATION,
    RECALL,
    OBSERVATION,
    REFLECTION,
}

data class InnerVoiceEvent(
    val id: Long,
    val type: InnerVoiceEventType,
    val content: String,
    val rootInputId: String?,
    val sessionId: String?,
    val ts: Long,
    val sequence: Long = 0,
    val metadata: Map<String, Any?> = emptyMap(),
    val origin: String = "user",
)

data class InnerVoiceConfig(
    val enabled: Boolean = true,
    val maxContentChars: Int = 500,
    val maxEventsPerSession: Int = 100,
)
