package freudian.poc.instrumentation

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

data class RuntimeEvent(
    val timestamp: String = Instant.now().toString(),
    val tick: Int? = null,
    val type: String,
    val attributes: Map<String, Any?> = emptyMap(),
)

interface EventLogger {
    fun log(event: RuntimeEvent)
}

class JsonlEventLogger(
    private val outputPath: Path,
    private val objectMapper: ObjectMapper = jacksonObjectMapper(),
) : EventLogger, Closeable {

    init {
        outputPath.parent?.let { Files.createDirectories(it) }
        Files.deleteIfExists(outputPath)
        Files.createFile(outputPath)
    }

    override fun log(event: RuntimeEvent) {
        val line = objectMapper.writeValueAsString(event)
        Files.writeString(
            outputPath,
            "$line\n",
            StandardOpenOption.APPEND,
        )
    }

    override fun close() {
        // no resources held open
    }
}

class CompositeEventLogger(
    private val delegates: List<EventLogger>,
) : EventLogger {
    override fun log(event: RuntimeEvent) {
        delegates.forEach { it.log(event) }
    }
}

class InMemoryEventLogger : EventLogger {
    private val mutableEvents = mutableListOf<RuntimeEvent>()
    val events: List<RuntimeEvent> get() = mutableEvents

    override fun log(event: RuntimeEvent) {
        mutableEvents += event
    }
}
