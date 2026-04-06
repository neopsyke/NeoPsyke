package ai.neopsyke.agent.ego.planner.runtime

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import ai.neopsyke.agent.support.TextSecurity
import ai.neopsyke.llm.ChatResponseFormat

private val logger = KotlinLogging.logger {}

/**
 * JSON schema definitions, response parsing, escape repair, and payload normalization.
 * Extracted from LlmEgoPlanner's parsePayloadWithRepair / repairInvalidJsonEscapes.
 */
object StructuredOutputHandler {

    val mapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    @PublishedApi internal val invalidJsonEscapeRegex = Regex("""\\(?!["\\/bfnrtu])""")

    /**
     * Parse raw LLM output into a typed result with JSON escape repair fallback.
     */
    inline fun <reified T> parseWithRepair(
        raw: String,
        onRepair: () -> Unit = {},
    ): T? {
        val json = TextSecurity.extractJsonObject(raw)
        return try {
            mapper.readValue<T>(json)
        } catch (initial: Exception) {
            val repaired = repairInvalidJsonEscapes(json)
            if (repaired == json) {
                logParseFailure(initial, raw)
                null
            } else {
                try {
                    val payload = mapper.readValue<T>(repaired)
                    onRepair()
                    payload
                } catch (_: Exception) {
                    logParseFailure(initial, raw)
                    null
                }
            }
        }
    }

    @PublishedApi
    internal fun logParseFailure(ex: Exception, raw: String) {
        logger.warn(ex) {
            "Failed to parse response. preview='${TextSecurity.preview(raw, 120)}'"
        }
    }

    fun repairInvalidJsonEscapes(json: String): String =
        json.replace(invalidJsonEscapeRegex, "")

    /** Standard planner decision response format used by all lanes. */
    val PLANNER_DECISION_RESPONSE_FORMAT = ChatResponseFormat.JsonSchema(
        name = "ego_planner_decision",
        schemaJson = """
            {
              "type": "object",
              "additionalProperties": false,
              "required": ["decision", "urgency", "defer_content", "long_term_memory_recall_query", "intention_kind", "commit_mode_preference", "action_type", "action_payload", "action_summary", "plan_goal", "plan_steps", "reason"],
              "properties": {
                "decision": { "type": "string", "enum": ["defer", "intend", "plan", "noop"] },
                "urgency": { "type": ["string", "null"], "enum": ["low", "medium", "high", null] },
                "defer_content": { "type": ["string", "null"], "maxLength": 600 },
                "long_term_memory_recall_query": { "type": ["string", "null"], "maxLength": 600 },
                "intention_kind": { "type": ["string", "null"], "enum": ["observe", "prepare", "stage", "request_authorization", "commit", null] },
                "commit_mode_preference": { "type": ["string", "null"], "enum": ["not_applicable", "approval_backed", "policy_autonomous", "admin_override", null] },
                "action_type": { "type": ["string", "null"] },
                "action_payload": { "type": ["string", "null"], "maxLength": 4000 },
                "action_summary": { "type": ["string", "null"], "maxLength": 180 },
                "plan_goal": { "type": ["string", "null"], "maxLength": 600 },
                "plan_steps": { "type": ["array", "null"], "items": { "type": "string", "maxLength": 120 }, "maxItems": 6 },
                "reason": { "type": ["string", "null"], "maxLength": 160 }
              }
            }
        """.trimIndent(),
        strict = true,
        relaxedSchemaJson = """
            {
              "type": "object",
              "additionalProperties": false,
              "required": ["decision", "urgency", "defer_content", "long_term_memory_recall_query", "intention_kind", "commit_mode_preference", "action_type", "action_payload", "action_summary", "plan_goal", "plan_steps", "reason"],
              "properties": {
                "decision": { "type": "string", "enum": ["defer", "intend", "plan", "noop"] },
                "urgency": { "type": ["string", "null"], "enum": ["low", "medium", "high", null] },
                "defer_content": { "type": ["string", "null"] },
                "long_term_memory_recall_query": { "type": ["string", "null"] },
                "intention_kind": { "type": ["string", "null"], "enum": ["observe", "prepare", "stage", "request_authorization", "commit", null] },
                "commit_mode_preference": { "type": ["string", "null"], "enum": ["not_applicable", "approval_backed", "policy_autonomous", "admin_override", null] },
                "action_type": { "type": ["string", "null"] },
                "action_payload": { "type": ["string", "null"] },
                "action_summary": { "type": ["string", "null"] },
                "plan_goal": { "type": ["string", "null"] },
                "plan_steps": { "type": ["array", "null"], "items": { "type": "string" } },
                "reason": { "type": ["string", "null"] }
              }
            }
        """.trimIndent(),
    )

    /**
     * Normalize a JsonNode action_payload into a string.
     * Matches current LlmEgoPlanner.normalizeActionPayload behavior.
     */
    fun normalizeActionPayload(node: JsonNode?): String? {
        if (node == null || node.isNull) return null
        return when {
            node.isTextual -> node.asText()
            node.isObject || node.isArray -> mapper.writeValueAsString(node)
            else -> node.asText()
        }
    }
}
