package ai.neopsyke.agent.assignments

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorkItemStoreTest {

    @Test
    fun `loadWorkItem replays across rolled event-log segments`() {
        val root = Files.createTempDirectory("psyke-pm-segmented-store")
        try {
            val store = WorkItemStore(
                workspaceRoot = root,
                maxEventLogSegmentBytes = 350,
                maxArchivedEventSegments = 2,
            )
            val workItemId = "segmented-item"
            val log = store.workItemEventLog(workItemId)

            log.append(
                WorkItemEvent.Created(
                    workItemId = workItemId,
                    title = "Segmented item",
                    instruction = "Track long-lived event history",
                    priority = WorkItemPriority.MEDIUM,
                    completionCriteria = "done",
                )
            )
            repeat(8) { index ->
                log.append(
                    WorkItemEvent.Updated(
                        workItemId = workItemId,
                        title = "Segmented item v$index",
                        instruction = "Track long-lived event history iteration $index " + "x".repeat(80),
                        operatorSummary = "summary-$index",
                        reason = "update-$index",
                    )
                )
            }

            val eventFiles = Files.list(root.resolve(workItemId)).use { stream ->
                stream.map { it.fileName.toString() }
                    .filter { it.startsWith("assignment-events") && it.endsWith(".jsonl") }
                    .sorted()
                    .toList()
            }
            assertTrue(eventFiles.size > 1, "Expected rolled event-log segments to be present.")
            assertTrue(
                eventFiles.any { it == "assignment-events.archive.jsonl" },
                "Expected archived event history to be preserved for replay."
            )

            val restored = store.loadWorkItem(workItemId)
            assertNotNull(restored)
            assertEquals("Segmented item v7", restored.workItem.title)
            assertEquals("summary-7", restored.workItem.operatorSummary)
            assertEquals(9, restored.eventCount)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
