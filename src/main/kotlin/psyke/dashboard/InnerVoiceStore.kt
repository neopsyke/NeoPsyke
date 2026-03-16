package psyke.dashboard

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.channels.Channel
import java.io.Closeable

class InnerVoiceStore(
    private val maxEventsPerSession: Int = 100,
    private val maxIdGlobalEvents: Int = 500,
) : Closeable {
    private val mapper = jacksonObjectMapper()
    private val lock = Any()
    private val subscribers = mutableMapOf<String, MutableSet<Channel<String>>>()
    private val eventBuffers = mutableMapOf<String, ArrayDeque<InnerVoiceEvent>>()

    // Global (session-independent) Id inner-voice stream
    private val idGlobalSubscribers = mutableSetOf<Channel<String>>()
    private val idGlobalBuffer = ArrayDeque<InnerVoiceEvent>()

    fun emit(event: InnerVoiceEvent) {
        val sessionId = event.sessionId ?: return
        val payloadJson: String
        synchronized(lock) {
            payloadJson = buildPayloadJson(event)
            if (event.origin == "id") {
                // Route to global Id stream
                if (idGlobalBuffer.size >= maxIdGlobalEvents) {
                    idGlobalBuffer.removeFirst()
                }
                idGlobalBuffer.addLast(event)
                broadcastToIdGlobal(payloadJson)
            } else {
                // Route to per-session conversation stream
                val buffer = eventBuffers.getOrPut(sessionId) { ArrayDeque() }
                if (buffer.size >= maxEventsPerSession) {
                    buffer.removeFirst()
                }
                buffer.addLast(event)
                broadcastToSession(sessionId, payloadJson)
            }
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

    fun subscribeIdGlobal(): DashboardFlowSubscription {
        val channel = Channel<String>(SUBSCRIBER_CHANNEL_CAPACITY)
        synchronized(lock) {
            idGlobalSubscribers.add(channel)
        }
        return DashboardFlowSubscription(channel) {
            synchronized(lock) {
                idGlobalSubscribers.remove(channel)
            }
            channel.close()
        }
    }

    fun getRecentIdGlobal(): List<InnerVoiceEvent> =
        synchronized(lock) { idGlobalBuffer.toList() }

    override fun close() {
        synchronized(lock) {
            subscribers.values.forEach { set -> set.forEach { it.close() } }
            subscribers.clear()
            eventBuffers.clear()
            idGlobalSubscribers.forEach { it.close() }
            idGlobalSubscribers.clear()
            idGlobalBuffer.clear()
        }
    }

    private fun buildPayloadJson(event: InnerVoiceEvent): String =
        mapper.writeValueAsString(
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
                    "metadata" to event.metadata,
                    "origin" to event.origin
                )
            )
        )

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

    private fun broadcastToIdGlobal(payloadJson: String) {
        val staleSubscribers = mutableListOf<Channel<String>>()
        idGlobalSubscribers.forEach { channel ->
            val result = channel.trySend(payloadJson)
            if (result.isFailure && result.isClosed) {
                staleSubscribers.add(channel)
            } else if (result.isFailure) {
                channel.tryReceive()
                channel.trySend(payloadJson)
            }
        }
        idGlobalSubscribers.removeAll(staleSubscribers.toSet())
    }

    private companion object {
        const val SUBSCRIBER_CHANNEL_CAPACITY: Int = 1_000
    }
}
