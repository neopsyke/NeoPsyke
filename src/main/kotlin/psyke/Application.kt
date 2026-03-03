package psyke

import mu.KotlinLogging
import psyke.config.AgentRuntimeSettingsLoader
import psyke.config.LlmRuntimeConfigLoader
import psyke.config.McpRuntimeConfigLoader
import psyke.eval.ReasoningEvalMode

private val logger = KotlinLogging.logger {}
private val output: ConsoleReporter = StdConsoleReporter

internal data class AppCliOptions(
    val showHelp: Boolean,
    val evalReasoningOnly: Boolean,
    val evalMemoryLiveOnly: Boolean,
    val evalReasoningMode: ReasoningEvalMode,
    val evalStage: String?,
    val evalReasoningMaxAttempts: Int,
    val evalReasoningTaskFilter: Set<String>,
    val evalMemoryMaxAttempts: Int,
    val evalMemoryTaskFilter: Set<String>,
    val unknownArgs: List<String>,
    val parseErrors: List<String>,
)

fun main(args: Array<String>) {
    logger.info { "Starting psyke Kotlin app." }

    val cliOptions = parseCliOptions(args)
    if (cliOptions.showHelp) {
        printAppHelp()
        return
    }
    if (cliOptions.parseErrors.isNotEmpty()) {
        cliOptions.parseErrors.forEach { logger.warn { it } }
        printAppHelp()
        return
    }
    if (cliOptions.unknownArgs.isNotEmpty()) {
        logger.warn { "Ignoring unknown app args: ${cliOptions.unknownArgs.joinToString(" ")}" }
    }

    val runtimeSettings = AgentRuntimeSettingsLoader.load()
    val config = runtimeSettings.agentConfig
    val mcpRuntimeConfig = McpRuntimeConfigLoader.load()
    val llmRuntimeConfig = LlmRuntimeConfigLoader.load()
    if (llmRuntimeConfig == null) {
        output.error("Unsupported LLM provider setting. Expected one of: groq, mistral (for LLM_PROVIDER and optional LLM_WEBSEARCH_PROVIDER).")
        logger.warn { "Unsupported LLM provider setting. Expected one of: groq, mistral (for LLM_PROVIDER and optional LLM_WEBSEARCH_PROVIDER)." }
        return
    }

    if (cliOptions.evalReasoningOnly) {
        AppModeRunners.runReasoningOnlyEval(
            llm = llmRuntimeConfig,
            cliOptions = cliOptions,
            runtimeSettings = runtimeSettings
        )
        return
    }
    if (cliOptions.evalMemoryLiveOnly) {
        AppModeRunners.runMemoryLiveEval(
            llm = llmRuntimeConfig,
            config = config,
            mcpRuntimeConfig = mcpRuntimeConfig,
            cliOptions = cliOptions,
            runtimeSettings = runtimeSettings
        )
        return
    }

    if (llmRuntimeConfig.apiKey.isBlank()) {
        val message = "${llmRuntimeConfig.apiKeyEnvVar} is not set. Export it to talk to ${llmRuntimeConfig.providerLabel}."
        output.error(message)
        logger.warn { message }
        return
    }

    AppModeRunners.runInteractiveMode(
        llm = llmRuntimeConfig,
        config = config,
        mcpRuntimeConfig = mcpRuntimeConfig,
        runtimeSettings = runtimeSettings
    )
}

private fun printAppHelp() {
    output.info(
        """
        psyke app options:
          --eval-reasoning-only           Run deterministic reasoning self-eval (no tools/actions)
          --eval-reasoning-mode MODE      Eval mode: logic (default) or model
          --eval-memory-live              Run live memory eval (real LLM + real MCP memory)
          --eval-stage ID                 Label this eval run (default: UTC date, e.g. 2026-02-28)
          --eval-reasoning-max-attempts N Max retries per reasoning task (default: 4)
          --eval-reasoning-tasks id1,id2  Run only selected reasoning task ids
          --eval-memory-max-attempts N    Max long-term memory assessment retries per memory task (default: 2)
          --eval-memory-tasks id1,id2     Run only selected memory eval task ids
          -h, --help                      Show this help message
        """.trimIndent()
    )
}

private fun parseCliOptions(args: Array<String>): AppCliOptions {
    var showHelp = false
    var evalReasoningOnly = false
    var evalMemoryLiveOnly = false
    var evalReasoningMode = ReasoningEvalMode.LOGIC
    var evalStage: String? = null
    var evalReasoningMaxAttempts = 4
    var evalReasoningTaskFilter: Set<String> = emptySet()
    var evalMemoryMaxAttempts = 2
    var evalMemoryTaskFilter: Set<String> = emptySet()
    val unknownArgs = mutableListOf<String>()
    val parseErrors = mutableListOf<String>()

    var index = 0
    while (index < args.size) {
        val arg = args[index]
        when {
            arg == "-h" || arg == "--help" -> {
                showHelp = true
                index += 1
            }

            arg == "--eval-reasoning-only" -> {
                evalReasoningOnly = true
                index += 1
            }
            arg == "--eval-memory-live" -> {
                evalMemoryLiveOnly = true
                index += 1
            }
            arg == "--eval-reasoning-mode" -> {
                val next = args.getOrNull(index + 1)
                val parsed = ReasoningEvalMode.parse(next)
                if (parsed == null) {
                    parseErrors += "Invalid value for --eval-reasoning-mode: '${next ?: "<missing>"}'. Expected logic or model."
                } else {
                    evalReasoningMode = parsed
                }
                index += 2
            }
            arg.startsWith("--eval-reasoning-mode=") -> {
                val raw = arg.substringAfter('=')
                val parsed = ReasoningEvalMode.parse(raw)
                if (parsed == null) {
                    parseErrors += "Invalid value for --eval-reasoning-mode: '$raw'. Expected logic or model."
                } else {
                    evalReasoningMode = parsed
                }
                index += 1
            }
            arg == "--eval-stage" -> {
                val next = args.getOrNull(index + 1)
                if (next.isNullOrBlank()) {
                    parseErrors += "Missing value for --eval-stage."
                } else {
                    evalStage = next.trim()
                }
                index += 2
            }
            arg.startsWith("--eval-stage=") -> {
                val raw = arg.substringAfter('=').trim()
                if (raw.isBlank()) {
                    parseErrors += "Invalid blank value for --eval-stage."
                } else {
                    evalStage = raw
                }
                index += 1
            }

            arg == "--eval-reasoning-max-attempts" -> {
                val next = args.getOrNull(index + 1)
                val parsed = next?.toIntOrNull()
                if (parsed == null || parsed < 1) {
                    parseErrors += "Invalid value for --eval-reasoning-max-attempts: '${next ?: "<missing>"}'. Expected positive integer."
                } else {
                    evalReasoningMaxAttempts = parsed
                }
                index += 2
            }
            arg.startsWith("--eval-reasoning-max-attempts=") -> {
                val raw = arg.substringAfter('=')
                val parsed = raw.toIntOrNull()
                if (parsed == null || parsed < 1) {
                    parseErrors += "Invalid value for --eval-reasoning-max-attempts: '$raw'. Expected positive integer."
                } else {
                    evalReasoningMaxAttempts = parsed
                }
                index += 1
            }

            arg == "--eval-reasoning-tasks" -> {
                val next = args.getOrNull(index + 1)
                if (next.isNullOrBlank()) {
                    parseErrors += "Missing value for --eval-reasoning-tasks."
                } else {
                    evalReasoningTaskFilter = next.split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet()
                }
                index += 2
            }
            arg.startsWith("--eval-reasoning-tasks=") -> {
                val raw = arg.substringAfter('=')
                evalReasoningTaskFilter = raw.split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet()
                index += 1
            }

            arg == "--eval-memory-max-attempts" -> {
                val next = args.getOrNull(index + 1)
                val parsed = next?.toIntOrNull()
                if (parsed == null || parsed < 1) {
                    parseErrors += "Invalid value for --eval-memory-max-attempts: '${next ?: "<missing>"}'. Expected positive integer."
                } else {
                    evalMemoryMaxAttempts = parsed
                }
                index += 2
            }
            arg.startsWith("--eval-memory-max-attempts=") -> {
                val raw = arg.substringAfter('=')
                val parsed = raw.toIntOrNull()
                if (parsed == null || parsed < 1) {
                    parseErrors += "Invalid value for --eval-memory-max-attempts: '$raw'. Expected positive integer."
                } else {
                    evalMemoryMaxAttempts = parsed
                }
                index += 1
            }

            arg == "--eval-memory-tasks" -> {
                val next = args.getOrNull(index + 1)
                if (next.isNullOrBlank()) {
                    parseErrors += "Missing value for --eval-memory-tasks."
                } else {
                    evalMemoryTaskFilter = next.split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet()
                }
                index += 2
            }
            arg.startsWith("--eval-memory-tasks=") -> {
                val raw = arg.substringAfter('=')
                evalMemoryTaskFilter = raw.split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet()
                index += 1
            }

            else -> {
                unknownArgs += arg
                index += 1
            }
        }
    }

    if (evalReasoningOnly && evalMemoryLiveOnly) {
        parseErrors += "Choose only one eval mode: --eval-reasoning-only or --eval-memory-live."
    }

    return AppCliOptions(
        showHelp = showHelp,
        evalReasoningOnly = evalReasoningOnly,
        evalMemoryLiveOnly = evalMemoryLiveOnly,
        evalReasoningMode = evalReasoningMode,
        evalStage = evalStage,
        evalReasoningMaxAttempts = evalReasoningMaxAttempts,
        evalReasoningTaskFilter = evalReasoningTaskFilter,
        evalMemoryMaxAttempts = evalMemoryMaxAttempts,
        evalMemoryTaskFilter = evalMemoryTaskFilter,
        unknownArgs = unknownArgs,
        parseErrors = parseErrors
    )
}
