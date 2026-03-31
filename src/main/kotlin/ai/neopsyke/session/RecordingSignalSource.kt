package ai.neopsyke.session

import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import mu.KotlinLogging
import ai.neopsyke.agent.cortex.sensory.CognitiveSignal
import ai.neopsyke.agent.cortex.sensory.RuntimeControlSignal
import ai.neopsyke.agent.cortex.sensory.Signal
import ai.neopsyke.agent.cortex.sensory.SignalSource
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.ConversationSecurityContexts
import ai.neopsyke.agent.model.Interlocutor
import ai.neopsyke.agent.model.Provenances
import ai.neopsyke.agent.model.RootInputIds
import ai.neopsyke.agent.model.StimulusEnvelope
import ai.neopsyke.agent.model.StimulusFamily
import ai.neopsyke.agent.model.StimulusTrustLevel
import java.time.Instant

private val logger = KotlinLogging.logger {}
private val signalMapper = jacksonObjectMapper()

/**
 * Wraps a [SignalSource] to record or replay signals.
 *
 * - **RECORD mode**: delegates to [delegate], records each
 *   [CognitiveSignal.StimulusReceived] to the channel. Control signals and
 *   [CognitiveSignal.NoStimulus] are passed through without recording.
 *
 * - **REPLAY mode**: ignores [delegate] and feeds signals from the recorded
 *   channel. When the channel is exhausted, emits
 *   [RuntimeControlSignal.ExitRequested] to stop the agent loop. If the
 *   channel diverges (code changed the signal flow), it switches to
 *   passthrough and delegates to the real source from that point forward.
 *
 * Hash strategy: signals are order-based (`"signal:<seq>"`). User inputs
 * arrive asynchronously so there is no stable content hash at this boundary;
 * the recording captures the order they were consumed. The content is stored
 * in the JSONL data payload for replay.
 */
class RecordingSignalSource(
    private val delegate: SignalSource,
    private val channel: RecordReplayChannel,
    private val manager: SessionRecordingManager? = null,
) : SignalSource {

    override suspend fun nextSignal(): Signal {
        return when (channel.mode) {
            SessionRecordingMode.RECORD -> recordSignal()
            SessionRecordingMode.REPLAY -> replaySignal()
            SessionRecordingMode.OFF -> delegate.nextSignal()
        }
    }

    private suspend fun recordSignal(): Signal {
        val signal = delegate.nextSignal()
        val stimulus = (signal as? CognitiveSignal.StimulusReceived)?.stimulus ?: return signal
        val seq = channel.nextSequenceIndex()
        val hash = RecordReplayChannel.hashContent("signal:$seq")
        val data = serializeStimulus(stimulus)
        channel.recordEntry(
            SessionRecordEntry(
                seq = seq,
                hash = hash,
                channel = SessionRecordingManager.CHANNEL_SIGNALS,
                data = data,
            )
        )
        // Capture the conversation context from the first signal for replay.
        // captureRecordingContext is atomic — only the first call writes.
        if (manager != null && manager.mode == SessionRecordingMode.RECORD) {
            manager.captureRecordingContext(
                RecordedContext(
                    source = stimulus.source,
                    sessionId = stimulus.conversationContext.sessionId,
                    interlocutorId = stimulus.conversationContext.interlocutor.id,
                    instructionTrust = stimulus.conversationContext.security.instructionTrust.name,
                    channelSurface = stimulus.conversationContext.security.channel.surface.name,
                    channelTransport = stimulus.conversationContext.security.channel.transport.name,
                    principalRole = stimulus.conversationContext.security.principal.role.name,
                    goalsEnabled = System.getenv("NEOPSYKE_GOALS_ENABLED")?.trim()?.lowercase() != "false",
                )
            )
        }
        return signal
    }

    private suspend fun replaySignal(): Signal {
        if (channel.passthroughMode) {
            return delegate.nextSignal()
        }
        val seq = channel.nextSequenceIndex()
        // Check exhaustion before calling replayOrDiverge. For signals,
        // exhaustion means the recorded session is over — not a divergence.
        if (seq >= channel.entryCount) {
            logger.info { "Signal channel exhausted at seq=$seq, ending session" }
            return RuntimeControlSignal.ExitRequested(source = "session-replay")
        }
        val hash = RecordReplayChannel.hashContent("signal:$seq")
        val data = channel.replayOrDiverge(seq, hash)
        if (data == null) {
            // Hash divergence — code changed the signal flow
            logger.info { "Signal channel diverged at seq=$seq, switching to live source" }
            return delegate.nextSignal()
        }
        val dataObj = data as? ObjectNode ?: run {
            logger.warn { "Signal channel: unexpected data node type at seq=$seq, switching to live source" }
            return delegate.nextSignal()
        }
        return CognitiveSignal.StimulusReceived(deserializeStimulus(dataObj))
    }

    companion object {
        private fun serializeStimulus(stimulus: StimulusEnvelope): ObjectNode {
            val node = signalMapper.createObjectNode()
            node.put("id", stimulus.id)
            node.put("family", stimulus.family.name)
            node.put("source", stimulus.source)
            node.put("content", stimulus.content)
            node.put("received_at", stimulus.receivedAt.toString())
            node.put("trust_level", stimulus.trustLevel.name)
            stimulus.correlationId?.let { node.put("correlation_id", it) }
            stimulus.causationId?.let { node.put("causation_id", it) }

            val ctx = signalMapper.createObjectNode()
            ctx.put("session_id", stimulus.conversationContext.sessionId)
            ctx.put("interlocutor_id", stimulus.conversationContext.interlocutor.id)
            val sec = stimulus.conversationContext.security
            ctx.put("instruction_trust", sec.instructionTrust.name)
            ctx.put("principal_role", sec.principal.role.name)
            ctx.put("channel_provider", sec.channel.provider)
            ctx.put("channel_surface", sec.channel.surface.name)
            ctx.put("channel_transport", sec.channel.transport.name)
            ctx.put("policy_scope_id", sec.policyScopeId)
            node.set<ObjectNode>("conversation_context", ctx)

            val meta = signalMapper.createObjectNode()
            stimulus.metadata.forEach { (k, v) -> meta.put(k, v) }
            node.set<ObjectNode>("metadata", meta)

            return node
        }

        internal fun deserializeStimulus(node: ObjectNode): StimulusEnvelope {
            val family = StimulusFamily.valueOf(node.path("family").asText())
            val source = node.path("source").asText()
            val trustLevel = StimulusTrustLevel.valueOf(
                node.path("trust_level").asText().ifEmpty { StimulusTrustLevel.DEFAULT.name }
            )

            val ctxNode = node.path("conversation_context")
            val sessionId = ctxNode.path("session_id").asText().ifEmpty {
                ConversationContext.DEFAULT_SESSION_ID
            }
            val interlocutorId = ctxNode.path("interlocutor_id").asText().ifEmpty { source }
            val instructionTrust = try {
                ai.neopsyke.agent.model.InstructionTrust.valueOf(
                    ctxNode.path("instruction_trust").asText()
                )
            } catch (_: Exception) {
                ai.neopsyke.agent.model.InstructionTrust.TRUSTED_INSTRUCTION
            }

            // Reconstruct the security context faithfully from recorded fields.
            val channelProvider = ctxNode.path("channel_provider").asText().ifEmpty { source }
            val policyScopeId = ctxNode.path("policy_scope_id").asText().ifEmpty { "default" }
            val security = when (instructionTrust) {
                ai.neopsyke.agent.model.InstructionTrust.TRUSTED_INSTRUCTION ->
                    ConversationSecurityContexts.ownerDirect(
                        provider = channelProvider,
                        channelId = sessionId,
                        policyScopeId = policyScopeId,
                    )
                ai.neopsyke.agent.model.InstructionTrust.UNTRUSTED_INSTRUCTION ->
                    ConversationSecurityContexts.internalAutomation(
                        provider = channelProvider,
                        channelId = sessionId,
                        policyScopeId = policyScopeId,
                    )
            }

            val metadata = buildMap {
                val metaNode = node.path("metadata")
                if (metaNode.isObject) {
                    metaNode.fields().forEach { (k, v) -> put(k, v.asText()) }
                }
            }

            return StimulusEnvelope(
                id = node.path("id").asText().ifEmpty { RootInputIds.next() },
                family = family,
                source = source,
                content = node.path("content").asText(),
                receivedAt = try {
                    Instant.parse(node.path("received_at").asText())
                } catch (_: Exception) {
                    Instant.now()
                },
                correlationId = node.path("correlation_id").asText().ifEmpty { null },
                causationId = node.path("causation_id").asText().ifEmpty { null },
                conversationContext = ConversationContext(
                    sessionId = sessionId,
                    interlocutor = Interlocutor.named(interlocutorId),
                    security = security,
                ),
                trustLevel = trustLevel,
                provenance = Provenances.fromStimulusTrustLevel(
                    source = source,
                    trustLevel = trustLevel,
                    sourceRef = "session-replay",
                ),
                metadata = metadata,
            )
        }
    }
}
