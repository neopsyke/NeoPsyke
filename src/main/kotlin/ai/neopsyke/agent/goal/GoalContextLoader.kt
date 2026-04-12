package ai.neopsyke.agent.goal

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import ai.neopsyke.agent.model.ConversationContext
import ai.neopsyke.agent.model.ConversationSecurityContexts
import ai.neopsyke.agent.model.GroundingMetadata
import ai.neopsyke.agent.model.GroundingSource
import ai.neopsyke.agent.model.Interlocutor

/**
 * Builds context at each tier for a goal.
 *
 * - Tier 1: compact summary (~100 tokens), always in memory
 * - Tier 2: working context from `context.md` (~1-2k tokens), loaded when Ego picks up work
 * - Tier 3: full detail from workspace files, fetched on demand
 */
object GoalContextLoader {
    private const val GOAL_RUNTIME_PROVIDER: String = "goal-runtime"


    /**
     * Build a Tier 1 summary from in-memory [GoalState].
     */
    fun tier1Summary(state: GoalState): GoalTier1Summary {
        val currentStep = state.goal.plan.steps.firstOrNull {
            it.status == StepStatus.IN_PROGRESS
        } ?: state.nextRunnableStep()

        val blockers = state.goal.plan.steps
            .filter { it.status == StepStatus.BLOCKED }
            .mapNotNull { step ->
                step.waitCondition?.let { wc ->
                    "${step.id}: ${wc.type.name.lowercase()}"
                }
            }

        return GoalTier1Summary(
            goalId = state.id,
            title = state.goal.title,
            status = state.goal.status,
            priority = state.goal.priority,
            currentStepDescription = currentStep?.description,
            blockers = blockers,
            lastWorkedAt = state.goal.lastWorkedAt,
            cronExpression = state.goal.cronExpression,
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
     * Build a [GoalRunActivation] for the Ego to process.
     */
    fun buildWorkUnit(
        state: GoalState,
        step: PlanStep,
        rootInputId: String,
        wakeReason: String,
    ): GoalRunActivation {
        val tier2 = tier2Context(state.goal.workspacePath)
        val provider = state.goal.contactChannel ?: GOAL_RUNTIME_PROVIDER
        return GoalRunActivation(
            goalId = state.id,
            stepId = step.id,
            rootInputId = rootInputId,
            stepDescription = step.description,
            acceptanceCriteria = step.acceptanceCriteria,
            workingContext = tier2,
            conversationContext = ConversationContext(
                sessionId = ConversationContext.DEFAULT_SESSION_ID,
                interlocutor = Interlocutor.named(GOAL_RUNTIME_PROVIDER),
                security = ConversationSecurityContexts.internalAutomation(
                    provider = provider,
                    channelId = rootInputId,
                ),
            ),
            wakeReason = wakeReason,
            groundingMetadata = GroundingMetadata(
                requirement = step.groundingRequirement,
                source = GroundingSource.GOAL_STEP_POLICY,
            ),
        )
    }

    /**
     * Write Tier 2 `context.md` at the end of a goal work cycle.
     *
     * This is the handoff document that preserves inter-session continuity.
     * It captures current state, what happened this cycle, and pointers to
     * detailed content. The Ego calls this via GoalManager after each
     * goal_advance resolution.
     *
     * @param state the current goal state (post-cycle)
     * @param stepId the step that was just worked on
     * @param resultSummary what happened during this cycle
     */
    fun writeContext(state: GoalState, stepId: String, resultSummary: String) {
        val workspacePath = state.goal.workspacePath
        Files.createDirectories(workspacePath)
        val contextFile = workspacePath.resolve("context.md")

        val goal = state.goal
        val step = goal.plan.steps.firstOrNull { it.id == stepId }
        val readySteps = state.readySteps()
        val blockedSteps = goal.plan.steps.filter { it.status == StepStatus.BLOCKED }
        val doneSteps = goal.plan.steps.filter { it.status == StepStatus.DONE }

        val content = buildString {
            appendLine("# ${goal.title}")
            appendLine()
            appendLine("**Status:** ${goal.status.name}")
            appendLine("**Priority:** ${goal.priority.name}")
            appendLine("**Last worked:** ${Instant.now()}")
            appendLine()

            appendLine("## Progress")
            appendLine("- Done: ${doneSteps.size}/${goal.plan.steps.size} steps")
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

            appendLine("## Plan Overview")
            for (s in goal.plan.steps) {
                val marker = when (s.status) {
                    StepStatus.DONE -> "[x]"
                    StepStatus.IN_PROGRESS -> "[~]"
                    StepStatus.FAILED -> "[!]"
                    StepStatus.SKIPPED -> "[-]"
                    StepStatus.BLOCKED -> "[B]"
                    else -> "[ ]"
                }
                val suffix = s.notes.trim().takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                appendLine("- $marker ${s.id}: ${s.description}$suffix")
            }
            appendLine()

            appendLine("## Completion Criteria")
            appendLine(goal.completionCriteria)
        }

        Files.writeString(contextFile, content)
    }
}
