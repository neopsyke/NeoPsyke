package ai.neopsyke.agent.memory.episodic

import java.io.Closeable

/**
 * Persistence interface for episodic memory entries.
 */
interface Logbook : Closeable {
    fun record(entry: LogbookEntry): Long
    fun query(query: LogbookQuery): LogbookRecall
    fun pruneOlderThan(retentionDays: Int): Int

    /**
     * Deletes **all** entries from the logbook.
     * Returns the number of entries removed.
     */
    fun clearAll(): Int = 0

    override fun close()
}
