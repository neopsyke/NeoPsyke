package psyke.metrics

import psyke.llm.ChatCallMetadata
import psyke.llm.ChatCallRecord
import psyke.llm.ChatCallStatus
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test
    fun `response latency median is computed for odd and even sample counts`() {
        val dbPath = Files.createTempFile("psyke-metrics-latency-test", ".db")
        val previous = System.getProperty("psyke.metrics.db")
        System.setProperty("psyke.metrics.db", dbPath.toString())
        try {
            SqliteMetricsRuntime(
                provider = "groq",
                apiKey = "same-key",
                egoModel = "g-ego",
                superegoModel = "g-superego"
            ).use { metrics ->
                metrics.recordEndToEndResponseLatency(100)
                metrics.recordEndToEndResponseLatency(300)
                var snapshot = metrics.snapshot()
                requireNotNull(snapshot)
                assertEquals(2, snapshot.runTotals.responseLatencyCount)
                assertEquals(400, snapshot.runTotals.responseLatencySumMs)
                assertEquals(200.0, snapshot.runTotals.medianEndToEndResponseLatencyMs)

                metrics.recordEndToEndResponseLatency(200)
                snapshot = metrics.snapshot()
                requireNotNull(snapshot)
                assertEquals(3, snapshot.runTotals.responseLatencyCount)
                assertEquals(600, snapshot.runTotals.responseLatencySumMs)
                assertEquals(200.0, snapshot.runTotals.medianEndToEndResponseLatencyMs)
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

    @Test
    fun `action call counters are tracked per run and aggregated for provider scope`() {
        val dbPath = Files.createTempFile("psyke-metrics-action-counts-test", ".db")
        val previous = System.getProperty("psyke.metrics.db")
        System.setProperty("psyke.metrics.db", dbPath.toString())
        try {
            SqliteMetricsRuntime(
                provider = "groq",
                apiKey = "same-key",
                egoModel = "g-ego",
                superegoModel = "g-superego"
            ).use { metrics ->
                metrics.recordActionCall("answer")
                metrics.recordActionCall("web_search")
                metrics.recordActionCall("web_search")
                val snapshot = metrics.snapshot()
                requireNotNull(snapshot)
                assertEquals(1L, snapshot.runActionCallsByType["answer"])
                assertEquals(2L, snapshot.runActionCallsByType["web_search"])
                assertEquals(1L, snapshot.persistentActionCallsByType["answer"])
                assertEquals(2L, snapshot.persistentActionCallsByType["web_search"])
            }

            SqliteMetricsRuntime(
                provider = "mistral",
                apiKey = "same-key",
                egoModel = "m-ego",
                superegoModel = "m-superego"
            ).use { metrics ->
                metrics.recordActionCall("answer")
                val snapshot = metrics.snapshot()
                requireNotNull(snapshot)
                assertEquals(1L, snapshot.runActionCallsByType["answer"])
                assertEquals(1L, snapshot.persistentActionCallsByType["answer"])
            }

            SqliteMetricsRuntime(
                provider = "groq",
                apiKey = "same-key",
                egoModel = "g-ego-2",
                superegoModel = "g-superego-2"
            ).use { metrics ->
                metrics.recordActionCall("answer")
                val snapshot = metrics.snapshot()
                requireNotNull(snapshot)
                assertEquals(1L, snapshot.runActionCallsByType["answer"])
                assertEquals(2L, snapshot.persistentActionCallsByType["answer"])
                assertEquals(2L, snapshot.persistentActionCallsByType["web_search"])
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

    @Test
    fun `snapshot includes token breakdowns by provider and role`() {
        val dbPath = Files.createTempFile("psyke-metrics-role-provider-breakdown-test", ".db")
        val previous = System.getProperty("psyke.metrics.db")
        System.setProperty("psyke.metrics.db", dbPath.toString())
        try {
            SqliteMetricsRuntime(
                provider = "multi",
                apiKey = "same-key",
                egoModel = "planner-model",
                superegoModel = "superego-model"
            ).use { metrics ->
                metrics.chatCallObserver("groq").onChatCall(
                    ChatCallRecord(
                        model = "planner-model",
                        metadata = ChatCallMetadata(actor = "ego", callSite = "input"),
                        latencyMs = 10,
                        totalTokens = 40,
                        status = ChatCallStatus.OK
                    )
                )
                metrics.chatCallObserver("mistral").onChatCall(
                    ChatCallRecord(
                        model = "superego-model",
                        metadata = ChatCallMetadata(actor = "superego", callSite = "action_review"),
                        latencyMs = 11,
                        totalTokens = 20,
                        status = ChatCallStatus.OK
                    )
                )
                metrics.chatCallObserver("mistral").onChatCall(
                    ChatCallRecord(
                        model = "meta-model",
                        metadata = ChatCallMetadata(actor = "ego", callSite = "meta_reasoner"),
                        latencyMs = 12,
                        totalTokens = 10,
                        status = ChatCallStatus.OK
                    )
                )

                val snapshot = metrics.snapshot()
                requireNotNull(snapshot)
                assertEquals(40L, snapshot.runTokensByProvider["groq"])
                assertEquals(30L, snapshot.runTokensByProvider["mistral"])
                assertEquals(40L, snapshot.runTokensByRole["planner"])
                assertEquals(20L, snapshot.runTokensByRole["superego"])
                assertEquals(10L, snapshot.runTokensByRole["meta_reasoner"])
                assertEquals(40L, snapshot.persistentTokensByProvider["groq"])
                assertEquals(30L, snapshot.persistentTokensByProvider["mistral"])
                assertEquals(40L, snapshot.persistentTokensByRole["planner"])
                assertEquals(20L, snapshot.persistentTokensByRole["superego"])
                assertEquals(10L, snapshot.persistentTokensByRole["meta_reasoner"])
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

    @Test
    fun `llmCallStats returns model stats with latency percentiles`() {
        val dbPath = Files.createTempFile("psyke-metrics-llm-stats-test", ".db")
        val previous = System.getProperty("psyke.metrics.db")
        System.setProperty("psyke.metrics.db", dbPath.toString())
        try {
            SqliteMetricsRuntime(
                provider = "groq",
                apiKey = "same-key",
                egoModel = "ego-model",
                superegoModel = "superego-model"
            ).use { metrics ->
                val observer = metrics.chatCallObserver("groq")
                observer.onChatCall(
                    ChatCallRecord(
                        model = "ego-model",
                        metadata = ChatCallMetadata(actor = "ego", callSite = "input"),
                        latencyMs = 100,
                        promptTokens = 20,
                        completionTokens = 10,
                        totalTokens = 30,
                        status = ChatCallStatus.OK
                    )
                )
                observer.onChatCall(
                    ChatCallRecord(
                        model = "ego-model",
                        metadata = ChatCallMetadata(actor = "ego", callSite = "input"),
                        latencyMs = 200,
                        promptTokens = 25,
                        completionTokens = 15,
                        totalTokens = 40,
                        status = ChatCallStatus.OK
                    )
                )
                observer.onChatCall(
                    ChatCallRecord(
                        model = "superego-model",
                        metadata = ChatCallMetadata(actor = "superego", callSite = "action_review"),
                        latencyMs = 50,
                        promptTokens = 10,
                        completionTokens = 5,
                        totalTokens = 15,
                        status = ChatCallStatus.OK
                    )
                )
                observer.onChatCall(
                    ChatCallRecord(
                        model = "ego-model",
                        metadata = ChatCallMetadata(actor = "ego", callSite = "input"),
                        latencyMs = 300,
                        totalTokens = 20,
                        status = ChatCallStatus.ERROR,
                        errorCode = "rate_limit"
                    )
                )

                val report = metrics.llmCallStats(runOnly = true)
                assertEquals(2, report.byModel.size)

                val egoStats = report.byModel["ego-model"]!!
                assertEquals(3, egoStats.callCount)
                assertEquals(1, egoStats.errorCount)
                assertEquals(90, egoStats.totalTokens)
                assertTrue(egoStats.p50LatencyMs in 100..300)
                assertTrue(egoStats.p95LatencyMs >= egoStats.p50LatencyMs)

                val superegoStats = report.byModel["superego-model"]!!
                assertEquals(1, superegoStats.callCount)
                assertEquals(0, superegoStats.errorCount)
                assertEquals(15, superegoStats.totalTokens)
                assertEquals(50, superegoStats.p50LatencyMs)

                assertTrue(report.byRole.containsKey("planner"))
                assertTrue(report.byRole.containsKey("superego"))

                assertEquals(1, report.errorBreakdown.size)
                assertEquals(1L, report.errorBreakdown["ego-model"]!!["rate_limit"])
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

    @Test
    fun `llmCallStats all-time scope aggregates across runs`() {
        val dbPath = Files.createTempFile("psyke-metrics-llm-stats-alltime-test", ".db")
        val previous = System.getProperty("psyke.metrics.db")
        System.setProperty("psyke.metrics.db", dbPath.toString())
        try {
            SqliteMetricsRuntime(
                provider = "groq",
                apiKey = "same-key",
                egoModel = "ego-model",
                superegoModel = "superego-model"
            ).use { metrics ->
                metrics.chatCallObserver("groq").onChatCall(
                    ChatCallRecord(
                        model = "ego-model",
                        metadata = ChatCallMetadata(actor = "ego", callSite = "input"),
                        latencyMs = 100,
                        totalTokens = 30,
                        status = ChatCallStatus.OK
                    )
                )
            }

            SqliteMetricsRuntime(
                provider = "groq",
                apiKey = "same-key",
                egoModel = "ego-model",
                superegoModel = "superego-model"
            ).use { metrics ->
                metrics.chatCallObserver("groq").onChatCall(
                    ChatCallRecord(
                        model = "ego-model",
                        metadata = ChatCallMetadata(actor = "ego", callSite = "input"),
                        latencyMs = 200,
                        totalTokens = 40,
                        status = ChatCallStatus.OK
                    )
                )

                val runReport = metrics.llmCallStats(runOnly = true)
                assertEquals(1, runReport.byModel["ego-model"]!!.callCount)

                val allReport = metrics.llmCallStats(runOnly = false)
                assertEquals(2, allReport.byModel["ego-model"]!!.callCount)
                assertEquals(70, allReport.byModel["ego-model"]!!.totalTokens)
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
