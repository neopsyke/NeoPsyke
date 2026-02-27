package psyke.llm

class CompositeChatCallObserver(
    private val observers: List<ChatCallObserver>,
) : ChatCallObserver {
    override fun onChatCall(record: ChatCallRecord) {
        observers.forEach { observer ->
            try {
                observer.onChatCall(record)
            } catch (_: Exception) {
                // keep fan-out robust when one observer fails
            }
        }
    }
}

fun combineChatCallObservers(vararg observers: ChatCallObserver?): ChatCallObserver? {
    val resolved = observers.filterNotNull()
    return when (resolved.size) {
        0 -> null
        1 -> resolved.first()
        else -> CompositeChatCallObserver(resolved)
    }
}
