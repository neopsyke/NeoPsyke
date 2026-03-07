package psyke.agent.memory.episodic

import java.io.Closeable

/**
 * Persistence interface for episodic memory entries.
 */
interface Logbook : Closeable {
    fun record(entry: LogbookEntry): Long
    fun query(query: LogbookQuery): LogbookRecall
    fun pruneOlderThan(retentionDays: Int): Int
    override fun close()
}
