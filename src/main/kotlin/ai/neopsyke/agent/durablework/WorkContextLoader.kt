package ai.neopsyke.agent.durablework

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import ai.neopsyke.agent.cortex.motor.actions.ContactChannelPolicy
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.ConversationSecurityContexts
import ai.neopsyke.agent.model.GroundingMetadata
import ai.neopsyke.agent.model.GroundingSource
import ai.neopsyke.agent.model.Interlocutor

/**
 * Builds context at each tier for a workItem.
 *
 * - Tier 1: compact summary (~100 tokens), always in memory
 * - Tier 2: working context from `context.md` (~1-2k tokens), loaded when Ego picks up work
 * - Tier 3: full detail from workspace files, fetched on demand
 */
object WorkContextLoader {
    private const val DURABLE_WORK_RUNTIME_PROVIDER: String = "durable-work-runtime"


    /**
     * Build a Tier 1 summary from in-memory [WorkItemState].
     */
    fun tier1Summary(state: WorkItemState): WorkItemTier1Summary {
        val currentStep = state.workItem.plan.steps.firstOrNull {
            it.status == StepStatus.IN_PROGRESS
        } ?: state.nextRunnableStep()

        val blockers = state.workItem.plan.steps
            .filter { it.status == StepStatus.BLOCKED }
            .mapNotNull { step ->
                step.waitCondition?.let { wc ->
                    "${step.id}: ${wc.type.name.lowercase()}"
                }
            }

        return WorkItemTier1Summary(
            workItemId = state.id,
            kind = state.workItem.kind,
            title = state.workItem.title,
            status = state.workItem.status,
            health = state.workItem.health,
            priority = state.workItem.priority,
            deliveryPolicy = state.workItem.deliveryPolicy,
            currentStepDescription = currentStep?.description,
            blockers = blockers,
            lastWorkedAt = state.workItem.lastWorkedAt,
            cronExpression = state.workItem.cronExpression,
        )
    }

    /**
     * Load Tier 2 working context from the workspace `context.md`.
     * Returns empty string if the file does not exist.
     */
    fun tier2Context(workspacePath: Path): String {
        val contextFile = workspacePath.resolve("context.md")
        return if (Files.exists(contextFile)) {
            Files.readString(contextFile)
        } else {
            ""
        }
    }

    /**
     * Load a specific Tier 3 artifact by relative path within the workspace.
     */
    fun tier3Artifact(workspacePath: Path, relativePath: String): String? {
        val file = workspacePath.resolve(relativePath)
        return if (Files.exists(file)) Files.readString(file) else null
    }

    /**
     * Build a [DurableWorkActivation] for the Ego to process.
     */
    fun buildWorkUnit(
        state: WorkItemState,
        step: PlanStep,
        rootInputId: String,
        wakeReason: String,
        wakeReasons: List<WakeReason> = emptyList(),
    ): DurableWorkActivation {
        val tier2 = tier2Context(state.workItem.workspacePath)
        val preferredChannel = state.workItem.contactChannel?.trim()?.lowercase()?.ifBlank { null }
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        val facts = mapOf(
            "current_date" to LocalDate.now(zone).toString(),
            "current_time_utc" to Instant.now().toString(),
            "current_time_local" to now.format(DateTimeFormatter.ofPattern("HH:mm:ss")) + " ($zone)",
        )
        // Durable-work activation is system-internal: it has no real transport
        // address. Use the runtime provider with an empty channelId so the
        // output gateway always routes through the resolver, and carry the
        // work item's contactChannel as a *hint* in channel.attributes.
        val baseSecurity = ConversationSecurityContexts.internalAutomation(
            provider = DURABLE_WORK_RUNTIME_PROVIDER,
            channelId = "",
        )
        val security = if (preferredChannel != null) {
            val hintedAttributes = baseSecurity.channel.attributes +
                (ContactChannelPolicy.PREFERRED_CHANNEL_ATTRIBUTE to preferredChannel)
            baseSecurity.copy(channel = baseSecurity.channel.copy(attributes = hintedAttributes))
        } else {
            baseSecurity
        }
        return DurableWorkActivation(
            workItemId = state.id,
            stepId = step.id,
            rootInputId = rootInputId,
            stepDescription = step.description,
            acceptanceCriteria = step.acceptanceCriteria,
            workingContext = tier2,
            conversationContext = ConversationContext(
                sessionId = ConversationContext.DEFAULT_SESSION_ID,
                interlocutor = Interlocutor.named(DURABLE_WORK_RUNTIME_PROVIDER),
                security = security,
            ),
            wakeReason = wakeReason,
            activationContext = ActivationContext(
                workItemId = state.id,
                planRevision = state.workItem.planRevision,
                wakeSequence = state.workItem.activationCount + 1,
                wakeReasons = wakeReasons.ifEmpty {
                    listOf(WakeReason(type = WakeReasonType.PLAN_READY, detail = wakeReason.ifBlank { null }))
                },
                runtimeUrgency = when (state.workItem.priority) {
                    WorkItemPriority.CRITICAL -> RuntimeUrgency.CRITICAL
                    WorkItemPriority.HIGH -> RuntimeUrgency.HIGH
                    WorkItemPriority.MEDIUM -> RuntimeUrgency.MEDIUM
                    WorkItemPriority.LOW -> RuntimeUrgency.LOW
                },
                deliveryMode = state.workItem.deliveryPolicy,
                monitoringSummary = MonitorActivationSnapshot(
                    summary = state.workItem.operatorSummary,
                    sourceKeys = state.durableState.monitor.sources.map { it.sourceKey },
                    seenItemCount = state.durableState.monitor.seenItems.size,
                    pendingChangeCount = state.durableState.monitor.changeLedger.size,
                    activeWindowKey = state.durableState.monitor.reporting.activeWindowKey,
                ),
            ),
            groundingMetadata = GroundingMetadata(
                requirement = step.groundingRequirement,
                source = GroundingSource.DURABLE_WORK_STEP_POLICY,
            ),
            planRevision = state.workItem.planRevision,
            deliveryPolicy = state.workItem.deliveryPolicy,
            health = state.workItem.health,
            activationReason = wakeReason,
            wakeSequence = state.workItem.activationCount,
            runtimeFacts = facts,
        )
    }

    /**
     * Write Tier 2 `context.md` at the end of a durable work cycle.
     *
     * This is the handoff document that preserves inter-session continuity.
     * It captures current state, what happened this cycle, and pointers to
     * detailed content. The Ego calls this via DurableWorkRuntime after each
     * work item activation resolution.
     *
     * @param state the current work item state (post-cycle)
     * @param stepId the step that was just worked on
     * @param resultSummary what happened during this cycle
     */
    fun writeContext(state: WorkItemState, stepId: String, resultSummary: String) {
        val workspacePath = state.workItem.workspacePath
        Files.createDirectories(workspacePath)
        val contextFile = workspacePath.resolve("context.md")

        val workItem = state.workItem
        val step = workItem.plan.steps.firstOrNull { it.id == stepId }
        val readySteps = state.readySteps()
        val blockedSteps = workItem.plan.steps.filter { it.status == StepStatus.BLOCKED }
        val doneSteps = workItem.plan.steps.filter { it.status == StepStatus.DONE }

        val content = buildString {
            appendLine("# ${workItem.title}")
            appendLine()
            appendLine("**Status:** ${workItem.status.name}")
            appendLine("**Priority:** ${workItem.priority.name}")
            appendLine("**Last worked:** ${Instant.now()}")
            appendLine()

            appendLine("## Progress")
            appendLine("- Done: ${doneSteps.size}/${workItem.plan.steps.size} steps")
            if (blockedSteps.isNotEmpty()) {
                appendLine("- Blocked: ${blockedSteps.joinToString { it.id }}")
            }
            if (readySteps.isNotEmpty()) {
                appendLine("- Ready: ${readySteps.joinToString { "${it.id}: ${it.description}" }}")
            }
            appendLine()

            appendLine("## Latest Cycle")
            appendLine("- **Step:** ${step?.id ?: stepId} — ${step?.description ?: "(unknown)"}")
            appendLine("- **Result:** $resultSummary")
            appendLine()

            val completedWithOutput = workItem.plan.steps.filter {
                it.status == StepStatus.DONE && it.notes.isNotBlank()
            }
            if (completedWithOutput.isNotEmpty()) {
                appendLine("## Prior Step Outputs")
                for (s in completedWithOutput) {
                    appendLine("- **${s.id}** (${s.description}): ${s.notes.trim()}")
                }
                appendLine()
            }

            appendLine("## Plan Overview")
            for (s in workItem.plan.steps) {
                val marker = when (s.status) {
                    StepStatus.DONE -> "[x]"
                    StepStatus.IN_PROGRESS -> "[~]"
                    StepStatus.FAILED -> "[!]"
                    StepStatus.SKIPPED -> "[-]"
                    StepStatus.BLOCKED -> "[B]"
                    else -> "[ ]"
                }
                appendLine("- $marker ${s.id}: ${s.description}")
            }
            appendLine()

            appendLine("## Completion Criteria")
            appendLine(workItem.completionCriteria)
        }

        Files.writeString(contextFile, content)
    }
}
