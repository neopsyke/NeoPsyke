package freudian.poc.model

enum class ActionType {
    INTERNAL_REFLECTION,
    WEB_LOOKUP,
    USER_MESSAGE;

    companion object {
        fun fromRaw(raw: String): ActionType = valueOf(raw.trim().uppercase())
    }
}
