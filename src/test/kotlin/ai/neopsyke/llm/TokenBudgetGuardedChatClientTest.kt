package ai.neopsyke.llm

import ai.neopsyke.metrics.MetricsSnapshot
import ai.neopsyke.metrics.MetricsTotals
import ai.neopsyke.support.StubChatModelClient
import ai.neopsyke.support.StubMetricsRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TokenBudgetGuardedChatClientTest {
    @Test
    fun `guard allows chat call when projected usage stays within all limits`() {
        val delegate = StubChatModelClient()
        val runtime = StubMetricsRuntime(
            snapshotValue = snapshot(
                runTotalTokens = 200,
                runTokensByProvider = mapOf("groq" to 120),
                runTokensByRole = mapOf(LlmRoleLabels.PLANNER to 90)
            )
        )
        val gate = LlmTokenBudgetGate(
            metricsRuntime = runtime,
            config = LlmTokenBudgetConfig(
                maxRunTotalTokens = 2_000,
                maxRunTokensPerProvider = 1_200,
                maxRunTokensPerRole = 900
            )
        )
        val client = TokenBudgetGuardedChatClient(
            delegate = delegate,
            budgetGate = gate,
            provider = "groq",
            role = LlmRoleLabels.PLANNER
        )

        client.chat(
            messages = listOf(ChatMessage(role = ChatRole.USER, content = "hello world")),
            options = ChatRequestOptions(maxTokens = 120)
        )

        assertEquals(1, delegate.calls.size)
    }

    @Test
    fun `guard blocks call when provider budget would be exceeded`() {
        val delegate = StubChatModelClient()
        val runtime = StubMetricsRuntime(
            snapshotValue = snapshot(
                runTotalTokens = 420,
                runTokensByProvider = mapOf("mistral" to 450),
                runTokensByRole = mapOf(LlmRoleLabels.SUPEREGO to 120)
            )
        )
        val gate = LlmTokenBudgetGate(
            metricsRuntime = runtime,
            config = LlmTokenBudgetConfig(
                maxRunTotalTokens = 2_000,
                maxRunTokensPerProvider = 500,
                maxRunTokensPerRole = 1_000
            )
        )
        val client = TokenBudgetGuardedChatClient(
            delegate = delegate,
            budgetGate = gate,
            provider = "mistral",
            role = LlmRoleLabels.SUPEREGO
        )

        assertFailsWith<LlmTokenBudgetExceededException> {
            client.chat(
                messages = listOf(ChatMessage(role = ChatRole.USER, content = "review this action carefully")),
                options = ChatRequestOptions(maxTokens = 90)
            )
        }
        assertEquals(0, delegate.calls.size)
    }

    @Test
    fun `guard blocks call when role budget would be exceeded`() {
        val delegate = StubChatModelClient()
        val runtime = StubMetricsRuntime(
            snapshotValue = snapshot(
                runTotalTokens = 220,
                runTokensByProvider = mapOf("groq" to 90),
                runTokensByRole = mapOf(LlmRoleLabels.META_REASONER to 260)
            )
        )
        val gate = LlmTokenBudgetGate(
            metricsRuntime = runtime,
            config = LlmTokenBudgetConfig(
                maxRunTotalTokens = 2_000,
                maxRunTokensPerProvider = 1_000,
                maxRunTokensPerRole = 300
            )
        )
        val client = TokenBudgetGuardedChatClient(
            delegate = delegate,
            budgetGate = gate,
            provider = "groq",
            role = LlmRoleLabels.META_REASONER
        )

        assertFailsWith<LlmTokenBudgetExceededException> {
            client.chat(
                messages = listOf(ChatMessage(role = ChatRole.USER, content = "assess deliberation pressure")),
                options = ChatRequestOptions(maxTokens = 70)
            )
        }
        assertEquals(0, delegate.calls.size)
    }

    private fun snapshot(
        runTotalTokens: Long,
        runTokensByProvider: Map<String, Long>,
        runTokensByRole: Map<String, Long>,
    ): MetricsSnapshot =
        MetricsSnapshot(
            runId = "run-1",
            provider = "multi",
            keyFingerprint = "fp",
            updatedAtIso = "2026-03-07T00:00:00Z",
            runTotals = MetricsTotals(
                calls = 1,
                promptTokens = 0,
                completionTokens = 0,
                totalTokens = runTotalTokens,
                deniedActions = 0,
                errorCount = 0
            ),
            persistentTotals = MetricsTotals(
                calls = 1,
                promptTokens = 0,
                completionTokens = 0,
                totalTokens = runTotalTokens,
                deniedActions = 0,
                errorCount = 0
            ),
            runCountForScope = 1,
            runTokensByProvider = runTokensByProvider,
            persistentTokensByProvider = runTokensByProvider,
            runTokensByRole = runTokensByRole,
            persistentTokensByRole = runTokensByRole
        )
}
