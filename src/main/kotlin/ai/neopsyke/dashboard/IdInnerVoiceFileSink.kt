package ai.neopsyke.dashboard

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.BufferedWriter
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path

/**
 * Persists Id-origin inner-voice events to a per-run JSONL file.
 *
 * Follows the same pattern as [ai.neopsyke.instrumentation.JsonlEventSink]:
 * one JSON object per line, flushed after every write so the file is
 * always in a consistent state for readers.
 *
 * The file is created on construction and survives regardless of
 * whether any dashboard is connected.
 */
class IdInnerVoiceFileSink(outputPath: Path) : Closeable {
    private val writer: BufferedWriter

    val path: Path = outputPath.toAbsolutePath().normalize()

    init {
        path.parent?.let { Files.createDirectories(it) }
        writer = Files.newBufferedWriter(path)
    }

    @Synchronized
    fun write(event: InnerVoiceEvent) {
        writer.write(jsonlMapper.writeValueAsString(event))
        writer.newLine()
        writer.flush()
    }

    override fun close() {
        writer.close()
    }

    private companion object {
        val jsonlMapper = jacksonObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
    }
}
