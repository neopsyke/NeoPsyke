package ai.neopsyke.agent.cortex.sensory

import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.config.DefaultInterlocutorResolver
import ai.neopsyke.agent.config.InterlocutorResolver
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.ConversationSecurityContexts
import ai.neopsyke.agent.model.InputPriority
import ai.neopsyke.agent.model.Interlocutor
import ai.neopsyke.agent.model.Percept
import ai.neopsyke.agent.model.PerceptFamily
import ai.neopsyke.agent.model.RootInputIds
import ai.neopsyke.agent.model.SanitizationRecord
import ai.neopsyke.agent.model.StimulusEnvelope
import ai.neopsyke.agent.model.StimulusFamily
import ai.neopsyke.agent.model.Provenances
import ai.neopsyke.agent.model.StimulusTrustLevel
import ai.neopsyke.agent.support.TextSecurity
import java.io.Closeable
import java.time.Instant
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class SensoryInput(
    val content: String,
    val priority: InputPriority = InputPriority.MEDIUM,
    val source: String = "external",
    val conversationContext: ConversationContext = ConversationContext.default(),
)

sealed interface Signal

sealed interface CognitiveSignal : Signal {
    data class StimulusReceived(val stimulus: StimulusEnvelope) : CognitiveSignal
    data object NoStimulus : CognitiveSignal
}

sealed interface RuntimeControlSignal : Signal {
    data class SourceClosed(val source: String) : RuntimeControlSignal
    data class ExitRequested(val source: String) : RuntimeControlSignal
    data object ShutdownRequested : RuntimeControlSignal
    data class ConfigReloaded(val key: String) : RuntimeControlSignal
}

data class GoalRuntimeCue(
    val goalId: String,
    val stepId: String,
    val reason: String,
) {
    fun toStimulus(): StimulusEnvelope =
        StimulusEnvelope(
            id = RootInputIds.next(),
            family = StimulusFamily.CUE,
            source = SOURCE,
            content = "goal_runtime_work_ready",
            receivedAt = Instant.now(),
            conversationContext = ConversationContext(
                sessionId = ConversationContext.DEFAULT_SESSION_ID,
                interlocutor = Interlocutor.named(SOURCE),
                security = ConversationSecurityContexts.internalAutomation(
                    provider = SOURCE,
                    channelId = ConversationContext.DEFAULT_SESSION_ID,
                ),
            ),
            provenance = Provenances.trustedSystemSignal(provider = SOURCE, sourceRef = goalId),
            trustLevel = StimulusTrustLevel.TRUSTED_INTERNAL,
            metadata = mapOf(
                METADATA_CUE_TYPE to CUE_TYPE_WORK_READY,
                METADATA_GOAL_ID to goalId,
                METADATA_STEP_ID to stepId,
                METADATA_REASON to reason,
            ),
        )

    companion object {
        private const val SOURCE: String = "goal-runtime"

        fun fromStimulus(stimulus: StimulusEnvelope): GoalRuntimeCue? {
            if (stimulus.family != StimulusFamily.CUE) return null
            if (stimulus.metadata[METADATA_CUE_TYPE] != CUE_TYPE_WORK_READY) return null
            val goalId = stimulus.metadata[METADATA_GOAL_ID] ?: return null
            val stepId = stimulus.metadata[METADATA_STEP_ID] ?: return null
            val reason = stimulus.metadata[METADATA_REASON].orEmpty()
            return GoalRuntimeCue(goalId = goalId, stepId = stepId, reason = reason)
        }
    }
}

object CognitiveCueMetadata {
    const val METADATA_CUE_TYPE: String = "cue_type"
    const val METADATA_GOAL_ID: String = "goal_id"
    const val METADATA_STEP_ID: String = "step_id"
    const val METADATA_REASON: String = "reason"
    const val METADATA_ROOT_IMPULSE_ID: String = "root_impulse_id"

    const val CUE_TYPE_ID_IMPULSE_READY: String = "id_impulse_ready"
    const val CUE_TYPE_WORK_READY: String = "goal_runtime_work_ready"
}

private const val METADATA_CUE_TYPE: String = CognitiveCueMetadata.METADATA_CUE_TYPE
private const val METADATA_GOAL_ID: String = CognitiveCueMetadata.METADATA_GOAL_ID
private const val METADATA_STEP_ID: String = CognitiveCueMetadata.METADATA_STEP_ID
private const val METADATA_REASON: String = CognitiveCueMetadata.METADATA_REASON
private const val METADATA_ROOT_IMPULSE_ID: String = CognitiveCueMetadata.METADATA_ROOT_IMPULSE_ID
private const val CUE_TYPE_ID_IMPULSE_READY: String = CognitiveCueMetadata.CUE_TYPE_ID_IMPULSE_READY
private const val CUE_TYPE_WORK_READY: String = CognitiveCueMetadata.CUE_TYPE_WORK_READY

fun interface SignalSource {
    suspend fun nextSignal(): Signal
}

class PerceptualAppraiser {
    fun appraise(stimulus: StimulusEnvelope): Percept =
        Percept(
            id = RootInputIds.next(),
            family = when (stimulus.family) {
                StimulusFamily.LINGUISTIC -> PerceptFamily.REQUEST
                StimulusFamily.OBSERVATION -> PerceptFamily.OBSERVATION
                StimulusFamily.FEEDBACK -> PerceptFamily.FEEDBACK
                StimulusFamily.CUE -> when (stimulus.metadata[METADATA_CUE_TYPE]) {
                    CUE_TYPE_ID_IMPULSE_READY -> PerceptFamily.DRIVE_ACTIVATION
                    else -> PerceptFamily.STATE_CHANGE
                }
            },
            summary = stimulus.content,
            source = stimulus.source,
            occurredAt = stimulus.receivedAt,
            conversationContext = stimulus.conversationContext,
            rootStimulusId = stimulus.id,
            provenance = stimulus.provenance,
            metadata = stimulus.metadata,
        )
}

class StdinSignalSource(
    private val readLineFn: () -> String? = { readLine() },
    private val prompt: () -> Unit = { print("you> ") },
) : SignalSource {
    override suspend fun nextSignal(): Signal = withContext(Dispatchers.IO) {
        prompt()
        val rawInput = readLineFn() ?: return@withContext RuntimeControlSignal.SourceClosed(source = "stdin")
        if (rawInput.trim().equals("exit", ignoreCase = true)) {
            return@withContext RuntimeControlSignal.ExitRequested(source = "stdin")
        }
        if (rawInput.isBlank()) {
            return@withContext CognitiveSignal.NoStimulus
        }
        CognitiveSignal.StimulusReceived(
                StimulusEnvelope(
                    id = RootInputIds.next(),
                    family = StimulusFamily.LINGUISTIC,
                    source = "stdin",
                    content = rawInput,
                    receivedAt = Instant.now(),
                    conversationContext = ConversationContext(
                        sessionId = ConversationContext.DEFAULT_SESSION_ID,
                        interlocutor = Interlocutor.named("stdin-user"),
                        security = ConversationSecurityContexts.ownerDirect(
                            provider = "stdin",
                            channelId = ConversationContext.DEFAULT_SESSION_ID,
                        ),
                    ),
                    trustLevel = StimulusTrustLevel.DEFAULT,
                    provenance = Provenances.trustedMessage(provider = "stdin", sourceRef = "stdin"),
                    metadata = mapOf("priority" to InputPriority.HIGH.name),
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
    val signalChannel: ReceiveChannel<Signal> get() = channel

    private val stdinReaderJob: Job? = if (includeStdin && scope != null) {
        scope.launch(Dispatchers.IO + CoroutineName("neopsyke-stdin-sensory")) {
            while (isActive) {
                prompt()
                val rawInput = readLineFn()
                if (rawInput == null) {
                    if (emitStdinClosedSignal) {
                        offerSignal(RuntimeControlSignal.SourceClosed(source = "stdin"))
                    }
                    break
                }
                val normalizedInput = rawInput.trim()
                if (normalizedInput.equals("exit", ignoreCase = true)) {
                    offerSignal(RuntimeControlSignal.ExitRequested(source = "stdin"))
                    break
                }
                if (stdinMode == StdinMode.CONTROL_ONLY) {
                    if (normalizedInput.isNotBlank()) {
                        controlOutput("control> Unknown command '$normalizedInput'. Available commands: exit")
                    }
                    continue
                }
                if (rawInput.isBlank()) {
                    offerSignal(CognitiveSignal.NoStimulus)
                    continue
                }
                submitInput(
                    content = rawInput,
                    source = "stdin",
                    priority = InputPriority.HIGH,
                )
            }
        }
    } else {
        null
    }

    fun notifyImpulseReady(rootImpulseId: String? = null): Boolean =
        offerSignal(
            CognitiveSignal.StimulusReceived(
                StimulusEnvelope(
                    id = RootInputIds.next(),
                    family = StimulusFamily.CUE,
                    source = "id",
                    content = CUE_TYPE_ID_IMPULSE_READY,
                    receivedAt = Instant.now(),
                    conversationContext = ConversationContext(
                        sessionId = ConversationContext.DEFAULT_SESSION_ID,
                        interlocutor = Interlocutor.named("id"),
                        security = ConversationSecurityContexts.internalAutomation(
                            provider = "id",
                            channelId = ConversationContext.DEFAULT_SESSION_ID,
                        ),
                    ),
                    trustLevel = StimulusTrustLevel.TRUSTED_INTERNAL,
                    provenance = Provenances.trustedSystemSignal(provider = "id", sourceRef = rootImpulseId),
                    metadata = buildMap {
                        put(METADATA_CUE_TYPE, CUE_TYPE_ID_IMPULSE_READY)
                        if (!rootImpulseId.isNullOrBlank()) {
                            put(METADATA_ROOT_IMPULSE_ID, rootImpulseId)
                        }
                    },
                )
            )
        )

    fun offerGoalRuntimeCue(cue: GoalRuntimeCue): Boolean =
        offerSignal(CognitiveSignal.StimulusReceived(cue.toStimulus()))

    fun submitInput(
        content: String,
        source: String,
        priority: InputPriority = InputPriority.HIGH,
        conversationContext: ConversationContext = ConversationContext.default(),
    ): Boolean = offerSignal(
        CognitiveSignal.StimulusReceived(
            StimulusEnvelope(
                id = RootInputIds.next(),
                family = StimulusFamily.LINGUISTIC,
                source = source,
                content = content,
                receivedAt = Instant.now(),
                conversationContext = conversationContext,
                trustLevel = StimulusTrustLevel.DEFAULT,
                provenance = when (conversationContext.security.instructionTrust) {
                    ai.neopsyke.agent.model.InstructionTrust.TRUSTED_INSTRUCTION ->
                        Provenances.trustedMessage(provider = source, sourceRef = conversationContext.sessionId)
                    ai.neopsyke.agent.model.InstructionTrust.UNTRUSTED_INSTRUCTION ->
                        Provenances.fromStimulusTrustLevel(
                            source = source,
                            trustLevel = StimulusTrustLevel.UNTRUSTED_EXTERNAL,
                            sourceRef = conversationContext.sessionId,
                        )
                },
                metadata = mapOf("priority" to priority.name),
            )
        )
    )

    override suspend fun nextSignal(): Signal =
        withTimeoutOrNull(pollTimeoutMs) {
            channel.receive()
        } ?: CognitiveSignal.NoStimulus

    override fun close() {
        stdinReaderJob?.cancel()
        channel.close()
    }

    private fun offerSignal(signal: Signal): Boolean {
        val result = channel.trySend(signal)
        if (result.isSuccess) return true
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
    private val source: SignalSource,
    private val interlocutorResolver: InterlocutorResolver = DefaultInterlocutorResolver(),
) {
    suspend fun nextSignal(): Signal {
        val signal = source.nextSignal()
        val stimulusSignal = signal as? CognitiveSignal.StimulusReceived ?: return signal
        val enrichedStimulus = enrichStimulus(stimulusSignal.stimulus) ?: return CognitiveSignal.NoStimulus
        return CognitiveSignal.StimulusReceived(enrichedStimulus)
    }

    private fun enrichStimulus(stimulus: StimulusEnvelope): StimulusEnvelope? {
        val sanitized = TextSecurity.clamp(stimulus.content.trim(), config.planner.maxInputChars)
        if (sanitized.isBlank()) {
            return null
        }

        val providedContext = stimulus.conversationContext
        val resolvedSessionId = if (providedContext.sessionId == ConversationContext.DEFAULT_SESSION_ID) {
            resolveSessionId(stimulus.source)
        } else {
            providedContext.sessionId
        }
        val resolvedInterlocutor = if (providedContext.interlocutor == Interlocutor.UNKNOWN) {
            interlocutorResolver.resolve(stimulus.source)
        } else {
            providedContext.interlocutor
        }

        return stimulus.copy(
            content = sanitized,
            conversationContext = ConversationContext(
                sessionId = resolvedSessionId,
                interlocutor = resolvedInterlocutor,
                security = providedContext.security,
            ),
            provenance = stimulus.provenance.copy(
                sanitization = SanitizationRecord(
                    method = "input_clamp",
                    originalChars = stimulus.content.length,
                )
            ),
        )
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
                source = StdinSignalSource(),
            )
    }
}
