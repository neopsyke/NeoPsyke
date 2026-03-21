package ai.neopsyke.eval

import ai.neopsyke.llm.ChatCallMetadata
import ai.neopsyke.llm.ChatCallObserver
import ai.neopsyke.llm.ChatCallRecord
import ai.neopsyke.llm.ChatCallStatus
import ai.neopsyke.llm.ChatCompletion
import ai.neopsyke.llm.ChatMessage
import ai.neopsyke.llm.ChatModelClient
import ai.neopsyke.llm.ChatRequestOptions
import ai.neopsyke.llm.ChatRole
import ai.neopsyke.llm.ChatUsage
import kotlin.math.max

class ReasoningLogicHarnessClient(
    override val modelName: String = "reasoning-logic-harness-v1",
    private val callObserver: ChatCallObserver? = null,
) : ChatModelClient {
    private val behavioralTaskRegex = Regex("^(ledger|assignment|state_machine)_(paraphrase|noise|reorder|repair)_[0-9]{2}$")

    override fun chat(messages: List<ChatMessage>, options: ChatRequestOptions): ChatCompletion {
        val startedAt = System.nanoTime()
        val metadata = options.metadata
        return try {
            val callSite = metadata.callSite
            val taskId = callSite.substringBefore(":attempt=").ifBlank { "unknown" }
            val attempt = callSite.substringAfter(":attempt=", "1").toIntOrNull() ?: 1
            val latestUser = messages.lastOrNull { it.role == ChatRole.USER }?.content.orEmpty()
            val response = responseFor(taskId = taskId, attempt = attempt, latestUser = latestUser)
            val promptTokenEstimate = estimateTokens(messages.sumOf { it.content.length })
            val completionTokenEstimate = estimateTokens(response.length)
            observeCall(
                metadata = metadata,
                startedAt = startedAt,
                status = ChatCallStatus.OK,
                promptTokens = promptTokenEstimate,
                completionTokens = completionTokenEstimate
            )
            ChatCompletion(
                content = response,
                model = modelName,
                usage = ChatUsage(
                    promptTokens = promptTokenEstimate,
                    completionTokens = completionTokenEstimate,
                    totalTokens = promptTokenEstimate + completionTokenEstimate
                )
            )
        } catch (ex: Exception) {
            observeCall(
                metadata = metadata,
                startedAt = startedAt,
                status = ChatCallStatus.ERROR,
                errorCode = ex::class.simpleName ?: "logic_harness_error",
                errorMessage = ex.message
            )
            throw ex
        }
    }

    private fun responseFor(taskId: String, attempt: Int, latestUser: String): String {
        return when (taskId) {
            "shape-lock" -> shapeLockResponse(attempt, latestUser)
            "feedback-carry" -> feedbackCarryResponse(attempt, latestUser)
            "multi-fix" -> multiFixResponse(attempt, latestUser)
            else -> {
                val match = behavioralTaskRegex.matchEntire(taskId)
                if (match != null) {
                    behavioralResponse(match.groupValues[1], attempt, latestUser)
                } else {
                    """{"status":"unsupported_task","task_id":"$taskId"}"""
                }
            }
        }
    }

    private fun shapeLockResponse(attempt: Int, latestUser: String): String {
        if (attempt <= 1) {
            return """{"ok":false}"""
        }
        val hasAllFeedback = latestUser.contains("ok must be true.") &&
            latestUser.contains("tag must be shape-lock.")
        return if (hasAllFeedback) {
            """{"ok":true,"tag":"shape-lock"}"""
        } else {
            """{"ok":false}"""
        }
    }

    private fun feedbackCarryResponse(attempt: Int, latestUser: String): String {
        if (attempt <= 1) {
            return "not-json"
        }
        if (attempt == 2) {
            val hasParseFeedback = latestUser.contains("Output is not parseable JSON")
            return if (hasParseFeedback) {
                """{"mode":"carry","attempt":1}"""
            } else {
                "not-json"
            }
        }
        val hasAttemptFeedback = latestUser.contains("attempt must be 2.")
        return if (hasAttemptFeedback) {
            """{"mode":"carry","attempt":2}"""
        } else {
            """{"mode":"carry","attempt":1}"""
        }
    }

    private fun multiFixResponse(attempt: Int, latestUser: String): String {
        if (attempt <= 1) {
            return """{"left":0,"right":0,"total":0}"""
        }
        val hasAllFeedback = latestUser.contains("left must be 7.") &&
            latestUser.contains("right must be 5.") &&
            latestUser.contains("total must be 12.")
        return if (hasAllFeedback) {
            """{"left":7,"right":5,"total":12}"""
        } else {
            """{"left":0,"right":0,"total":0}"""
        }
    }

    private fun behavioralResponse(family: String, attempt: Int, latestUser: String): String {
        if (attempt <= 1) {
            return when (family) {
                "ledger" -> """{"balances":{"A":0,"B":0,"C":0},"checksum":0}"""
                "assignment" -> """{"assignment":{"J1":"Ben","J2":"Ada","J3":"Cy","J4":"Cy"}}"""
                "state_machine" -> """{"x":0,"y":0,"checksum":"0-0-0"}"""
                else -> """{"status":"unsupported_family","family":"$family"}"""
            }
        }
        return when (family) {
            "ledger" -> {
                val hasAllFeedback = latestUser.contains("balances.A must be 5.") &&
                    latestUser.contains("balances.B must be 4.") &&
                    latestUser.contains("balances.C must be 7.") &&
                    latestUser.contains("checksum must be 16.")
                if (hasAllFeedback) {
                    """{"balances":{"A":5,"B":4,"C":7},"checksum":16}"""
                } else {
                    """{"balances":{"A":0,"B":0,"C":0},"checksum":0}"""
                }
            }

            "assignment" -> {
                val hasAllFeedback = latestUser.contains("assignment.J1 must be Ada.") &&
                    latestUser.contains("assignment.J2 must be Cy.") &&
                    latestUser.contains("assignment.J3 must be Ben.") &&
                    latestUser.contains("assignment.J4 must be Ada.")
                if (hasAllFeedback) {
                    """{"assignment":{"J1":"Ada","J2":"Cy","J3":"Ben","J4":"Ada"}}"""
                } else {
                    """{"assignment":{"J1":"Ben","J2":"Ada","J3":"Cy","J4":"Cy"}}"""
                }
            }

            "state_machine" -> {
                val hasAllFeedback = latestUser.contains("x must be 22.") &&
                    latestUser.contains("y must be 8.") &&
                    latestUser.contains("checksum must be 22-8-30.")
                if (hasAllFeedback) {
                    """{"x":22,"y":8,"checksum":"22-8-30"}"""
                } else {
                    """{"x":0,"y":0,"checksum":"0-0-0"}"""
                }
            }

            else -> """{"status":"unsupported_family","family":"$family"}"""
        }
    }

    private fun estimateTokens(chars: Int): Int =
        max(1, chars / 4)

    private fun observeCall(
        metadata: ChatCallMetadata,
        startedAt: Long,
        status: ChatCallStatus,
        promptTokens: Int? = null,
        completionTokens: Int? = null,
        errorCode: String? = null,
        errorMessage: String? = null,
    ) {
        try {
            val totalTokens = if (promptTokens != null && completionTokens != null) {
                promptTokens + completionTokens
            } else {
                null
            }
            callObserver?.onChatCall(
                ChatCallRecord(
                    model = modelName,
                    metadata = metadata,
                    latencyMs = elapsedMillis(startedAt),
                    promptTokens = promptTokens,
                    completionTokens = completionTokens,
                    totalTokens = totalTokens,
                    status = status,
                    errorCode = errorCode,
                    errorMessage = errorMessage
                )
            )
        } catch (_: Exception) {
            // Keep eval harness resilient.
        }
    }
}

private fun elapsedMillis(startedAtNanos: Long): Long =
    max(1L, (System.nanoTime() - startedAtNanos) / 1_000_000L)
