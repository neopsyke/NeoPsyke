package psyke

import mu.KotlinLogging
import psyke.agent.AgentConfig
import psyke.agent.EgoAgent
import psyke.agent.EgoPlanner
import psyke.agent.MistralWebSearchProvider
import psyke.agent.MotorCortex
import psyke.agent.SuperegoGatekeeper
import psyke.llm.MistralChatClient

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

    MistralChatClient(apiKey = apiKey, modelName = egoModel).use { egoClient ->
        MistralChatClient(apiKey = apiKey, modelName = superegoModel).use { superegoClient ->
            logger.info { "Ego model=$egoModel Superego model=$superegoModel" }

            val planner = EgoPlanner(egoClient, config)
            val gatekeeper = SuperegoGatekeeper(superegoClient, config)
            val webSearchProvider = MistralWebSearchProvider(egoClient, config)
            val motorCortex = MotorCortex(webSearchProvider)

            EgoAgent(
                planner = planner,
                superego = gatekeeper,
                motorCortex = motorCortex,
                config = config
            ).runInteractive()
        }
    }
}
