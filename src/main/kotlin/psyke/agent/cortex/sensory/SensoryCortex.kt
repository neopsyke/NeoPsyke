package psyke.agent.cortex.sensory

import psyke.agent.core.AgentConfig
import psyke.agent.core.InputPriority
import psyke.agent.support.TextSecurity

data class SensoryInput(
    val content: String,
    val priority: InputPriority = InputPriority.MEDIUM,
    val source: String = "external",
)

sealed interface SensorySignal {
    data class InputReceived(val input: SensoryInput) : SensorySignal
    data class SourceClosed(val source: String) : SensorySignal
    data class ExitRequested(val source: String) : SensorySignal
    data object NoInput : SensorySignal
}

fun interface SensoryInputSource {
    fun nextSignal(): SensorySignal
}

class StdinSensoryInputSource(
    private val readLineFn: () -> String? = { readLine() },
    private val prompt: () -> Unit = { print("you> ") },
    ) : SensoryInputSource {
    override fun nextSignal(): SensorySignal {
        prompt()
        val rawInput = readLineFn() ?: return SensorySignal.SourceClosed(source = "stdin")
        if (rawInput.trim().equals("exit", ignoreCase = true)) {
            return SensorySignal.ExitRequested(source = "stdin")
        }
        if (rawInput.isBlank()) {
            return SensorySignal.NoInput
        }
        return SensorySignal.InputReceived(
            SensoryInput(
                content = rawInput,
                priority = InputPriority.HIGH,
                source = "stdin"
            )
        )
    }
}

class SensoryCortex(
    private val config: AgentConfig,
    private val source: SensoryInputSource,
) {
    fun nextSignal(): SensorySignal {
        val signal = source.nextSignal()
        if (signal !is SensorySignal.InputReceived) {
            return signal
        }

        val sanitized = TextSecurity.clamp(signal.input.content.trim(), config.planner.maxInputChars)
        if (sanitized.isBlank()) {
            return SensorySignal.NoInput
        }
        return SensorySignal.InputReceived(
            signal.input.copy(content = sanitized)
        )
    }

    companion object {
        fun stdin(config: AgentConfig): SensoryCortex =
            SensoryCortex(
                config = config,
                source = StdinSensoryInputSource()
            )
    }
}
