package ai.neopsyke.prompt

import ai.neopsyke.llm.ChatRole
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectories
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PromptCatalogTest {
    @Test
    fun `renders sections with declared variables`() {
        val root = Files.createTempDirectory("prompt-catalog-test")
        root.resolve("planner").createDirectories()
        root.resolve("planner/sample.yaml").writeText(
            """
            id: planner/sample
            version: 1
            variables: [name]
            sections:
              - key: sample_system
                role: system
                band: required_core
                importance: high
                floor_tokens: 4
                content: "Hello {{name}}"
            """.trimIndent()
        )

        val rendered = PromptCatalog.fromRoot(root).renderSections("planner/sample", mapOf("name" to "Neo"))

        assertEquals("planner/sample", rendered.id)
        assertEquals(1, rendered.version)
        assertTrue(rendered.hash.isNotBlank())
        assertEquals(ChatRole.SYSTEM, rendered.sections.single().role)
        assertEquals("Hello Neo", rendered.sections.single().content)
    }

    @Test
    fun `rejects undeclared variables`() {
        val root = Files.createTempDirectory("prompt-catalog-test")
        root.resolve("sample.yaml").writeText(
            """
            id: sample
            version: 1
            variables: []
            text: "Hello {{name}}"
            """.trimIndent()
        )

        assertFailsWith<IllegalArgumentException> {
            PromptCatalog.fromRoot(root).renderText("sample")
        }
    }

    @Test
    fun `keeps active schema when hot reload edit is invalid`() {
        val root = Files.createTempDirectory("prompt-catalog-test")
        root.resolve("schemas").createDirectories()
        val schemaPath = root.resolve("schemas/sample.yaml")
        schemaPath.writeText(
            """
            id: sample
            name: sample_schema
            strict: true
            schema: |
              {"type":"object","additionalProperties":false}
            """.trimIndent()
        )
        val catalog = PromptCatalog.fromRoot(root)
        val first = catalog.responseFormat("sample")

        Thread.sleep(5)
        schemaPath.writeText(
            """
            id: sample
            name: sample_schema
            strict: true
            schema: |
              {"type":
            """.trimIndent()
        )
        val second = catalog.responseFormat("sample")

        assertEquals(first.hash, second.hash)
        assertEquals(first.format.schemaJson, second.format.schemaJson)
    }

    @Test
    fun `fails startup load when no valid prior schema exists`() {
        val root = Files.createTempDirectory("prompt-catalog-test")
        root.resolve("schemas").createDirectories()
        root.resolve("schemas/sample.yaml").writeText(
            """
            id: sample
            name: sample_schema
            strict: true
            schema: |
              {"type":
            """.trimIndent()
        )

        assertFailsWith<IllegalStateException> {
            PromptCatalog.fromRoot(root).responseFormat("sample")
        }
    }

    @Test
    fun `all committed prompt assets render and schemas parse`() {
        val root = repoRoot().resolve("config/prompts")
        val catalog = PromptCatalog.fromRoot(root)
        val promptFiles = Files.walk(root).use { stream ->
            stream.filter { it.isRegularFile() && it.extension == "yaml" && !slashPath(it).contains("/schemas/") }
                .toList()
        }
        val schemaFiles = Files.walk(root.resolve("schemas")).use { stream ->
            stream.filter { it.isRegularFile() && it.extension == "yaml" }.toList()
        }

        promptFiles.forEach { file ->
            val node = yamlMapper.readTree(file.toFile())
            val promptId = node.path("id").asText()
            val variables = node.path("variables").map { it.asText() }.associateWith { "fixture_$it" }
            val firstHash = if (node.hasNonNull("text")) {
                catalog.renderText(promptId, variables).hash
            } else {
                catalog.renderSections(promptId, variables).hash
            }
            val secondHash = if (node.hasNonNull("text")) {
                catalog.renderText(promptId, variables).hash
            } else {
                catalog.renderSections(promptId, variables).hash
            }
            assertEquals(firstHash, secondHash, "Prompt hash should be stable for $promptId")
        }

        schemaFiles.forEach { file ->
            val schemaId = yamlMapper.readTree(file.toFile()).path("id").asText()
            val rendered = catalog.responseFormat(schemaId)
            assertTrue(rendered.hash.isNotBlank(), "Schema hash should be present for $schemaId")
            assertTrue(rendered.format.schemaJson.isNotBlank(), "Schema JSON should be present for $schemaId")
        }
    }

    @Test
    fun `kotlin sources do not embed llm prompt instruction text outside prompt catalog`() {
        val srcRoot = repoRoot().resolve("src/main/kotlin/ai/neopsyke")
        val sourceFiles = Files.walk(srcRoot).use { stream ->
            stream.filter { it.isRegularFile() && it.extension == "kt" }
                .filter { !slashPath(it).contains("/prompt/") }
                .toList()
        }
        val findings = sourceFiles.flatMap { file ->
            file.readText().lineSequence().mapIndexedNotNull { index, line ->
                val marker = embeddedPromptMarkers.firstOrNull { line.contains(it) } ?: return@mapIndexedNotNull null
                if (isAllowedFixtureData(line)) return@mapIndexedNotNull null
                "${slashPath(file.relativeTo(repoRoot()))}:${index + 1}: $marker"
            }.toList()
        }

        assertTrue(findings.isEmpty(), "Embedded prompt-like Kotlin text found:\n${findings.joinToString("\n")}")
    }

    private fun repoRoot(): Path = Paths.get("").toAbsolutePath().normalize()

    private fun slashPath(path: Path): String = path.toString().replace('\\', '/')

    private fun isAllowedFixtureData(line: String): Boolean =
        line.contains("userStatement = \"You are friendly and like to be direct.\"")

    private companion object {
        private val yamlMapper = ObjectMapper(YAMLFactory())
        private val embeddedPromptMarkers = listOf(
            "You are ",
            "Return STRICT JSON",
            "Return strict JSON",
            "JSON schema:",
            "SYSTEM_PROMPT",
            "buildSystemPrompt",
            "buildUserPrompt",
            "plannerDescription = \"",
            "payloadGuidance = \"",
            "payloadSchemaExample = \"",
        )
    }
}
