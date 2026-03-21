package freudian.poc.runtime

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import freudian.poc.config.PocConfig
import java.nio.file.Files
import java.nio.file.Path

data class AblationSummary(
    val idOff: RunSummary,
    val idOn: RunSummary,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id_off" to idOff.toMap(),
        "id_on" to idOn.toMap(),
        "delta" to mapOf(
            "impulses_fired" to (idOn.impulsesFired - idOff.impulsesFired),
            "impulses_accepted" to (idOn.impulsesAccepted - idOff.impulsesAccepted),
            "impulses_denied" to (idOn.impulsesDenied - idOff.impulsesDenied),
            "actions_executed" to (idOn.actionsExecuted - idOff.actionsExecuted),
            "actions_denied_by_superego" to (idOn.actionsDeniedBySuperego - idOff.actionsDeniedBySuperego),
        )
    )

    fun writeJson(path: Path) {
        path.parent?.let { Files.createDirectories(it) }
        val objectMapper = jacksonObjectMapper()
        Files.writeString(path, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(toMap()))
    }
}

object AblationRunner {
    fun run(
        baseConfig: PocConfig,
        runScenario: (PocConfig) -> RunSummary,
    ): AblationSummary {
        val idOffConfig = baseConfig.copy(id = baseConfig.id.copy(enabled = false))
        val idOnConfig = baseConfig.copy(id = baseConfig.id.copy(enabled = true))

        val idOffSummary = runScenario(idOffConfig)
        val idOnSummary = runScenario(idOnConfig)
        return AblationSummary(idOff = idOffSummary, idOn = idOnSummary)
    }
}
