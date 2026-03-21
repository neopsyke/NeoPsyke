package ai.neopsyke.instrumentation

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import mu.KotlinLogging
import ai.neopsyke.agent.memory.scratchpad.ScratchpadDebugSnapshot
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

private val logger = KotlinLogging.logger {}

class ScratchpadDumpSink(
    private val outputDir: Path = Path.of(".neopsyke/workspace-dumps"),
    scope: CoroutineScope,
) : InstrumentationSink {
    private data class WriteJob(val filePath: Path, val text: String)

    private val writeChannel = Channel<WriteJob>(capacity = 64)
    private var dirCreated = false

    init {
        scope.launch(Dispatchers.IO + CoroutineName("workspace-dump-writer")) {
            for (job in writeChannel) {
                try {
                    if (!dirCreated) {
                        Files.createDirectories(outputDir.toAbsolutePath().normalize())
                        dirCreated = true
                    }
                    Files.writeString(
                        job.filePath, job.text,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND
                    )
                    logger.info { "Workspace dump written to: ${job.filePath}" }
                } catch (ex: Exception) {
                    logger.warn(ex) { "Failed to write workspace dump to ${job.filePath}" }
                }
            }
        }
    }

    override fun onEvent(event: AgentEvent) {
        if (event.type != EVENT_TYPE) return
        val sessionId = event.data["session_id"] as? String ?: return
        val snapshot = event.data["snapshot"] as? ScratchpadDebugSnapshot ?: return
        val candidateAnswer = event.data["candidate_answer"] as? String ?: ""
        val ts = event.tsIso
        val text = formatDump(ts, sessionId, snapshot, candidateAnswer)
        val filePath = outputDir.resolve("$sessionId.txt").toAbsolutePath().normalize()
        writeChannel.trySend(WriteJob(filePath, text))
    }

    override fun close() {
        writeChannel.close()
    }

    companion object {
        const val EVENT_TYPE: String = "scratchpad_pre_final_dump"

        private fun formatDump(
            timestamp: String,
            sessionId: String,
            snapshot: ScratchpadDebugSnapshot,
            candidateAnswer: String,
        ): String = buildString {
            append("================ WORKSPACE DUMP ================\n")
            append("Timestamp:  ").append(timestamp).append('\n')
            append("Session:    ").append(sessionId).append('\n')
            append("Root Input: ").append(snapshot.head.rootInputId).append('\n')
            append("Goal:       ").append(snapshot.head.goal.ifBlank { "(none)" }).append('\n')
            append("Confidence: ").append("%.2f".format(snapshot.head.workspaceConfidence)).append('\n')
            append("Sections:   ").append(snapshot.head.sectionCount)
            append(" | Evidence: ").append(snapshot.head.evidenceCount)
            append(" | Version: ").append(snapshot.head.version).append('\n')
            append('\n')
            if (snapshot.sections.isNotEmpty()) {
                append("--- SECTIONS ---\n")
                snapshot.sections.forEachIndexed { index, section ->
                    append('[').append(index + 1).append("] ")
                    append(section.title)
                    append(" (source=").append(section.source).append(")\n")
                    if (section.summary.isNotBlank()) {
                        append("    Summary: ").append(section.summary).append('\n')
                    }
                    if (section.content.isNotBlank()) {
                        append("    ").append(section.content.replace("\n", "\n    ")).append('\n')
                    }
                    append('\n')
                }
            }
            if (snapshot.evidence.isNotEmpty()) {
                append("--- EVIDENCE ---\n")
                snapshot.evidence.forEach { item ->
                    append("- ").append(item).append('\n')
                }
                append('\n')
            }
            val trimmedAnswer = candidateAnswer.trim()
            if (trimmedAnswer.isNotEmpty()) {
                append("--- CANDIDATE ANSWER ---\n")
                append(trimmedAnswer).append('\n')
                append('\n')
            }
            append("=================================================\n\n")
        }
    }
}
