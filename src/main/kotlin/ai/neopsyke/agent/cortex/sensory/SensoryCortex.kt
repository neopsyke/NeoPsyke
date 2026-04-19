package ai.neopsyke.agent.cortex.sensory

import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.config.DefaultInterlocutorResolver
import ai.neopsyke.agent.config.InterlocutorResolver
import ai.neopsyke.agent.assignments.WakeReasonType
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.ConversationSecurityContexts
import ai.neopsyke.agent.model.ActionExecutionStatus
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.ActionOrigin
import ai.neopsyke.agent.model.GroundingMetadata
import ai.neopsyke.agent.model.GroundingRequirement
import ai.neopsyke.agent.model.GroundingSource
import ai.neopsyke.agent.model.OriginSource
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
import mu.KotlinLogging
import java.io.Closeable
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
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

private val sensoryCortexLogger = KotlinLogging.logger("SensoryCortex")

data class SensoryInput(
    val content: String,
    val priority: InputPriority = InputPriority.MEDIUM,
    val source: String = "external",
    val conversationContext: ConversationContext = ConversationContext.default(),
)

sealed interface Signal

sealed interface CognitiveSignal : Signal {
    data class StimulusReceived(
        val stimulus: StimulusEnvelope,
        val percept: Percept? = null,
    ) : CognitiveSignal

    data class FeedbackReceived(
        val cue: ActionFeedbackCue,
    ) : CognitiveSignal

    data object NoStimulus : CognitiveSignal
}

sealed interface RuntimeControlSignal : Signal {
    data class SourceClosed(val source: String) : RuntimeControlSignal
    data class ExitRequested(val source: String) : RuntimeControlSignal
    data object ShutdownRequested : RuntimeControlSignal
    data class ConfigReloaded(val key: String) : RuntimeControlSignal
}

data class AssignmentCue(
    val workItemId: String,
    val stepId: String,
    val reason: String,
    val wakeReasonType: WakeReasonType? = null,
    val wakeReasonDetail: String? = null,
) {
    fun toStimulus(): StimulusEnvelope =
        StimulusEnvelope(
            id = RootInputIds.next(),
            family = StimulusFamily.CUE,
            source = SOURCE,
            content = "assignment_runtime_work_ready",
            receivedAt = Instant.now(),
            conversationContext = ConversationContext(
                sessionId = ConversationContext.DEFAULT_SESSION_ID,
                interlocutor = Interlocutor.named(SOURCE),
                security = ConversationSecurityContexts.internalAutomation(
                    provider = SOURCE,
                    channelId = ConversationContext.DEFAULT_SESSION_ID,
                ),
            ),
            provenance = Provenances.trustedSystemSignal(provider = SOURCE, sourceRef = workItemId),
            trustLevel = StimulusTrustLevel.TRUSTED_INTERNAL,
            metadata = mapOf(
                METADATA_CUE_TYPE to CUE_TYPE_WORK_READY,
                METADATA_WORK_ITEM_ID to workItemId,
                METADATA_STEP_ID to stepId,
                METADATA_REASON to reason,
                METADATA_WAKE_REASON_TYPE to wakeReasonType?.name.orEmpty(),
                METADATA_WAKE_REASON_DETAIL to wakeReasonDetail.orEmpty(),
            ),
        )

    companion object {
        private const val SOURCE: String = "assignment-runtime"

        fun fromStimulus(stimulus: StimulusEnvelope): AssignmentCue? {
            if (stimulus.family != StimulusFamily.CUE) return null
            if (stimulus.metadata[METADATA_CUE_TYPE] != CUE_TYPE_WORK_READY) return null
            val workItemId = stimulus.metadata[METADATA_WORK_ITEM_ID] ?: return null
            val stepId = stimulus.metadata[METADATA_STEP_ID] ?: return null
            val reason = stimulus.metadata[METADATA_REASON].orEmpty()
            val wakeReasonType = stimulus.metadata[METADATA_WAKE_REASON_TYPE]
                ?.takeIf { it.isNotBlank() }
                ?.let { raw -> runCatching { WakeReasonType.valueOf(raw) }.getOrNull() }
            val wakeReasonDetail = stimulus.metadata[METADATA_WAKE_REASON_DETAIL]?.ifBlank { null }
            return AssignmentCue(
                workItemId = workItemId,
                stepId = stepId,
                reason = reason,
                wakeReasonType = wakeReasonType,
                wakeReasonDetail = wakeReasonDetail,
            )
        }
    }
}

data class ActionFeedbackCue(
    val rootInputId: String,
    val actionType: ActionType,
    val actionSummary: String,
    val feedbackContent: String,
    val statusSummary: String,
    val plannerSignal: String,
    val executionStatus: ActionExecutionStatus,
    val conversationContext: ConversationContext,
    val observedEvidence: Boolean? = null,
    val actionErrorCategory: String? = null,
    val fetchErrorCategory: String? = null,
    val sourceActionId: Long? = null,
    val rootInputReceivedAtMs: Long? = null,
    val attempts: Int = 0,
    val urgency: String? = null,
    val requiresFollowUpThought: Boolean = false,
    val origin: ActionOrigin = ActionOrigin.USER,
    val groundingMetadata: GroundingMetadata,
) {
    fun toStimulus(): StimulusEnvelope =
        StimulusEnvelope(
            id = RootInputIds.next(),
            family = StimulusFamily.FEEDBACK,
            source = SOURCE,
            content = feedbackContent,
            receivedAt = Instant.now(),
            conversationContext = conversationContext,
            correlationId = rootInputId,
            causationId = sourceActionId?.toString(),
            trustLevel = StimulusTrustLevel.TRUSTED_INTERNAL,
            provenance = Provenances.trustedSystemSignal(provider = SOURCE, sourceRef = rootInputId),
            metadata = buildMap {
                put(METADATA_CUE_TYPE, CUE_TYPE_ACTION_FEEDBACK)
                put(METADATA_ROOT_INPUT_ID, rootInputId)
                put(METADATA_ACTION_TYPE, actionType.id)
                put(METADATA_ACTION_SUMMARY, actionSummary)
                put(METADATA_STATUS_SUMMARY, statusSummary)
                put(METADATA_PLANNER_SIGNAL, plannerSignal)
                put(METADATA_EXECUTION_STATUS, executionStatus.name)
                observedEvidence?.let { put(METADATA_OBSERVED_EVIDENCE, it.toString()) }
                actionErrorCategory?.takeIf { it.isNotBlank() }?.let { put(METADATA_ACTION_ERROR_CATEGORY, it) }
                fetchErrorCategory?.takeIf { it.isNotBlank() }?.let { put(METADATA_FETCH_ERROR_CATEGORY, it) }
                rootInputReceivedAtMs?.let { put(METADATA_ROOT_INPUT_RECEIVED_AT_MS, it.toString()) }
                put(METADATA_ATTEMPTS, attempts.toString())
                urgency?.takeIf { it.isNotBlank() }?.let { put(METADATA_URGENCY, it) }
                put(METADATA_REQUIRES_FOLLOW_UP_THOUGHT, requiresFollowUpThought.toString())
                put(METADATA_ORIGIN_SOURCE, origin.source.name)
                origin.needId?.takeIf { it.isNotBlank() }?.let { put(METADATA_ORIGIN_NEED_ID, it) }
                origin.rootImpulseId?.takeIf { it.isNotBlank() }?.let { put(METADATA_ORIGIN_ROOT_IMPULSE_ID, it) }
                put(METADATA_GROUNDING_REQUIRED, groundingMetadata.requirement.name)
                put(METADATA_GROUNDING_SOURCE, groundingMetadata.source.name)
            },
        )

    companion object {
        private const val SOURCE: String = "action-feedback"

        fun fromStimulus(stimulus: StimulusEnvelope): ActionFeedbackCue? {
            if (stimulus.family != StimulusFamily.FEEDBACK) return null
            if (stimulus.metadata[METADATA_CUE_TYPE] != CUE_TYPE_ACTION_FEEDBACK) return null
            val rootInputId = stimulus.metadata[METADATA_ROOT_INPUT_ID] ?: stimulus.correlationId ?: return null
            val actionType = stimulus.metadata[METADATA_ACTION_TYPE]
                ?.let { ActionType.fromRaw(it) }
                ?: return null
            val executionStatus = stimulus.metadata[METADATA_EXECUTION_STATUS]
                ?.let { runCatching { ActionExecutionStatus.valueOf(it) }.getOrNull() }
                ?: ActionExecutionStatus.SUCCESS
            val originSource = stimulus.metadata[METADATA_ORIGIN_SOURCE]
                ?.let { raw -> runCatching { OriginSource.valueOf(raw) }.getOrNull() }
                ?: OriginSource.USER
            val groundingRequirement = stimulus.metadata[METADATA_GROUNDING_REQUIRED]
                ?.let { raw -> runCatching { GroundingRequirement.valueOf(raw.uppercase()) }.getOrNull() }
            val groundingSource = stimulus.metadata[METADATA_GROUNDING_SOURCE]
                ?.let { raw -> runCatching { GroundingSource.valueOf(raw.uppercase()) }.getOrNull() }
            val groundingMetadata = if (groundingRequirement != null && groundingSource != null) {
                GroundingMetadata(requirement = groundingRequirement, source = groundingSource)
            } else {
                // Stimulus missing grounding metadata keys — default to NOT_REQUIRED.
                // This is the only site where a default is allowed; all other envelope
                // construction must provide grounding metadata explicitly.
                sensoryCortexLogger.warn {
                    "ActionFeedbackCue.fromStimulus: grounding metadata missing from stimulus " +
                        "metadata keys; defaulting to NOT_REQUIRED/INHERITED. " +
                        "root_input_id=$rootInputId action_type=${actionType.id}"
                }
                GroundingMetadata(
                    requirement = GroundingRequirement.NOT_REQUIRED,
                    source = GroundingSource.INHERITED,
                )
            }
            return ActionFeedbackCue(
                rootInputId = rootInputId,
                actionType = actionType,
                actionSummary = stimulus.metadata[METADATA_ACTION_SUMMARY].orEmpty(),
                feedbackContent = stimulus.content,
                statusSummary = stimulus.metadata[METADATA_STATUS_SUMMARY].orEmpty(),
                plannerSignal = stimulus.metadata[METADATA_PLANNER_SIGNAL].orEmpty(),
                executionStatus = executionStatus,
                conversationContext = stimulus.conversationContext,
                observedEvidence = stimulus.metadata[METADATA_OBSERVED_EVIDENCE]?.toBooleanStrictOrNull(),
                actionErrorCategory = stimulus.metadata[METADATA_ACTION_ERROR_CATEGORY],
                fetchErrorCategory = stimulus.metadata[METADATA_FETCH_ERROR_CATEGORY],
                sourceActionId = stimulus.causationId?.toLongOrNull(),
                rootInputReceivedAtMs = stimulus.metadata[METADATA_ROOT_INPUT_RECEIVED_AT_MS]?.toLongOrNull(),
                attempts = stimulus.metadata[METADATA_ATTEMPTS]?.toIntOrNull() ?: 0,
                urgency = stimulus.metadata[METADATA_URGENCY],
                requiresFollowUpThought = stimulus.metadata[METADATA_REQUIRES_FOLLOW_UP_THOUGHT]
                    ?.toBooleanStrictOrNull()
                    ?: false,
                origin = ActionOrigin(
                    source = originSource,
                    needId = stimulus.metadata[METADATA_ORIGIN_NEED_ID],
                    rootImpulseId = stimulus.metadata[METADATA_ORIGIN_ROOT_IMPULSE_ID],
                ),
                groundingMetadata = groundingMetadata,
            )
        }
    }
}

object CognitiveCueMetadata {
    const val METADATA_CUE_TYPE: String = "cue_type"
    const val METADATA_WORK_ITEM_ID: String = "assignment_id"
    const val METADATA_STEP_ID: String = "step_id"
    const val METADATA_REASON: String = "reason"
    const val METADATA_WAKE_REASON_TYPE: String = "wake_reason_type"
    const val METADATA_WAKE_REASON_DETAIL: String = "wake_reason_detail"
    const val METADATA_ROOT_IMPULSE_ID: String = "root_impulse_id"
    const val METADATA_ROOT_INPUT_ID: String = "root_input_id"
    const val METADATA_ACTION_TYPE: String = "action_type"
    const val METADATA_ACTION_SUMMARY: String = "action_summary"
    const val METADATA_STATUS_SUMMARY: String = "status_summary"
    const val METADATA_PLANNER_SIGNAL: String = "planner_signal"
    const val METADATA_EXECUTION_STATUS: String = "execution_status"
    const val METADATA_OBSERVED_EVIDENCE: String = "observed_evidence"
    const val METADATA_ACTION_ERROR_CATEGORY: String = "action_error_category"
    const val METADATA_FETCH_ERROR_CATEGORY: String = "fetch_error_category"
    const val METADATA_ROOT_INPUT_RECEIVED_AT_MS: String = "root_input_received_at_ms"
    const val METADATA_ATTEMPTS: String = "attempts"
    const val METADATA_URGENCY: String = "urgency"
    const val METADATA_REQUIRES_FOLLOW_UP_THOUGHT: String = "requires_follow_up_thought"
    const val METADATA_ORIGIN_SOURCE: String = "origin_source"
    const val METADATA_ORIGIN_NEED_ID: String = "origin_need_id"
    const val METADATA_ORIGIN_ROOT_IMPULSE_ID: String = "origin_root_impulse_id"
    const val METADATA_GROUNDING_REQUIRED: String = "grounding_required"
    const val METADATA_GROUNDING_SOURCE: String = "grounding_source"

    const val CUE_TYPE_ID_IMPULSE_READY: String = "id_impulse_ready"
    const val CUE_TYPE_WORK_READY: String = "assignment_runtime_work_ready"
    const val CUE_TYPE_ACTION_FEEDBACK: String = "action_feedback"
}

private const val METADATA_CUE_TYPE: String = CognitiveCueMetadata.METADATA_CUE_TYPE
private const val METADATA_WORK_ITEM_ID: String = CognitiveCueMetadata.METADATA_WORK_ITEM_ID
private const val METADATA_STEP_ID: String = CognitiveCueMetadata.METADATA_STEP_ID
private const val METADATA_REASON: String = CognitiveCueMetadata.METADATA_REASON
private const val METADATA_WAKE_REASON_TYPE: String = CognitiveCueMetadata.METADATA_WAKE_REASON_TYPE
private const val METADATA_WAKE_REASON_DETAIL: String = CognitiveCueMetadata.METADATA_WAKE_REASON_DETAIL
private const val METADATA_ROOT_IMPULSE_ID: String = CognitiveCueMetadata.METADATA_ROOT_IMPULSE_ID
private const val METADATA_ROOT_INPUT_ID: String = CognitiveCueMetadata.METADATA_ROOT_INPUT_ID
private const val METADATA_ACTION_TYPE: String = CognitiveCueMetadata.METADATA_ACTION_TYPE
private const val METADATA_ACTION_SUMMARY: String = CognitiveCueMetadata.METADATA_ACTION_SUMMARY
private const val METADATA_STATUS_SUMMARY: String = CognitiveCueMetadata.METADATA_STATUS_SUMMARY
private const val METADATA_PLANNER_SIGNAL: String = CognitiveCueMetadata.METADATA_PLANNER_SIGNAL
private const val METADATA_EXECUTION_STATUS: String = CognitiveCueMetadata.METADATA_EXECUTION_STATUS
private const val METADATA_OBSERVED_EVIDENCE: String = CognitiveCueMetadata.METADATA_OBSERVED_EVIDENCE
private const val METADATA_ACTION_ERROR_CATEGORY: String = CognitiveCueMetadata.METADATA_ACTION_ERROR_CATEGORY
private const val METADATA_FETCH_ERROR_CATEGORY: String = CognitiveCueMetadata.METADATA_FETCH_ERROR_CATEGORY
private const val METADATA_ROOT_INPUT_RECEIVED_AT_MS: String = CognitiveCueMetadata.METADATA_ROOT_INPUT_RECEIVED_AT_MS
private const val METADATA_ATTEMPTS: String = CognitiveCueMetadata.METADATA_ATTEMPTS
private const val METADATA_URGENCY: String = CognitiveCueMetadata.METADATA_URGENCY
private const val METADATA_REQUIRES_FOLLOW_UP_THOUGHT: String =
    CognitiveCueMetadata.METADATA_REQUIRES_FOLLOW_UP_THOUGHT
private const val METADATA_ORIGIN_SOURCE: String = CognitiveCueMetadata.METADATA_ORIGIN_SOURCE
private const val METADATA_ORIGIN_NEED_ID: String = CognitiveCueMetadata.METADATA_ORIGIN_NEED_ID
private const val METADATA_ORIGIN_ROOT_IMPULSE_ID: String = CognitiveCueMetadata.METADATA_ORIGIN_ROOT_IMPULSE_ID
private const val METADATA_GROUNDING_REQUIRED: String = CognitiveCueMetadata.METADATA_GROUNDING_REQUIRED
private const val METADATA_GROUNDING_SOURCE: String = CognitiveCueMetadata.METADATA_GROUNDING_SOURCE
private const val CUE_TYPE_ID_IMPULSE_READY: String = CognitiveCueMetadata.CUE_TYPE_ID_IMPULSE_READY
private const val CUE_TYPE_WORK_READY: String = CognitiveCueMetadata.CUE_TYPE_WORK_READY
private const val CUE_TYPE_ACTION_FEEDBACK: String = CognitiveCueMetadata.CUE_TYPE_ACTION_FEEDBACK

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
    private val policyScope: ai.neopsyke.agent.model.PolicyScope = ai.neopsyke.agent.model.PolicyScope.DEFAULT,
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
                            policyScope = policyScope,
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
    @Volatile private var instrumentation: ai.neopsyke.instrumentation.AgentInstrumentation =
        ai.neopsyke.instrumentation.NoopAgentInstrumentation

    fun setInstrumentation(inst: ai.neopsyke.instrumentation.AgentInstrumentation) {
        instrumentation = inst
    }

    private val syntheticSignals = Channel<Signal>(SYNTHETIC_SIGNAL_QUEUE)
    private val syntheticSignalCount = AtomicInteger(0)

    fun offerActionFeedback(cue: ActionFeedbackCue): Boolean =
        offerSyntheticSignal(CognitiveSignal.FeedbackReceived(cue))

    fun offerAssignmentCue(cue: AssignmentCue): Boolean =
        offerSyntheticSignal(CognitiveSignal.StimulusReceived(cue.toStimulus()))

    fun hasPendingSyntheticSignals(): Boolean = syntheticSignalCount.get() > 0

    suspend fun nextSignal(): Signal {
        syntheticSignals.tryReceive().getOrNull()?.let { signal ->
            syntheticSignalCount.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
            val stimulusSignal = signal as? CognitiveSignal.StimulusReceived ?: return signal
            val enrichedStimulus = enrichStimulus(stimulusSignal.stimulus)
            if (enrichedStimulus == null) {
                sensoryCortexLogger.warn {
                    "Synthetic stimulus dropped: blank content after sanitization" +
                        " family=${stimulusSignal.stimulus.family}" +
                        " source=${stimulusSignal.stimulus.source}" +
                        " content_len=${stimulusSignal.stimulus.content.length}"
                }
                instrumentation.emit(
                    ai.neopsyke.instrumentation.AgentEvent(
                        type = "signal_dropped",
                        data = mapOf(
                            "channel" to "synthetic",
                            "reason" to "blank_after_sanitization",
                            "family" to stimulusSignal.stimulus.family.name,
                            "source" to stimulusSignal.stimulus.source,
                            "content_length" to stimulusSignal.stimulus.content.length,
                            "metadata_keys" to stimulusSignal.stimulus.metadata.keys.toList(),
                        ),
                    )
                )
                return CognitiveSignal.NoStimulus
            }
            return CognitiveSignal.StimulusReceived(
                stimulus = enrichedStimulus,
                percept = PerceptualAppraiser().appraise(enrichedStimulus),
            )
        }
        val signal = source.nextSignal()
        val stimulusSignal = signal as? CognitiveSignal.StimulusReceived ?: return signal
        val enrichedStimulus = enrichStimulus(stimulusSignal.stimulus)
        if (enrichedStimulus == null) {
            sensoryCortexLogger.warn {
                "Source stimulus dropped: blank content after sanitization" +
                    " family=${stimulusSignal.stimulus.family}" +
                    " source=${stimulusSignal.stimulus.source}" +
                    " content_len=${stimulusSignal.stimulus.content.length}"
            }
            instrumentation.emit(
                ai.neopsyke.instrumentation.AgentEvent(
                    type = "signal_dropped",
                    data = mapOf(
                        "channel" to "source",
                        "reason" to "blank_after_sanitization",
                        "family" to stimulusSignal.stimulus.family.name,
                        "source" to stimulusSignal.stimulus.source,
                        "content_length" to stimulusSignal.stimulus.content.length,
                        "metadata_keys" to stimulusSignal.stimulus.metadata.keys.toList(),
                    ),
                )
            )
            return CognitiveSignal.NoStimulus
        }
        return CognitiveSignal.StimulusReceived(
            stimulus = enrichedStimulus,
            percept = PerceptualAppraiser().appraise(enrichedStimulus),
        )
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

    private fun offerSyntheticSignal(signal: Signal): Boolean {
        val result = syntheticSignals.trySend(signal)
        if (result.isSuccess) {
            syntheticSignalCount.incrementAndGet()
            return true
        }
        syntheticSignals.tryReceive().getOrNull()?.let {
            syntheticSignalCount.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
        }
        val retry = syntheticSignals.trySend(signal)
        if (retry.isSuccess) {
            syntheticSignalCount.incrementAndGet()
        }
        if (!retry.isSuccess) {
            sensoryCortexLogger.warn { "Synthetic signal queue full after retry; signal dropped" }
        }
        return retry.isSuccess
    }

    companion object {
        private const val SYNTHETIC_SIGNAL_QUEUE: Int = 1_024
        fun stdin(config: AgentConfig): SensoryCortex =
            SensoryCortex(
                config = config,
                source = StdinSignalSource(policyScope = config.policyScope),
            )
    }
}
