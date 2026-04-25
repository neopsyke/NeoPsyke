package ai.neopsyke.prompt

import ai.neopsyke.agent.support.PromptBudgetAllocator
import ai.neopsyke.llm.ChatCallMetadata
import ai.neopsyke.llm.ChatResponseFormat
import ai.neopsyke.llm.ChatRole
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import mu.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

class PromptCatalog private constructor(
    private val root: Path,
    private val classLoader: ClassLoader,
) {
    private val activePrompts = ConcurrentHashMap<String, LoadedPrompt>()
    private val activeSchemas = ConcurrentHashMap<String, LoadedSchema>()

    fun renderSections(
        promptId: String,
        variables: Map<String, String> = emptyMap(),
    ): RenderedPrompt {
        val prompt = loadPrompt(promptId)
        val renderedSections = prompt.asset.sections.map { section ->
            val content = renderTemplate(
                id = promptId,
                template = section.content,
                allowedVariables = prompt.asset.variables,
                variables = variables,
            )
            PromptBudgetAllocator.Section(
                key = section.key,
                role = parseRole(section.role, promptId),
                band = parseBand(section.band, promptId),
                importance = parseImportance(section.importance, promptId),
                floorTokens = section.floorTokens ?: 0,
                content = content,
            )
        }
        return RenderedPrompt(
            id = prompt.asset.id,
            version = prompt.asset.version,
            hash = prompt.hash,
            sections = renderedSections,
        )
    }

    fun renderText(promptId: String, variables: Map<String, String> = emptyMap()): RenderedText {
        val prompt = loadPrompt(promptId)
        val text = prompt.asset.text
            ?: throw IllegalStateException("Prompt $promptId has no text field.")
        return RenderedText(
            id = prompt.asset.id,
            version = prompt.asset.version,
            hash = prompt.hash,
            text = renderTemplate(promptId, text, prompt.asset.variables, variables),
        )
    }

    fun responseFormat(schemaId: String): RenderedSchema {
        val schema = loadSchema(schemaId)
        return RenderedSchema(
            id = schema.asset.id,
            hash = schema.hash,
            format = ChatResponseFormat.JsonSchema(
                name = schema.asset.name,
                schemaJson = schema.asset.schema.trim(),
                strict = schema.asset.strict,
                relaxedSchemaJson = schema.asset.relaxedSchema?.trim(),
            )
        )
    }

    fun metadata(metadata: ChatCallMetadata, prompt: RenderedPrompt, schema: RenderedSchema? = null): ChatCallMetadata =
        metadata.copy(
            promptId = prompt.id,
            promptVersion = prompt.version,
            promptHash = prompt.hash,
            schemaId = schema?.id,
            schemaHash = schema?.hash,
        )

    fun metadata(metadata: ChatCallMetadata, prompt: RenderedText, schema: RenderedSchema? = null): ChatCallMetadata =
        metadata.copy(
            promptId = prompt.id,
            promptVersion = prompt.version,
            promptHash = prompt.hash,
            schemaId = schema?.id,
            schemaHash = schema?.hash,
        )

    private fun loadPrompt(promptId: String): LoadedPrompt {
        val current = activePrompts[promptId]
        val source = resolveSource("$promptId.yaml")
        if (current != null && !source.changedSince(current.source)) return current
        val loaded = try {
            val text = source.readText()
            val asset = mapper.readValue<PromptAsset>(text)
            validatePrompt(promptId, asset)
            LoadedPrompt(asset = asset, source = source, hash = sha256(text))
        } catch (ex: Exception) {
            if (current == null) throw IllegalStateException("Prompt asset $promptId failed initial load from ${source.path}", ex)
            logger.error(ex) {
                "prompt_catalog.reload_failed type=prompt prompt_id=$promptId path=${source.path} " +
                    "active_version=${current.asset.version} active_hash=${current.hash}"
            }
            current
        }
        activePrompts[promptId] = loaded
        return loaded
    }

    private fun loadSchema(schemaId: String): LoadedSchema {
        val current = activeSchemas[schemaId]
        val source = resolveSource("schemas/$schemaId.yaml")
        if (current != null && !source.changedSince(current.source)) return current
        val loaded = try {
            val text = source.readText()
            val asset = mapper.readValue<SchemaAsset>(text)
            validateSchema(schemaId, asset)
            LoadedSchema(asset = asset, source = source, hash = sha256(text))
        } catch (ex: Exception) {
            if (current == null) throw IllegalStateException("Schema asset $schemaId failed initial load from ${source.path}", ex)
            logger.error(ex) {
                "prompt_catalog.reload_failed type=schema schema_id=$schemaId path=${source.path} " +
                    "active_hash=${current.hash}"
            }
            current
        }
        activeSchemas[schemaId] = loaded
        return loaded
    }

    private fun resolveSource(relative: String): PromptSource {
        val file = root.resolve(relative)
        if (Files.exists(file)) {
            return PromptSource(path = file, resourceName = null, lastModifiedMillis = Files.getLastModifiedTime(file).toMillis())
        }
        val resource = "prompts/$relative"
        classLoader.getResource(resource)
            ?: throw IllegalStateException("Prompt asset not found at $file or classpath:$resource")
        return PromptSource(path = Paths.get(resource), resourceName = resource, lastModifiedMillis = null)
    }

    private fun PromptSource.readText(): String {
        if (resourceName == null) return Files.readString(path)
        return classLoader.getResourceAsStream(resourceName)?.bufferedReader()?.readText()
            ?: throw IllegalStateException("Prompt resource disappeared: $resourceName")
    }

    private fun PromptSource.changedSince(previous: PromptSource): Boolean {
        if (resourceName != null) return false
        if (previous.resourceName != null) return true
        val now = Files.getLastModifiedTime(path).toMillis()
        return now != previous.lastModifiedMillis
    }

    private fun validatePrompt(promptId: String, asset: PromptAsset) {
        require(asset.id == promptId) { "prompt id mismatch: expected $promptId got ${asset.id}" }
        require(asset.version > 0) { "prompt $promptId version must be positive" }
        require(asset.sections.isNotEmpty() || !asset.text.isNullOrBlank()) { "prompt $promptId must define sections or text" }
        asset.sections.forEach { section ->
            require(section.key.isNotBlank()) { "prompt $promptId has blank section key" }
            parseRole(section.role, promptId)
            parseBand(section.band, promptId)
            parseImportance(section.importance, promptId)
            require(section.content.isNotBlank()) { "prompt $promptId section ${section.key} content is blank" }
        }
    }

    private fun validateSchema(schemaId: String, asset: SchemaAsset) {
        require(asset.id == schemaId) { "schema id mismatch: expected $schemaId got ${asset.id}" }
        require(asset.name.isNotBlank()) { "schema $schemaId name is blank" }
        mapper.readTree(asset.schema)
        asset.relaxedSchema?.takeIf { it.isNotBlank() }?.let { mapper.readTree(it) }
    }

    private fun renderTemplate(
        id: String,
        template: String,
        allowedVariables: Set<String>,
        variables: Map<String, String>,
    ): String {
        val referenced = variableRegex.findAll(template).map { it.groupValues[1] }.toSet()
        val unknown = referenced - allowedVariables
        require(unknown.isEmpty()) { "Prompt $id references undeclared variables: ${unknown.sorted()}" }
        val missing = referenced - variables.keys
        require(missing.isEmpty()) { "Prompt $id missing variables: ${missing.sorted()}" }
        val unexpected = variables.keys - allowedVariables
        require(unexpected.isEmpty()) { "Prompt $id received undeclared variables: ${unexpected.sorted()}" }
        return variableRegex.replace(template) { match -> variables.getValue(match.groupValues[1]) }
            .also {
                require(!variableRegex.containsMatchIn(it)) { "Prompt $id rendered with unresolved placeholders" }
            }
            .trim()
    }

    private fun parseRole(raw: String, promptId: String): ChatRole =
        when (raw.trim().lowercase()) {
            "system" -> ChatRole.SYSTEM
            "user" -> ChatRole.USER
            "assistant" -> ChatRole.ASSISTANT
            else -> throw IllegalArgumentException("Prompt $promptId has invalid role '$raw'")
        }

    private fun parseBand(raw: String, promptId: String): PromptBudgetAllocator.Band =
        when (raw.trim().lowercase()) {
            "required_core" -> PromptBudgetAllocator.Band.REQUIRED_CORE
            "required_context" -> PromptBudgetAllocator.Band.REQUIRED_CONTEXT
            "optional" -> PromptBudgetAllocator.Band.OPTIONAL
            else -> throw IllegalArgumentException("Prompt $promptId has invalid band '$raw'")
        }

    private fun parseImportance(raw: String?, promptId: String): PromptBudgetAllocator.Importance =
        when (raw?.trim()?.lowercase() ?: "medium") {
            "high" -> PromptBudgetAllocator.Importance.HIGH
            "medium" -> PromptBudgetAllocator.Importance.MEDIUM
            "low" -> PromptBudgetAllocator.Importance.LOW
            else -> throw IllegalArgumentException("Prompt $promptId has invalid importance '$raw'")
        }

    data class RenderedPrompt(
        val id: String,
        val version: Int,
        val hash: String,
        val sections: List<PromptBudgetAllocator.Section>,
    )

    data class RenderedText(
        val id: String,
        val version: Int,
        val hash: String,
        val text: String,
    )

    data class RenderedSchema(
        val id: String,
        val hash: String,
        val format: ChatResponseFormat.JsonSchema,
    )

    private data class PromptSource(
        val path: Path,
        val resourceName: String?,
        val lastModifiedMillis: Long?,
    )

    private data class LoadedPrompt(
        val asset: PromptAsset,
        val source: PromptSource,
        val hash: String,
    )

    private data class LoadedSchema(
        val asset: SchemaAsset,
        val source: PromptSource,
        val hash: String,
    )

    private data class PromptAsset(
        val id: String = "",
        val version: Int = 0,
        val variables: Set<String> = emptySet(),
        val sections: List<PromptSectionAsset> = emptyList(),
        val text: String? = null,
    )

    private data class PromptSectionAsset(
        val key: String = "",
        val role: String = "",
        val band: String = "optional",
        val importance: String? = null,
        @param:JsonProperty("floor_tokens")
        val floorTokens: Int? = null,
        val content: String = "",
    )

    private data class SchemaAsset(
        val id: String = "",
        val name: String = "",
        val strict: Boolean = true,
        val schema: String = "",
        @param:JsonProperty("relaxed_schema")
        val relaxedSchema: String? = null,
    )

    companion object {
        private val mapper = ObjectMapper(YAMLFactory())
            .registerKotlinModule()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        private val variableRegex = Regex("""\{\{([a-zA-Z0-9_]+)}}""")

        val shared: PromptCatalog by lazy {
            val root = System.getenv("NEOPSYKE_PROMPTS_DIR")?.trim()?.takeIf { it.isNotBlank() }
                ?.let { Paths.get(it) }
                ?: Paths.get("config/prompts")
            PromptCatalog(
                root = root,
                classLoader = Thread.currentThread().contextClassLoader ?: PromptCatalog::class.java.classLoader,
            )
        }

        fun fromRoot(root: Path): PromptCatalog =
            PromptCatalog(root = root, classLoader = PromptCatalog::class.java.classLoader)

        private fun sha256(text: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
