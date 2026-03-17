package psyke.agent.project

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Builds context at each tier for a project.
 *
 * - Tier 1: compact summary (~100 tokens), always in memory
 * - Tier 2: working context from `context.md` (~1-2k tokens), loaded when Ego picks up work
 * - Tier 3: full detail from workspace files, fetched on demand
 */
object ProjectContextLoader {

    /**
     * Build a Tier 1 summary from in-memory [ProjectState].
     */
    fun tier1Summary(state: ProjectState): ProjectTier1Summary {
        val currentStep = state.project.plan.steps.firstOrNull {
            it.status == StepStatus.IN_PROGRESS
        } ?: state.nextRunnableStep()

        val blockers = state.project.plan.steps
            .filter { it.status == StepStatus.BLOCKED }
            .mapNotNull { step ->
                step.waitCondition?.let { wc ->
                    "${step.id}: ${wc.type.name.lowercase()}"
                }
            }

        return ProjectTier1Summary(
            projectId = state.id,
            title = state.project.title,
            status = state.project.status,
            priority = state.project.priority,
            currentStepDescription = currentStep?.description,
            blockers = blockers,
            lastWorkedAt = state.project.lastWorkedAt,
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
     * Build a [ProjectWorkUnit] for the Ego to process.
     */
    fun buildWorkUnit(
        state: ProjectState,
        step: PlanStep,
        rootInputId: String,
        wakeReason: String,
    ): ProjectWorkUnit {
        val tier2 = tier2Context(state.project.workspacePath)
        return ProjectWorkUnit(
            projectId = state.id,
            stepId = step.id,
            rootInputId = rootInputId,
            stepDescription = step.description,
            acceptanceCriteria = step.acceptanceCriteria,
            workingContext = tier2,
            wakeReason = wakeReason,
        )
    }

    /**
     * Write Tier 2 `context.md` at the end of a project work cycle.
     *
     * This is the handoff document that preserves inter-session continuity.
     * It captures current state, what happened this cycle, and pointers to
     * detailed content. The Ego calls this via ProjectManager after each
     * project_advance resolution.
     *
     * @param state the current project state (post-cycle)
     * @param stepId the step that was just worked on
     * @param resultSummary what happened during this cycle
     */
    fun writeContext(state: ProjectState, stepId: String, resultSummary: String) {
        val workspacePath = state.project.workspacePath
        Files.createDirectories(workspacePath)
        val contextFile = workspacePath.resolve("context.md")

        val project = state.project
        val step = project.plan.steps.firstOrNull { it.id == stepId }
        val readySteps = state.readySteps()
        val blockedSteps = project.plan.steps.filter { it.status == StepStatus.BLOCKED }
        val doneSteps = project.plan.steps.filter { it.status == StepStatus.DONE }

        val content = buildString {
            appendLine("# ${project.title}")
            appendLine()
            appendLine("**Status:** ${project.status.name}")
            appendLine("**Priority:** ${project.priority.name}")
            appendLine("**Last worked:** ${Instant.now()}")
            appendLine()

            appendLine("## Progress")
            appendLine("- Done: ${doneSteps.size}/${project.plan.steps.size} steps")
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
            for (s in project.plan.steps) {
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
            appendLine(project.completionCriteria)
        }

        Files.writeString(contextFile, content)
    }
}
