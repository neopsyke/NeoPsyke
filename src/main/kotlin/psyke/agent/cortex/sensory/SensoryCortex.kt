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

// ── Signal hierarchy ─────────────────────────────────────────────────

/**
 * Top-level signal type consumed by the Ego loop.
 *
 * Sub-hierarchies:
 * - [SensorySignal] — external perception (user input, source lifecycle)
 * - [SystemSignal]  — internal system events (Id impulses, shutdown)
 * - [ProjectSignal] — project subsystem events (step progress, timers)
 */
sealed interface Signal

/** External perception signals — user input and source lifecycle. */
sealed interface SensorySignal : Signal {
    data class InputReceived(val input: SensoryInput) : SensorySignal
    data class SourceClosed(val source: String) : SensorySignal
    data class ExitRequested(val source: String) : SensorySignal
    data object NoInput : SensorySignal
}

/** Internal system events — Id impulses, shutdown, config changes. */
sealed interface SystemSignal : Signal {
    /** The Id has enqueued an impulse and the Ego should wake up to process it. */
    data object ImpulseReady : SystemSignal
    /** Graceful shutdown requested (e.g., SIGTERM). */
    data object ShutdownRequested : SystemSignal
    /** A configuration key was hot-reloaded. */
    data class ConfigReloaded(val key: String) : SystemSignal
}

/** Project subsystem events consumed by the Ego loop. */
sealed interface ProjectSignal : Signal {
    data class WorkReady(
        val projectId: String,
        val stepId: String,
        val reason: String,
    ) : ProjectSignal
}

// ── Signal sources ───────────────────────────────────────────────────

fun interface SignalSource {
    suspend fun nextSignal(): Signal
}

class StdinSignalSource(
    private val readLineFn: () -> String? = { readLine() },
    private val prompt: () -> Unit = { print("you> ") },
) : SignalSource {
    override suspend fun nextSignal(): Signal = withContext(Dispatchers.IO) {
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

class AsyncSignalSource(
    private val includeStdin: Boolean = true,
    private val emitStdinClosedSignal: Boolean = true,
    private val pollTimeoutMs: Long = DEFAULT_POLL_TIMEOUT_MS,
    private val stdinMode: StdinMode = StdinMode.CHAT_AND_CONTROL,
    private val readLineFn: () -> String? = { readLine() },
    private val prompt: () -> Unit = { print("you> ") },
    private val controlOutput: (String) -> Unit = ::println,
    scope: CoroutineScope? = null,
) : SignalSource, Closeable {
    enum class StdinMode {
        CHAT_AND_CONTROL,
        CONTROL_ONLY,
    }

    private val channel = Channel<Signal>(MAX_SIGNAL_QUEUE)

    /**
     * Exposes the underlying signal channel for use with `select {}` in
     * multiplexed signal source compositions.
     */
    val signalChannel: ReceiveChannel<Signal> get() = channel
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

    /** Wake the Ego loop so it picks up a queued Id impulse. */
    fun notifyImpulseReady(): Boolean = offerSignal(SystemSignal.ImpulseReady)

    /** Inject any [Signal] into the channel (used by ProjectManager, etc.). */
    fun offerProjectSignal(signal: ProjectSignal): Boolean = offerSignal(signal)

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

    override suspend fun nextSignal(): Signal =
        withTimeoutOrNull(pollTimeoutMs) {
            channel.receive()
        } ?: SensorySignal.NoInput

    override fun close() {
        stdinReaderJob?.cancel()
        channel.close()
    }

    private fun offerSignal(signal: Signal): Boolean {
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

// ── Backward-compatibility aliases ───────────────────────────────────
// These allow existing code to compile during incremental migration.

@Deprecated("Use SignalSource", replaceWith = ReplaceWith("SignalSource"))
typealias SensoryInputSource = SignalSource

@Deprecated("Use StdinSignalSource", replaceWith = ReplaceWith("StdinSignalSource"))
typealias StdinSensoryInputSource = StdinSignalSource

@Deprecated("Use AsyncSignalSource", replaceWith = ReplaceWith("AsyncSignalSource"))
typealias AsyncSensoryInputSource = AsyncSignalSource

// ── SensoryCortex ────────────────────────────────────────────────────

class SensoryCortex(
    private val config: AgentConfig,
    private val source: SignalSource,
    private val interlocutorResolver: InterlocutorResolver = DefaultInterlocutorResolver(),
) {
    suspend fun nextSignal(): Signal {
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
                source = StdinSignalSource()
            )
    }
}
