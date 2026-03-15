package psyke.agent.cortex.sensory

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import psyke.agent.config.AgentConfig
import psyke.agent.model.ConversationContext
import psyke.agent.config.DefaultInterlocutorResolver
import psyke.agent.model.InputPriority
import psyke.agent.model.Interlocutor
import psyke.agent.config.InterlocutorResolver
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
    suspend fun nextSignal(): SensorySignal
}

class StdinSensoryInputSource(
    private val readLineFn: () -> String? = { readLine() },
    private val prompt: () -> Unit = { print("you> ") },
    ) : SensoryInputSource {
    override suspend fun nextSignal(): SensorySignal = withContext(Dispatchers.IO) {
        prompt()
        val rawInput = readLineFn() ?: return@withContext SensorySignal.SourceClosed(source = "stdin")
        if (rawInput.trim().equals("exit", ignoreCase = true)) {
            return@withContext SensorySignal.ExitRequested(source = "stdin")
        }
        if (rawInput.isBlank()) {
            return@withContext SensorySignal.NoInput
        }
        SensorySignal.InputReceived(
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

    /**
     * Exposes the underlying signal channel for use with `select {}` in
     * multiplexed sensory source compositions.
     */
    val signalChannel: ReceiveChannel<SensorySignal> get() = channel
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

    override suspend fun nextSignal(): SensorySignal =
        withTimeoutOrNull(pollTimeoutMs) {
            channel.receive()
        } ?: SensorySignal.NoInput

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
    suspend fun nextSignal(): SensorySignal {
        val signal = source.nextSignal()
        if (signal !is SensorySignal.InputReceived) {
            return signal
        }

        val sanitized = TextSecurity.clamp(signal.input.content.trim(), config.planner.maxInputChars)
        if (sanitized.isBlank()) {
            return SensorySignal.NoInput
        }

        val providedContext = signal.input.conversationContext
        val resolvedSessionId = if (providedContext.sessionId == ConversationContext.DEFAULT_SESSION_ID) {
            resolveSessionId(signal.input.source)
        } else {
            providedContext.sessionId
        }
        val resolvedInterlocutor = if (providedContext.interlocutor == Interlocutor.UNKNOWN) {
            interlocutorResolver.resolve(signal.input.source)
        } else {
            providedContext.interlocutor
        }
        val enrichedInput = signal.input.copy(
            content = sanitized,
            conversationContext = ConversationContext(resolvedSessionId, resolvedInterlocutor)
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
