package ai.neopsyke.support

import ai.neopsyke.instrumentation.AgentEvent
import ai.neopsyke.instrumentation.AgentInstrumentation
import ai.neopsyke.instrumentation.InstrumentationSink
import ai.neopsyke.llm.ChatCompletion
import ai.neopsyke.llm.ChatMessage
import ai.neopsyke.llm.ChatCallObserver
import ai.neopsyke.llm.ChatModelClient
import ai.neopsyke.llm.ChatRequestOptions
import ai.neopsyke.metrics.MetricsRuntime
import ai.neopsyke.metrics.MetricsSnapshot
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class StubChatModelClient(
    override val modelName: String = "stub-model",
) : ChatModelClient {
    data class ObservedCall(
        val messages: List<ChatMessage>,
        val options: ChatRequestOptions,
    )

    private val queuedResponses = ArrayDeque<ChatCompletion>()
    private val queuedResponsesByCallSite = mutableMapOf<String, ArrayDeque<ChatCompletion>>()
    val calls = mutableListOf<ObservedCall>()
    var lastMessages: List<ChatMessage> = emptyList()
        private set
    var lastOptions: ChatRequestOptions = ChatRequestOptions()
        private set

    fun enqueueRawResponse(content: String) {
        queuedResponses.addLast(ChatCompletion(content = content, model = modelName))
    }

    fun enqueueRawResponseForCallSite(callSite: String, content: String) {
        val queue = queuedResponsesByCallSite.getOrPut(callSite) { ArrayDeque() }
        queue.addLast(ChatCompletion(content = content, model = modelName))
    }

    override fun chat(messages: List<ChatMessage>, options: ChatRequestOptions): ChatCompletion {
        val callSite = options.metadata.callSite.orEmpty()
        calls += ObservedCall(messages = messages, options = options)
        lastMessages = messages
        lastOptions = options

        queuedResponsesByCallSite[callSite]?.removeFirstOrNull()?.let { return it }
        if (callSite == "input_intent_router") {
            return ChatCompletion(content = """{"route":"general_action","reasoning":"test default"}""", model = modelName)
        }
        if (callSite == "grounding_classifier") {
            return ChatCompletion(content = """{"grounding_required":false}""", model = modelName)
        }
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

    override fun recordActionCall(actionType: String) {}

    override fun recordPlannerNoop() {}

    override fun recordPlannerOutputRepaired() {}

    override fun recordDroppedEvents(count: Long) {}

    override fun recordQueueSaturation(queueType: String) {}

    override fun recordMemoryRecall(hitCount: Int, latencyMs: Long, recallChars: Int, truncated: Boolean) {}

    override fun recordMemoryRecallFailure(latencyMs: Long) {}

    override fun recordLongTermMemoryRecallSkipped() {}

    override fun recordLongTermMemoryAssessment(saveRecommended: Boolean) {}

    override fun recordLongTermMemoryAssessmentParseFailure() {}

    override fun recordMemoryImprint(saved: Boolean, summaryChars: Int, latencyMs: Long) {}

    override fun recordEpisodicRecall(hitCount: Int, recallChars: Int) {}

    override fun recordLessonRecall(hitCount: Int, recallChars: Int) {}

    override fun recordEndToEndResponseLatency(latencyMs: Long) {}

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
