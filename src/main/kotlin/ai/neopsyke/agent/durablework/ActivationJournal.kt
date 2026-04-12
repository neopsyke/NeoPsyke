package ai.neopsyke.agent.durablework

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Append-only JSONL journal for durable-work activations.
 *
 * Records activation boundaries (started, context materialized, step selected,
 * finished, recovered) to support restart recovery. On restart, the last
 * unfinished activation entry tells the runtime which items need lease recovery.
 */
class ActivationJournal(private val path: Path) {

    private val mapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    fun append(entry: ActivationJournalEntry) {
        val json = mapper.writeValueAsString(entry)
        Files.createDirectories(path.parent)
        Files.newOutputStream(
            path,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
            StandardOpenOption.SYNC,
        ).bufferedWriter().use { writer ->
            writer.append(json)
            writer.append('\n')
            writer.flush()
        }
    }

    fun readAll(): List<ActivationJournalEntry> {
        if (!Files.exists(path)) return emptyList()
        return Files.readAllLines(path)
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                try {
                    mapper.readValue<ActivationJournalEntry>(line)
                } catch (e: Exception) {
                    logger.warn { "Skipping malformed activation journal entry: ${e.message}" }
                    null
                }
            }
    }

    /**
     * Find work items with unfinished activations (started but no corresponding finished/recovered).
     * These need lease recovery on restart.
     */
    fun findUnfinishedActivations(): List<ActivationJournalEntry> {
        val entries = readAll()
        val started = mutableMapOf<String, ActivationJournalEntry>()
        for (entry in entries) {
            when (entry.boundary) {
                ActivationBoundary.STARTED -> started[entry.workItemId] = entry
                ActivationBoundary.FINISHED,
                ActivationBoundary.RECOVERED -> started.remove(entry.workItemId)
                else -> {}
            }
        }
        return started.values.toList()
    }
}

data class ActivationJournalEntry(
    val workItemId: String,
    val stepId: String = "",
    val leaseToken: String = "",
    val planRevision: Int = 1,
    val boundary: ActivationBoundary,
    val timestamp: Instant = Instant.now(),
    val detail: String = "",
)

enum class ActivationBoundary {
    STARTED,
    CONTEXT_MATERIALIZED,
    STEP_SELECTED,
    NEXT_WAKE_SCHEDULED,
    FINISHED,
    RECOVERED,
}
