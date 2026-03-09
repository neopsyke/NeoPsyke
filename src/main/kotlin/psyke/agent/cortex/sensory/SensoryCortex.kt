package psyke.agent.cortex.sensory

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel
import psyke.agent.core.AgentConfig
import psyke.agent.core.ConversationContext
import psyke.agent.core.DefaultInterlocutorResolver
import psyke.agent.core.InputPriority
import psyke.agent.core.InterlocutorResolver
import psyke.agent.support.TextSecurity
import java.io.Closeable

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
    private val stdinMode: StdinMode = StdinMode.CHAT_AND_CONTROL,
    private val readLineFn: () -> String? = { readLine() },
    private val prompt: () -> Unit = { print("you> ") },
    private val controlOutput: (String) -> Unit = ::println,
    scope: CoroutineScope? = null,
) : SensoryInputSource, Closeable {
    enum class StdinMode {
        CHAT_AND_CONTROL,
        CONTROL_ONLY,
    }

    private val channel = Channel<SensorySignal>(MAX_SIGNAL_QUEUE)
    private val stdinReaderJob: Job? = if (includeStdin && scope != null) {
        scope.launch(Dispatchers.IO + CoroutineName("psyke-stdin-sensory")) {
            while (isActive) {
                prompt()
                val rawInput = readLineFn()
                if (rawInput == null) {
                    if (emitStdinClosedSignal) {
                        offerSignal(SensorySignal.SourceClosed(source = "stdin"))
                    }
                    break
                }
                val normalizedInput = rawInput.trim()
                if (normalizedInput.equals("exit", ignoreCase = true)) {
                    offerSignal(SensorySignal.ExitRequested(source = "stdin"))
                    break
                }
                if (stdinMode == StdinMode.CONTROL_ONLY) {
                    if (normalizedInput.isNotBlank()) {
                        controlOutput(
                            "control> Unknown command '$normalizedInput'. Available commands: exit"
                        )
                    }
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
        // Temporarily bridge to coroutines — will become a suspend fun in Phase 3
        return runBlocking {
            withTimeoutOrNull(pollTimeoutMs) {
                channel.receive()
            } ?: SensorySignal.NoInput
        }
    }

    override fun close() {
        stdinReaderJob?.cancel()
        channel.close()
    }

    private fun offerSignal(signal: SensorySignal): Boolean {
        val result = channel.trySend(signal)
        if (result.isSuccess) {
            return true
        }
        // Drop oldest and retry (same overflow behavior as before)
        channel.tryReceive()
        return channel.trySend(signal).isSuccess
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
