package ai.neopsyke.agent.ego.planner.prompt

import ai.neopsyke.agent.model.ActionType
import ai.neopsyke.agent.model.EgoTrigger
import ai.neopsyke.agent.model.PlannerContext
import ai.neopsyke.agent.support.DenialReasonClassifier
import ai.neopsyke.agent.support.PromptBudgetAllocator
import ai.neopsyke.llm.ChatRole
import java.util.Locale

/**
 * Shared prompt sections used across multiple lane prompt profiles.
 * Extracted from LlmEgoPlanner.buildMessages() to avoid duplication.
 */
object SharedPromptSections {

    const val GOAL_WORKING_CONTEXT_MAX_CHARS: Int = 1_200

    fun formatTriggerText(trigger: EgoTrigger): String =
        when (trigger) {
            is EgoTrigger.IncomingInput -> "INPUT: ${trigger.input.content}"
            is EgoTrigger.IncomingImpulse -> "IMPULSE(need=${trigger.impulse.needId}): ${trigger.impulse.prompt}"
            is EgoTrigger.ActionFeedback -> {
                val cue = trigger.feedback.cue
                buildString {
                    append("ACTION_FEEDBACK(action=")
                    append(cue.actionType.id)
                    append(", status=")
                    append(cue.executionStatus.name.lowercase())
                    append("): ")
                    append(cue.feedbackContent)
                    if (cue.actionSummary.isNotBlank()) {
                        append("\naction_summary=")
                        append(cue.actionSummary)
                    }
                    if (cue.statusSummary.isNotBlank() && cue.statusSummary != cue.feedbackContent) {
                        append("\nstatus_summary=")
                        append(cue.statusSummary)
                    }
                    if (cue.plannerSignal.isNotBlank() && cue.plannerSignal != cue.feedbackContent) {
                        append("\nplanner_signal=")
                        append(cue.plannerSignal)
                    }
                    cue.actionErrorCategory?.takeIf { it.isNotBlank() }?.let {
                        append("\naction_error_category=")
                        append(it)
                    }
                    cue.fetchErrorCategory?.takeIf { it.isNotBlank() }?.let {
                        append("\nfetch_error_category=")
                        append(it)
                    }
                }
            }
            is EgoTrigger.DeferredIntention -> {
                val thought = trigger.intention.toPendingThought()
                val planInfo = thought.planContext?.let { ctx ->
                    """
                    Active plan context:
                    plan_goal=${ctx.planGoal}
                    step=${ctx.stepIndex + 1}/${ctx.totalSteps}
                    step_description=${ctx.stepDescription}
                    Re-evaluate this step in light of current context. You may refine, skip, or act.
                    """.trimIndent()
                } ?: ""
                val denialContext = if (thought.deniedActionType != null && !thought.deniedActionPayload.isNullOrBlank()) {
                    val technicalDenial = DenialReasonClassifier.isLikelyTechnical(
                        reasonCode = thought.denialReasonCode,
                        reason = thought.denialReason
                    )
                    val retryGuidance = if (technicalDenial) {
                        "Denied reason appears technical/transient; retrying the same payload once is allowed if still best."
                    } else {
                        "Do not repeat the denied action payload; prefer a materially different next step."
                    }
                    """
                    Denied action context:
                    denied_action_type=${thought.deniedActionType.name.lowercase()}
                    denied_action_payload=${thought.deniedActionPayload}
                    denied_reason=${thought.denialReason ?: "none"}
                    denied_reason_code=${thought.denialReasonCode ?: "none"}
                    $retryGuidance
                    """.trimIndent()
                } else {
                    "Denied action context: none"
                }
                val parts = listOf("DEFERRED_INTENTION(pass=${thought.passes}): ${thought.content}", planInfo, denialContext)
                    .filter { it.isNotBlank() }
                parts.joinToString("\n")
            }
            is EgoTrigger.GoalWork -> {
                val wu = trigger.workUnit
                buildString {
                    append("GOAL_WORK(goal=, step=${wu.stepId}): ${wu.stepDescription}")
                    if (wu.wakeReason.isNotBlank()) {
                        append("\nwake_reason=")
                        append(wu.wakeReason)
                    }
                    if (wu.acceptanceCriteria.isNotBlank()) {
                        append("\nacceptance_criteria=")
                        append(wu.acceptanceCriteria)
                    }
                    if (wu.workingContext.isNotBlank()) {
                        append("\nworking_context=")
                        append(wu.workingContext.take(GOAL_WORKING_CONTEXT_MAX_CHARS))
                    }
                }
            }
        }

    fun actionSchemaEnum(actions: Set<ActionType>): String =
        actions
            .map { it.id }
            .sorted()
            .joinToString("|")
            .ifBlank { "contact_user" }

    fun actionGuidanceBlock(context: PlannerContext): String {
        val dispatchableActions = if (context.dispatchableActions.isEmpty()) {
            context.availableActions
        } else {
            context.dispatchableActions
        }
        val plannerVisibleActions = dispatchableActions
            .filter { context.availableActions.contains(it) }
            .toSet()
        return context.actionDefinitions
            .filter { plannerVisibleActions.contains(it.actionType) }
            .sortedBy { it.actionType.id }
            .joinToString("\n") { definition ->
                val example = definition.payloadSchemaExample?.trim().orEmpty()
                val exampleSuffix = if (example.isBlank()) "" else " Example: $example"
                val trustSuffix = definition.allowedInstructionTrust
                    .map { it.name.lowercase() }
                    .sorted()
                    .joinToString(",")
                val autonomousSuffix = when {
                    definition.supportsAutonomousCommit -> " autonomous_commit_capable"
                    definition.directCommitAllowed -> " direct_commit_capable"
                    else -> " staged_or_approval_commit"
                }
                "- ${definition.description} " +
                    "effect_class=${definition.effectClass.name.lowercase()} " +
                    "allowed_instruction_trust=$trustSuffix " +
                    "commit_path=$autonomousSuffix " +
                    "Payload: ${definition.payloadGuidance}.$exampleSuffix"
            }
            .ifBlank { "- contact_user: payload is plain text to deliver to the interlocutor." }
    }

    fun plannerVisibleActionSchemaEnum(context: PlannerContext): String {
        val dispatchableActions = if (context.dispatchableActions.isEmpty()) {
            context.availableActions
        } else {
            context.dispatchableActions
        }
        val plannerVisibleActions = dispatchableActions
            .filter { context.availableActions.contains(it) }
            .toSet()
        return plannerVisibleActions
            .map { it.id }
            .sorted()
            .joinToString("|")
            .ifBlank { "contact_user" }
    }

    // --- Reusable context sections ---

    fun queueSnapshotSection(context: PlannerContext) = PromptBudgetAllocator.Section(
        key = "planner_queue_snapshot",
        role = ChatRole.USER,
        band = PromptBudgetAllocator.Band.OPTIONAL,
        content = """
            Queue snapshot:
            pending_inputs=${context.queue.pendingInputCount}
            pending_thoughts=${context.queue.deferredIntentionCount}
            pending_actions=${context.queue.pendingActionCount}
            pending_intentions=${context.queue.pendingIntentionCount}
        """.trimIndent()
    )

    fun actionAvailabilitySection(context: PlannerContext): PromptBudgetAllocator.Section {
        val availableActionList = context.availableActions
            .map { it.id }
            .sorted()
            .joinToString(", ")
            .ifBlank { "none" }
        val dispatchableActions = if (context.dispatchableActions.isEmpty()) {
            context.availableActions
        } else {
            context.dispatchableActions
        }
        val unavailableActionList = dispatchableActions
            .filterNot { context.availableActions.contains(it) }
            .map { it.id }
            .sorted()
            .joinToString(", ")
            .ifBlank { "none" }
        return PromptBudgetAllocator.Section(
            key = "planner_action_availability",
            role = ChatRole.USER,
            band = PromptBudgetAllocator.Band.REQUIRED_CONTEXT,
            importance = PromptBudgetAllocator.Importance.HIGH,
            floorTokens = 20,
            content = """
                Runtime action availability:
                available_action_types=$availableActionList
                unavailable_action_types=$unavailableActionList
                Never propose unavailable_action_types.
            """.trimIndent()
        )
    }

    fun securityContextSection(context: PlannerContext) = PromptBudgetAllocator.Section(
        key = "planner_security_context",
        role = ChatRole.USER,
        band = PromptBudgetAllocator.Band.REQUIRED_CONTEXT,
        floorTokens = 18,
        content = "Conversation security context:\n${context.conversationSecuritySummary.ifBlank { "none" }}\n\nThread trust state:\n${context.threadSecuritySummary.ifBlank { "none" }}"
    )

    fun triggerProvenanceSection(context: PlannerContext) = PromptBudgetAllocator.Section(
        key = "planner_trigger_provenance",
        role = ChatRole.USER,
        band = PromptBudgetAllocator.Band.REQUIRED_CONTEXT,
        floorTokens = 18,
        content = "Trigger provenance summary:\n${context.triggerProvenanceSummary.ifBlank { "none" }}"
    )

    fun perceptThreadSection(context: PlannerContext) = PromptBudgetAllocator.Section(
        key = "planner_percept_thread_context",
        role = ChatRole.USER,
        band = PromptBudgetAllocator.Band.REQUIRED_CONTEXT,
        floorTokens = 18,
        content = """
            Percept and thread state:
            percept_family=${context.perceptFamily?.name?.lowercase() ?: "none"}
            percept_summary=${context.perceptSummary.ifBlank { "none" }}
            cognitive_thread_id=${context.cognitiveThreadId ?: "none"}
            cognitive_thread_status=${context.cognitiveThreadStatus?.name?.lowercase() ?: "none"}
        """.trimIndent()
    )

    fun opportunityContextSection(context: PlannerContext) = PromptBudgetAllocator.Section(
        key = "planner_opportunity_context",
        role = ChatRole.USER,
        band = PromptBudgetAllocator.Band.REQUIRED_CONTEXT,
        floorTokens = 18,
        content = """
            Opportunity context:
            opportunity_kind=${context.opportunityKind?.name?.lowercase() ?: "none"}
            opportunity_summary=${context.opportunitySummary.ifBlank { "none" }}
            allowed_intentions=${context.allowedIntentions.map { it.name.lowercase() }.sorted().joinToString(", ").ifBlank { "none" }}
            allowed_commit_modes=${context.allowedCommitModes.map { it.name.lowercase() }.sorted().joinToString(", ").ifBlank { "none" }}
        """.trimIndent()
    )

    fun recentDialogueSection(context: PlannerContext) = PromptBudgetAllocator.Section(
        key = "planner_recent_dialogue",
        role = ChatRole.USER,
        band = PromptBudgetAllocator.Band.OPTIONAL,
        content = "Recent dialogue:\n${formatDialogue(context)}"
    )

    fun shortTermSummarySection(context: PlannerContext) = PromptBudgetAllocator.Section(
        key = "planner_short_term_summary",
        role = ChatRole.USER,
        band = PromptBudgetAllocator.Band.REQUIRED_CONTEXT,
        importance = PromptBudgetAllocator.Importance.HIGH,
        floorTokens = 24,
        content = "Short-term context summary:\n${context.shortTermContextSummary.ifBlank { "none" }}"
    )

    fun longTermRecallSection(context: PlannerContext) = PromptBudgetAllocator.Section(
        key = "planner_long_term_recall",
        role = ChatRole.USER,
        band = PromptBudgetAllocator.Band.REQUIRED_CONTEXT,
        floorTokens = 24,
        content = "Relevant long-term memory:\n${context.longTermMemoryRecall.ifBlank { "none" }}"
    )

    fun lessonsSection(context: PlannerContext) = PromptBudgetAllocator.Section(
        key = "planner_lessons",
        role = ChatRole.USER,
        band = PromptBudgetAllocator.Band.REQUIRED_CONTEXT,
        floorTokens = 20,
        content = "Lessons learned (avoid repeated failed strategies):\n${context.lessons.ifBlank { "none" }}"
    )

    fun episodicRecallSection(context: PlannerContext) = PromptBudgetAllocator.Section(
        key = "planner_episodic_recall",
        role = ChatRole.USER,
        band = PromptBudgetAllocator.Band.REQUIRED_CONTEXT,
        floorTokens = 24,
        content = "Recent past events:\n${context.episodicRecall.ifBlank { "none" }}"
    )

    fun scratchpadSection(context: PlannerContext) = PromptBudgetAllocator.Section(
        key = "planner_scratchpad_summary",
        role = ChatRole.USER,
        band = PromptBudgetAllocator.Band.REQUIRED_CONTEXT,
        floorTokens = 20,
        content = "Working notes for this request:\n${context.scratchpadSummary.ifBlank { "none" }}"
    )

    fun sessionDigestSection(context: PlannerContext) = PromptBudgetAllocator.Section(
        key = "planner_session_digest",
        role = ChatRole.USER,
        band = PromptBudgetAllocator.Band.REQUIRED_CONTEXT,
        floorTokens = 16,
        content = "Recent completed work summaries:\n${context.sessionScratchpadDigest.ifBlank { "none" }}"
    )

    fun ambientContextSection(context: PlannerContext): PromptBudgetAllocator.Section? =
        context.ambientContext.takeIf { !it.isEmpty() }?.let {
            PromptBudgetAllocator.Section(
                key = "planner_ambient_context",
                role = ChatRole.USER,
                band = PromptBudgetAllocator.Band.REQUIRED_CONTEXT,
                floorTokens = 20,
                content = "Background context:\n${it.render()}"
            )
        }

    fun activeGoalsSection(context: PlannerContext): PromptBudgetAllocator.Section? =
        context.goalWorkSummary.takeIf { it.isNotBlank() }?.let {
            PromptBudgetAllocator.Section(
                key = "planner_active_goals",
                role = ChatRole.USER,
                band = PromptBudgetAllocator.Band.OPTIONAL,
                content = "Persistent goals (resolve references into typed goal_reference / goal_id fields for goal_operation):\n$it"
            )
        }

    fun evidenceHintsSection(context: PlannerContext) = PromptBudgetAllocator.Section(
        key = "planner_evidence_hints",
        role = ChatRole.USER,
        band = PromptBudgetAllocator.Band.REQUIRED_CONTEXT,
        floorTokens = 18,
        content = "External evidence hints:\n${context.evidenceHints.ifBlank { "none" }}"
    )

    fun deliberationPressureSection(context: PlannerContext): PromptBudgetAllocator.Section {
        val deliberation = context.deliberation
        return PromptBudgetAllocator.Section(
            key = "planner_deliberation_pressure",
            role = ChatRole.USER,
            band = PromptBudgetAllocator.Band.REQUIRED_CONTEXT,
            floorTokens = 24,
            content = """
                Deliberation pressure:
                step_index=${deliberation.stepIndex}
                decision_pressure=${String.format(Locale.ROOT, "%.3f", deliberation.decisionPressure)}
                stale_streak=${deliberation.staleStreak}
                progress_score=${String.format(Locale.ROOT, "%.3f", deliberation.progressScore)}
                denial_count=${deliberation.denialCount}
                steps_since_new_evidence=${deliberation.stepsSinceNewEvidence}
                repeat_signature_hits=${deliberation.repeatSignatureHits}
                noop_streak=${deliberation.noopStreak}
                Guidance:
                - if decision_pressure >= 0.75, prefer a concrete action or final answer.
                - if decision_pressure >= 0.90, avoid new deferred-intention loops unless strictly necessary.
                - if external evidence hints already contain useful signals, avoid repeating the same external call unless refresh/retry is explicitly requested.
            """.trimIndent()
        )
    }

    fun metaGuidanceSection(context: PlannerContext) = PromptBudgetAllocator.Section(
        key = "planner_meta_guidance",
        role = ChatRole.USER,
        band = PromptBudgetAllocator.Band.REQUIRED_CONTEXT,
        floorTokens = 16,
        content = "Meta reasoning guidance:\n${context.metaGuidance.ifBlank { "none" }}"
    )

    fun idImpulseContextSection(context: PlannerContext): PromptBudgetAllocator.Section? =
        context.idState?.let { idState ->
            PromptBudgetAllocator.Section(
                key = "planner_id_impulse_context",
                role = ChatRole.USER,
                band = PromptBudgetAllocator.Band.REQUIRED_CONTEXT,
                importance = PromptBudgetAllocator.Importance.HIGH,
                floorTokens = 40,
                content = buildSelfMotivatedContext(idState),
            )
        }

    fun triggerSection(trigger: EgoTrigger) = PromptBudgetAllocator.Section(
        key = "planner_trigger",
        role = ChatRole.USER,
        band = PromptBudgetAllocator.Band.REQUIRED_CORE,
        importance = PromptBudgetAllocator.Importance.HIGH,
        floorTokens = 30,
        content = "Trigger:\n${formatTriggerText(trigger)}"
    )

    fun formatDialogue(context: PlannerContext): String =
        if (context.recentDialogue.isEmpty()) {
            "none"
        } else {
            context.recentDialogue.joinToString("\n") { turn ->
                "${turn.role.name.lowercase()}: ${turn.content}"
            }
        }

    private fun buildSelfMotivatedContext(idState: ai.neopsyke.agent.model.IdStateSnapshot): String {
        val motivation = String.format(Locale.ROOT, "%.3f", idState.triggeringTension)
        return when (idState.convergence) {
            ai.neopsyke.agent.id.ConvergenceMode.INTERNALIZE -> {
                if (idState.allowEscalation) {
                    """
                    Self-motivated context:
                    This trigger is self-initiated, not a user request.
                    Prefer researching and reflecting internally using action=reflect_internal.
                    Only address the user (action=contact_user) if you discover something immediately valuable or actionable.
                    Act proportionally to your motivation level ($motivation).
                    Only act if there is genuine value; prefer noop otherwise.
                    """.trimIndent()
                } else {
                    """
                    Self-motivated context:
                    This trigger is self-initiated, not a user request.
                    Research using available tools, then use action=reflect_evidence to record what you learned from same-request evidence.
                    Do not address the user directly.
                    Act proportionally to your motivation level ($motivation).
                    Only act if there is genuine value; prefer noop otherwise.
                    """.trimIndent()
                }
            }
            ai.neopsyke.agent.id.ConvergenceMode.CONTACT_USER -> {
                """
                Self-motivated context:
                This trigger is self-initiated, not a user request.
                Use action=contact_user to share observations, ask questions, or offer help.
                The contact_user action is the only channel for delivering text to the user.
                Act proportionally to your motivation level ($motivation).
                Only act if there is genuine value; prefer noop otherwise.
                """.trimIndent()
            }
        }
    }
}
