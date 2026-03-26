package ai.neopsyke.poc.model

enum class ActionType {
    REFLECT_INTERNAL,
    WEB_SEARCH,
    CONTACT_USER;

    companion object {
        fun fromRaw(raw: String): ActionType = valueOf(raw.trim().uppercase())
    }
}
