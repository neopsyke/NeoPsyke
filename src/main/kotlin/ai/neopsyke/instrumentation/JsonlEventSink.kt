package ai.neopsyke.instrumentation

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path

private val jsonlMapper = jacksonObjectMapper()
    .setSerializationInclusion(JsonInclude.Include.NON_NULL)

class JsonlEventSink(outputPath: Path) : InstrumentationSink {
    private val writer: BufferedWriter

    init {
        val normalized = outputPath.toAbsolutePath().normalize()
        normalized.parent?.let { Files.createDirectories(it) }
        writer = Files.newBufferedWriter(normalized)
    }

    @Synchronized
    override fun onEvent(event: AgentEvent) {
        writer.write(jsonlMapper.writeValueAsString(event))
        writer.newLine()
        writer.flush()
    }

    override fun close() {
        writer.close()
    }
}
