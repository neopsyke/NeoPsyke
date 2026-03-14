package psyke.dashboard

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.channels.Channel
import java.io.Closeable

class InnerVoiceStore(
    private val maxEventsPerSession: Int = 100,
) : Closeable {
    private val mapper = jacksonObjectMapper()
    private val lock = Any()
    private val subscribers = mutableMapOf<String, MutableSet<Channel<String>>>()
    private val eventBuffers = mutableMapOf<String, ArrayDeque<InnerVoiceEvent>>()

    fun emit(event: InnerVoiceEvent) {
        val sessionId = event.sessionId ?: return
        val payloadJson: String
        synchronized(lock) {
            val buffer = eventBuffers.getOrPut(sessionId) { ArrayDeque() }
            if (buffer.size >= maxEventsPerSession) {
                buffer.removeFirst()
            }
            buffer.addLast(event)
            payloadJson = mapper.writeValueAsString(
                mapOf(
                    "type" to "thinking",
                    "event" to mapOf(
                        "id" to event.id,
                        "type" to event.type.name,
                        "content" to event.content,
                        "root_input_id" to event.rootInputId,
                        "session_id" to event.sessionId,
                        "ts" to event.ts,
                        "sequence" to event.sequence,
                        "metadata" to event.metadata
                    )
                )
            )
            broadcastToSession(sessionId, payloadJson)
        }
    }

    fun subscribe(sessionId: String): DashboardFlowSubscription? {
        val channel = Channel<String>(SUBSCRIBER_CHANNEL_CAPACITY)
        synchronized(lock) {
            val sessionSubscribers = subscribers.getOrPut(sessionId) { linkedSetOf() }
            sessionSubscribers.add(channel)
        }
        return DashboardFlowSubscription(channel) {
            synchronized(lock) {
                subscribers[sessionId]?.remove(channel)
                if (subscribers[sessionId].isNullOrEmpty()) {
                    subscribers.remove(sessionId)
                }
            }
            channel.close()
        }
    }

    override fun close() {
        synchronized(lock) {
            subscribers.values.forEach { set -> set.forEach { it.close() } }
            subscribers.clear()
            eventBuffers.clear()
        }
    }

    private fun broadcastToSession(sessionId: String, payloadJson: String) {
        val sessionSubscribers = subscribers[sessionId] ?: return
        val staleSubscribers = mutableListOf<Channel<String>>()
        sessionSubscribers.forEach { channel ->
            val result = channel.trySend(payloadJson)
            if (result.isFailure && result.isClosed) {
                staleSubscribers.add(channel)
            } else if (result.isFailure) {
                channel.tryReceive()
                channel.trySend(payloadJson)
            }
        }
        sessionSubscribers.removeAll(staleSubscribers.toSet())
        if (sessionSubscribers.isEmpty()) {
            subscribers.remove(sessionId)
        }
    }

    private companion object {
        const val SUBSCRIBER_CHANNEL_CAPACITY: Int = 1_000
    }
}
