package ai.neopsyke.agent.project

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
 * File-backed storage for project workspaces.
 *
 * Directory layout per project:
 * ```
 * {workspaceRoot}/{project-id}/
 *   project.json        # metadata snapshot
 *   events.jsonl         # append-only event log
 *   snapshot.json        # periodic state snapshot
 *   workspace/
 *     context.md         # Tier 2 working context
 *     scratch.md         # free-form notes
 *     artifacts/         # step outputs
 * ```
 */
class ProjectStore(private val workspaceRoot: Path) {

    private val mapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    fun scanProjects(): List<String> {
        if (!Files.isDirectory(workspaceRoot)) return emptyList()
        return Files.list(workspaceRoot).use { stream ->
            stream.filter { Files.isDirectory(it) }
                .map { it.fileName.toString() }
                .filter { Files.exists(workspaceRoot.resolve(it).resolve(EVENTS_FILE)) }
                .toList()
        }
    }

    fun loadProject(projectId: String): ProjectState? {
        val dir = workspaceRoot.resolve(projectId)
        if (!Files.isDirectory(dir)) return null

        val projectStatePath = dir.resolve(PROJECT_FILE)
        val snapshotPath = dir.resolve(SNAPSHOT_FILE)
        val eventsPath = dir.resolve(EVENTS_FILE)
        val eventLog = ProjectEventLog(eventsPath)

        val (baseState, replayFrom) = when {
            Files.exists(projectStatePath) -> {
                try {
                    val snapshot = mapper.readValue<ProjectSnapshot>(Files.readString(projectStatePath))
                    snapshot.toState(dir.resolve(WORKSPACE_DIR)) to snapshot.eventCount
                } catch (e: Exception) {
                    logger.warn { "Failed to read project.json for $projectId, falling back: ${e.message}" }
                    loadSnapshotBase(snapshotPath, dir, projectId)
                }
            }

            Files.exists(snapshotPath) -> loadSnapshotBase(snapshotPath, dir, projectId)
            else -> null to 0
        }

        val events = eventLog.readFrom(replayFrom)
        if (baseState == null && events.isEmpty()) return null

        var state = baseState ?: run {
            val firstEvent = events.firstOrNull() ?: return null
            if (firstEvent !is ProjectEvent.Created) {
                logger.warn { "First event for $projectId is not Created, cannot reconstruct" }
                return null
            }
            ProjectStateMachine.initialState(firstEvent, dir.resolve(WORKSPACE_DIR))
        }

        val eventsToReplay = if (baseState == null) events.drop(1) else events
        for (event in eventsToReplay) {
            state = ProjectStateMachine.transition(state, event).first
        }
        return state
    }

    fun saveProjectState(projectId: String, state: ProjectState) {
        val dir = workspaceRoot.resolve(projectId)
        Files.createDirectories(dir)
        val json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(ProjectSnapshot.from(state))
        atomicWrite(dir.resolve(PROJECT_FILE), json)
    }

    fun saveSnapshot(projectId: String, state: ProjectState) {
        val dir = workspaceRoot.resolve(projectId)
        Files.createDirectories(dir)
        val snapshot = ProjectSnapshot.from(state)
        val json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot)
        atomicWrite(dir.resolve(SNAPSHOT_FILE), json)
    }

    fun createWorkspace(projectId: String): Path {
        val dir = workspaceRoot.resolve(projectId).resolve(WORKSPACE_DIR)
        Files.createDirectories(dir)
        Files.createDirectories(dir.resolve("artifacts"))
        return dir
    }

    fun appendScratchEntry(projectId: String, entry: String) {
        val workspace = createWorkspace(projectId)
        val scratch = workspace.resolve("scratch.md")
        Files.writeString(
            scratch,
            entry.trim() + "\n\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }

    fun writeArtifact(projectId: String, stepId: String, artifactName: String, content: String) {
        val artifactsDir = createWorkspace(projectId).resolve("artifacts").resolve(stepId)
        Files.createDirectories(artifactsDir)
        atomicWrite(artifactsDir.resolve(artifactName), content)
    }

    fun deleteProject(projectId: String) {
        val dir = workspaceRoot.resolve(projectId)
        if (!Files.exists(dir)) return
        Files.walk(dir)
            .sorted(Comparator.reverseOrder())
            .forEach { Files.deleteIfExists(it) }
    }

    fun eventLog(projectId: String): ProjectEventLog =
        ProjectEventLog(workspaceRoot.resolve(projectId).resolve(EVENTS_FILE))

    fun projectDir(projectId: String): Path = workspaceRoot.resolve(projectId)

    private fun loadSnapshotBase(snapshotPath: Path, dir: Path, projectId: String): Pair<ProjectState?, Int> =
        if (Files.exists(snapshotPath)) {
            try {
                val snapshot = mapper.readValue<ProjectSnapshot>(Files.readString(snapshotPath))
                snapshot.toState(dir.resolve(WORKSPACE_DIR)) to snapshot.eventCount
            } catch (e: Exception) {
                logger.warn { "Failed to read snapshot for $projectId, falling back to full replay: ${e.message}" }
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
        const val PROJECT_FILE = "project.json"
        const val EVENTS_FILE = "events.jsonl"
        const val SNAPSHOT_FILE = "snapshot.json"
        const val WORKSPACE_DIR = "workspace"
    }
}

/**
 * Serializable snapshot for crash recovery. Stores enough state to avoid
 * replaying the full event log.
 */
internal data class ProjectSnapshot(
    val projectId: String = "",
    val title: String = "",
    val instruction: String = "",
    val status: String = "",
    val priority: String = "",
    val completionCriteria: String = "",
    val createdAt: String = "",
    val lastWorkedAt: String? = null,
    val suspendedUntil: String? = null,
    val cronExpression: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val plan: ProjectPlan? = null,
    val producedKeys: Set<String> = emptySet(),
    val eventCount: Int = 0,
) {
    fun toState(workspacePath: Path): ProjectState = ProjectState(
        project = Project(
            id = projectId,
            title = title,
            instruction = instruction,
            status = ProjectStatus.valueOf(status),
            priority = ProjectPriority.valueOf(priority),
            plan = plan ?: ProjectPlan.empty(),
            completionCriteria = completionCriteria,
            createdAt = java.time.Instant.parse(createdAt),
            lastWorkedAt = lastWorkedAt?.let { java.time.Instant.parse(it) },
            suspendedUntil = suspendedUntil?.let { java.time.Instant.parse(it) },
            cronExpression = cronExpression,
            workspacePath = workspacePath,
            metadata = metadata,
        ),
        producedKeys = producedKeys,
        eventCount = eventCount,
    )

    companion object {
        fun from(state: ProjectState): ProjectSnapshot = ProjectSnapshot(
            projectId = state.id,
            title = state.project.title,
            instruction = state.project.instruction,
            status = state.project.status.name,
            priority = state.project.priority.name,
            completionCriteria = state.project.completionCriteria,
            createdAt = state.project.createdAt.toString(),
            lastWorkedAt = state.project.lastWorkedAt?.toString(),
            suspendedUntil = state.project.suspendedUntil?.toString(),
            cronExpression = state.project.cronExpression,
            metadata = state.project.metadata,
            plan = state.project.plan,
            producedKeys = state.producedKeys,
            eventCount = state.eventCount,
        )
    }
}
