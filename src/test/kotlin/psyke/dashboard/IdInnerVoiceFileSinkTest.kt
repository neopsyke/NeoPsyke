package psyke.dashboard

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IdInnerVoiceFileSinkTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `events are written as JSONL and readable back`() {
        val tempDir = Files.createTempDirectory("id-voice-test")
        val filePath = tempDir.resolve("id-inner-voice-test.jsonl")
        val sink = IdInnerVoiceFileSink(filePath)

        val event1 = InnerVoiceEvent(
            id = 1,
            type = InnerVoiceEventType.DELIBERATION,
            content = "Id is thinking about needs.",
            rootInputId = "impulse-1",
            sessionId = "default",
            ts = 1000L,
            origin = "id"
        )
        val event2 = InnerVoiceEvent(
            id = 2,
            type = InnerVoiceEventType.REFLECTION,
            content = "Learned that user prefers concise answers.",
            rootInputId = "impulse-1",
            sessionId = "default",
            ts = 2000L,
            origin = "id",
            metadata = mapOf("action_type" to "reflect", "keywords" to "concise,answers")
        )

        sink.write(event1)
        sink.write(event2)
        sink.close()

        val lines = Files.readAllLines(filePath)
        assertEquals(2, lines.size)

        val parsed1 = mapper.readValue<Map<String, Any?>>(lines[0])
        assertEquals(1, (parsed1["id"] as Number).toLong().toInt())
        assertEquals("DELIBERATION", parsed1["type"])
        assertEquals("id", parsed1["origin"])
        assertEquals("Id is thinking about needs.", parsed1["content"])

        val parsed2 = mapper.readValue<Map<String, Any?>>(lines[1])
        assertEquals(2, (parsed2["id"] as Number).toLong().toInt())
        assertEquals("REFLECTION", parsed2["type"])
        assertEquals("Learned that user prefers concise answers.", parsed2["content"])

        // Cleanup
        Files.deleteIfExists(filePath)
        Files.deleteIfExists(tempDir)
    }

    @Test
    fun `file is created in specified directory`() {
        val tempDir = Files.createTempDirectory("id-voice-test-dir")
        val subDir = tempDir.resolve("nested").resolve("logs")
        val filePath = subDir.resolve("id-inner-voice.jsonl")

        val sink = IdInnerVoiceFileSink(filePath)
        assertTrue(Files.exists(subDir), "Parent directories should be created")
        assertTrue(Files.exists(filePath), "File should be created")

        sink.close()

        // Cleanup
        Files.deleteIfExists(filePath)
        Files.deleteIfExists(subDir)
        Files.deleteIfExists(tempDir.resolve("nested"))
        Files.deleteIfExists(tempDir)
    }
}
