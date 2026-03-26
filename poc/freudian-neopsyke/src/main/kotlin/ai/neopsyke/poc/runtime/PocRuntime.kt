package ai.neopsyke.poc.runtime

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ai.neopsyke.poc.config.PocConfig
import ai.neopsyke.poc.cortex.SensoryCortex
import ai.neopsyke.poc.ego.Ego
import ai.neopsyke.poc.id.IdModule
import ai.neopsyke.poc.instrumentation.EventLogger
import ai.neopsyke.poc.instrumentation.RuntimeEvent
import java.nio.file.Files
import java.nio.file.Path

class PocRuntime(
    private val config: PocConfig,
    private val sensoryCortex: SensoryCortex,
    private val idModule: IdModule,
    private val ego: Ego,
    private val eventLogger: EventLogger,
) {
    fun run(): RunSummary {
        var userRequestsProcessed = 0
        var impulsesFired = 0
        var impulsesAccepted = 0
        var impulsesDenied = 0
        var actionsProposed = 0
        var actionsDeniedBySuperego = 0
        var actionsExecuted = 0

        for (tick in 0 until config.runtime.totalTicks) {
            val userRequests = sensoryCortex.pollUserRequests(tick)
            userRequests.forEach { userRequest ->
                ego.submitUserRequest(tick, userRequest)
                userRequestsProcessed += 1
            }

            val impulse = idModule.tick(tick)
            if (impulse != null) {
                impulsesFired += 1
                ego.submitImpulse(tick, impulse)
            }

            val processingResult = ego.processAllPending(tick)
            actionsProposed += processingResult.actionsProposed
            actionsDeniedBySuperego += processingResult.actionsDeniedBySuperego
            actionsExecuted += processingResult.actionsExecuted

            processingResult.impulseFeedback.forEach { feedback ->
                idModule.onImpulseFeedback(tick, feedback)
                when (feedback.result) {
                    ai.neopsyke.poc.model.ImpulseResult.ACCEPTED -> impulsesAccepted += 1
                    ai.neopsyke.poc.model.ImpulseResult.DENIED -> impulsesDenied += 1
                }
            }
        }

        // Defensive closure: if any impulse lifecycle remains unresolved at run end, force deny.
        val forcedFeedback = ego.forceDenyAllImpulses(config.runtime.totalTicks)
        forcedFeedback.forEach { feedback ->
            idModule.onImpulseFeedback(config.runtime.totalTicks, feedback)
            impulsesDenied += 1
        }

        val summary = RunSummary(
            totalTicks = config.runtime.totalTicks,
            userRequestsProcessed = userRequestsProcessed,
            impulsesFired = impulsesFired,
            impulsesAccepted = impulsesAccepted,
            impulsesDenied = impulsesDenied,
            actionsProposed = actionsProposed,
            actionsDeniedBySuperego = actionsDeniedBySuperego,
            actionsExecuted = actionsExecuted,
            finalNeeds = idModule.needsSnapshot(),
        )

        eventLogger.log(
            RuntimeEvent(
                tick = config.runtime.totalTicks,
                type = "run_summary",
                attributes = summary.toMap(),
            )
        )
        return summary
    }
}

data class RunSummary(
    val totalTicks: Int,
    val userRequestsProcessed: Int,
    val impulsesFired: Int,
    val impulsesAccepted: Int,
    val impulsesDenied: Int,
    val actionsProposed: Int,
    val actionsDeniedBySuperego: Int,
    val actionsExecuted: Int,
    val finalNeeds: Map<String, Double>,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "total_ticks" to totalTicks,
        "user_requests_processed" to userRequestsProcessed,
        "impulses_fired" to impulsesFired,
        "impulses_accepted" to impulsesAccepted,
        "impulses_denied" to impulsesDenied,
        "actions_proposed" to actionsProposed,
        "actions_denied_by_superego" to actionsDeniedBySuperego,
        "actions_executed" to actionsExecuted,
        "final_needs" to finalNeeds,
    )

    fun writeJson(path: Path) {
        path.parent?.let { Files.createDirectories(it) }
        val objectMapper = jacksonObjectMapper()
        Files.writeString(path, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(toMap()))
    }
}
