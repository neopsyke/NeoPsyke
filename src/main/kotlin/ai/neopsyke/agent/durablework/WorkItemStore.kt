package ai.neopsyke.agent.durablework

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
 *   workItem.json        # metadata snapshot
 *   goal-events.jsonl    # append-only event log
 *   goal-snapshot.json   # periodic state snapshot
 *   workspace/
 *     context.md         # Tier 2 working context
 *     scratch.md         # free-form notes
 *     artifacts/         # step outputs
 * ```
 */
class WorkItemStore(private val workspaceRoot: Path) {

    private val mapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    fun scanWorkItems(): List<String> {
        if (!Files.isDirectory(workspaceRoot)) return emptyList()
        return Files.list(workspaceRoot).use { stream ->
            stream.filter { Files.isDirectory(it) }
                .map { it.fileName.toString() }
                .filter { Files.exists(workspaceRoot.resolve(it).resolve(EVENTS_FILE)) }
                .toList()
        }
    }

    fun loadWorkItem(workItemId: String): WorkItemState? {
        val dir = workspaceRoot.resolve(workItemId)
        if (!Files.isDirectory(dir)) return null

        val goalStatePath = dir.resolve(GOAL_FILE)
        val snapshotPath = dir.resolve(SNAPSHOT_FILE)
        val eventsPath = dir.resolve(EVENTS_FILE)
        val eventLog = WorkItemEventLog(eventsPath)

        val (baseState, replayFrom) = when {
            Files.exists(goalStatePath) -> {
                try {
                    val snapshot = mapper.readValue<WorkItemSnapshot>(Files.readString(goalStatePath))
                    snapshot.toState(dir.resolve(WORKSPACE_DIR)) to snapshot.eventCount
                } catch (e: Exception) {
                    logger.warn { "Failed to read workItem.json for $workItemId, falling back: ${e.message}" }
                    loadSnapshotBase(snapshotPath, dir, workItemId)
                }
            }

            Files.exists(snapshotPath) -> loadSnapshotBase(snapshotPath, dir, workItemId)
            else -> null to 0
        }

        val events = eventLog.readFrom(replayFrom)
        if (baseState == null && events.isEmpty()) return null

        var state = baseState ?: run {
            val firstEvent = events.firstOrNull() ?: return null
            if (firstEvent !is WorkItemEvent.Created) {
                logger.warn { "First event for $workItemId is not Created, cannot reconstruct" }
                return null
            }
            WorkItemStateMachine.initialState(firstEvent, dir.resolve(WORKSPACE_DIR))
        }

        val eventsToReplay = if (baseState == null) events.drop(1) else events
        for (event in eventsToReplay) {
            state = WorkItemStateMachine.transition(state, event).first
        }
        return state
    }

    fun saveWorkItemState(workItemId: String, state: WorkItemState) {
        val dir = workspaceRoot.resolve(workItemId)
        Files.createDirectories(dir)
        val json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(WorkItemSnapshot.from(state))
        atomicWrite(dir.resolve(GOAL_FILE), json)
    }

    fun saveSnapshot(workItemId: String, state: WorkItemState) {
        val dir = workspaceRoot.resolve(workItemId)
        Files.createDirectories(dir)
        val snapshot = WorkItemSnapshot.from(state)
        val json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot)
        atomicWrite(dir.resolve(SNAPSHOT_FILE), json)
    }

    fun createWorkspace(workItemId: String): Path {
        val dir = workspaceRoot.resolve(workItemId).resolve(WORKSPACE_DIR)
        Files.createDirectories(dir)
        Files.createDirectories(dir.resolve("artifacts"))
        return dir
    }

    fun appendScratchEntry(workItemId: String, entry: String) {
        val workspace = createWorkspace(workItemId)
        val scratch = workspace.resolve("scratch.md")
        Files.writeString(
            scratch,
            entry.trim() + "\n\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }

    fun writeArtifact(workItemId: String, stepId: String, artifactName: String, content: String) {
        val artifactsDir = createWorkspace(workItemId).resolve("artifacts").resolve(stepId)
        Files.createDirectories(artifactsDir)
        atomicWrite(artifactsDir.resolve(artifactName), content)
    }

    fun deleteWorkItem(workItemId: String) {
        val dir = workspaceRoot.resolve(workItemId)
        if (!Files.exists(dir)) return
        Files.walk(dir)
            .sorted(Comparator.reverseOrder())
            .forEach { Files.deleteIfExists(it) }
    }

    fun workItemEventLog(workItemId: String): WorkItemEventLog =
        WorkItemEventLog(workspaceRoot.resolve(workItemId).resolve(EVENTS_FILE))

    fun workItemDir(workItemId: String): Path = workspaceRoot.resolve(workItemId)

    private fun loadSnapshotBase(snapshotPath: Path, dir: Path, workItemId: String): Pair<WorkItemState?, Int> =
        if (Files.exists(snapshotPath)) {
            try {
                val snapshot = mapper.readValue<WorkItemSnapshot>(Files.readString(snapshotPath))
                snapshot.toState(dir.resolve(WORKSPACE_DIR)) to snapshot.eventCount
            } catch (e: Exception) {
                logger.warn { "Failed to read snapshot for $workItemId, falling back to full replay: ${e.message}" }
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
internal data class WorkItemSnapshot(
    val workItemId: String = "",
    val kind: String = WorkItemKind.LONG_TERM_GOAL.name,
    val title: String = "",
    val objective: String = "",
    val instruction: String = "",
    val status: String = "",
    val health: String = WorkItemHealth.HEALTHY.name,
    val priority: String = "",
    val completionCriteria: String = "",
    val deliveryPolicy: String = DeliveryPolicy.IMMEDIATE.name,
    val planRevision: Int = 1,
    val schemaVersion: Int = 1,
    val createdAt: String = "",
    val lastWorkedAt: String? = null,
    val suspendedUntil: String? = null,
    val cronExpression: String? = null,
    val contactChannel: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val plan: WorkItemPlan? = null,
    val producedKeys: Set<String> = emptySet(),
    val eventCount: Int = 0,
) {
    fun toState(workspacePath: Path): WorkItemState = WorkItemState(
        workItem = WorkItem(
            id = workItemId,
            kind = runCatching { WorkItemKind.valueOf(kind) }.getOrDefault(WorkItemKind.LONG_TERM_GOAL),
            title = title,
            objective = objective,
            instruction = instruction,
            status = WorkItemStatus.valueOf(status),
            health = runCatching { WorkItemHealth.valueOf(health) }.getOrDefault(WorkItemHealth.HEALTHY),
            priority = WorkItemPriority.valueOf(priority),
            plan = plan ?: WorkItemPlan.empty(),
            completionCriteria = completionCriteria,
            deliveryPolicy = runCatching { DeliveryPolicy.valueOf(deliveryPolicy) }.getOrDefault(DeliveryPolicy.IMMEDIATE),
            planRevision = planRevision,
            schemaVersion = schemaVersion,
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
        fun from(state: WorkItemState): WorkItemSnapshot = WorkItemSnapshot(
            workItemId = state.id,
            kind = state.workItem.kind.name,
            title = state.workItem.title,
            objective = state.workItem.objective,
            instruction = state.workItem.instruction,
            status = state.workItem.status.name,
            health = state.workItem.health.name,
            priority = state.workItem.priority.name,
            completionCriteria = state.workItem.completionCriteria,
            deliveryPolicy = state.workItem.deliveryPolicy.name,
            planRevision = state.workItem.planRevision,
            schemaVersion = state.workItem.schemaVersion,
            createdAt = state.workItem.createdAt.toString(),
            lastWorkedAt = state.workItem.lastWorkedAt?.toString(),
            suspendedUntil = state.workItem.suspendedUntil?.toString(),
            cronExpression = state.workItem.cronExpression,
            contactChannel = state.workItem.contactChannel,
            metadata = state.workItem.metadata,
            plan = state.workItem.plan,
            producedKeys = state.producedKeys,
            eventCount = state.eventCount,
        )
    }
}
