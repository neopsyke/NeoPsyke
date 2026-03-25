package ai.neopsyke

import mu.KotlinLogging
import ai.neopsyke.config.AgentRuntimeSettingsLoader
import ai.neopsyke.config.LlmRuntimeConfigLoader
import ai.neopsyke.config.MemoryRuntimeConfigLoader
import ai.neopsyke.eval.ReasoningEvalMode

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
    val clearMemoryAll: Boolean = false,
    val clearMemoryVector: Boolean = false,
    val clearMemoryEpisodic: Boolean = false,
    val clearMemoryLessons: Boolean = false,
    val freudLive: Boolean = false,
    val freudLiveTimeoutSeconds: Int = 120,
    val unknownArgs: List<String>,
    val parseErrors: List<String>,
) {
    val hasClearMemoryRequest: Boolean
        get() = clearMemoryAll || clearMemoryVector || clearMemoryEpisodic || clearMemoryLessons
}

fun main(args: Array<String>) {
    logger.info { "Starting NeoPsyke Kotlin app." }

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
    val memoryRuntimeConfig = MemoryRuntimeConfigLoader.load()
    val llmRuntimeConfig = LlmRuntimeConfigLoader.load()

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
            memoryRuntimeConfig = memoryRuntimeConfig,
            cliOptions = cliOptions,
            runtimeSettings = runtimeSettings
        )
        return
    }

    if (cliOptions.freudLive) {
        AppModeRunners.runFreudLiveMode(
            llm = llmRuntimeConfig,
            config = config,
            memoryRuntimeConfig = memoryRuntimeConfig,
            runtimeSettings = runtimeSettings,
            cliOptions = cliOptions
        )
        return
    }

    AppModeRunners.runInteractiveMode(
        llm = llmRuntimeConfig,
        config = config,
        memoryRuntimeConfig = memoryRuntimeConfig,
        runtimeSettings = runtimeSettings,
        cliOptions = cliOptions
    )
}

private fun printAppHelp() {
    output.info(
        """
        NeoPsyke app options:
          --eval-reasoning-only           Run deterministic reasoning self-eval (no tools/actions)
          --eval-reasoning-mode MODE      Eval mode: logic (default) or model
          --eval-memory-live              Run live memory eval (real LLM + real long-term memory provider)
          --eval-stage ID                 Label this eval run (default: UTC date, e.g. 2026-02-28)
          --eval-reasoning-max-attempts N Max retries per reasoning task (default: 4)
          --eval-reasoning-tasks id1,id2  Run only selected reasoning task ids
          --eval-memory-max-attempts N    Max long-term memory assessment retries per memory task (default: 2)
          --eval-memory-tasks id1,id2     Run only selected memory eval task ids
          --clear-memory-all              Clear ALL long-term memory (vector + episodic) before starting
          --clear-memory-vector           Clear vector/hippocampus memory before starting
          --clear-memory-episodic         Clear episodic logbook memory before starting
          --clear-memory-lessons         Clear lessons from vector memory before starting
          --freud-live                    Run single-input live eval (reads stdin, writes answer to stdout)
          --freud-live-timeout N          Timeout in seconds for freud-live mode (default: 120)
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
    var clearMemoryAll = false
    var clearMemoryVector = false
    var clearMemoryEpisodic = false
    var clearMemoryLessons = false
    var freudLive = false
    var freudLiveTimeoutSeconds = 120
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

            arg == "--freud-live" -> {
                freudLive = true
                index += 1
            }
            arg == "--freud-live-timeout" -> {
                val next = args.getOrNull(index + 1)
                val parsed = next?.toIntOrNull()
                if (parsed == null || parsed < 1) {
                    parseErrors += "Invalid value for --freud-live-timeout: '${next ?: "<missing>"}'. Expected positive integer (seconds)."
                } else {
                    freudLiveTimeoutSeconds = parsed
                }
                index += 2
            }
            arg.startsWith("--freud-live-timeout=") -> {
                val raw = arg.substringAfter('=')
                val parsed = raw.toIntOrNull()
                if (parsed == null || parsed < 1) {
                    parseErrors += "Invalid value for --freud-live-timeout: '$raw'. Expected positive integer (seconds)."
                } else {
                    freudLiveTimeoutSeconds = parsed
                }
                index += 1
            }

            arg == "--clear-memory-all" -> {
                clearMemoryAll = true
                index += 1
            }
            arg == "--clear-memory-vector" -> {
                clearMemoryVector = true
                index += 1
            }
            arg == "--clear-memory-episodic" -> {
                clearMemoryEpisodic = true
                index += 1
            }
            arg == "--clear-memory-lessons" -> {
                clearMemoryLessons = true
                index += 1
            }

            else -> {
                unknownArgs += arg
                index += 1
            }
        }
    }

    val evalModeCount = listOf(evalReasoningOnly, evalMemoryLiveOnly, freudLive).count { it }
    if (evalModeCount > 1) {
        parseErrors += "Choose only one mode: --eval-reasoning-only, --eval-memory-live, or --freud-live."
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
        clearMemoryAll = clearMemoryAll,
        clearMemoryVector = clearMemoryVector,
        clearMemoryEpisodic = clearMemoryEpisodic,
        clearMemoryLessons = clearMemoryLessons,
        freudLive = freudLive,
        freudLiveTimeoutSeconds = freudLiveTimeoutSeconds,
        unknownArgs = unknownArgs,
        parseErrors = parseErrors
    )
}
