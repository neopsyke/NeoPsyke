package ai.neopsyke.agent.ego

import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.cortex.sensory.ActionFeedbackCue
import ai.neopsyke.agent.cortex.sensory.CognitiveCueMetadata
import ai.neopsyke.agent.cortex.sensory.AssignmentCue
import ai.neopsyke.agent.assignments.AssignmentActivation
import ai.neopsyke.agent.assignments.AssignmentGateway
import ai.neopsyke.agent.model.CognitiveThread
import ai.neopsyke.agent.model.CognitiveThreadKind
import ai.neopsyke.agent.model.CognitiveThreadStatus
import ai.neopsyke.agent.model.InputPriority
import ai.neopsyke.agent.model.Opportunity
import ai.neopsyke.agent.model.PendingFeedback
import ai.neopsyke.agent.model.PendingInput
import ai.neopsyke.agent.model.Percept
import ai.neopsyke.agent.model.Provenances
import ai.neopsyke.agent.model.RootInputIds
import ai.neopsyke.agent.model.StimulusEnvelope
import ai.neopsyke.agent.model.StimulusFamily
import ai.neopsyke.agent.model.StimulusTrustLevel
import ai.neopsyke.instrumentation.AgentEvents
import ai.neopsyke.instrumentation.AgentInstrumentation
import java.time.Instant
import mu.KotlinLogging

private val ingressLogger = KotlinLogging.logger {}

internal class StimulusIngressCoordinator(
    private val config: AgentConfig,
    private val scheduler: AttentionScheduler,
    private val cognitiveThreads: CognitiveThreadStore,
    private val assignmentGateway: AssignmentGateway,
    private val instrumentation: AgentInstrumentation,
    private val telemetry: EgoTelemetry,
    private val shapeOpportunityContract: (Opportunity, String?, ai.neopsyke.agent.model.ConversationContext) -> Opportunity,
    private val emitThreadUpdate: (CognitiveThread, String?, String) -> Unit,
    private val emitOpportunityEnqueued: (Opportunity, String?, String, ai.neopsyke.agent.model.GroundingMetadata?) -> Unit,
) {
    sealed interface Outcome {
        data object NoWork : Outcome
        data class RunLoop(
            val cleanupRootInputId: String? = null,
            val cleanupConversationContext: ai.neopsyke.agent.model.ConversationContext? = null,
        ) : Outcome
    }

    fun ingest(stimulus: StimulusEnvelope, percept: Percept): Outcome {
        AssignmentCue.fromStimulus(stimulus)?.let { cue ->
            return enqueueAssignment(cue, stimulus, percept)
        }
        if (stimulus.metadata[CognitiveCueMetadata.METADATA_CUE_TYPE] ==
            CognitiveCueMetadata.CUE_TYPE_ID_IMPULSE_READY
        ) {
            return bindImpulseWake(stimulus, percept)
        }
        ActionFeedbackCue.fromStimulus(stimulus)?.let { cue ->
            return enqueueFeedback(cue, stimulus, percept)
        }
        return enqueueInput(stimulus, percept)
    }

    fun ingestFeedbackCue(cue: ActionFeedbackCue): Outcome {
        val stimulus = StimulusEnvelope(
            id = RootInputIds.next(),
            family = StimulusFamily.FEEDBACK,
            source = "action-feedback-internal",
            content = cue.feedbackContent,
            receivedAt = Instant.now(),
            conversationContext = cue.conversationContext,
            correlationId = cue.rootInputId,
            causationId = cue.sourceActionId?.toString(),
            trustLevel = StimulusTrustLevel.TRUSTED_INTERNAL,
            provenance = Provenances.trustedSystemSignal(
                provider = "action-feedback-internal",
                sourceRef = cue.rootInputId,
            ),
            metadata = mapOf(
                CognitiveCueMetadata.METADATA_CUE_TYPE to CognitiveCueMetadata.CUE_TYPE_ACTION_FEEDBACK,
            ),
        )
        val percept = Percept(
            id = RootInputIds.next(),
            family = ai.neopsyke.agent.model.PerceptFamily.FEEDBACK,
            summary = cue.feedbackContent,
            source = stimulus.source,
            occurredAt = stimulus.receivedAt,
            conversationContext = cue.conversationContext,
            rootStimulusId = stimulus.id,
            provenance = stimulus.provenance,
            metadata = stimulus.metadata,
        )
        return enqueueFeedback(cue, stimulus, percept)
    }

    private fun enqueueAssignment(
        cue: AssignmentCue,
        stimulus: StimulusEnvelope,
        percept: Percept,
    ): Outcome {
        val work = assignmentGateway.nextWorkFromCue(cue)
        if (work == null) {
            instrumentation.emit(AgentEvents.assignmentUnavailable(cue.reason))
            return Outcome.NoWork
        }
        ingressLogger.info { "Assignment picked: ${work.workItemId}/${work.stepId}" }
        val thread = cognitiveThreads.bindPercept(
            percept = percept.copy(conversationContext = work.conversationContext),
            rootInputId = work.rootInputId,
            kind = CognitiveThreadKind.ASSIGNMENT_DIRECTED,
            title = work.stepDescription,
        )
        emitThreadUpdate(thread, work.rootInputId, "assignment_percept_bound")
        cognitiveThreads.bindAssignment(work)
        val opportunity = shapeOpportunityContract(
            cognitiveThreads.assignmentOpportunity(work),
            work.rootInputId,
            work.conversationContext,
        )
        scheduler.enqueueAssignment(work, opportunity)
        emitOpportunityEnqueued(opportunity, work.rootInputId, "assignment_runtime", work.groundingMetadata)
        return Outcome.RunLoop(
            cleanupRootInputId = work.rootInputId,
            cleanupConversationContext = work.conversationContext,
        )
    }

    private fun bindImpulseWake(stimulus: StimulusEnvelope, percept: Percept): Outcome {
        val rootInputId = stimulus.metadata[CognitiveCueMetadata.METADATA_ROOT_IMPULSE_ID] ?: stimulus.id
        val thread = cognitiveThreads.bindPercept(
            percept = percept,
            rootInputId = rootInputId,
            kind = CognitiveThreadKind.DRIVE,
            title = stimulus.content,
        )
        emitThreadUpdate(thread, rootInputId, "impulse_percept_bound")
        return Outcome.RunLoop()
    }

    private fun enqueueFeedback(
        cue: ActionFeedbackCue,
        stimulus: StimulusEnvelope,
        percept: Percept,
    ): Outcome {
        val resumedFromWaitingThread =
            cognitiveThreads.snapshot(cue.rootInputId, cue.conversationContext)?.waitState?.status ==
                CognitiveThreadStatus.WAITING ||
                cognitiveThreads.thread(cue.rootInputId, cue.conversationContext)?.status == CognitiveThreadStatus.WAITING
        val thread = cognitiveThreads.bindPercept(
            percept = percept,
            rootInputId = cue.rootInputId,
            kind = cognitiveThreads.thread(cue.rootInputId, cue.conversationContext)?.kind ?: CognitiveThreadKind.CONVERSATION,
            title = cue.actionSummary.ifBlank { stimulus.content },
        )
        emitThreadUpdate(thread, cue.rootInputId, "feedback_percept_bound")
        val feedback = PendingFeedback(
            cue = cue,
            percept = percept.copy(cognitiveThreadId = thread.id),
            stimulusId = stimulus.id,
            stimulusContent = stimulus.content,
            receivedAtMs = stimulus.receivedAt.toEpochMilli(),
            resumedFromWaitingThread = resumedFromWaitingThread,
        )
        cue.groundingMetadata.let { metadata ->
            instrumentation.emit(
                AgentEvents.groundingMetadataPropagated(
                    rootInputId = cue.rootInputId,
                    fromEnvelopeType = "action_feedback_cue",
                    toEnvelopeType = "pending_feedback",
                    groundingRequired = metadata.requirement == ai.neopsyke.agent.model.GroundingRequirement.REQUIRED,
                    source = metadata.source.name.lowercase(),
                )
            )
        }
        val opportunity = shapeOpportunityContract(
            cognitiveThreads.feedbackOpportunity(feedback),
            cue.rootInputId,
            cue.conversationContext,
        )
        if (!scheduler.enqueueFeedback(feedback, opportunity)) {
            ingressLogger.warn { "Input queue full; dropping feedback stimulus." }
            instrumentation.emit(AgentEvents.warning("Input queue full; dropping feedback stimulus."))
            telemetry.recordQueueSaturation(
                queueType = "input",
                capacity = config.maxPendingInputs,
                reason = "enqueue_feedback_failed_full",
            )
            return Outcome.NoWork
        }
        emitOpportunityEnqueued(opportunity, cue.rootInputId, "feedback", cue.groundingMetadata)
        telemetry.emitQueueSnapshot("feedback_enqueued")
        return Outcome.RunLoop()
    }

    private fun enqueueInput(
        stimulus: StimulusEnvelope,
        percept: Percept,
    ): Outcome {
        val thread = cognitiveThreads.bindPercept(
            percept = percept,
            rootInputId = stimulus.id,
            kind = CognitiveThreadKind.CONVERSATION,
            title = stimulus.content,
        )
        emitThreadUpdate(thread, stimulus.id, "input_percept_bound")
        val input = PendingInput(
            id = 0L,
            content = stimulus.content,
            priority = stimulus.metadata["priority"]
                ?.let { runCatching { InputPriority.valueOf(it) }.getOrNull() }
                ?: InputPriority.HIGH,
            source = stimulus.source,
            rootInputId = stimulus.id,
            receivedAtMs = stimulus.receivedAt.toEpochMilli(),
            conversationContext = stimulus.conversationContext,
            percept = percept.copy(cognitiveThreadId = thread.id),
            cognitiveThreadId = thread.id,
        )
        val opportunity = shapeOpportunityContract(
            cognitiveThreads.inputOpportunity(input),
            input.rootInputId,
            input.conversationContext,
        )
        if (!scheduler.enqueueInput(input, opportunity)) {
            ingressLogger.warn { "Input queue full; dropping input." }
            instrumentation.emit(AgentEvents.warning("Input queue full; dropping input."))
            telemetry.recordQueueSaturation(
                queueType = "input",
                capacity = config.maxPendingInputs,
                reason = "enqueue_input_failed_full",
            )
            return Outcome.NoWork
        }
        scheduler.latestQueuedInput()?.let { queuedInput ->
            instrumentation.emit(AgentEvents.inputQueued(queuedInput))
        }
        emitOpportunityEnqueued(opportunity, input.rootInputId, "input", input.groundingMetadata)
        telemetry.emitQueueSnapshot("input_enqueued")
        return Outcome.RunLoop()
    }
}
