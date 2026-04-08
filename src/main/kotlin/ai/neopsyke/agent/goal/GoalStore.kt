package ai.neopsyke.agent.goal

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

private val logger = KotlinLogging.logger {}

/**
 * File-backed storage for goal workspaces.
 *
 * Directory layout per goal:
 * ```
 * {workspaceRoot}/{goal-id}/
 *   goal.json        # metadata snapshot
 *   goal-events.jsonl    # append-only event log
 *   goal-snapshot.json   # periodic state snapshot
 *   workspace/
 *     context.md         # Tier 2 working context
 *     scratch.md         # free-form notes
 *     artifacts/         # step outputs
 * ```
 */
class GoalStore(private val workspaceRoot: Path) {

    private val mapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    fun scanGoals(): List<String> {
        if (!Files.isDirectory(workspaceRoot)) return emptyList()
        return Files.list(workspaceRoot).use { stream ->
            stream.filter { Files.isDirectory(it) }
                .map { it.fileName.toString() }
                .filter { Files.exists(workspaceRoot.resolve(it).resolve(EVENTS_FILE)) }
                .toList()
        }
    }

    fun loadGoal(goalId: String): GoalState? {
        val dir = workspaceRoot.resolve(goalId)
        if (!Files.isDirectory(dir)) return null

        val goalStatePath = dir.resolve(GOAL_FILE)
        val snapshotPath = dir.resolve(SNAPSHOT_FILE)
        val eventsPath = dir.resolve(EVENTS_FILE)
        val eventLog = GoalEventLog(eventsPath)

        val (baseState, replayFrom) = when {
            Files.exists(goalStatePath) -> {
                try {
                    val snapshot = mapper.readValue<GoalSnapshot>(Files.readString(goalStatePath))
                    snapshot.toState(dir.resolve(WORKSPACE_DIR)) to snapshot.eventCount
                } catch (e: Exception) {
                    logger.warn { "Failed to read goal.json for $goalId, falling back: ${e.message}" }
                    loadSnapshotBase(snapshotPath, dir, goalId)
                }
            }

            Files.exists(snapshotPath) -> loadSnapshotBase(snapshotPath, dir, goalId)
            else -> null to 0
        }

        val events = eventLog.readFrom(replayFrom)
        if (baseState == null && events.isEmpty()) return null

        var state = baseState ?: run {
            val firstEvent = events.firstOrNull() ?: return null
            if (firstEvent !is GoalEvent.Created) {
                logger.warn { "First event for $goalId is not Created, cannot reconstruct" }
                return null
            }
            GoalStateMachine.initialState(firstEvent, dir.resolve(WORKSPACE_DIR))
        }

        val eventsToReplay = if (baseState == null) events.drop(1) else events
        for (event in eventsToReplay) {
            state = GoalStateMachine.transition(state, event).first
        }
        return state
    }

    fun saveGoalState(goalId: String, state: GoalState) {
        val dir = workspaceRoot.resolve(goalId)
        Files.createDirectories(dir)
        val json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(GoalSnapshot.from(state))
        atomicWrite(dir.resolve(GOAL_FILE), json)
    }

    fun saveSnapshot(goalId: String, state: GoalState) {
        val dir = workspaceRoot.resolve(goalId)
        Files.createDirectories(dir)
        val snapshot = GoalSnapshot.from(state)
        val json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot)
        atomicWrite(dir.resolve(SNAPSHOT_FILE), json)
    }

    fun createWorkspace(goalId: String): Path {
        val dir = workspaceRoot.resolve(goalId).resolve(WORKSPACE_DIR)
        Files.createDirectories(dir)
        Files.createDirectories(dir.resolve("artifacts"))
        return dir
    }

    fun appendScratchEntry(goalId: String, entry: String) {
        val workspace = createWorkspace(goalId)
        val scratch = workspace.resolve("scratch.md")
        Files.writeString(
            scratch,
            entry.trim() + "\n\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }

    fun writeArtifact(goalId: String, stepId: String, artifactName: String, content: String) {
        val artifactsDir = createWorkspace(goalId).resolve("artifacts").resolve(stepId)
        Files.createDirectories(artifactsDir)
        atomicWrite(artifactsDir.resolve(artifactName), content)
    }

    fun deleteGoal(goalId: String) {
        val dir = workspaceRoot.resolve(goalId)
        if (!Files.exists(dir)) return
        Files.walk(dir)
            .sorted(Comparator.reverseOrder())
            .forEach { Files.deleteIfExists(it) }
    }

    fun goalEventLog(goalId: String): GoalEventLog =
        GoalEventLog(workspaceRoot.resolve(goalId).resolve(EVENTS_FILE))

    fun goalDir(goalId: String): Path = workspaceRoot.resolve(goalId)

    private fun loadSnapshotBase(snapshotPath: Path, dir: Path, goalId: String): Pair<GoalState?, Int> =
        if (Files.exists(snapshotPath)) {
            try {
                val snapshot = mapper.readValue<GoalSnapshot>(Files.readString(snapshotPath))
                snapshot.toState(dir.resolve(WORKSPACE_DIR)) to snapshot.eventCount
            } catch (e: Exception) {
                logger.warn { "Failed to read snapshot for $goalId, falling back to full replay: ${e.message}" }
                null to 0
            }
        } else {
            null to 0
        }

    private fun atomicWrite(path: Path, content: String) {
        Files.createDirectories(path.parent)
        val temp = Files.createTempFile(path.parent, path.fileName.toString(), ".tmp")
        Files.writeString(temp, content, StandardOpenOption.TRUNCATE_EXISTING)
        try {
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val GOAL_FILE = "goal.json"
        const val EVENTS_FILE = "goal-events.jsonl"
        const val SNAPSHOT_FILE = "goal-snapshot.json"
        const val WORKSPACE_DIR = "workspace"
    }
}

/**
 * Serializable snapshot for crash recovery. Stores enough state to avoid
 * replaying the full event log.
 */
internal data class GoalSnapshot(
    val goalId: String = "",
    val title: String = "",
    val instruction: String = "",
    val status: String = "",
    val priority: String = "",
    val completionCriteria: String = "",
    val createdAt: String = "",
    val lastWorkedAt: String? = null,
    val suspendedUntil: String? = null,
    val cronExpression: String? = null,
    val contactChannel: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val plan: GoalPlan? = null,
    val producedKeys: Set<String> = emptySet(),
    val eventCount: Int = 0,
) {
    fun toState(workspacePath: Path): GoalState = GoalState(
        goal = Goal(
            id = goalId,
            title = title,
            instruction = instruction,
            status = GoalStatus.valueOf(status),
            priority = GoalPriority.valueOf(priority),
            plan = plan ?: GoalPlan.empty(),
            completionCriteria = completionCriteria,
            createdAt = java.time.Instant.parse(createdAt),
            lastWorkedAt = lastWorkedAt?.let { java.time.Instant.parse(it) },
            suspendedUntil = suspendedUntil?.let { java.time.Instant.parse(it) },
            cronExpression = cronExpression,
            contactChannel = contactChannel,
            workspacePath = workspacePath,
            metadata = metadata,
        ),
        producedKeys = producedKeys,
        eventCount = eventCount,
    )

    companion object {
        fun from(state: GoalState): GoalSnapshot = GoalSnapshot(
            goalId = state.id,
            title = state.goal.title,
            instruction = state.goal.instruction,
            status = state.goal.status.name,
            priority = state.goal.priority.name,
            completionCriteria = state.goal.completionCriteria,
            createdAt = state.goal.createdAt.toString(),
            lastWorkedAt = state.goal.lastWorkedAt?.toString(),
            suspendedUntil = state.goal.suspendedUntil?.toString(),
            cronExpression = state.goal.cronExpression,
            contactChannel = state.goal.contactChannel,
            metadata = state.goal.metadata,
            plan = state.goal.plan,
            producedKeys = state.producedKeys,
            eventCount = state.eventCount,
        )
    }
}
