package ai.neopsyke.llm

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.util.concurrent.ConcurrentHashMap

internal enum class LlmProvider {
    OPENAI,
    GROQ,
    GEMINI,
    MISTRAL,
}

internal data class StructuredOutputAdaptation(
    val responseFormat: ChatResponseFormat?,
    val droppedKeywords: Set<String> = emptySet(),
    val strictDowngraded: Boolean = false,
    val jsonSchemaDisabled: Boolean = false,
)

internal object StructuredOutputCompatibility {
    private val emittedWarnings: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun adapt(
        provider: LlmProvider,
        modelName: String,
        responseFormat: ChatResponseFormat?,
        mapper: ObjectMapper,
    ): StructuredOutputAdaptation {
        if (responseFormat == null) {
            return StructuredOutputAdaptation(responseFormat = null)
        }
        if (responseFormat !is ChatResponseFormat.JsonSchema) {
            return StructuredOutputAdaptation(responseFormat = responseFormat)
        }

        val capabilities = StructuredOutputModelCapabilities.forProvider(provider, modelName)
        if (!capabilities.supportsJsonSchema) {
            return StructuredOutputAdaptation(
                responseFormat = null,
                jsonSchemaDisabled = true
            )
        }

        val strictAllowed = capabilities.supportsStrictJsonSchema
        val strictDowngraded = responseFormat.strict && !strictAllowed
        val strict = if (strictAllowed) responseFormat.strict else false
        val schemaNode = mapper.readTree(responseFormat.schemaJson)
        if (strict && capabilities.requiresAllObjectPropertiesRequired) {
            normalizeStrictObjectRequirements(schemaNode)
        }
        val droppedKeywords = linkedSetOf<String>()
        if (capabilities.disallowedSchemaKeywords.isNotEmpty()) {
            pruneUnsupportedKeywords(
                node = schemaNode,
                disallowedKeywords = capabilities.disallowedSchemaKeywords,
                droppedKeywords = droppedKeywords
            )
        }

        return StructuredOutputAdaptation(
            responseFormat = responseFormat.copy(
                schemaJson = mapper.writeValueAsString(schemaNode),
                strict = strict
            ),
            droppedKeywords = droppedKeywords,
            strictDowngraded = strictDowngraded
        )
    }

    fun warningMessageIfNeeded(
        provider: LlmProvider,
        modelName: String,
        requestedFormat: ChatResponseFormat?,
        adaptation: StructuredOutputAdaptation,
        metadata: ChatCallMetadata,
    ): String? {
        if (
            !adaptation.jsonSchemaDisabled &&
            !adaptation.strictDowngraded &&
            adaptation.droppedKeywords.isEmpty()
        ) {
            return null
        }
        val schemaName = (requestedFormat as? ChatResponseFormat.JsonSchema)?.name ?: "unknown"
        val key = listOf(
            provider.name,
            modelName,
            schemaName,
            adaptation.jsonSchemaDisabled.toString(),
            adaptation.strictDowngraded.toString(),
            adaptation.droppedKeywords.sorted().joinToString(",")
        ).joinToString("|")
        if (!emittedWarnings.add(key)) {
            return null
        }

        val callSite = metadata.callSite.ifBlank { "unknown" }
        val actor = metadata.actor.ifBlank { "unknown" }
        val details = buildString {
            append("Structured-output schema adapted for provider=")
            append(provider.name.lowercase())
            append(" model=")
            append(modelName)
            append(" actor=")
            append(actor)
            append(" call_site=")
            append(callSite)
            append(" schema=")
            append(schemaName)
            append(".")
            if (adaptation.jsonSchemaDisabled) {
                append(" JSON schema output disabled by model capability.")
            }
            if (adaptation.strictDowngraded) {
                append(" strict downgraded to false.")
            }
            if (adaptation.droppedKeywords.isNotEmpty()) {
                append(" dropped_keywords=")
                append(adaptation.droppedKeywords.sorted().joinToString(","))
                append(".")
            }
        }
        return details
    }

    private fun pruneUnsupportedKeywords(
        node: com.fasterxml.jackson.databind.JsonNode,
        disallowedKeywords: Set<String>,
        droppedKeywords: MutableSet<String>,
    ) {
        when (node) {
            is ObjectNode -> {
                val fieldNames = node.fieldNames().asSequence().toList()
                for (field in fieldNames) {
                    if (disallowedKeywords.contains(field)) {
                        node.remove(field)
                        droppedKeywords += field
                        continue
                    }
                    pruneUnsupportedKeywords(node.get(field), disallowedKeywords, droppedKeywords)
                }
            }

            is ArrayNode -> {
                for (item in node) {
                    pruneUnsupportedKeywords(item, disallowedKeywords, droppedKeywords)
                }
            }
        }
    }

    private fun normalizeStrictObjectRequirements(node: com.fasterxml.jackson.databind.JsonNode) {
        when (node) {
            is ObjectNode -> {
                normalizeObjectNode(node)
                val iterator = node.fields()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    normalizeStrictObjectRequirements(entry.value)
                }
            }

            is ArrayNode -> {
                for (item in node) {
                    normalizeStrictObjectRequirements(item)
                }
            }
        }
    }

    private fun normalizeObjectNode(node: ObjectNode) {
        val typeNode = node.get("type")
        if (typeNode?.isTextual != true || typeNode.asText() != "object") return
        val propertiesNode = node.get("properties") as? ObjectNode ?: return

        val requiredArray = (node.get("required") as? ArrayNode)?.deepCopy()
            ?: JsonNodeFactory.instance.arrayNode()
        val requiredSet = requiredArray.mapNotNull { value ->
            if (value.isTextual) value.asText() else null
        }.toMutableSet()

        val iterator = propertiesNode.fields()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val propertyName = entry.key
            val propertySchema = entry.value
            if (!requiredSet.contains(propertyName)) {
                requiredArray.add(propertyName)
                requiredSet += propertyName
                if (propertySchema is ObjectNode) {
                    makePropertyNullable(propertySchema)
                }
            }
        }
        node.set<ArrayNode>("required", requiredArray)
    }

    private fun makePropertyNullable(propertySchema: ObjectNode) {
        when (val typeNode = propertySchema.get("type")) {
            null -> return
            is ArrayNode -> {
                val hasNull = typeNode.any { it.isTextual && it.asText() == "null" }
                if (!hasNull) {
                    typeNode.add("null")
                }
            }

            else -> {
                if (!typeNode.isTextual) return
                if (typeNode.asText() == "null") return
                val types = JsonNodeFactory.instance.arrayNode()
                    .add(typeNode.asText())
                    .add("null")
                propertySchema.set<ArrayNode>("type", types)
            }
        }

        val enumNode = propertySchema.get("enum") as? ArrayNode
        if (enumNode != null && enumNode.none { it.isNull }) {
            enumNode.addNull()
        }
    }
}

private data class StructuredOutputModelCapabilities(
    val supportsJsonSchema: Boolean,
    val supportsStrictJsonSchema: Boolean,
    val disallowedSchemaKeywords: Set<String>,
    val requiresAllObjectPropertiesRequired: Boolean,
) {
    companion object {
        fun forProvider(provider: LlmProvider, @Suppress("UNUSED_PARAMETER") modelName: String): StructuredOutputModelCapabilities {
            return when (provider) {
                LlmProvider.OPENAI -> openAiCompatibleDefaults()
                LlmProvider.GROQ -> openAiCompatibleDefaults()
                LlmProvider.GEMINI -> openAiCompatibleDefaults()
                LlmProvider.MISTRAL -> openAiCompatibleDefaults()
            }
        }

        private fun openAiCompatibleDefaults(): StructuredOutputModelCapabilities =
            StructuredOutputModelCapabilities(
                supportsJsonSchema = true,
                supportsStrictJsonSchema = true,
                disallowedSchemaKeywords = OPENAI_COMPAT_DISALLOWED_SCHEMA_KEYWORDS,
                requiresAllObjectPropertiesRequired = true
            )

        private val OPENAI_COMPAT_DISALLOWED_SCHEMA_KEYWORDS: Set<String> = setOf(
            "allOf",
            "anyOf",
            "oneOf",
            "not",
            "if",
            "then",
            "else",
            "dependentRequired",
            "dependentSchemas",
            "patternProperties",
            "unevaluatedProperties",
            "contains",
            "minContains",
            "maxContains",
            "prefixItems",
            "propertyNames",
            "unevaluatedItems",
            "\$defs"
        )
    }
}
