package psyke.support

import psyke.instrumentation.AgentEvent
import psyke.instrumentation.AgentInstrumentation
import psyke.instrumentation.InstrumentationSink
import psyke.llm.ChatCompletion
import psyke.llm.ChatMessage
import psyke.llm.ChatCallObserver
import psyke.llm.ChatModelClient
import psyke.llm.ChatRequestOptions
import psyke.metrics.MetricsRuntime
import psyke.metrics.MetricsSnapshot
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class StubChatModelClient(
    override val modelName: String = "stub-model",
) : ChatModelClient {
    private val queuedResponses = ArrayDeque<ChatCompletion>()
    var lastMessages: List<ChatMessage> = emptyList()
        private set
    var lastOptions: ChatRequestOptions = ChatRequestOptions()
        private set

    fun enqueueRawResponse(content: String) {
        queuedResponses.addLast(ChatCompletion(content = content, model = modelName))
    }

    override fun chat(messages: List<ChatMessage>, options: ChatRequestOptions): ChatCompletion {
        lastMessages = messages
        lastOptions = options
        return queuedResponses.removeFirstOrNull()
            ?: ChatCompletion(content = """{"decision":"noop","reason":"empty queue"}""", model = modelName)
    }
}

class RecordingInstrumentation : AgentInstrumentation {
    val events = mutableListOf<AgentEvent>()

    override fun emit(event: AgentEvent) {
        events.add(event)
    }
}

class StubMetricsRuntime(
    var snapshotValue: MetricsSnapshot? = null,
) : MetricsRuntime {
    var deniedActionCount: Int = 0
        private set
    var providerName: String? = null
        private set
    var observedRecords: Int = 0
        private set

    override fun chatCallObserver(provider: String): ChatCallObserver {
        providerName = provider
        return ChatCallObserver {
            observedRecords += 1
        }
    }

    override fun recordDeniedAction() {
        deniedActionCount += 1
    }

    override fun recordDroppedEvents(count: Long) {}

    override fun recordQueueSaturation(queueType: String) {}

    override fun snapshot(): MetricsSnapshot? = snapshotValue
}

class RecordingSink(
    private val expected: Int = 0,
) : InstrumentationSink {
    val events = mutableListOf<AgentEvent>()
    private val latch = if (expected > 0) CountDownLatch(expected) else null

    override fun onEvent(event: AgentEvent) {
        synchronized(events) {
            events.add(event)
        }
        latch?.countDown()
    }

    fun await(timeoutMs: Long = 1_500): Boolean =
        latch?.await(timeoutMs, TimeUnit.MILLISECONDS) ?: true
}
