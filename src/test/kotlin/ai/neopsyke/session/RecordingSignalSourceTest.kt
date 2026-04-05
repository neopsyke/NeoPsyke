package ai.neopsyke.session

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ai.neopsyke.agent.cortex.sensory.CognitiveSignal
import ai.neopsyke.agent.cortex.sensory.GoalRuntimeCue
import ai.neopsyke.agent.cortex.sensory.RuntimeControlSignal
import ai.neopsyke.agent.cortex.sensory.Signal
import ai.neopsyke.agent.cortex.sensory.SignalSource
import ai.neopsyke.agent.model.ChannelSurface
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.ConversationSecurityContexts
import ai.neopsyke.agent.model.Interlocutor
import ai.neopsyke.agent.model.PrincipalRole
import ai.neopsyke.agent.model.Provenances
import ai.neopsyke.agent.model.StimulusEnvelope
import ai.neopsyke.agent.model.StimulusFamily
import ai.neopsyke.agent.model.StimulusTrustLevel
import ai.neopsyke.agent.model.TransportClass
import ai.neopsyke.instrumentation.AgentEvent
import ai.neopsyke.instrumentation.AgentInstrumentation
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class RecordingSignalSourceTest {
    private val mapper = jacksonObjectMapper()

    private class RecordingInstrumentation : AgentInstrumentation {
        val events = mutableListOf<AgentEvent>()
        override fun emit(event: AgentEvent) {
            events.add(event)
        }
    }

    private fun testStimulus(content: String): StimulusEnvelope =
        StimulusEnvelope(
            id = "test-${content.hashCode()}",
            family = StimulusFamily.LINGUISTIC,
            source = "test",
            content = content,
            receivedAt = Instant.parse("2026-03-26T12:00:00Z"),
            conversationContext = ConversationContext.default(),
            trustLevel = StimulusTrustLevel.DEFAULT,
            provenance = Provenances.trustedMessage(provider = "test", sourceRef = "test"),
        )

    private class QueuedSignalSource(private val signals: List<Signal>) : SignalSource {
        private var index = 0
        override suspend fun nextSignal(): Signal {
            if (index >= signals.size) return RuntimeControlSignal.ExitRequested(source = "test-exhausted")
            return signals[index++]
        }
    }

    @Test
    fun `RECORD mode passes through and records signals`() = runBlocking {
        val file = Files.createTempFile("session-signal-rec-", ".jsonl")
        try {
            val stimulus = testStimulus("hello user")
            val delegate = QueuedSignalSource(listOf(
                CognitiveSignal.StimulusReceived(stimulus),
                RuntimeControlSignal.ExitRequested(source = "test"),
            ))

            val channel = RecordReplayChannel(
                channelName = "signals",
                mode = SessionRecordingMode.RECORD,
                file = file,
            )
            val source = RecordingSignalSource(delegate = delegate, channel = channel)

            // First call should record the stimulus
            val signal1 = source.nextSignal()
            assertIs<CognitiveSignal.StimulusReceived>(signal1)
            assertEquals("hello user", signal1.stimulus.content)

            // Second call is a control signal — not recorded
            val signal2 = source.nextSignal()
            assertIs<RuntimeControlSignal.ExitRequested>(signal2)

            channel.close()

            // Verify one entry was recorded
            val lines = Files.readAllLines(file).filter { it.isNotBlank() }
            assertEquals(1, lines.size)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `REPLAY mode feeds signals from recording`() = runBlocking {
        val file = Files.createTempFile("session-signal-replay-", ".jsonl")
        try {
            // Record a stimulus
            val stimulus = testStimulus("recorded input")
            val recordDelegate = QueuedSignalSource(listOf(
                CognitiveSignal.StimulusReceived(stimulus),
            ))
            val recordChannel = RecordReplayChannel(
                channelName = "signals",
                mode = SessionRecordingMode.RECORD,
                file = file,
            )
            val recordSource = RecordingSignalSource(delegate = recordDelegate, channel = recordChannel)
            recordSource.nextSignal() // records the signal
            recordChannel.close()

            // Replay
            val instrumentation = RecordingInstrumentation()
            val replayDelegate = QueuedSignalSource(emptyList()) // never called
            val replayChannel = RecordReplayChannel(
                channelName = "signals",
                mode = SessionRecordingMode.REPLAY,
                file = file,
                instrumentation = instrumentation,
            )
            val replaySource = RecordingSignalSource(delegate = replayDelegate, channel = replayChannel)

            val replayed = replaySource.nextSignal()
            assertIs<CognitiveSignal.StimulusReceived>(replayed)
            assertEquals("recorded input", replayed.stimulus.content)
            assertEquals("test", replayed.stimulus.source)
            assertEquals(StimulusFamily.LINGUISTIC, replayed.stimulus.family)

            // Next call should signal exhaustion
            val exit = replaySource.nextSignal()
            assertIs<RuntimeControlSignal.ExitRequested>(exit)
            assertEquals("session-replay", exit.source)

            replayChannel.close()
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `REPLAY preserves stimulus metadata`() = runBlocking {
        val file = Files.createTempFile("session-signal-meta-", ".jsonl")
        try {
            val stimulus = StimulusEnvelope(
                id = "meta-test-id",
                family = StimulusFamily.CUE,
                source = "id",
                content = "id_impulse_ready",
                receivedAt = Instant.parse("2026-03-26T14:00:00Z"),
                conversationContext = ConversationContext.default(),
                trustLevel = StimulusTrustLevel.TRUSTED_INTERNAL,
                provenance = Provenances.trustedSystemSignal(provider = "id", sourceRef = "impulse-1"),
                metadata = mapOf("cue_type" to "id_impulse_ready", "root_impulse_id" to "imp-123"),
            )
            val recordDelegate = QueuedSignalSource(listOf(
                CognitiveSignal.StimulusReceived(stimulus),
            ))
            val recordChannel = RecordReplayChannel(
                channelName = "signals",
                mode = SessionRecordingMode.RECORD,
                file = file,
            )
            RecordingSignalSource(delegate = recordDelegate, channel = recordChannel).nextSignal()
            recordChannel.close()

            // Replay
            val replayChannel = RecordReplayChannel(
                channelName = "signals",
                mode = SessionRecordingMode.REPLAY,
                file = file,
            )
            val replaySource = RecordingSignalSource(
                delegate = QueuedSignalSource(emptyList()),
                channel = replayChannel,
            )
            val replayed = replaySource.nextSignal()
            assertIs<CognitiveSignal.StimulusReceived>(replayed)
            val s = replayed.stimulus
            assertEquals(StimulusFamily.CUE, s.family)
            assertEquals("id", s.source)
            assertEquals("id_impulse_ready", s.content)
            assertEquals(StimulusTrustLevel.TRUSTED_INTERNAL, s.trustLevel)
            assertEquals("id_impulse_ready", s.metadata["cue_type"])
            assertEquals("imp-123", s.metadata["root_impulse_id"])

            replayChannel.close()
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `REPLAY preserves correlation and causation ids`() = runBlocking {
        val file = Files.createTempFile("session-signal-correlation-", ".jsonl")
        try {
            val stimulus = StimulusEnvelope(
                id = "feedback-id",
                family = StimulusFamily.FEEDBACK,
                source = "action-feedback",
                content = "result ready",
                receivedAt = Instant.parse("2026-03-26T14:30:00Z"),
                correlationId = "root-123",
                causationId = "77",
                conversationContext = ConversationContext.default(),
                trustLevel = StimulusTrustLevel.TRUSTED_INTERNAL,
                provenance = Provenances.trustedSystemSignal(provider = "action-feedback", sourceRef = "root-123"),
                metadata = mapOf("cue_type" to "action_feedback"),
            )
            val recordChannel = RecordReplayChannel(
                channelName = "signals",
                mode = SessionRecordingMode.RECORD,
                file = file,
            )
            RecordingSignalSource(
                delegate = QueuedSignalSource(listOf(CognitiveSignal.StimulusReceived(stimulus))),
                channel = recordChannel,
            ).nextSignal()
            recordChannel.close()

            val replayChannel = RecordReplayChannel(
                channelName = "signals",
                mode = SessionRecordingMode.REPLAY,
                file = file,
            )
            val replayed = RecordingSignalSource(
                delegate = QueuedSignalSource(emptyList()),
                channel = replayChannel,
            ).nextSignal()
            val replayedStimulus = assertIs<CognitiveSignal.StimulusReceived>(replayed).stimulus
            assertEquals("root-123", replayedStimulus.correlationId)
            assertEquals("77", replayedStimulus.causationId)
            replayChannel.close()
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `REPLAY preserves untrusted external security context`() = runBlocking {
        val file = Files.createTempFile("session-signal-external-security-", ".jsonl")
        try {
            val stimulus = StimulusEnvelope(
                id = "external-security-id",
                family = StimulusFamily.LINGUISTIC,
                source = "slack",
                content = "please create a goal",
                receivedAt = Instant.parse("2026-03-26T15:00:00Z"),
                conversationContext = ConversationContext(
                    sessionId = "shared-session",
                    interlocutor = Interlocutor.named("teammate"),
                    security = ConversationSecurityContexts.externalParticipant(
                        provider = "slack",
                        channelId = "team-channel",
                        surface = ChannelSurface.GROUP,
                        transport = TransportClass.CHAT,
                    ),
                ),
                trustLevel = StimulusTrustLevel.DEFAULT,
                provenance = Provenances.defaultExternal(sourceRef = "team-channel"),
            )
            val recordChannel = RecordReplayChannel(
                channelName = "signals",
                mode = SessionRecordingMode.RECORD,
                file = file,
            )
            RecordingSignalSource(
                delegate = QueuedSignalSource(listOf(CognitiveSignal.StimulusReceived(stimulus))),
                channel = recordChannel,
            ).nextSignal()
            recordChannel.close()

            val replayChannel = RecordReplayChannel(
                channelName = "signals",
                mode = SessionRecordingMode.REPLAY,
                file = file,
            )
            val replayed = RecordingSignalSource(
                delegate = QueuedSignalSource(emptyList()),
                channel = replayChannel,
            ).nextSignal()
            val replayedStimulus = assertIs<CognitiveSignal.StimulusReceived>(replayed).stimulus
            assertEquals(PrincipalRole.EXTERNAL_PARTICIPANT, replayedStimulus.conversationContext.security.principal.role)
            assertEquals(
                ai.neopsyke.agent.model.InstructionTrust.UNTRUSTED_INSTRUCTION,
                replayedStimulus.conversationContext.security.instructionTrust,
            )
            assertEquals(ChannelSurface.GROUP, replayedStimulus.conversationContext.security.channel.surface)
            assertEquals("team-channel", replayedStimulus.conversationContext.security.channel.channelId)
            replayChannel.close()
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `REPLAY preserves trusted internal automation security context`() = runBlocking {
        val file = Files.createTempFile("session-signal-internal-security-", ".jsonl")
        try {
            val stimulus = GoalRuntimeCue(goalId = "goal-1", stepId = "step-1", reason = "ready").toStimulus()
            val recordChannel = RecordReplayChannel(
                channelName = "signals",
                mode = SessionRecordingMode.RECORD,
                file = file,
            )
            RecordingSignalSource(
                delegate = QueuedSignalSource(listOf(CognitiveSignal.StimulusReceived(stimulus))),
                channel = recordChannel,
            ).nextSignal()
            recordChannel.close()

            val replayChannel = RecordReplayChannel(
                channelName = "signals",
                mode = SessionRecordingMode.REPLAY,
                file = file,
            )
            val replayed = RecordingSignalSource(
                delegate = QueuedSignalSource(emptyList()),
                channel = replayChannel,
            ).nextSignal()
            val replayedStimulus = assertIs<CognitiveSignal.StimulusReceived>(replayed).stimulus
            assertEquals(PrincipalRole.SYSTEM_INTERNAL, replayedStimulus.conversationContext.security.principal.role)
            assertEquals(ChannelSurface.AUTOMATION, replayedStimulus.conversationContext.security.channel.surface)
            assertEquals(TransportClass.INTERNAL, replayedStimulus.conversationContext.security.channel.transport)
            replayChannel.close()
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `REPLAY legacy untrusted signal defaults to external participant instead of internal automation`() = runBlocking {
        val file = Files.createTempFile("session-signal-legacy-untrusted-", ".jsonl")
        try {
            val data = mapper.createObjectNode().apply {
                put("id", "legacy-untrusted-id")
                put("family", StimulusFamily.LINGUISTIC.name)
                put("source", "legacy-source")
                put("content", "legacy payload")
                put("received_at", Instant.parse("2026-03-26T16:00:00Z").toString())
                put("trust_level", StimulusTrustLevel.DEFAULT.name)
                set<com.fasterxml.jackson.databind.node.ObjectNode>(
                    "conversation_context",
                    mapper.createObjectNode().apply {
                        put("session_id", "legacy-session")
                        put("interlocutor_id", "legacy-user")
                        put("instruction_trust", ai.neopsyke.agent.model.InstructionTrust.UNTRUSTED_INSTRUCTION.name)
                        put("channel_provider", "legacy-provider")
                    },
                )
                set<com.fasterxml.jackson.databind.node.ObjectNode>("metadata", mapper.createObjectNode())
            }
            val entry = SessionRecordEntry(
                seq = 0,
                hash = RecordReplayChannel.hashContent("signal:0"),
                channel = SessionRecordingManager.CHANNEL_SIGNALS,
                data = data,
            )
            Files.writeString(file, mapper.writeValueAsString(entry) + "\n")

            val replayChannel = RecordReplayChannel(
                channelName = "signals",
                mode = SessionRecordingMode.REPLAY,
                file = file,
            )
            val replayed = RecordingSignalSource(
                delegate = QueuedSignalSource(emptyList()),
                channel = replayChannel,
            ).nextSignal()
            val replayedStimulus = assertIs<CognitiveSignal.StimulusReceived>(replayed).stimulus
            assertEquals(PrincipalRole.EXTERNAL_PARTICIPANT, replayedStimulus.conversationContext.security.principal.role)
            assertEquals(
                ai.neopsyke.agent.model.InstructionTrust.UNTRUSTED_INSTRUCTION,
                replayedStimulus.conversationContext.security.instructionTrust,
            )
            replayChannel.close()
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `NoStimulus signals are not recorded`() = runBlocking {
        val file = Files.createTempFile("session-signal-nostim-", ".jsonl")
        try {
            val delegate = QueuedSignalSource(listOf(
                CognitiveSignal.NoStimulus,
                CognitiveSignal.StimulusReceived(testStimulus("real input")),
                CognitiveSignal.NoStimulus,
            ))
            val channel = RecordReplayChannel(
                channelName = "signals",
                mode = SessionRecordingMode.RECORD,
                file = file,
            )
            val source = RecordingSignalSource(delegate = delegate, channel = channel)

            // NoStimulus — not recorded
            val s1 = source.nextSignal()
            assertIs<CognitiveSignal.NoStimulus>(s1)

            // StimulusReceived — recorded
            val s2 = source.nextSignal()
            assertIs<CognitiveSignal.StimulusReceived>(s2)

            // NoStimulus — not recorded
            val s3 = source.nextSignal()
            assertIs<CognitiveSignal.NoStimulus>(s3)

            channel.close()

            val lines = Files.readAllLines(file).filter { it.isNotBlank() }
            assertEquals(1, lines.size)
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
