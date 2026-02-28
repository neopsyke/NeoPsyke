package psyke.llm

interface PersistentMetricsChatCallObserver : ChatCallObserver

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

    fun hasPersistentMetricsObserver(): Boolean =
        observers.any { observer ->
            when (observer) {
                is PersistentMetricsChatCallObserver -> true
                is CompositeChatCallObserver -> observer.hasPersistentMetricsObserver()
                else -> false
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

fun hasPersistentMetricsObserver(observer: ChatCallObserver?): Boolean =
    when (observer) {
        null -> false
        is PersistentMetricsChatCallObserver -> true
        is CompositeChatCallObserver -> observer.hasPersistentMetricsObserver()
        else -> false
    }
