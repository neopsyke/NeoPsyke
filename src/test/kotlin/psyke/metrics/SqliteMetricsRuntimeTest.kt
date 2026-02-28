package psyke.metrics

import psyke.llm.ChatCallMetadata
import psyke.llm.ChatCallRecord
import psyke.llm.ChatCallStatus
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class SqliteMetricsRuntimeTest {
    @Test
    fun `persistent totals are isolated by provider`() {
        val dbPath = Files.createTempFile("psyke-metrics-runtime-test", ".db")
        val previous = System.getProperty("psyke.metrics.db")
        System.setProperty("psyke.metrics.db", dbPath.toString())
        try {
            SqliteMetricsRuntime(
                provider = "groq",
                apiKey = "same-key",
                egoModel = "g-ego",
                superegoModel = "g-superego"
            ).use { metrics ->
                metrics.chatCallObserver("groq").onChatCall(
                    ChatCallRecord(
                        model = "openai/gpt-oss-20b",
                        metadata = ChatCallMetadata(actor = "ego", callSite = "test"),
                        latencyMs = 10,
                        totalTokens = 30,
                        status = ChatCallStatus.OK
                    )
                )
                val snapshot = metrics.snapshot()
                requireNotNull(snapshot)
                assertEquals("groq", snapshot.provider)
                assertEquals(30, snapshot.runTotals.totalTokens)
                assertEquals(30, snapshot.persistentTotals.totalTokens)
                assertEquals(1, snapshot.runCountForScope)
            }

            SqliteMetricsRuntime(
                provider = "mistral",
                apiKey = "same-key",
                egoModel = "m-ego",
                superegoModel = "m-superego"
            ).use { metrics ->
                metrics.chatCallObserver("mistral").onChatCall(
                    ChatCallRecord(
                        model = "mistral-small-latest",
                        metadata = ChatCallMetadata(actor = "ego", callSite = "test"),
                        latencyMs = 10,
                        totalTokens = 70,
                        status = ChatCallStatus.OK
                    )
                )
                val snapshot = metrics.snapshot()
                requireNotNull(snapshot)
                assertEquals("mistral", snapshot.provider)
                assertEquals(70, snapshot.runTotals.totalTokens)
                assertEquals(70, snapshot.persistentTotals.totalTokens)
                assertEquals(1, snapshot.runCountForScope)
            }

            SqliteMetricsRuntime(
                provider = "groq",
                apiKey = "same-key",
                egoModel = "g-ego-2",
                superegoModel = "g-superego-2"
            ).use { metrics ->
                metrics.chatCallObserver("groq").onChatCall(
                    ChatCallRecord(
                        model = "openai/gpt-oss-20b",
                        metadata = ChatCallMetadata(actor = "ego", callSite = "test-2"),
                        latencyMs = 10,
                        totalTokens = 20,
                        status = ChatCallStatus.OK
                    )
                )
                val snapshot = metrics.snapshot()
                requireNotNull(snapshot)
                assertEquals("groq", snapshot.provider)
                assertEquals(20, snapshot.runTotals.totalTokens)
                assertEquals(50, snapshot.persistentTotals.totalTokens)
                assertEquals(2, snapshot.runCountForScope)
            }
        } finally {
            if (previous == null) {
                System.clearProperty("psyke.metrics.db")
            } else {
                System.setProperty("psyke.metrics.db", previous)
            }
            Files.deleteIfExists(dbPath)
            Files.deleteIfExists(dbPath.resolveSibling("metrics.salt"))
        }
    }
}
