package psyke

import mu.KotlinLogging
import psyke.agent.AgentConfig
import psyke.agent.EgoAgent
import psyke.agent.EgoPlanner
import psyke.agent.MistralWebSearchProvider
import psyke.agent.MotorCortex
import psyke.agent.SuperegoGatekeeper
import psyke.llm.MistralChatClient
import psyke.metrics.MetricsRuntimeFactory

private val logger = KotlinLogging.logger {}

fun main() {
    logger.info { "Starting psyke Kotlin app." }

    val apiKey = System.getenv("MISTRAL_API_KEY")
    if (apiKey.isNullOrBlank()) {
        logger.warn { "MISTRAL_API_KEY is not set. Export it to talk to Mistral." }
        return
    }

    val config = AgentConfig.fromEnv()
    val egoModel = System.getenv("MISTRAL_EGO_MODEL") ?: MistralChatClient.DEFAULT_MODEL
    val superegoModel = System.getenv("MISTRAL_SUPEREGO_MODEL") ?: egoModel

    MetricsRuntimeFactory.create(
        apiKey = apiKey,
        egoModel = egoModel,
        superegoModel = superegoModel
    ).use { metrics ->
        MistralChatClient(
            apiKey = apiKey,
            modelName = egoModel,
            callObserver = metrics.chatCallObserver(provider = "mistral")
        ).use { egoClient ->
            MistralChatClient(
                apiKey = apiKey,
                modelName = superegoModel,
                callObserver = metrics.chatCallObserver(provider = "mistral")
            ).use { superegoClient ->
                logger.info { "Ego model=$egoModel Superego model=$superegoModel" }

                val planner = EgoPlanner(egoClient, config)
                val gatekeeper = SuperegoGatekeeper(superegoClient, config)
                val webSearchProvider = MistralWebSearchProvider(egoClient, config)
                val motorCortex = MotorCortex(webSearchProvider)

                EgoAgent(
                    planner = planner,
                    superego = gatekeeper,
                    motorCortex = motorCortex,
                    config = config,
                    onActionDenied = metrics::recordDeniedAction
                ).runInteractive()
            }
        }
    }
}
