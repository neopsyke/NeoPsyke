package ai.neopsyke.poc

import ai.neopsyke.poc.config.PocConfig
import ai.neopsyke.poc.config.PocConfigLoader
import ai.neopsyke.poc.cortex.DeterministicMotorCortex
import ai.neopsyke.poc.cortex.ScriptedSensoryCortex
import ai.neopsyke.poc.ego.DeterministicEgoPlanner
import ai.neopsyke.poc.ego.Ego
import ai.neopsyke.poc.id.IdModule
import ai.neopsyke.poc.instrumentation.CompositeEventLogger
import ai.neopsyke.poc.instrumentation.JsonlEventLogger
import ai.neopsyke.poc.instrumentation.RuntimeEvent
import ai.neopsyke.poc.model.ActionType
import ai.neopsyke.poc.runtime.AblationRunner
import ai.neopsyke.poc.runtime.PocRuntime
import ai.neopsyke.poc.runtime.RunSummary
import ai.neopsyke.poc.superego.DeterministicSuperego
import java.nio.file.Path
import java.util.Random

fun main(arguments: Array<String>) {
    val cliArguments = CliArguments.parse(arguments)
    val configPath = Path.of(cliArguments.configPath)
    val baseConfig = PocConfigLoader.load(configPath)

    when (cliArguments.mode) {
        RunMode.RUN -> {
            val summary = runSingleScenario(baseConfig)
            summary.writeJson(Path.of(baseConfig.logging.summaryPath))
            println("Run completed. Summary written to ${baseConfig.logging.summaryPath}")
            println(summary.toMap())
        }

        RunMode.ABLATION -> {
            val ablationSummary = AblationRunner.run(baseConfig) { scenarioConfig ->
                runSingleScenario(scenarioConfig)
            }
            val outputPath = Path.of(cliArguments.ablationOutputPath ?: "build/ablation-summary.json")
            ablationSummary.writeJson(outputPath)
            println("Ablation completed. Report written to $outputPath")
            println(ablationSummary.toMap())
        }
    }
}

private fun runSingleScenario(config: PocConfig): RunSummary {
    val random = if (config.runtime.deterministicMode) {
        Random(config.runtime.deterministicSeed)
    } else {
        Random()
    }

    val eventsPath = Path.of(config.logging.eventsPathForCurrentRun(config.id.enabled))
    val eventLogger = JsonlEventLogger(eventsPath)
    val compositeEventLogger = CompositeEventLogger(listOf(eventLogger))

    val scriptedSensoryCortex = ScriptedSensoryCortex(
        scheduledRequests = config.sensory.scheduledUserRequests.map { it.tick to it.content }
    )

    val idModule = IdModule(
        config = config.id,
        random = random,
        eventLogger = compositeEventLogger,
    )

    val ego = Ego(
        planner = DeterministicEgoPlanner(config.ego),
        superego = DeterministicSuperego(config.superego),
        motorCortex = DeterministicMotorCortex(
            executableActionTypes = config.motor.executableActionTypes.map { ActionType.fromRaw(it) }.toSet()
        ),
        eventLogger = compositeEventLogger,
    )

    compositeEventLogger.log(
        RuntimeEvent(
            type = "run_start",
            attributes = mapOf(
                "deterministic_mode" to config.runtime.deterministicMode,
                "deterministic_seed" to config.runtime.deterministicSeed,
                "id_enabled" to config.id.enabled,
                "total_ticks" to config.runtime.totalTicks,
            )
        )
    )

    return PocRuntime(
        config = config,
        sensoryCortex = scriptedSensoryCortex,
        idModule = idModule,
        ego = ego,
        eventLogger = compositeEventLogger,
    ).run().also {
        eventLogger.close()
    }
}

private enum class RunMode {
    RUN,
    ABLATION,
}

private data class CliArguments(
    val mode: RunMode,
    val configPath: String,
    val ablationOutputPath: String?,
) {
    companion object {
        fun parse(arguments: Array<String>): CliArguments {
            var mode = RunMode.RUN
            var configPath = "config/poc.yaml"
            var ablationOutputPath: String? = null

            var index = 0
            while (index < arguments.size) {
                when (arguments[index]) {
                    "--mode" -> {
                        mode = RunMode.valueOf(arguments.getOrElse(index + 1) {
                            error("Missing value for --mode")
                        }.uppercase())
                        index += 2
                    }

                    "--config" -> {
                        configPath = arguments.getOrElse(index + 1) {
                            error("Missing value for --config")
                        }
                        index += 2
                    }

                    "--ablation-output" -> {
                        ablationOutputPath = arguments.getOrElse(index + 1) {
                            error("Missing value for --ablation-output")
                        }
                        index += 2
                    }

                    else -> error("Unknown argument: ${arguments[index]}")
                }
            }

            return CliArguments(
                mode = mode,
                configPath = configPath,
                ablationOutputPath = ablationOutputPath,
            )
        }
    }
}

private fun ai.neopsyke.poc.config.LoggingConfig.eventsPathForCurrentRun(idEnabled: Boolean): String {
    val suffix = if (idEnabled) "id-on" else "id-off"
    val dotIndex = eventsPath.lastIndexOf('.')
    return if (dotIndex <= 0) {
        "$eventsPath-$suffix"
    } else {
        val prefix = eventsPath.substring(0, dotIndex)
        val extension = eventsPath.substring(dotIndex)
        "$prefix-$suffix$extension"
    }
}
