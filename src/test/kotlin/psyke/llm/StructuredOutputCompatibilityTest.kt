package psyke.llm

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StructuredOutputCompatibilityTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `adapter strips unsupported combinators for openai compatible providers`() {
        val responseFormat = ChatResponseFormat.JsonSchema(
            name = "superego_review",
            strict = true,
            schemaJson = """
                {
                  "type": "object",
                  "required": ["allow"],
                  "properties": {
                    "allow": { "type": "boolean" }
                  },
                  "allOf": [
                    {
                      "if": { "properties": { "allow": { "const": true } } },
                      "then": { "required": ["allow"] }
                    }
                  ]
                }
            """.trimIndent()
        )

        val adaptation = StructuredOutputCompatibility.adapt(
            provider = LlmProvider.OPENAI,
            modelName = "gpt-5-mini",
            responseFormat = responseFormat,
            mapper = mapper
        )
        val adaptedFormat = adaptation.responseFormat as? ChatResponseFormat.JsonSchema
        assertNotNull(adaptedFormat)
        assertTrue(adaptation.droppedKeywords.contains("allOf"))
        assertTrue(adaptedFormat.strict)

        val adaptedSchema = mapper.readTree(adaptedFormat.schemaJson)
        assertFalse(adaptedSchema.has("allOf"))
    }

    @Test
    fun `adapter keeps strict schema unchanged when no disallowed keywords are present`() {
        val responseFormat = ChatResponseFormat.JsonSchema(
            name = "meta_reasoner_assessment",
            strict = true,
            schemaJson = """
                {
                  "type": "object",
                  "required": ["verdict"],
                  "properties": {
                    "verdict": { "type": "string" }
                  }
                }
            """.trimIndent()
        )

        val adaptation = StructuredOutputCompatibility.adapt(
            provider = LlmProvider.MISTRAL,
            modelName = "mistral-small-latest",
            responseFormat = responseFormat,
            mapper = mapper
        )
        val adaptedFormat = adaptation.responseFormat as? ChatResponseFormat.JsonSchema
        assertNotNull(adaptedFormat)
        assertTrue(adaptation.droppedKeywords.isEmpty())
        assertFalse(adaptation.strictDowngraded)
        assertEquals(
            mapper.readTree(responseFormat.schemaJson),
            mapper.readTree(adaptedFormat.schemaJson)
        )
    }

    @Test
    fun `warning message is emitted once per adaptation signature`() {
        val responseFormat = ChatResponseFormat.JsonSchema(
            name = "superego_review",
            strict = true,
            schemaJson = """
                {
                  "type": "object",
                  "allOf": [{ "if": { "type": "object" }, "then": { "type": "object" } }]
                }
            """.trimIndent()
        )
        val adaptation = StructuredOutputCompatibility.adapt(
            provider = LlmProvider.OPENAI,
            modelName = "gpt-4o-mini",
            responseFormat = responseFormat,
            mapper = mapper
        )
        val metadata = ChatCallMetadata(actor = "superego", callSite = "action_review")

        val first = StructuredOutputCompatibility.warningMessageIfNeeded(
            provider = LlmProvider.OPENAI,
            modelName = "gpt-4o-mini",
            requestedFormat = responseFormat,
            adaptation = adaptation,
            metadata = metadata
        )
        val second = StructuredOutputCompatibility.warningMessageIfNeeded(
            provider = LlmProvider.OPENAI,
            modelName = "gpt-4o-mini",
            requestedFormat = responseFormat,
            adaptation = adaptation,
            metadata = metadata
        )

        assertNotNull(first)
        assertTrue(first.contains("dropped_keywords"))
        assertNull(second)
    }

    @Test
    fun `adapter normalizes strict object required fields and nullable optional properties`() {
        val responseFormat = ChatResponseFormat.JsonSchema(
            name = "superego_review",
            strict = true,
            schemaJson = """
                {
                  "type": "object",
                  "additionalProperties": false,
                  "required": ["allow", "confidence", "policy_risk"],
                  "properties": {
                    "allow": { "type": "boolean" },
                    "reason": { "type": "string", "maxLength": 180 },
                    "reason_code": { "type": "string" },
                    "confidence": { "type": "number", "minimum": 0.0, "maximum": 1.0 },
                    "policy_risk": { "type": "string", "enum": ["low", "medium", "high"] }
                  }
                }
            """.trimIndent()
        )

        val adaptation = StructuredOutputCompatibility.adapt(
            provider = LlmProvider.OPENAI,
            modelName = "gpt-4o-mini",
            responseFormat = responseFormat,
            mapper = mapper
        )

        val adaptedFormat = adaptation.responseFormat as? ChatResponseFormat.JsonSchema
        assertNotNull(adaptedFormat)
        val schema = mapper.readTree(adaptedFormat.schemaJson)
        val required = schema.get("required")
        assertNotNull(required)
        val requiredSet = required.map { it.asText() }.toSet()
        assertTrue(requiredSet.contains("allow"))
        assertTrue(requiredSet.contains("confidence"))
        assertTrue(requiredSet.contains("policy_risk"))
        assertTrue(requiredSet.contains("reason"))
        assertTrue(requiredSet.contains("reason_code"))

        val reasonType = schema.get("properties").get("reason").get("type")
        assertTrue(reasonType.isArray)
        assertTrue(reasonType.any { it.asText() == "string" })
        assertTrue(reasonType.any { it.asText() == "null" })
    }
}
