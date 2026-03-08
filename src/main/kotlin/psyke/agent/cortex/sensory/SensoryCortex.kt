package psyke.agent.cortex.sensory

import psyke.agent.core.AgentConfig
import psyke.agent.core.ConversationContext
import psyke.agent.core.DefaultInterlocutorResolver
import psyke.agent.core.InputPriority
import psyke.agent.core.InterlocutorResolver
import psyke.agent.support.TextSecurity
import java.io.Closeable
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class SensoryInput(
    val content: String,
    val priority: InputPriority = InputPriority.MEDIUM,
    val source: String = "external",
    val conversationContext: ConversationContext = ConversationContext.default(),
)

sealed interface SensorySignal {
    data class InputReceived(val input: SensoryInput) : SensorySignal
    data class SourceClosed(val source: String) : SensorySignal
    data class ExitRequested(val source: String) : SensorySignal
    data object NoInput : SensorySignal
}

fun interface SensoryInputSource {
    fun nextSignal(): SensorySignal
}

class StdinSensoryInputSource(
    private val readLineFn: () -> String? = { readLine() },
    private val prompt: () -> Unit = { print("you> ") },
    ) : SensoryInputSource {
    override fun nextSignal(): SensorySignal {
        prompt()
        val rawInput = readLineFn() ?: return SensorySignal.SourceClosed(source = "stdin")
        if (rawInput.trim().equals("exit", ignoreCase = true)) {
            return SensorySignal.ExitRequested(source = "stdin")
        }
        if (rawInput.isBlank()) {
            return SensorySignal.NoInput
        }
        return SensorySignal.InputReceived(
            SensoryInput(
                content = rawInput,
                priority = InputPriority.HIGH,
                source = "stdin"
            )
        )
    }
}

class AsyncSensoryInputSource(
    private val includeStdin: Boolean = true,
    private val emitStdinClosedSignal: Boolean = true,
    private val pollTimeoutMs: Long = DEFAULT_POLL_TIMEOUT_MS,
    private val readLineFn: () -> String? = { readLine() },
    private val prompt: () -> Unit = { print("you> ") },
) : SensoryInputSource, Closeable {
    private val queue = LinkedBlockingQueue<SensorySignal>(MAX_SIGNAL_QUEUE)
    @Volatile
    private var running: Boolean = true
    private val stdinReaderThread: Thread? = if (includeStdin) {
        thread(name = "psyke-stdin-sensory", isDaemon = true) {
            while (running) {
                prompt()
                val rawInput = readLineFn()
                if (rawInput == null) {
                    if (emitStdinClosedSignal) {
                        offerSignal(SensorySignal.SourceClosed(source = "stdin"))
                    }
                    break
                }
                if (rawInput.trim().equals("exit", ignoreCase = true)) {
                    offerSignal(SensorySignal.ExitRequested(source = "stdin"))
                    continue
                }
                if (rawInput.isBlank()) {
                    offerSignal(SensorySignal.NoInput)
                    continue
                }
                offerSignal(
                    SensorySignal.InputReceived(
                        SensoryInput(
                            content = rawInput,
                            priority = InputPriority.HIGH,
                            source = "stdin"
                        )
                    )
                )
            }
        }
    } else {
        null
    }

    fun submitInput(
        content: String,
        source: String,
        priority: InputPriority = InputPriority.HIGH,
        conversationContext: ConversationContext = ConversationContext.default(),
    ): Boolean = offerSignal(
        SensorySignal.InputReceived(
            SensoryInput(
                content = content,
                priority = priority,
                source = source,
                conversationContext = conversationContext
            )
        )
    )

    override fun nextSignal(): SensorySignal {
        return try {
            val signal = queue.poll(pollTimeoutMs, TimeUnit.MILLISECONDS)
            signal ?: SensorySignal.NoInput
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            SensorySignal.NoInput
        }
    }

    override fun close() {
        running = false
        stdinReaderThread?.interrupt()
        queue.clear()
    }

    private fun offerSignal(signal: SensorySignal): Boolean {
        if (queue.offer(signal)) {
            return true
        }
        queue.poll()
        return queue.offer(signal)
    }

    private companion object {
        const val MAX_SIGNAL_QUEUE: Int = 4_096
        const val DEFAULT_POLL_TIMEOUT_MS: Long = 250L
    }
}

class SensoryCortex(
    private val config: AgentConfig,
    private val source: SensoryInputSource,
    private val interlocutorResolver: InterlocutorResolver = DefaultInterlocutorResolver(),
) {
    fun nextSignal(): SensorySignal {
        val signal = source.nextSignal()
        if (signal !is SensorySignal.InputReceived) {
            return signal
        }

        val sanitized = TextSecurity.clamp(signal.input.content.trim(), config.planner.maxInputChars)
        if (sanitized.isBlank()) {
            return SensorySignal.NoInput
        }

        val resolvedContext = signal.input.conversationContext
        val enrichedInput = signal.input.copy(
            content = sanitized,
            conversationContext = if (resolvedContext.sessionId.isNotBlank()) {
                resolvedContext
            } else {
                val sessionId = resolveSessionId(signal.input.source)
                val interlocutor = interlocutorResolver.resolve(signal.input.source)
                ConversationContext(sessionId, interlocutor)
            }
        )

        return SensorySignal.InputReceived(enrichedInput)
    }

    private fun resolveSessionId(source: String): String = when {
        source == "stdin" -> ConversationContext.DEFAULT_SESSION_ID
        source.startsWith("chat:") -> source.removePrefix("chat:")
        else -> ConversationContext.DEFAULT_SESSION_ID
    }

    companion object {
        fun stdin(config: AgentConfig): SensoryCortex =
            SensoryCortex(
                config = config,
                source = StdinSensoryInputSource()
            )
    }
}
