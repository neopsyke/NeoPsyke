package ai.neopsyke.agent.durablework

import kotlinx.coroutines.CoroutineScope
import mu.KotlinLogging
import ai.neopsyke.agent.cortex.motor.actions.async.AsyncOperationEvent
import ai.neopsyke.agent.cortex.motor.actions.async.AsyncOperationRegistry
import ai.neopsyke.agent.cortex.sensory.DurableWorkCue
import ai.neopsyke.agent.ego.ActionExecutionGateDecision
import ai.neopsyke.agent.id.WorkItemCommitment
import ai.neopsyke.agent.model.ActionExecutionStatus
import ai.neopsyke.agent.model.ActionOutcome
import ai.neopsyke.agent.model.PendingAction
import ai.neopsyke.agent.model.OriginSource
import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.instrumentation.AgentEvents
import ai.neopsyke.instrumentation.AgentInstrumentation
import ai.neopsyke.instrumentation.NoopAgentInstrumentation
import java.nio.file.Files
import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

private data class DurableWorkRunSession(
    val workItemId: String,
    val stepId: String,
    val rootInputId: String,
    val createdAt: Instant = Instant.now(),
    val actionCount: Int = 0,
    val lastResultSummary: String = "",
    val allowFollowUp: Boolean = true,
    val requeueReason: String? = null,
    val wakeReasons: List<WakeReason> = emptyList(),
)

class DurableWorkRuntime(
    private val config: DurableWorkConfig,
    private val store: WorkItemStore,
    private val planner: WorkPlanBuilder = DeterministicWorkPlanBuilder(),
    private val verifier: WorkStepVerifier = DeterministicWorkStepVerifier(),
    private val asyncOperationRegistry: AsyncOperationRegistry = AsyncOperationRegistry.empty(),
    private val instrumentation: AgentInstrumentation = NoopAgentInstrumentation,
    private val cueEmitter: (DurableWorkCue) -> Unit = {},
) : DurableWorkGateway {
    private val states = ConcurrentHashMap<String, WorkItemState>()
    private val sessionsByRootInputId = ConcurrentHashMap<String, DurableWorkRunSession>()

    /** Per-item locks for single-writer invariant. */
    private val itemLocks = ConcurrentHashMap<String, java.util.concurrent.locks.ReentrantLock>()

    /** Active leases: workItemId → leaseToken. */
    private val activeLeases = ConcurrentHashMap<String, String>()

    /** Activation journal for restart recovery. */
    private val activationJournal = ActivationJournal(
        config.workspaceRoot.resolve("activation-journal.jsonl")
    )
    private val workEffectLedger = WorkEffectLedger(
        config.workspaceRoot.resolve("effect-ledger.json")
    )
    private val effectIntentByActionId = ConcurrentHashMap<Long, String>()

    @Volatile
    private var activeWorkItemsSnapshot: List<WorkItemCommitment> = emptyList()

    @Volatile
    private var pendingWorkSummarySnapshot: String = ""

    @Volatile
    private var timerScheduler: TimerScheduler? = null

    @Volatile
    private var waitConditionMonitor: WaitConditionMonitor? = null

    override fun start(scope: CoroutineScope) {
        timerScheduler = TimerScheduler(
            resolutionMs = config.timerResolutionMs,
            onWakeUp = ::onTimerWake,
        ).also { it.start(scope) }
        waitConditionMonitor = WaitConditionMonitor(
            checkIntervalMs = config.conditionCheckIntervalMs,
            asyncOperationRegistry = asyncOperationRegistry,
            onConditionSatisfied = { workItemId, stepId, resolution ->
                onWaitConditionSatisfied(workItemId, stepId, resolution)
            },
            onConditionTimedOut = { workItemId, stepId ->
                applyEvent(workItemId, WorkItemEvent.WaitConditionTimedOut(workItemId, stepId))
            },
        ).also { it.start(scope) }

        workEffectLedger.load()
        restoreWorkItems()
        refreshAmbientSnapshots()
    }

    override fun stop() {
        timerScheduler?.stop()
        timerScheduler = null
        waitConditionMonitor?.stop()
        waitConditionMonitor = null
    }

    override fun nextWorkFromCue(cue: DurableWorkCue): DurableWorkActivation? {
        val itemId = cue.workItemId
        return withItemLock(itemId) {
            val state = states[itemId] ?: return@withItemLock null

            // Lease check: if already leased, coalesce this wake
            if (activeLeases.containsKey(itemId)) {
                coalescePendingWake(itemId, cue)
                return@withItemLock null
            }

            val preparedState = ensureWakeReadyState(itemId, state, cue.wakeReasonType, cue.reason)
            val step = preparedState.nextRunnableStep() ?: return@withItemLock null

            // Acquire lease
            val leaseToken = acquireLease(itemId)

            val startedState = if (step.status == StepStatus.READY) {
                applyEvent(preparedState.id, WorkItemEvent.StepStarted(preparedState.id, step.id)) ?: preparedState
            } else {
                preparedState
            }
            val startedStep = startedState.workItem.plan.steps.firstOrNull { it.id == step.id } ?: step
            val rootInputId = buildWorkItemRootInputId(preparedState.id, step.id)
            val wakeReasons = listOf(
                WakeReason(
                    type = cue.wakeReasonType ?: inferWakeReasonType(cue.reason),
                    detail = cue.wakeReasonDetail ?: cue.reason.ifBlank { null },
                )
            )
            sessionsByRootInputId[rootInputId] = DurableWorkRunSession(
                workItemId = preparedState.id,
                stepId = step.id,
                rootInputId = rootInputId,
                wakeReasons = wakeReasons,
            )

            // Record activation event and journal boundary
            applyEvent(itemId, WorkItemEvent.ActivationStarted(
                workItemId = itemId,
                stepId = step.id,
                leaseToken = leaseToken,
                planRevision = startedState.workItem.planRevision,
                wakeReasons = wakeReasons,
            ))
            activationJournal.append(ActivationJournalEntry(
                workItemId = itemId,
                stepId = step.id,
                leaseToken = leaseToken,
                planRevision = startedState.workItem.planRevision,
                boundary = ActivationBoundary.STARTED,
            ))
            activationJournal.append(ActivationJournalEntry(
                workItemId = itemId,
                stepId = step.id,
                leaseToken = leaseToken,
                planRevision = startedState.workItem.planRevision,
                boundary = ActivationBoundary.STEP_SELECTED,
            ))

            instrumentation.emit(AgentEvents.durableWorkWakeUp(state.id, "work_ready", cue.reason))
            val activation = WorkContextLoader.buildWorkUnit(
                state = startedState,
                step = startedStep,
                rootInputId = rootInputId,
                wakeReason = cue.reason,
                wakeReasons = wakeReasons,
            )
            activationJournal.append(ActivationJournalEntry(
                workItemId = itemId,
                stepId = step.id,
                leaseToken = leaseToken,
                planRevision = startedState.workItem.planRevision,
                boundary = ActivationBoundary.CONTEXT_MATERIALIZED,
            ))
            applyEvent(itemId, WorkItemEvent.LeaseHeartbeat(itemId, leaseToken))
            activation
        }
    }

    override fun pendingWorkSummary(): String {
        return pendingWorkSummarySnapshot
    }

    override fun beforeActionExecution(action: PendingAction): ActionExecutionGateDecision {
        if (action.origin.source != OriginSource.DURABLE_WORK) {
            return ActionExecutionGateDecision.allow()
        }
        val rootInputId = action.rootInputId ?: return ActionExecutionGateDecision.allow()
        val session = sessionsByRootInputId[rootInputId] ?: return ActionExecutionGateDecision.allow()
        val effectClass = classifyEffectClass(action.type)
        if (effectClass == EffectClass.OBSERVE) {
            return ActionExecutionGateDecision.allow()
        }
        val planRevision = states[session.workItemId]?.workItem?.planRevision ?: DEFAULT_PLAN_REVISION
        val effectIntentId = workEffectLedger.deriveEffectIntentId(
            workItemId = session.workItemId,
            planRevision = planRevision,
            stepId = session.stepId,
            logicalEffectKey = action.type.id,
        )
        if (workEffectLedger.isEffectCompleted(effectIntentId)) {
            return ActionExecutionGateDecision.deny(
                reason = "Skipped duplicate durable-work side effect (${action.type.id}).",
                reasonCode = EFFECT_INTENT_DUPLICATE_REASON_CODE,
                source = EFFECT_INTENT_GATE_SOURCE,
            )
        }
        val recorded = workEffectLedger.recordIntent(
            effectIntentId = effectIntentId,
            actionType = action.type.id,
            effectClass = effectClass,
        )
        if (!recorded) {
            return ActionExecutionGateDecision.deny(
                reason = "Skipped duplicate durable-work side effect (${action.type.id}).",
                reasonCode = EFFECT_INTENT_DUPLICATE_REASON_CODE,
                source = EFFECT_INTENT_GATE_SOURCE,
            )
        }
        effectIntentByActionId[action.id] = effectIntentId
        applyEvent(
            session.workItemId,
            WorkItemEvent.EffectIntentRecorded(
                workItemId = session.workItemId,
                effectIntentId = effectIntentId,
                actionType = action.type.id,
            )
        )
        return ActionExecutionGateDecision.allow()
    }

    override fun onActionExecuted(action: PendingAction, outcome: ActionOutcome, observedEvidence: Boolean) {
        if (action.origin.source != OriginSource.DURABLE_WORK) return
        val rootInputId = action.rootInputId ?: return
        val session = sessionsByRootInputId[rootInputId] ?: return
        finalizeEffectIntent(session.workItemId, action, outcome)
        val stepOutput = outcome.plannerSignal.ifBlank { outcome.statusSummary }
        val actionCount = session.actionCount + 1
        sessionsByRootInputId[rootInputId] = session.copy(
            actionCount = actionCount,
            lastResultSummary = stepOutput,
            allowFollowUp = actionCount < config.actionsPerCycle,
        )

        val afterAction = applyEvent(
            session.workItemId,
            WorkItemEvent.StepActionExecuted(session.workItemId, session.stepId, stepOutput)
        ) ?: return
        val step = afterAction.workItem.plan.steps.firstOrNull { it.id == session.stepId } ?: return
        if (outcome.executionStatus == ai.neopsyke.agent.model.ActionExecutionStatus.WAITING && outcome.asyncWait == null) {
            val message =
                "Work item action returned WAITING without async handles; treating as retryable contract violation."
            logger.warn { "Work item async wait contract violation: workItem=${session.workItemId}, step=${session.stepId}, action=${action.type.id}" }
            instrumentation.emit(AgentEvents.warning(message))
            sessionsByRootInputId[rootInputId] = sessionsByRootInputId[rootInputId]
                ?.copy(allowFollowUp = false, requeueReason = null)
                ?: return
            applyEvent(
                session.workItemId,
                WorkItemEvent.StepAcceptanceFailed(
                    workItemId = session.workItemId,
                    stepId = session.stepId,
                    reason = message,
                )
            )
            return
        }
        if (outcome.waiting) {
            sessionsByRootInputId[rootInputId] = sessionsByRootInputId[rootInputId]
                ?.copy(allowFollowUp = false, requeueReason = null)
                ?: return
            applyEvent(
                session.workItemId,
                WorkItemEvent.StepBlocked(
                    workItemId = session.workItemId,
                    stepId = session.stepId,
                    waitCondition = buildWaitConditionForAsyncOutcome(outcome),
                )
            )
            return
        }
        val verification = verifier.evaluate(afterAction.workItem, step, action, outcome, observedEvidence)

        when (verification.verdict) {
            WorkStepVerdict.PASS -> {
                sessionsByRootInputId[rootInputId] = sessionsByRootInputId[rootInputId]
                    ?.copy(allowFollowUp = false, requeueReason = null)
                    ?: return
                applyEvent(session.workItemId, WorkItemEvent.StepAcceptancePassed(session.workItemId, session.stepId))
            }

            WorkStepVerdict.RETRY -> {
                sessionsByRootInputId[rootInputId] = sessionsByRootInputId[rootInputId]
                    ?.copy(allowFollowUp = false, requeueReason = null)
                    ?: return
                applyEvent(
                    session.workItemId,
                    WorkItemEvent.StepAcceptanceFailed(
                        session.workItemId,
                        session.stepId,
                        verification.reason.ifBlank { outcome.statusSummary },
                    )
                )
            }

            WorkStepVerdict.BLOCK -> {
                sessionsByRootInputId[rootInputId] = sessionsByRootInputId[rootInputId]
                    ?.copy(allowFollowUp = false, requeueReason = null)
                    ?: return
                val waitCondition = verification.waitCondition ?: WaitCondition(
                    type = WaitConditionType.CONDITION_CHECK,
                    params = emptyMap(),
                    registeredAt = Instant.now(),
                )
                applyEvent(
                    session.workItemId,
                    WorkItemEvent.StepBlocked(session.workItemId, session.stepId, waitCondition)
                )
            }

            WorkStepVerdict.CONTINUE -> {
                sessionsByRootInputId[rootInputId] = sessionsByRootInputId[rootInputId]
                    ?.copy(
                        allowFollowUp = actionCount < config.actionsPerCycle,
                        requeueReason = "step_continue"
                    )
                    ?: return
            }

            WorkStepVerdict.FAIL -> {
                sessionsByRootInputId[rootInputId] = sessionsByRootInputId[rootInputId]
                    ?.copy(allowFollowUp = false, requeueReason = null)
                    ?: return
                applyEvent(
                    session.workItemId,
                    WorkItemEvent.StepAcceptanceFailed(
                        session.workItemId,
                        session.stepId,
                        verification.reason.ifBlank { outcome.statusSummary },
                    )
                )
            }
        }
    }

    override fun onActionBlocked(action: PendingAction, reason: String, reasonCode: String?, source: String) {
        if (action.origin.source != OriginSource.DURABLE_WORK) return
        val rootInputId = action.rootInputId ?: return
        val session = sessionsByRootInputId[rootInputId] ?: return
        abandonEffectIntent(
            workItemId = session.workItemId,
            action = action,
            reason = listOfNotNull(reasonCode, source, reason).joinToString(": ").ifBlank { "blocked" },
        )
        sessionsByRootInputId[rootInputId] = session.copy(allowFollowUp = false, requeueReason = null)
        applyEvent(
            session.workItemId,
            WorkItemEvent.StepAcceptanceFailed(
                workItemId = session.workItemId,
                stepId = session.stepId,
                reason = listOfNotNull(reasonCode, source, reason).joinToString(": "),
            )
        )
    }

    override fun allowFollowUp(action: PendingAction): Boolean {
        if (action.origin.source != OriginSource.DURABLE_WORK) return true
        val session = action.rootInputId?.let { sessionsByRootInputId[it] } ?: return true
        return session.allowFollowUp && session.actionCount < config.actionsPerCycle
    }

    override fun finalizeDurableWorkCycle(rootInputId: String) {
        val session = sessionsByRootInputId.remove(rootInputId) ?: return
        val state = states[session.workItemId] ?: return
        val leaseToken = state.workItem.activeLease ?: activeLeases[session.workItemId].orEmpty()

        writeWorkspaceCycleArtifacts(state, session)
        recordDeliveryOutcome(state, session)
        session.wakeReasons.firstOrNull()?.type?.takeIf { it in REVIEW_WAKE_REASON_TYPES }?.let { wakeReasonType ->
            applyEvent(
                session.workItemId,
                WorkItemEvent.ReviewRecorded(
                    workItemId = session.workItemId,
                    wakeReasonType = wakeReasonType,
                    outcome = session.lastResultSummary.ifBlank { "review_completed" },
                    summary = state.workItem.operatorSummary.ifBlank { null },
                )
            )
        }
        applyEvent(
            session.workItemId,
            WorkItemEvent.WorkCycleCompleted(
                workItemId = session.workItemId,
                stepId = session.stepId,
                actionsExecuted = session.actionCount,
            )
        )
        applyEvent(
            session.workItemId,
            WorkItemEvent.ActivationFinished(
                workItemId = session.workItemId,
                stepId = session.stepId,
                leaseToken = leaseToken,
                actionsExecuted = session.actionCount,
            )
        )

        // Record activation finished and release lease
        activationJournal.append(ActivationJournalEntry(
            workItemId = session.workItemId,
            stepId = session.stepId,
            leaseToken = leaseToken,
            planRevision = state.workItem.planRevision,
            boundary = ActivationBoundary.FINISHED,
            detail = session.lastResultSummary.take(ACTIVATION_JOURNAL_DETAIL_MAX_CHARS),
        ))
        releaseLease(session.workItemId)

        if (!session.requeueReason.isNullOrBlank()) {
            val refreshed = states[session.workItemId] ?: return
            val runnable = refreshed.nextRunnableStep() ?: return
            cueEmitter(
                DurableWorkCue(
                    workItemId = refreshed.id,
                    stepId = runnable.id,
                    reason = session.requeueReason,
                    wakeReasonType = WakeReasonType.COALESCED_WAKE,
                    wakeReasonDetail = session.requeueReason,
                )
            )
        }
    }

    override fun notifyStepPlannerNoop(rootInputId: String, reason: String) {
        val session = sessionsByRootInputId[rootInputId] ?: return
        val state = states[session.workItemId] ?: return
        val step = state.workItem.plan.steps.firstOrNull { it.id == session.stepId } ?: return
        logger.warn {
            "Planner noop for work step: workItem=${session.workItemId} step=${session.stepId} " +
                "attempt=${step.attempts + 1}/${step.maxAttempts} reason=$reason"
        }
        // Count the noop as an attempt so the step can eventually fail after maxAttempts.
        applyEvent(
            session.workItemId,
            WorkItemEvent.StepActionExecuted(session.workItemId, session.stepId, "planner_noop: $reason")
        )
        applyEvent(
            session.workItemId,
            WorkItemEvent.StepAcceptanceFailed(session.workItemId, session.stepId, "planner_noop: $reason")
        )
        // If the step is still retryable (READY), set requeueReason so finalizeDurableWorkCycle
        // will requeue after releasing the lease.
        val refreshed = states[session.workItemId] ?: return
        val updatedStep = refreshed.workItem.plan.steps.firstOrNull { it.id == session.stepId } ?: return
        if (updatedStep.status == StepStatus.READY) {
            sessionsByRootInputId[rootInputId] = session.copy(
                actionCount = session.actionCount,
                allowFollowUp = false,
                requeueReason = "planner_noop_retry",
            )
        }
    }

    override fun notifyAsyncOperationEvent(event: AsyncOperationEvent): Int =
        waitConditionMonitor?.notifyAsyncEvent(event) ?: 0

    override fun executeOperation(request: DurableWorkOperationRequest): DurableWorkOperationResult {
        logger.info {
            "durable_work_operation: op=${request.operation} workItemId='${request.workItemId.orEmpty()}' " +
                "title='${request.title?.take(60).orEmpty()}' cron='${request.cronExpression.orEmpty()}'"
        }
        return when (request.operation) {
            DurableWorkOperation.CREATE -> {
                val instruction = request.instruction?.trim().orEmpty()
                val cronExpression = request.cronExpression?.trim().orEmpty()
                if (instruction.isBlank()) {
                    DurableWorkOperationResult(false, "Work item creation requires an instruction.")
                } else if (cronExpression.isNotBlank() && !isValidCronExpression(cronExpression)) {
                    DurableWorkOperationResult(false, "Work item creation requires a valid 5-field cron_expression.")
                } else {
                    val workItemId = createWorkItem(
                        instruction = instruction,
                        title = request.title?.takeIf { it.isNotBlank() } ?: instruction.take(60),
                        priority = request.priority ?: WorkItemPriority.MEDIUM,
                        completionCriteria = request.completionCriteria ?: "User confirms the work item is met.",
                        cronExpression = cronExpression.ifBlank { null },
                        contactChannel = request.contactChannel?.trim()?.ifBlank { null },
                        operatorSummary = request.operatorSummary?.trim()?.ifBlank { null },
                        kind = request.workItemKind ?: WorkItemKind.RECURRENT_TASK,
                        planSteps = request.planSteps,
                    )
                    if (workItemId.isBlank()) {
                        DurableWorkOperationResult(false, "Work item creation was rejected.")
                    } else {
                        val scheduleSummary = cronExpression.takeIf { it.isNotBlank() }
                            ?.let { " Recurs on cron '$it'." }
                            .orEmpty()
                        DurableWorkOperationResult(
                            true,
                            "Work item created: ${request.title?.takeIf { it.isNotBlank() } ?: instruction.take(60)}.$scheduleSummary",
                            workItemId
                        )
                    }
                }
            }

            DurableWorkOperation.STATUS -> {
                val workItemId = request.workItemId.orEmpty()
                val state = states[workItemId]
                if (state == null) {
                    DurableWorkOperationResult(false, "Work item not found.")
                } else {
                    val step = state.nextRunnableStep()
                    DurableWorkOperationResult(
                        true,
                        "status=${state.workItem.status} next_step=${step?.description ?: "none"}",
                        workItemId = workItemId,
                    )
                }
            }

            DurableWorkOperation.REVIEW -> {
                val workItemId = request.workItemId.orEmpty()
                val state = states[workItemId]
                if (state == null) {
                    DurableWorkOperationResult(false, "Work item not found.")
                } else {
                    val reason = request.reason?.trim().orEmpty().ifBlank { "manual_review" }
                    val wakeReasonType = if (request.reviewSource == ReviewRequestSource.ID) {
                        WakeReasonType.ID_REVIEW
                    } else {
                        WakeReasonType.MANUAL_REVIEW
                    }
                    if (request.reviewSource == ReviewRequestSource.ID) {
                        applyEvent(
                            workItemId,
                            WorkItemEvent.IdReviewRequested(
                                workItemId = workItemId,
                                reason = reason,
                            )
                        )
                    }
                    val eligibleState = if (request.reviewSource == ReviewRequestSource.ID && !isEligibleForIdReview(state)) {
                        applyEvent(
                            workItemId,
                            WorkItemEvent.IdReviewDeferred(
                                workItemId = workItemId,
                                reason = "not reviewable in current state",
                            )
                        )
                        null
                    } else {
                        ensureWakeReadyState(workItemId, state, wakeReasonType, reason)
                    }
                    val step = eligibleState?.nextRunnableStep()
                    if (step == null) {
                        if (request.reviewSource == ReviewRequestSource.ID) {
                            applyEvent(
                                workItemId,
                                WorkItemEvent.IdReviewDeferred(
                                    workItemId = workItemId,
                                    reason = "no runnable responsibility step available",
                                )
                            )
                        }
                        DurableWorkOperationResult(false, "Work item has no runnable step.")
                    } else {
                        if (request.reviewSource == ReviewRequestSource.ID) {
                            applyEvent(
                                workItemId,
                                WorkItemEvent.IdReviewAccepted(
                                    workItemId = workItemId,
                                    reason = reason,
                                )
                            )
                        }
                        cueEmitter(
                            DurableWorkCue(
                                workItemId = workItemId,
                                stepId = step.id,
                                reason = reason,
                                wakeReasonType = wakeReasonType,
                                wakeReasonDetail = reason,
                            )
                        )
                        DurableWorkOperationResult(true, "Work item review requested.", workItemId)
                    }
                }
            }

            DurableWorkOperation.LIST -> {
                val summaries = allWorkItems()
                val message = if (summaries.isEmpty()) {
                    "No work items."
                } else {
                    summaries.joinToString("\n") { summary ->
                        "${summary.workItemId}: ${summary.title} (${summary.status})"
                    }
                }
                DurableWorkOperationResult(true, message)
            }

            DurableWorkOperation.PAUSE -> {
                val workItemId = request.workItemId.orEmpty()
                if (!states.containsKey(workItemId)) {
                    DurableWorkOperationResult(false, "Work item not found.")
                } else {
                    applyEvent(
                        workItemId,
                        WorkItemEvent.Suspended(
                            workItemId = workItemId,
                            reason = request.reason ?: "Paused by user",
                        )
                    )
                    DurableWorkOperationResult(true, "Work item paused.", workItemId)
                }
            }

            DurableWorkOperation.RESUME -> {
                val workItemId = request.workItemId.orEmpty()
                if (!states.containsKey(workItemId)) {
                    DurableWorkOperationResult(false, "Work item not found.")
                } else {
                    applyEvent(workItemId, WorkItemEvent.Resumed(workItemId))
                    DurableWorkOperationResult(true, "Work item resumed.", workItemId)
                }
            }

            DurableWorkOperation.REPRIORITIZE -> {
                val workItemId = request.workItemId.orEmpty()
                val state = states[workItemId]
                val newPriority = request.priority
                if (state == null || newPriority == null) {
                    DurableWorkOperationResult(false, "Work item reprioritize requires workItemId and priority.")
                } else {
                    applyEvent(workItemId, WorkItemEvent.PriorityChanged(workItemId, newPriority))
                    DurableWorkOperationResult(true, "Work item priority updated to $newPriority.", workItemId)
                }
            }

            DurableWorkOperation.COMPLETE -> {
                val workItemId = request.workItemId.orEmpty()
                if (!states.containsKey(workItemId)) {
                    DurableWorkOperationResult(false, "Work item not found.")
                } else {
                    applyEvent(workItemId, WorkItemEvent.Completed(workItemId))
                    DurableWorkOperationResult(true, "Work item marked completed.", workItemId)
                }
            }

            DurableWorkOperation.RETIRE -> {
                val workItemId = request.workItemId.orEmpty()
                if (!states.containsKey(workItemId)) {
                    DurableWorkOperationResult(false, "Work item not found.")
                } else {
                    applyEvent(
                        workItemId,
                        WorkItemEvent.Retired(
                            workItemId = workItemId,
                            reason = request.reason ?: "Retired by request",
                        )
                    )
                    DurableWorkOperationResult(true, "Work item retired.", workItemId)
                }
            }

            DurableWorkOperation.DELETE -> {
                val workItemId = request.workItemId.orEmpty()
                if (workItemId.isBlank()) {
                    DurableWorkOperationResult(false, "Work item delete requires workItemId.")
                } else if (!deleteWorkItemState(workItemId)) {
                    DurableWorkOperationResult(false, "Work item not found.")
                } else {
                    DurableWorkOperationResult(true, "Work item deleted.", workItemId)
                }
            }

            DurableWorkOperation.DELETE_ALL -> {
                val deletedCount = deleteAllWorkItemStates()
                val message = if (deletedCount == 0) {
                    "No work items to delete."
                } else {
                    "Deleted $deletedCount work items."
                }
                DurableWorkOperationResult(true, message)
            }

            DurableWorkOperation.REVISE_PLAN -> {
                val workItemId = request.workItemId.orEmpty()
                val state = states[workItemId]
                if (state == null) {
                    logger.warn { "REVISE_PLAN work item not found: workItemId='$workItemId' available=${states.keys}" }
                    DurableWorkOperationResult(false, "Work item not found.")
                } else if (request.planSteps != null && request.planSteps.isNotEmpty()) {
                    val plan = buildValidatedPlanFromPayload(
                        planSteps = request.planSteps,
                        fallbackAcceptanceCriteria = state.workItem.completionCriteria,
                    )
                    if (plan == null) {
                        emitInvalidPlan(
                            workItemId = workItemId,
                            path = "revise_plan_rejected",
                            reason = "Plan payload failed structural validation.",
                        )
                        return DurableWorkOperationResult(false, "Work item plan revision rejected: invalid plan structure.")
                    }
                    applyEvent(
                        workItemId,
                        WorkItemEvent.PlanRevised(
                            workItemId = workItemId,
                            plan = plan,
                            reason = request.reason ?: "Revised by user request",
                        )
                    )
                    DurableWorkOperationResult(true, "Work item plan revised.", workItemId)
                } else {
                    if (config.allowRuntimePlanFallback) {
                        logger.warn { "REVISE_PLAN without pre-built plan steps for workItemId='$workItemId'; using deterministic fallback." }
                        instrumentation.emit(
                            ai.neopsyke.instrumentation.AgentEvent(
                                type = "durable_work_missing_plan",
                                data = mapOf("work_item_id" to workItemId, "path" to "revise_plan_fallback")
                            )
                        )
                        val plan = planner.generatePlan(state.workItem)
                        applyEvent(
                            workItemId,
                            WorkItemEvent.PlanRevised(
                                workItemId = workItemId,
                                plan = plan,
                                reason = request.reason ?: "Revised by user request",
                            )
                        )
                        DurableWorkOperationResult(true, "Work item plan revised.", workItemId)
                    } else {
                        logger.warn { "REVISE_PLAN rejected for workItemId='$workItemId': missing pre-built plan steps." }
                        instrumentation.emit(
                            ai.neopsyke.instrumentation.AgentEvent(
                                type = "durable_work_missing_plan",
                                data = mapOf("work_item_id" to workItemId, "path" to "revise_plan_fail_closed")
                            )
                        )
                        DurableWorkOperationResult(
                            success = false,
                            message = "Work item plan revision requires pre-built plan steps from Ego.",
                            workItemId = workItemId,
                        )
                    }
                }
            }

            DurableWorkOperation.UPDATE -> {
                val workItemId = request.workItemId.orEmpty()
                val state = states[workItemId]
                if (workItemId.isBlank() || state == null) {
                    logger.warn { "UPDATE work item not found: workItemId='$workItemId' available=${states.keys}" }
                    DurableWorkOperationResult(false, "Work item update requires a valid work_item_id. Work item not found.")
                } else {
                    val newCron = request.cronExpression?.trim()?.ifBlank { null }
                    if (newCron != null && !isValidCronExpression(newCron)) {
                        DurableWorkOperationResult(false, "Invalid cron_expression: '$newCron'.")
                    } else {
                        applyEvent(
                            workItemId,
                            WorkItemEvent.Updated(
                                workItemId = workItemId,
                                cronExpression = newCron,
                                instruction = request.instruction?.trim()?.ifBlank { null },
                                title = request.title?.trim()?.ifBlank { null },
                                completionCriteria = request.completionCriteria?.trim()?.ifBlank { null },
                                contactChannel = request.contactChannel?.trim()?.ifBlank { null },
                                operatorSummary = request.operatorSummary?.trim()?.ifBlank { null },
                                reason = request.reason?.trim()?.ifBlank { null },
                            )
                        )
                        if (newCron != null) {
                            timerScheduler?.cancel(workItemId)
                            timerScheduler?.registerCron(workItemId, newCron)
                        }
                        persistState(workItemId, states[workItemId]!!)
                        val changes = listOfNotNull(
                            newCron?.let { "cron=$it" },
                            request.instruction?.let { "instruction updated" },
                            request.title?.let { "title updated" },
                            request.completionCriteria?.let { "criteria updated" },
                        ).joinToString(", ").ifBlank { "no fields changed" }
                        logger.info { "Work item updated: workItemId='$workItemId' changes=[$changes]" }
                        DurableWorkOperationResult(true, "Work item updated: $changes.", workItemId)
                    }
                }
            }
        }
    }

    override fun allWorkItems(): List<WorkItemTier1Summary> =
        states.values
            .sortedByDescending { it.workItem.priority.ordinal }
            .map { WorkContextLoader.tier1Summary(it) }

    override fun workItemStatus(workItemId: String): WorkItemState? = states[workItemId]

    override fun workItemProjection(workItemId: String): WorkItemProjection? =
        states[workItemId]?.let { WorkItemProjectionBuilder.build(it) }

    override fun allProjections(): List<WorkItemProjection> =
        states.values
            .sortedByDescending { it.workItem.priority.ordinal }
            .map { WorkItemProjectionBuilder.build(it) }

    override fun reviewableResponsibilities(limit: Int): List<ReviewableResponsibility> =
        states.values
            .asSequence()
            .filter { !it.isTerminal() && it.workItem.kind == WorkItemKind.RESPONSIBILITY }
            .filter { it.workItem.status != WorkItemStatus.SUSPENDED }
            .filter { it.workItem.reviewPolicy.enabled && it.workItem.reviewPolicy.idReviewEligible }
            .sortedWith(
                compareByDescending<WorkItemState> { it.workItem.priority.ordinal }
                    .thenBy { it.workItem.nextReviewAt ?: Instant.MAX }
            )
            .take(limit)
            .map { state ->
                ReviewableResponsibility(
                    workItemId = state.id,
                    title = state.workItem.title,
                    operatorSummary = state.workItem.operatorSummary.ifBlank { state.workItem.instruction },
                    nextReviewAt = state.workItem.nextReviewAt,
                    lastReviewAt = state.workItem.lastReviewAt,
                    priority = state.workItem.priority,
                )
            }
            .toList()

    override fun activeWorkItems(): List<WorkItemCommitment> = activeWorkItemsSnapshot

    fun createWorkItem(
        instruction: String,
        title: String = instruction.take(60),
        priority: WorkItemPriority = WorkItemPriority.MEDIUM,
        completionCriteria: String = "User confirms the work item is met.",
        cronExpression: String? = null,
        contactChannel: String? = null,
        operatorSummary: String? = null,
        kind: WorkItemKind = WorkItemKind.RECURRENT_TASK,
        planSteps: List<ai.neopsyke.agent.ego.planner.model.DurableWorkPlanStepPayload>? = null,
    ): String {
        val preBuiltPlan = when {
            planSteps != null && planSteps.isNotEmpty() -> {
                buildValidatedPlanFromPayload(
                    planSteps = planSteps,
                    fallbackAcceptanceCriteria = completionCriteria,
                ) ?: run {
                    emitInvalidPlan(
                        workItemId = "",
                        path = "create_rejected",
                        reason = "Plan payload failed structural validation.",
                    )
                    return ""
                }
            }
            config.allowRuntimePlanFallback -> {
                null
            }
            else -> {
                logger.warn { "CREATE rejected: missing pre-built plan steps while runtime fallback is disabled." }
                instrumentation.emit(
                    ai.neopsyke.instrumentation.AgentEvent(
                        type = "durable_work_missing_plan",
                        data = mapOf("work_item_id" to "", "path" to "create_fail_closed")
                    )
                )
                return ""
            }
        }

        val activeCount = states.values.count { !it.isTerminal() }
        if (activeCount >= config.maxActiveWorkItems) {
            logger.warn { "Max active work items reached (${config.maxActiveWorkItems}), rejecting creation" }
            return ""
        }

        val workItemId = generateWorkItemId(title)
        val workspacePath = store.createWorkspace(workItemId)
        val created = WorkItemEvent.Created(
            workItemId = workItemId,
            kind = kind,
            title = title,
            instruction = instruction,
            priority = priority,
            completionCriteria = completionCriteria,
            contactChannel = contactChannel,
        )
        val initial = WorkItemStateMachine.initialState(created, workspacePath).let { state ->
            if (cronExpression.isNullOrBlank()) {
                state
            } else {
                state.copy(workItem = state.workItem.copy(cronExpression = cronExpression))
            }
        }.let { state ->
            state.copy(
                workItem = state.workItem.copy(
                    kind = kind,
                    operatorSummary = operatorSummary.orEmpty(),
                    reviewPolicy = if (kind == WorkItemKind.RESPONSIBILITY) {
                        ReviewPolicy(
                            enabled = true,
                            reviewIntervalMs = config.monitoring.overdueResponsibilityReviewIntervalMs,
                            maxSkippedReviews = 3,
                            idReviewEligible = true,
                        )
                    } else {
                        state.workItem.reviewPolicy
                    },
                    nextReviewAt = if (kind == WorkItemKind.RESPONSIBILITY) {
                        Instant.now().plusMillis(config.monitoring.overdueResponsibilityReviewIntervalMs)
                    } else {
                        state.workItem.nextReviewAt
                    },
                )
            )
        }
        states[workItemId] = initial
        refreshAmbientSnapshots()
        store.workItemEventLog(workItemId).append(created)
        persistState(workItemId, initial)

        if (!cronExpression.isNullOrBlank()) {
            timerScheduler?.registerCron(workItemId, cronExpression)
        }
        if (initial.workItem.reviewPolicy.enabled && initial.workItem.nextReviewAt != null) {
            timerScheduler?.register(workItemId, initial.workItem.nextReviewAt)
        }
        if (preBuiltPlan != null) {
            applyEvent(workItemId, WorkItemEvent.PlanGenerated(workItemId, preBuiltPlan))
        } else {
            logger.warn { "CREATE without pre-built plan steps for workItemId='$workItemId'; using deterministic fallback." }
            instrumentation.emit(
                ai.neopsyke.instrumentation.AgentEvent(
                    type = "durable_work_missing_plan",
                    data = mapOf("work_item_id" to workItemId, "path" to "create_fallback")
                )
            )
            generatePlan(workItemId)
        }
        instrumentation.emit(AgentEvents.durableWorkCreated(workItemId, title, priority.name))
        logger.info { "Work item created: $workItemId ('$title')" }
        return workItemId
    }

    fun applyEventExternal(workItemId: String, event: WorkItemEvent) {
        applyEvent(workItemId, event)
    }

    private fun buildValidatedPlanFromPayload(
        planSteps: List<ai.neopsyke.agent.ego.planner.model.DurableWorkPlanStepPayload>,
        fallbackAcceptanceCriteria: String,
    ): WorkItemPlan? {
        if (planSteps.isEmpty()) return null

        val normalizedIds = linkedMapOf<String, Int>()
        val normalized = planSteps.mapIndexed { i, step ->
            val description = step.description.trim()
            if (description.isBlank()) {
                logger.warn { "Plan step validation failed: check=blank_description index=$i" }
                return null
            }

            val baseId = step.id?.trim()?.ifBlank { null } ?: "step-${i + 1}"
            val uniqueId = normalizeUniqueStepId(baseId, normalizedIds)
            val groundingRequirement = parseGroundingRequirementStrict(step.groundingRequirement) ?: run {
                logger.warn { "Plan step validation failed: check=invalid_grounding grounding_requirement='${step.groundingRequirement}' id='$baseId'" }
                return null
            }

            val requires = step.requires
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toSet()
            val produces = step.produces
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toSet()

            ValidatedPlanStep(
                id = uniqueId,
                description = description,
                acceptanceCriteria = step.acceptanceCriteria?.trim().orEmpty().ifBlank { fallbackAcceptanceCriteria },
                requires = requires,
                produces = produces,
                maxAttempts = (step.maxAttempts ?: DEFAULT_STEP_MAX_ATTEMPTS).coerceIn(1, MAX_STEP_ATTEMPTS),
                groundingRequirement = groundingRequirement,
            )
        }

        val producedKeys = normalized.flatMap { it.produces }.toSet()
        val missingRequires = normalized.flatMap { step ->
            step.requires.filterNot { req -> req in producedKeys }
                .map { req -> "${step.id}:$req" }
        }
        if (missingRequires.isNotEmpty()) {
            logger.warn { "Plan step validation failed: check=missing_requires references=$missingRequires" }
            return null
        }

        if (hasDependencyCycle(normalized)) {
            logger.warn { "Plan step validation failed: check=dependency_cycle" }
            return null
        }

        val steps = normalized.map { step ->
            PlanStep(
                id = step.id,
                description = step.description,
                status = StepStatus.PENDING,
                acceptanceCriteria = step.acceptanceCriteria,
                requires = step.requires,
                produces = step.produces,
                maxAttempts = step.maxAttempts,
                groundingRequirement = step.groundingRequirement,
            )
        }
        logger.debug {
            "Plan payload validated: step_count=${steps.size} step_ids=${steps.map { it.id }}"
        }
        return WorkItemPlan(steps = steps, generatedAt = java.time.Instant.now())
    }

    private fun normalizeUniqueStepId(
        baseId: String,
        seen: MutableMap<String, Int>,
    ): String {
        val normalizedBase = baseId.lowercase()
            .replace(Regex("[^a-z0-9._-]"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
            .ifBlank { "step" }
        val count = (seen[normalizedBase] ?: 0) + 1
        seen[normalizedBase] = count
        return if (count == 1) normalizedBase else "$normalizedBase-$count"
    }

    private fun hasDependencyCycle(steps: List<ValidatedPlanStep>): Boolean {
        if (steps.isEmpty()) return false
        val producersByKey = mutableMapOf<String, MutableSet<String>>()
        steps.forEach { step ->
            step.produces.forEach { key ->
                producersByKey.getOrPut(key) { linkedSetOf() }.add(step.id)
            }
        }

        val graph = steps.associate { it.id to linkedSetOf<String>() }.toMutableMap()
        steps.forEach { step ->
            step.requires.forEach { req ->
                producersByKey[req].orEmpty().forEach { producerId ->
                    graph.getOrPut(producerId) { linkedSetOf() }.add(step.id)
                }
            }
        }

        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()

        fun dfs(node: String): Boolean {
            if (node in visiting) return true
            if (node in visited) return false
            visiting.add(node)
            val hasCycle = graph[node].orEmpty().any { dfs(it) }
            visiting.remove(node)
            visited.add(node)
            return hasCycle
        }

        return graph.keys.any { dfs(it) }
    }

    private fun emitInvalidPlan(workItemId: String, path: String, reason: String) {
        instrumentation.emit(
            ai.neopsyke.instrumentation.AgentEvent(
                type = "durable_work_invalid_plan",
                data = mapOf(
                    "work_item_id" to workItemId,
                    "path" to path,
                    "reason" to reason,
                )
            )
        )
    }

    private fun generatePlan(workItemId: String) {
        val state = states[workItemId] ?: return
        val plan = planner.generatePlan(state.workItem)
        applyEvent(workItemId, WorkItemEvent.PlanGenerated(workItemId, plan))
    }

    private fun applyEvent(workItemId: String, event: WorkItemEvent): WorkItemState? {
        val state = states[workItemId] ?: return null
        val oldStatus = state.workItem.status
        val (newState, commands) = WorkItemStateMachine.transition(state, event)
        val updatedState = projectStateAfterEvent(newState, event)
        states[workItemId] = updatedState
        refreshAmbientSnapshots()
        store.workItemEventLog(workItemId).append(event)

        if (oldStatus != updatedState.workItem.status) {
            instrumentation.emit(
                AgentEvents.durableWorkStatusChanged(
                    workItemId,
                    oldStatus.name,
                    updatedState.workItem.status.name,
                )
            )
        }
        when (event) {
            is WorkItemEvent.StepStarted -> {
                val step = updatedState.workItem.plan.steps.firstOrNull { it.id == event.stepId }
                instrumentation.emit(
                    AgentEvents.durableWorkStepStarted(workItemId, event.stepId, step?.description.orEmpty())
                )
            }

            is WorkItemEvent.StepAcceptancePassed -> {
                val step = updatedState.workItem.plan.steps.firstOrNull { it.id == event.stepId }
                instrumentation.emit(
                    AgentEvents.durableWorkStepCompleted(workItemId, event.stepId, true, step?.attempts ?: 1)
                )
            }

            is WorkItemEvent.StepAcceptanceFailed -> {
                val step = updatedState.workItem.plan.steps.firstOrNull { it.id == event.stepId }
                if (step?.status == StepStatus.FAILED) {
                    instrumentation.emit(
                        AgentEvents.durableWorkStepCompleted(workItemId, event.stepId, false, step.attempts)
                    )
                }
            }

            is WorkItemEvent.StepBlocked -> {
                instrumentation.emit(
                    AgentEvents.durableWorkBlocked(workItemId, event.stepId, event.waitCondition.type.name)
                )
            }

            is WorkItemEvent.WorkCycleCompleted -> {
                instrumentation.emit(
                    AgentEvents.durableWorkCycleCompleted(workItemId, event.stepId, event.actionsExecuted)
                )
            }

            else -> {}
        }

        dispatchCommands(commands)
        if (event is WorkItemEvent.ReviewRecorded &&
            updatedState.workItem.reviewPolicy.enabled &&
            updatedState.workItem.nextReviewAt != null &&
            !updatedState.isTerminal()
        ) {
            timerScheduler?.register(workItemId, updatedState.workItem.nextReviewAt)
        }
        if (updatedState.isTerminal()) {
            instrumentation.emit(AgentEvents.durableWorkCompleted(workItemId))
            logger.info { "Work item $workItemId is terminal (${updatedState.workItem.status})" }
        }
        return updatedState
    }

    private fun dispatchCommands(commands: List<WorkItemCommand>) {
        for (cmd in commands) {
            when (cmd) {
                is WorkItemCommand.EmitWorkReady -> cueEmitter(cmd.cue)
                is WorkItemCommand.ScheduleWakeTimer -> {
                    timerScheduler?.register(cmd.workItemId, cmd.wakeAt)
                    val state = states[cmd.workItemId]
                    activationJournal.append(
                        ActivationJournalEntry(
                            workItemId = cmd.workItemId,
                            stepId = cmd.stepId.orEmpty(),
                            leaseToken = state?.workItem?.activeLease.orEmpty(),
                            planRevision = state?.workItem?.planRevision ?: DEFAULT_PLAN_REVISION,
                            boundary = ActivationBoundary.NEXT_WAKE_SCHEDULED,
                            detail = cmd.reason.take(ACTIVATION_JOURNAL_DETAIL_MAX_CHARS),
                        )
                    )
                }
                is WorkItemCommand.CancelWakeTimer -> timerScheduler?.cancel(cmd.workItemId)
                is WorkItemCommand.RegisterWaitCondition -> {
                    if (cmd.condition.type != WaitConditionType.TIMER) {
                        waitConditionMonitor?.register(cmd.workItemId, cmd.stepId, cmd.condition)
                    }
                }

                is WorkItemCommand.ClearWaitCondition -> waitConditionMonitor?.unregister(cmd.workItemId, cmd.stepId)
                is WorkItemCommand.PersistWorkItem -> {
                    val state = states[cmd.workItemId] ?: continue
                    persistState(cmd.workItemId, state)
                }

                is WorkItemCommand.NotifyUser -> {
                    logger.info { "User notification (${cmd.workItemId}): ${cmd.message}" }
                }
            }
        }
    }

    private fun restoreWorkItems() {
        pruneExpiredCompletedWorkItems()
        for (workItemId in store.scanWorkItems()) {
            try {
                val state = store.loadWorkItem(workItemId) ?: continue
                states[workItemId] = state
                restoreSchedules(state)
                emitRestoredWorkReady(state)
            } catch (ex: Exception) {
                logger.warn(ex) { "Failed to restore work item $workItemId" }
            }
        }
        // Recover unfinished activations from the journal
        recoverUnfinishedActivations()
        reconcileEffectIntentsAfterRecovery()
        refreshAmbientSnapshots()
    }

    private fun recoverUnfinishedActivations() {
        val unfinished = activationJournal.findUnfinishedActivations()
        for (entry in unfinished) {
            if (states[entry.workItemId] == null) continue
            logger.warn {
                "Recovering unfinished activation: workItem=${entry.workItemId} " +
                    "step=${entry.stepId} lease=${entry.leaseToken}"
            }
            // Mark lease as expired and record recovery
            applyEvent(entry.workItemId, WorkItemEvent.LeaseExpired(
                workItemId = entry.workItemId,
                leaseToken = entry.leaseToken,
                reason = "restart_recovery",
            ))
            applyEvent(entry.workItemId, WorkItemEvent.ActivationRecovered(
                workItemId = entry.workItemId,
                leaseToken = entry.leaseToken,
                reason = "restart_recovery",
            ))
            activationJournal.append(ActivationJournalEntry(
                workItemId = entry.workItemId,
                stepId = entry.stepId,
                leaseToken = entry.leaseToken,
                boundary = ActivationBoundary.RECOVERED,
                detail = "restart_recovery",
            ))
            // Re-emit work ready so the Ego can pick up where it left off
            val recovered = states[entry.workItemId] ?: continue
            if (!recovered.isTerminal()) {
                val step = recovered.nextRunnableStep()
                if (step != null) {
                    cueEmitter(
                        DurableWorkCue(
                            workItemId = entry.workItemId,
                            stepId = step.id,
                            reason = "activation_recovered",
                            wakeReasonType = WakeReasonType.RECOVERY,
                            wakeReasonDetail = "restart_recovery",
                        )
                    )
                }
            }
        }
    }

    private fun emitRestoredWorkReady(state: WorkItemState) {
        if (state.isTerminal()) return
        val step = state.nextRunnableStep() ?: return
        cueEmitter(
            DurableWorkCue(
                workItemId = state.id,
                stepId = step.id,
                reason = "work_item_restored_ready",
                wakeReasonType = WakeReasonType.WORK_ITEM_RESTORED_READY,
                wakeReasonDetail = "work_item_restored_ready",
            )
        )
    }

    private fun restoreSchedules(state: WorkItemState) {
        val workItem = state.workItem
        if (!workItem.cronExpression.isNullOrBlank()) {
            timerScheduler?.registerCron(state.id, workItem.cronExpression)
        }
        if (workItem.status == WorkItemStatus.SUSPENDED && workItem.suspendedUntil != null) {
            timerScheduler?.register(state.id, workItem.suspendedUntil)
        }
        if (workItem.reviewPolicy.enabled && workItem.nextReviewAt != null && !state.isTerminal()) {
            timerScheduler?.register(state.id, workItem.nextReviewAt)
        }
        workItem.plan.steps
            .filter { it.status == StepStatus.BLOCKED && it.waitCondition != null }
            .forEach { step ->
                val condition = step.waitCondition ?: return@forEach
                when (condition.type) {
                    WaitConditionType.TIMER -> {
                        val wakeAt = condition.timeoutAt ?: condition.params["wake_at"]?.let { Instant.parse(it) }
                        if (wakeAt != null) {
                            timerScheduler?.register(state.id, wakeAt)
                        }
                    }

                    else -> waitConditionMonitor?.register(state.id, step.id, condition)
                }
            }
    }

    private fun onTimerWake(workItemId: String, scheduledAtMs: Long) {
        val state = states[workItemId] ?: return
        val scheduledAt = Instant.ofEpochMilli(scheduledAtMs)
        logger.info { "onTimerWake: workItem=$workItemId status=${state.workItem.status} scheduledAt=$scheduledAt" }
        if (!state.workItem.cronExpression.isNullOrBlank() && state.workItem.status in setOf(WorkItemStatus.COMPLETED, WorkItemStatus.FAILED)) {
            applyEvent(workItemId, WorkItemEvent.CronCycleStarted(workItemId, scheduledAt))
        }
        if (state.workItem.status == WorkItemStatus.SUSPENDED) {
            val resumeAt = state.workItem.suspendedUntil
            if (resumeAt != null && resumeAt.toEpochMilli() <= scheduledAtMs) {
                applyEvent(workItemId, WorkItemEvent.Resumed(workItemId, scheduledAt))
            }
        }
        if (state.workItem.reviewPolicy.enabled &&
            state.workItem.nextReviewAt != null &&
            state.workItem.nextReviewAt.toEpochMilli() <= scheduledAtMs &&
            state.workItem.status != WorkItemStatus.SUSPENDED &&
            !state.isTerminal()
        ) {
            val reviewReadyState = ensureWakeReadyState(
                workItemId = workItemId,
                state = state,
                wakeReasonType = WakeReasonType.OVERDUE_CHECK,
                reason = "review_due",
            )
            val step = reviewReadyState.nextRunnableStep()
            if (step != null) {
                cueEmitter(
                    DurableWorkCue(
                        workItemId = workItemId,
                        stepId = step.id,
                        reason = "review_due",
                        wakeReasonType = WakeReasonType.OVERDUE_CHECK,
                        wakeReasonDetail = "review_due",
                    )
                )
            }
        }

        val timerSteps = state.workItem.plan.steps.filter { step ->
            step.status == StepStatus.BLOCKED &&
                step.waitCondition?.type == WaitConditionType.TIMER &&
                (step.waitCondition.timeoutAt?.let { it.toEpochMilli() <= scheduledAtMs }
                    ?: step.waitCondition.params["wake_at"]?.let { Instant.parse(it).toEpochMilli() <= scheduledAtMs }
                    ?: false)
        }
        timerSteps.forEach { step ->
            applyEvent(
                workItemId,
                WorkItemEvent.WaitConditionSatisfied(workItemId, step.id, "timer")
            )
        }
        // Cron wake for ACTIVE work items: emit work-ready cue so the Ego picks up the step
        if (!state.workItem.cronExpression.isNullOrBlank() && state.workItem.status == WorkItemStatus.ACTIVE) {
            val cronReadyState = ensureWakeReadyState(
                workItemId = workItemId,
                state = state,
                wakeReasonType = WakeReasonType.CRON_DUE,
                reason = "cron_wake_active",
            )
            val step = cronReadyState.nextRunnableStep()
            if (step != null) {
                logger.info { "Cron wake emitting work-ready cue: workItem=$workItemId step=${step.id}" }
                cueEmitter(
                    DurableWorkCue(
                        workItemId = workItemId,
                        stepId = step.id,
                        reason = "cron_wake_active",
                        wakeReasonType = WakeReasonType.CRON_DUE,
                        wakeReasonDetail = "cron_wake_active",
                    )
                )
            } else {
                logger.info { "Cron wake for ACTIVE workItem=$workItemId but no runnable step found" }
            }
        }
    }

    private fun isValidCronExpression(expression: String): Boolean =
        CronParser.nextAfter(expression, ZonedDateTime.now()) != null

    private fun onWaitConditionSatisfied(
        workItemId: String,
        stepId: String,
        resolution: WaitConditionResolution,
    ) {
        applyEvent(
            workItemId,
            WorkItemEvent.WaitConditionSatisfied(
                workItemId = workItemId,
                stepId = stepId,
                conditionType = resolution.conditionType,
                resolutionSummary = resolution.summary.ifBlank { null },
                resolutionStatus = resolution.status,
            )
        )
    }

    private fun recordDeliveryOutcome(state: WorkItemState, session: DurableWorkRunSession) {
        val summary = session.lastResultSummary.trim()
        val fingerprint = summary.ifBlank { null }?.hashCode()?.toString()
        val lastFingerprint = state.durableState.delivery.lastDeliveredDeltaSignature
        val duplicate = fingerprint != null && fingerprint == lastFingerprint
        if (summary.isBlank()) {
            applyEvent(
                state.id,
                WorkItemEvent.DeliverySuppressed(
                    workItemId = state.id,
                    reason = DeliverySuppressionReason.NO_MEANINGFUL_CHANGE,
                )
            )
            applyEvent(
                state.id,
                WorkItemEvent.DeliveryDecisionRecorded(
                    workItemId = state.id,
                    decision = DeliveryDecision.SUPPRESS_AS_NO_CHANGE,
                    suppressionReason = DeliverySuppressionReason.NO_MEANINGFUL_CHANGE,
                )
            )
            return
        }

        if (duplicate) {
            applyEvent(
                state.id,
                WorkItemEvent.DeliverySuppressed(
                    workItemId = state.id,
                    reason = DeliverySuppressionReason.DUPLICATE_DELTA,
                    summary = summary,
                )
            )
            applyEvent(
                state.id,
                WorkItemEvent.DeliveryDecisionRecorded(
                    workItemId = state.id,
                    decision = DeliveryDecision.SUPPRESS_AS_DUPLICATE,
                    suppressionReason = DeliverySuppressionReason.DUPLICATE_DELTA,
                    fingerprint = fingerprint,
                    summary = summary,
                )
            )
            return
        }

        applyEvent(
            state.id,
            WorkItemEvent.MeaningfulChangeDetected(
                workItemId = state.id,
                itemKey = session.stepId,
                changeClass = ChangeClass.NOTEWORTHY,
                summary = summary,
            )
        )

        when (state.workItem.deliveryPolicy) {
            DeliveryPolicy.IMMEDIATE,
            DeliveryPolicy.ONLY_ON_CHANGE -> {
                applyEvent(
                    state.id,
                    WorkItemEvent.DeliveryDecisionRecorded(
                        workItemId = state.id,
                        decision = DeliveryDecision.NOTIFY_NOW,
                        fingerprint = fingerprint,
                        summary = summary,
                    )
                )
                applyEvent(state.id, WorkItemEvent.DeliverySent(state.id, summary))
            }
            DeliveryPolicy.DIGEST -> {
                val activeWindowKey = state.durableState.delivery.activeDigestWindow?.windowKey
                    ?: "digest-${state.id}-${System.currentTimeMillis().toString(36)}"
                if (state.durableState.delivery.activeDigestWindow == null) {
                    applyEvent(state.id, WorkItemEvent.ReportWindowOpened(state.id, activeWindowKey))
                }
                applyEvent(
                    state.id,
                    WorkItemEvent.DeliveryDecisionRecorded(
                        workItemId = state.id,
                        decision = DeliveryDecision.QUEUE_FOR_DIGEST,
                        fingerprint = fingerprint,
                        summary = summary,
                    )
                )
            }
            DeliveryPolicy.MANUAL_REVIEW -> {
                applyEvent(
                    state.id,
                    WorkItemEvent.DeliveryDecisionRecorded(
                        workItemId = state.id,
                        decision = DeliveryDecision.MANUAL_REVIEW_ONLY,
                        suppressionReason = DeliverySuppressionReason.MANUAL_REVIEW_POLICY,
                        fingerprint = fingerprint,
                        summary = summary,
                    )
                )
            }
        }
    }

    private fun writeWorkspaceCycleArtifacts(state: WorkItemState, session: DurableWorkRunSession) {
        val summary = session.lastResultSummary.ifBlank { "Cycle completed." }
        try {
            WorkContextLoader.writeContext(state, session.stepId, summary)
            store.appendScratchEntry(
                state.id,
                buildString {
                    appendLine("## ${Instant.now()}")
                    appendLine("- step_id: ${session.stepId}")
                    appendLine("- root_input_id: ${session.rootInputId}")
                    appendLine("- actions_executed: ${session.actionCount}")
                    appendLine("- result: $summary")
                }.trim()
            )
            store.writeArtifact(
                workItemId = state.id,
                stepId = session.stepId,
                artifactName = "cycle-${state.eventCount}.md",
                content = buildString {
                    appendLine("# Work Item Cycle")
                    appendLine()
                    appendLine("- work_item_id: ${state.id}")
                    appendLine("- step_id: ${session.stepId}")
                    appendLine("- root_input_id: ${session.rootInputId}")
                    appendLine("- actions_executed: ${session.actionCount}")
                    appendLine("- wake_reason: ${session.requeueReason ?: "n/a"}")
                    appendLine()
                    appendLine("## Summary")
                    appendLine(summary)
                }
            )
            applyEvent(state.id, WorkItemEvent.ContextUpdated(state.id, 2, "workspace_cycle_written"))
        } catch (ex: Exception) {
            logger.warn(ex) { "Failed to write workspace cycle artifacts for workItem=${state.id}" }
        }
    }

    private fun persistState(workItemId: String, state: WorkItemState) {
        store.saveWorkItemState(workItemId, state)
        if (state.eventCount % config.snapshotEveryNEvents == 0) {
            store.saveSnapshot(workItemId, state)
        }
    }

    private fun deleteAllWorkItemStates(): Int =
        states.keys.toList().count { workItemId -> deleteWorkItemState(workItemId) }

    private fun deleteWorkItemState(workItemId: String): Boolean {
        val state = states[workItemId] ?: return false
        runCatching { store.deleteWorkItem(workItemId) }
            .onFailure { ex ->
                logger.warn(ex) { "Failed to delete workspace for workItem=$workItemId" }
                return false
            }
        timerScheduler?.cancel(workItemId)
        state.workItem.plan.steps.forEach { step ->
            waitConditionMonitor?.unregister(workItemId, step.id)
        }
        states.remove(workItemId)
        sessionsByRootInputId.entries.removeIf { (_, session) -> session.workItemId == workItemId }
        refreshAmbientSnapshots()
        return true
    }

    private fun pruneExpiredCompletedWorkItems() {
        if (!Files.isDirectory(config.workspaceRoot)) return
        val cutoff = Instant.now().minus(config.completedWorkItemRetentionDays.toLong(), ChronoUnit.DAYS)
        for (workItemId in store.scanWorkItems()) {
            val state = runCatching { store.loadWorkItem(workItemId) }.getOrNull() ?: continue
            if (state.workItem.status != WorkItemStatus.COMPLETED) continue
            val completedAt = state.workItem.plan.steps.mapNotNull { it.completedAt }.maxOrNull() ?: state.workItem.lastWorkedAt
            if (completedAt != null && completedAt.isBefore(cutoff)) {
                runCatching { store.deleteWorkItem(workItemId) }
                    .onFailure { ex -> logger.warn(ex) { "Failed to prune completed work item $workItemId" } }
            }
        }
    }

    private fun buildWorkItemRootInputId(workItemId: String, stepId: String): String =
        "work:$workItemId:$stepId"

    private fun refreshAmbientSnapshots() {
        val active = states.values
            .filter { !it.isTerminal() }
            .sortedByDescending { it.workItem.priority.ordinal }

        activeWorkItemsSnapshot =
            active.map { state ->
                WorkItemCommitment(
                    id = state.id,
                    instruction = state.workItem.instruction,
                    lastActedAt = state.workItem.lastWorkedAt,
                )
            }

        pendingWorkSummarySnapshot = if (active.isEmpty()) {
            ""
        } else {
            active.joinToString("\n") { state ->
                val step = state.nextRunnableStep()
                val stepInfo = step?.let { "${it.status.name.lowercase()}: ${it.description}" } ?: "no runnable steps"
                "- [${state.workItem.priority}] ${state.workItem.title} ($stepInfo)"
            }
        }
    }

    private fun buildWaitConditionForAsyncOutcome(outcome: ActionOutcome): WaitCondition {
        val wait = requireNotNull(outcome.asyncWait) {
            "Work item async wait outcomes must provide asyncWait handles."
        }
        return WaitCondition(
            type = WaitConditionType.ASYNC_OPERATION,
            params = emptyMap(),
            registeredAt = Instant.now(),
            timeoutAt = wait.handles.mapNotNull { it.timeoutAt }.minOrNull(),
            onTimeout = TimeoutAction.FAIL,
            asyncWait = wait,
        )
    }

    private fun finalizeEffectIntent(
        workItemId: String,
        action: PendingAction,
        outcome: ActionOutcome,
    ) {
        val effectIntentId = effectIntentByActionId.remove(action.id) ?: return
        when (outcome.executionStatus) {
            ActionExecutionStatus.SUCCESS -> {
                workEffectLedger.confirmEffect(effectIntentId)
                applyEvent(
                    workItemId,
                    WorkItemEvent.EffectConfirmed(
                        workItemId = workItemId,
                        effectIntentId = effectIntentId,
                    )
                )
            }

            ActionExecutionStatus.WAITING -> Unit

            else -> {
                workEffectLedger.abandonEffect(effectIntentId, outcome.statusSummary)
                applyEvent(
                    workItemId,
                    WorkItemEvent.EffectAbandoned(
                        workItemId = workItemId,
                        effectIntentId = effectIntentId,
                        reason = outcome.statusSummary.take(EFFECT_REASON_MAX_CHARS),
                    )
                )
            }
        }
    }

    private fun abandonEffectIntent(
        workItemId: String,
        action: PendingAction,
        reason: String,
    ) {
        val effectIntentId = effectIntentByActionId.remove(action.id) ?: return
        workEffectLedger.abandonEffect(effectIntentId, reason)
        applyEvent(
            workItemId,
            WorkItemEvent.EffectAbandoned(
                workItemId = workItemId,
                effectIntentId = effectIntentId,
                reason = reason.take(EFFECT_REASON_MAX_CHARS),
            )
        )
    }

    private fun classifyEffectClass(actionType: ActionType): EffectClass =
        when (actionType.id) {
            ActionType.WEB_SEARCH.id,
            ActionType.WEBSITE_FETCH.id,
            ActionType.REFLECT_EVIDENCE.id -> EffectClass.OBSERVE

            ActionType.REFLECT_INTERNAL.id,
            ActionType.RESOLUTION_DRAFT.id,
            ActionType.DURABLE_WORK_OPERATION.id -> EffectClass.INTERNAL_STATEFUL

            else -> EffectClass.EXTERNAL_MUTATING
        }

    private fun reconcileEffectIntentsAfterRecovery() {
        val reconciledEffectIds = workEffectLedger.reconcileOnRecovery()
        for (effectIntentId in reconciledEffectIds) {
            val workItemId = parseWorkItemIdFromEffectIntentId(effectIntentId) ?: continue
            applyEvent(
                workItemId,
                WorkItemEvent.EffectUncertain(
                    workItemId = workItemId,
                    effectIntentId = effectIntentId,
                    reason = "recovery_reconciliation",
                )
            )
        }
    }

    private fun parseWorkItemIdFromEffectIntentId(effectIntentId: String): String? =
        effectIntentId.split(':', limit = EFFECT_INTENT_SEGMENT_LIMIT)
            .firstOrNull()
            ?.takeIf { it.isNotBlank() && states.containsKey(it) }

    private fun projectStateAfterEvent(
        state: WorkItemState,
        event: WorkItemEvent,
    ): WorkItemState {
        var updated = state
        if (event is WorkItemEvent.StepAcceptanceFailed || event is WorkItemEvent.Failed) {
            updated = updated.copy(
                workItem = updated.workItem.copy(
                    failureWindow = incrementFailureWindow(
                        updated.workItem.failureWindow,
                        event.timestamp,
                    )
                )
            )
        }
        if (event is WorkItemEvent.StepAcceptancePassed || event is WorkItemEvent.Completed) {
            updated = updated.copy(
                workItem = updated.workItem.copy(
                    failureWindow = resetFailureWindow(updated.workItem.failureWindow)
                )
            )
        }
        updated = when (event) {
            is WorkItemEvent.DeliverySent -> {
                updated.copy(
                    durableState = updated.durableState.copy(
                        delivery = updated.durableState.delivery.copy(
                            lastDeliveryAt = event.timestamp,
                            lastReportedSummary = event.summary,
                            lastDeliveredDeltaSignature = event.summary.hashCode().toString(),
                            lastSuppressionReason = null,
                        ),
                    )
                )
            }
            is WorkItemEvent.DeliveryDecisionRecorded -> {
                val delivery = updated.durableState.delivery
                val summary = event.summary?.trim().orEmpty()
                val nextWindow = when (event.decision) {
                    DeliveryDecision.QUEUE_FOR_DIGEST -> {
                        val existing = delivery.activeDigestWindow ?: DigestWindow(
                            windowKey = "digest-${updated.id}",
                            openedAt = event.timestamp,
                        )
                        existing.copy(
                            itemKeys = (existing.itemKeys + listOfNotNull(summary.ifBlank { null }))
                                .takeLast(config.monitoring.reportWindowSize)
                        )
                    }
                    else -> delivery.activeDigestWindow
                }
                val nextPending = when (event.decision) {
                    DeliveryDecision.QUEUE_FOR_DIGEST -> {
                        (delivery.pendingEntries + PendingDeliveryEntry(
                            summary = summary,
                            fingerprint = event.fingerprint ?: summary.hashCode().toString(),
                            itemKeys = listOf(updated.id),
                            meaningful = true,
                            wakeReasonType = sessionsByRootInputId.values.firstOrNull { it.workItemId == updated.id }
                                ?.wakeReasons?.firstOrNull()?.type,
                        )).takeLast(config.maxPendingDigestEntries)
                    }
                    else -> delivery.pendingEntries
                }
                updated.copy(
                    durableState = updated.durableState.copy(
                        delivery = delivery.copy(
                            pendingEntries = nextPending,
                            activeDigestWindow = nextWindow,
                            lastDecision = event.decision,
                            lastSuppressionReason = event.suppressionReason,
                            lastDeliveredDeltaSignature = event.fingerprint ?: delivery.lastDeliveredDeltaSignature,
                        )
                    )
                )
            }
            is WorkItemEvent.DeliverySuppressed -> {
                updated.copy(
                    durableState = updated.durableState.copy(
                        delivery = updated.durableState.delivery.copy(
                            lastSuppressionReason = event.reason,
                        )
                    )
                )
            }
            is WorkItemEvent.ReportWindowOpened -> {
                updated.copy(
                    durableState = updated.durableState.copy(
                        delivery = updated.durableState.delivery.copy(
                            activeDigestWindow = DigestWindow(
                                windowKey = event.windowKey,
                                openedAt = event.timestamp,
                            ),
                            lastDigestWindowAt = event.timestamp,
                        ),
                        monitor = updated.durableState.monitor.copy(
                            reporting = updated.durableState.monitor.reporting.copy(
                                activeWindowKey = event.windowKey,
                                openedAt = event.timestamp,
                                closedAt = null,
                            )
                        )
                    )
                )
            }
            is WorkItemEvent.ReportWindowClosed -> {
                updated.copy(
                    durableState = updated.durableState.copy(
                        delivery = updated.durableState.delivery.copy(
                            activeDigestWindow = updated.durableState.delivery.activeDigestWindow
                                ?.copy(closedAt = event.timestamp),
                        ),
                        monitor = updated.durableState.monitor.copy(
                            reporting = updated.durableState.monitor.reporting.copy(
                                activeWindowKey = event.windowKey,
                                closedAt = event.timestamp,
                            )
                        )
                    )
                )
            }
            is WorkItemEvent.MeaningfulChangeDetected -> {
                val monitor = updated.durableState.monitor
                val change = ChangeRecord(
                    itemKey = event.itemKey,
                    changeClass = event.changeClass,
                    observedAt = event.timestamp,
                    reportEligible = true,
                    summary = event.summary,
                )
                updated.copy(
                    workItem = updated.workItem.copy(lastMeaningfulChangeAt = event.timestamp),
                    durableState = updated.durableState.copy(
                        monitor = monitor.copy(
                            changeLedger = (monitor.changeLedger + change)
                                .takeLast(config.monitoring.maxRetainedChangeRecords),
                            lastMeaningfulChangeAt = event.timestamp,
                        ),
                        delivery = updated.durableState.delivery.copy(
                            lastMeaningfulChangeAt = event.timestamp,
                        )
                    )
                )
            }
            is WorkItemEvent.SeenItemRecorded -> {
                val monitor = updated.durableState.monitor
                val record = SeenItemRecord(
                    stableItemKey = event.itemKey,
                    firstSeenAt = event.timestamp,
                    lastSeenAt = event.timestamp,
                    lastFingerprint = event.fingerprint,
                )
                updated.copy(
                    durableState = updated.durableState.copy(
                        monitor = monitor.copy(
                            seenItems = (monitor.seenItems + record)
                                .groupBy { it.stableItemKey }
                                .map { (_, records) -> records.last() }
                                .takeLast(config.monitoring.maxRetainedSeenItems),
                            dedupeKeys = monitor.dedupeKeys + event.itemKey,
                        )
                    )
                )
            }
            is WorkItemEvent.SeenItemUpdated -> {
                val monitor = updated.durableState.monitor
                val seen = monitor.seenItems.filterNot { it.stableItemKey == event.itemKey } + SeenItemRecord(
                    stableItemKey = event.itemKey,
                    firstSeenAt = monitor.seenItems.firstOrNull { it.stableItemKey == event.itemKey }?.firstSeenAt
                        ?: event.timestamp,
                    lastSeenAt = event.timestamp,
                    lastFingerprint = event.fingerprint,
                    lifecycleStatus = SeenItemLifecycleStatus.CHANGED,
                )
                updated.copy(
                    durableState = updated.durableState.copy(
                        monitor = monitor.copy(
                            seenItems = seen.takeLast(config.monitoring.maxRetainedSeenItems),
                        )
                    )
                )
            }
            is WorkItemEvent.MonitorScanStarted -> {
                updated.copy(
                    durableState = updated.durableState.copy(
                        monitor = updated.durableState.monitor.copy(
                            sources = upsertSourceState(
                                updated.durableState.monitor.sources,
                                MonitorSourceState(
                                    sourceKey = event.sourceKey,
                                    lastScanAt = event.timestamp,
                                )
                            )
                        )
                    )
                )
            }
            is WorkItemEvent.MonitorScanCompleted -> {
                updated.copy(
                    durableState = updated.durableState.copy(
                        monitor = updated.durableState.monitor.copy(
                            sources = upsertSourceState(
                                updated.durableState.monitor.sources,
                                MonitorSourceState(
                                    sourceKey = event.sourceKey,
                                    lastScanAt = event.timestamp,
                                    lastSuccessfulScanAt = event.timestamp,
                                    lastScanSummary = event.scanSummary,
                                )
                            )
                        )
                    )
                )
            }
            is WorkItemEvent.MonitorCursorAdvanced -> {
                updated.copy(
                    durableState = updated.durableState.copy(
                        monitor = updated.durableState.monitor.copy(
                            sources = upsertSourceState(
                                updated.durableState.monitor.sources,
                                MonitorSourceState(
                                    sourceKey = event.sourceKey,
                                    cursorOrCheckpoint = event.cursor,
                                )
                            )
                        )
                    )
                )
            }
            is WorkItemEvent.ReviewRecorded -> {
                val review = updated.durableState.monitor.review
                val intervalMs = updated.workItem.reviewPolicy.reviewIntervalMs
                updated.copy(
                    workItem = updated.workItem.copy(
                        lastReviewAt = event.timestamp,
                        nextReviewAt = intervalMs?.let { event.timestamp.plusMillis(it) },
                    ),
                    durableState = updated.durableState.copy(
                        monitor = updated.durableState.monitor.copy(
                            review = review.copy(
                                lastReviewAt = event.timestamp,
                                nextReviewDueAt = intervalMs?.let { event.timestamp.plusMillis(it) },
                                skippedReviewCount = 0,
                                latestReviewReason = event.outcome,
                                history = (review.history + ReviewRecord(
                                    reviewedAt = event.timestamp,
                                    wakeReasonType = event.wakeReasonType,
                                    outcome = event.outcome,
                                    summary = event.summary,
                                )).takeLast(config.monitoring.maxRetainedReviewHistory),
                            )
                        )
                    )
                )
            }
            is WorkItemEvent.IdReviewRequested -> updated
            is WorkItemEvent.IdReviewAccepted -> updated
            is WorkItemEvent.IdReviewDeferred -> {
                val review = updated.durableState.monitor.review
                updated.copy(
                    durableState = updated.durableState.copy(
                        monitor = updated.durableState.monitor.copy(
                            review = review.copy(
                                skippedReviewCount = review.skippedReviewCount + 1,
                                latestReviewReason = event.reason,
                            )
                        )
                    )
                )
            }
            is WorkItemEvent.Retired -> {
                updated.copy(
                    workItem = updated.workItem.copy(status = WorkItemStatus.RETIRED)
                )
            }
            else -> updated
        }
        var projectedHealth = projectHealth(updated)
        if (updated.workItem.health != WorkItemHealth.HEALTHY &&
            projectedHealth == WorkItemHealth.HEALTHY
        ) {
            projectedHealth = updated.workItem.health
        }
        var projectedStatus = updated.workItem.status
        if (projectedHealth == WorkItemHealth.STALLED &&
            projectedStatus !in TERMINAL_OR_ESCALATED_STATUSES
        ) {
            projectedStatus = WorkItemStatus.STALLED
        }
        if (projectedHealth != updated.workItem.health || projectedStatus != updated.workItem.status) {
            updated = updated.copy(
                workItem = updated.workItem.copy(
                    health = projectedHealth,
                    status = projectedStatus,
                )
            )
        }
        return updated
    }

    private fun incrementFailureWindow(window: FailureWindow, now: Instant): FailureWindow {
        val nowMs = now.toEpochMilli()
        val shouldResetWindow =
            window.windowStartMs == 0L || nowMs - window.windowStartMs > window.windowDurationMs
        return if (shouldResetWindow) {
            window.copy(
                failureCount = 1,
                windowStartMs = nowMs,
            )
        } else {
            window.copy(failureCount = window.failureCount + 1)
        }
    }

    private fun resetFailureWindow(window: FailureWindow): FailureWindow =
        window.copy(failureCount = 0, windowStartMs = 0L)

    private fun generateWorkItemId(title: String): String {
        val slug = title.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(40)
        val suffix = System.currentTimeMillis().toString(36).takeLast(6)
        return "$slug-$suffix"
    }

    // ── Lease management ────────────────────────────────────────────────

    private fun acquireLease(workItemId: String): String {
        val token = "lease-${System.currentTimeMillis().toString(36)}-${(Math.random() * 1000).toInt()}"
        activeLeases[workItemId] = token
        applyEvent(workItemId, WorkItemEvent.LeaseAcquired(workItemId, token))
        return token
    }

    private fun releaseLease(workItemId: String) {
        val token = activeLeases.remove(workItemId)
        if (token != null) {
            val state = states[workItemId]
            if (state != null) {
                // Process any coalesced pending wakes
                val pendingReasons = state.workItem.pendingWakeReasons
                if (pendingReasons.isNotEmpty()) {
                    // Clear pending wakes and re-emit work ready
                    val cleared = state.copy(
                        workItem = state.workItem.copy(pendingWakeReasons = emptyList())
                    )
                    states[workItemId] = cleared
                    val step = cleared.nextRunnableStep()
                    if (step != null && !cleared.isTerminal()) {
                        cueEmitter(
                            DurableWorkCue(
                                workItemId = workItemId,
                                stepId = step.id,
                                reason = "coalesced_wakes: ${pendingReasons.joinToString(",") { it.renderHumanReadable() }}",
                                wakeReasonType = WakeReasonType.COALESCED_WAKE,
                                wakeReasonDetail = pendingReasons.joinToString(",") { it.renderHumanReadable() },
                            )
                        )
                    }
                }
            }
        }
    }

    private fun coalescePendingWake(workItemId: String, cue: DurableWorkCue) {
        val state = states[workItemId] ?: return
        val pendingReasons = state.workItem.pendingWakeReasons
        if (pendingReasons.size >= config.maxPendingWakeReasonsPerItem) {
            logger.warn { "Max pending wake reasons reached for $workItemId, dropping: ${cue.reason}" }
            return
        }
        val wakeReason = WakeReason(
            type = cue.wakeReasonType ?: inferWakeReasonType(cue.reason),
            detail = cue.wakeReasonDetail ?: cue.reason.ifBlank { null },
        )
        val updated = state.copy(
            workItem = state.workItem.copy(
                pendingWakeReasons = pendingReasons + wakeReason
            )
        )
        states[workItemId] = updated
        applyEvent(
            workItemId,
            WorkItemEvent.WakeCoalesced(
                workItemId = workItemId,
                wakeReason = wakeReason
            )
        )
        logger.debug { "Wake coalesced for $workItemId: ${cue.reason} (${pendingReasons.size + 1} pending)" }
    }

    // ── Health projection ────────────────────────────────────────────────

    private fun projectHealth(state: WorkItemState): WorkItemHealth =
        when (state.workItem.status) {
            WorkItemStatus.COMPLETED -> WorkItemHealth.HEALTHY
            WorkItemStatus.FAILED -> WorkItemHealth.FAILED
            WorkItemStatus.STALLED -> WorkItemHealth.STALLED
            WorkItemStatus.NEEDS_ATTENTION -> WorkItemHealth.NEEDS_ATTENTION
            WorkItemStatus.BLOCKED -> WorkItemHealth.BLOCKED
            else -> {
                val failureWindow = state.workItem.failureWindow
                if (isResponsibilityReviewOverdue(state)) {
                    WorkItemHealth.NEEDS_ATTENTION
                } else if (failureWindow.failureCount >= failureWindow.maxFailuresInWindow) {
                    WorkItemHealth.STALLED
                } else {
                    WorkItemHealth.HEALTHY
                }
            }
        }

    private fun ensureWakeReadyState(
        workItemId: String,
        state: WorkItemState,
        wakeReasonType: WakeReasonType?,
        reason: String,
    ): WorkItemState {
        if (!shouldRearmResponsibilityCycle(state, wakeReasonType)) return state
        return applyEvent(
            workItemId,
            WorkItemEvent.ResponsibilityCycleRearmed(
                workItemId = workItemId,
                reason = reason,
            )
        ) ?: states[workItemId] ?: state
    }

    private fun shouldRearmResponsibilityCycle(
        state: WorkItemState,
        wakeReasonType: WakeReasonType?,
    ): Boolean =
        state.workItem.kind == WorkItemKind.RESPONSIBILITY &&
            !state.isTerminal() &&
            state.workItem.status != WorkItemStatus.SUSPENDED &&
            state.nextRunnableStep() == null &&
            wakeReasonType in RESPONSIBILITY_REARM_WAKE_REASON_TYPES &&
            state.workItem.plan.steps.isNotEmpty() &&
            state.workItem.plan.steps.all {
                it.status in setOf(StepStatus.DONE, StepStatus.SKIPPED, StepStatus.FAILED)
            }

    private fun isEligibleForIdReview(state: WorkItemState): Boolean =
        !state.isTerminal() &&
            state.workItem.kind == WorkItemKind.RESPONSIBILITY &&
            state.workItem.status != WorkItemStatus.SUSPENDED &&
            state.workItem.reviewPolicy.enabled &&
            state.workItem.reviewPolicy.idReviewEligible

    private fun isResponsibilityReviewOverdue(state: WorkItemState): Boolean =
        state.workItem.kind == WorkItemKind.RESPONSIBILITY &&
            !state.isTerminal() &&
            state.workItem.status != WorkItemStatus.SUSPENDED &&
            state.workItem.nextReviewAt?.isBefore(Instant.now()) == true

    private fun inferWakeReasonType(reason: String): WakeReasonType =
        when {
            reason.contains("review", ignoreCase = true) -> WakeReasonType.MANUAL_REVIEW
            reason.contains("recovered", ignoreCase = true) -> WakeReasonType.RECOVERY
            reason.contains("cron", ignoreCase = true) -> WakeReasonType.CRON_DUE
            reason.contains("timer", ignoreCase = true) -> WakeReasonType.TIMER_DUE
            reason.contains("change", ignoreCase = true) -> WakeReasonType.MONITOR_CHANGE_DETECTED
            reason.contains("unblocked", ignoreCase = true) -> WakeReasonType.WAIT_RESOLVED
            reason.contains("retry", ignoreCase = true) -> WakeReasonType.STEP_RETRY
            reason.contains("completed", ignoreCase = true) -> WakeReasonType.STEP_COMPLETED
            else -> WakeReasonType.PLAN_READY
        }

    private fun upsertSourceState(
        sources: List<MonitorSourceState>,
        update: MonitorSourceState,
    ): List<MonitorSourceState> {
        val existing = sources.firstOrNull { it.sourceKey == update.sourceKey }
        val merged = if (existing == null) {
            update
        } else {
            existing.copy(
                sourceKind = update.sourceKind.ifBlank { existing.sourceKind },
                cursorOrCheckpoint = update.cursorOrCheckpoint ?: existing.cursorOrCheckpoint,
                lastScanAt = update.lastScanAt ?: existing.lastScanAt,
                lastSuccessfulScanAt = update.lastSuccessfulScanAt ?: existing.lastSuccessfulScanAt,
                lastScanSummary = update.lastScanSummary ?: existing.lastScanSummary,
            )
        }
        return (sources.filterNot { it.sourceKey == update.sourceKey } + merged)
            .takeLast(config.monitoring.reportWindowSize)
    }

    // ── Per-item synchronization ────────────────────────────────────────

    private fun <T> withItemLock(workItemId: String, action: () -> T): T {
        val lock = itemLocks.getOrPut(workItemId) { java.util.concurrent.locks.ReentrantLock() }
        lock.lock()
        try {
            return action()
        } finally {
            lock.unlock()
        }
    }

    companion object {
        const val DEFAULT_STEP_MAX_ATTEMPTS: Int = 3
        const val MAX_STEP_ATTEMPTS: Int = 10

        fun parseGroundingRequirement(raw: String?): ai.neopsyke.agent.model.GroundingRequirement =
            when (raw?.trim()?.lowercase()) {
                "required" -> ai.neopsyke.agent.model.GroundingRequirement.REQUIRED
                "not_required", "", null -> ai.neopsyke.agent.model.GroundingRequirement.NOT_REQUIRED
                else -> ai.neopsyke.agent.model.GroundingRequirement.NOT_REQUIRED
            }

        fun parseGroundingRequirementStrict(raw: String?): ai.neopsyke.agent.model.GroundingRequirement? =
            when (raw?.trim()?.lowercase()) {
                "required" -> ai.neopsyke.agent.model.GroundingRequirement.REQUIRED
                "not_required", "", null -> ai.neopsyke.agent.model.GroundingRequirement.NOT_REQUIRED
                else -> null
            }

        private const val ACTIVATION_JOURNAL_DETAIL_MAX_CHARS: Int = 200
        private const val DEFAULT_PLAN_REVISION: Int = 1
        private const val EFFECT_REASON_MAX_CHARS: Int = 180
        private const val EFFECT_INTENT_SEGMENT_LIMIT: Int = 4
        private const val EFFECT_INTENT_DUPLICATE_REASON_CODE: String = "durable_work_effect_duplicate"
        private const val EFFECT_INTENT_GATE_SOURCE: String = "durable_work_effect_ledger"
        private val REVIEW_WAKE_REASON_TYPES: Set<WakeReasonType> = setOf(
            WakeReasonType.MANUAL_REVIEW,
            WakeReasonType.ID_REVIEW,
            WakeReasonType.OVERDUE_CHECK,
        )
        private val RESPONSIBILITY_REARM_WAKE_REASON_TYPES: Set<WakeReasonType> = setOf(
            WakeReasonType.MANUAL_REVIEW,
            WakeReasonType.ID_REVIEW,
            WakeReasonType.OVERDUE_CHECK,
            WakeReasonType.CRON_DUE,
        )
        private val TERMINAL_OR_ESCALATED_STATUSES: Set<WorkItemStatus> = setOf(
            WorkItemStatus.COMPLETED,
            WorkItemStatus.FAILED,
            WorkItemStatus.STALLED,
            WorkItemStatus.NEEDS_ATTENTION,
            WorkItemStatus.RETIRED,
        )
    }

    private data class ValidatedPlanStep(
        val id: String,
        val description: String,
        val acceptanceCriteria: String,
        val requires: Set<String>,
        val produces: Set<String>,
        val maxAttempts: Int,
        val groundingRequirement: ai.neopsyke.agent.model.GroundingRequirement,
    )
}
