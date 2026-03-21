package ai.neopsyke.llm

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.test.Test
import kotlin.test.assertTrue

class StructuredOutputContractTest {
    private val mapper = jacksonObjectMapper()
    private val agentSourceRoot: Path = Paths.get("src/main/kotlin/ai/neopsyke/agent")

    @Test
    fun `strict structured-output schemas are openai-compatible without runtime adaptation`() {
        val schemaConstantRegex = Regex(
            """const\s+val\s+([A-Z0-9_]+_RESPONSE_SCHEMA)\s*:\s*String\s*=\s*\"\"\"(.*?)\"\"\"""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        val formatRegex = Regex(
            """ChatResponseFormat\.JsonSchema\(\s*name\s*=\s*"([^"]+)"\s*,\s*schemaJson\s*=\s*([A-Z0-9_]+)\s*,\s*strict\s*=\s*(true|false)""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        val failures = mutableListOf<String>()

        Files.walk(agentSourceRoot).use { paths ->
            paths
                .filter { path -> Files.isRegularFile(path) && path.toString().endsWith(".kt") }
                .forEach { path ->
                    val source = Files.readString(path)
                    val schemaByConstant = schemaConstantRegex
                        .findAll(source)
                        .associate { match ->
                            match.groupValues[1] to match.groupValues[2].trimIndent().trim()
                        }
                    if (schemaByConstant.isEmpty()) return@forEach

                    for (match in formatRegex.findAll(source)) {
                        val schemaName = match.groupValues[1]
                        val constantName = match.groupValues[2]
                        val strict = match.groupValues[3].toBooleanStrictOrNull() ?: false
                        if (!strict) continue
                        val schemaJson = schemaByConstant[constantName]
                        if (schemaJson == null) {
                            failures += "${path.invariantSeparatorsPathString}: unresolved schema constant $constantName for $schemaName."
                            continue
                        }

                        val format = ChatResponseFormat.JsonSchema(
                            name = schemaName,
                            schemaJson = schemaJson,
                            strict = true
                        )
                        val adaptation = StructuredOutputCompatibility.adapt(
                            provider = LlmProvider.OPENAI,
                            modelName = "gpt-4o-mini",
                            responseFormat = format,
                            mapper = mapper
                        )
                        val adapted = adaptation.responseFormat as? ChatResponseFormat.JsonSchema
                        if (adapted == null) {
                            failures += "${path.invariantSeparatorsPathString}: schema $schemaName unexpectedly disabled."
                            continue
                        }
                        if (adaptation.droppedKeywords.isNotEmpty()) {
                            failures += "${path.invariantSeparatorsPathString}: schema $schemaName uses disallowed keywords " +
                                adaptation.droppedKeywords.sorted().joinToString(", ")
                        }
                        val originalNode = mapper.readTree(schemaJson)
                        val adaptedNode = mapper.readTree(adapted.schemaJson)
                        if (originalNode != adaptedNode) {
                            failures += "${path.invariantSeparatorsPathString}: schema $schemaName requires runtime normalization; " +
                                "make source schema strict-compatible."
                        }
                        failures += collectStrictObjectIssues(
                            node = originalNode,
                            schemaName = schemaName,
                            sourcePath = path
                        )
                    }
                }
        }

        assertTrue(
            failures.isEmpty(),
            "Strict structured-output schema contract failures:\n${failures.joinToString("\n")}"
        )
    }

    @Test
    fun `strict-json prompt call sites without provider response_format stay on explicit allowlist`() {
        val expectedUnstructuredCallsites = setOf(
            "src/main/kotlin/ai/neopsyke/agent/ego/ScratchpadFinalizer.kt",
            "src/main/kotlin/ai/neopsyke/agent/memory/longterm/LongTermMemoryAdvisor.kt"
        )
        val actualUnstructuredCallsites = mutableSetOf<String>()

        Files.walk(agentSourceRoot).use { paths ->
            paths
                .filter { path -> Files.isRegularFile(path) && path.toString().endsWith(".kt") }
                .forEach { path ->
                    val source = Files.readString(path)
                    if (!source.contains("STRICT JSON")) return@forEach
                    if (source.contains("responseFormat =")) return@forEach
                    actualUnstructuredCallsites += path.invariantSeparatorsPathString
                }
        }

        val unexpected = actualUnstructuredCallsites - expectedUnstructuredCallsites
        val missingExpected = expectedUnstructuredCallsites - actualUnstructuredCallsites
        assertTrue(
            unexpected.isEmpty() && missingExpected.isEmpty(),
            buildString {
                append("Unstructured STRICT JSON prompt allowlist drift detected.")
                if (unexpected.isNotEmpty()) {
                    append(" Unexpected: ${unexpected.sorted().joinToString(", ")}.")
                }
                if (missingExpected.isNotEmpty()) {
                    append(" Missing expected: ${missingExpected.sorted().joinToString(", ")}.")
                }
            }
        )
    }

    private fun collectStrictObjectIssues(
        node: JsonNode,
        schemaName: String,
        sourcePath: Path,
        path: String = "$",
    ): List<String> {
        val issues = mutableListOf<String>()
        when (node) {
            is ObjectNode -> {
                val nodeType = node.get("type")
                val properties = node.get("properties") as? ObjectNode
                if (nodeType?.isTextual == true && nodeType.asText() == "object" && properties != null) {
                    val propertyNames = properties.fieldNames().asSequence().toSet()
                    val required = node.get("required")
                    if (required !is ArrayNode) {
                        issues += "${sourcePath.invariantSeparatorsPathString}: schema $schemaName at $path missing required array."
                    } else {
                        val requiredNames = required.mapNotNull { if (it.isTextual) it.asText() else null }.toSet()
                        if (requiredNames != propertyNames) {
                            val missing = (propertyNames - requiredNames).sorted()
                            val extra = (requiredNames - propertyNames).sorted()
                            issues += "${sourcePath.invariantSeparatorsPathString}: schema $schemaName at $path has required mismatch " +
                                "(missing=${missing.joinToString("|")}, extra=${extra.joinToString("|")})."
                        }
                    }
                }

                val iterator = node.fields()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    issues += collectStrictObjectIssues(
                        node = entry.value,
                        schemaName = schemaName,
                        sourcePath = sourcePath,
                        path = "$path.${entry.key}"
                    )
                }
            }

            is ArrayNode -> {
                node.forEachIndexed { idx, child ->
                    issues += collectStrictObjectIssues(
                        node = child,
                        schemaName = schemaName,
                        sourcePath = sourcePath,
                        path = "$path[$idx]"
                    )
                }
            }
        }
        return issues
    }
}
