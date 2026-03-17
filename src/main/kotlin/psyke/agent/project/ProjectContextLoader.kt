package psyke.agent.project

import java.nio.file.Files
import java.nio.file.Path

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
        } ?: state.nextReadyStep()

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
    fun buildWorkUnit(state: ProjectState, step: PlanStep): ProjectWorkUnit {
        val tier2 = tier2Context(state.project.workspacePath)
        return ProjectWorkUnit(
            projectId = state.id,
            stepId = step.id,
            stepDescription = step.description,
            acceptanceCriteria = step.acceptanceCriteria,
            workingContext = tier2,
        )
    }
}
