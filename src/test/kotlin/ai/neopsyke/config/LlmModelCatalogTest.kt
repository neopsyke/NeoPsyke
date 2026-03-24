package ai.neopsyke.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class LlmModelCatalogTest {
    @Test
    fun `cheapestProfileForProvider returns cheapest distinct model`() {
        val catalog = LlmModelCatalog(
            byProvider = mapOf(
                LlmProvider.OPENAI to listOf(
                    LlmModelProfile("gpt-5", LlmModelTier.MEDIUM_HIGH, tokenWeight = 2.60),
                    LlmModelProfile("gpt-5-mini", LlmModelTier.MEDIUM, tokenWeight = 1.35),
                    LlmModelProfile("gpt-5-nano", LlmModelTier.LIGHT, tokenWeight = 0.85),
                )
            )
        )

        val selected = catalog.cheapestProfileForProvider(
            provider = LlmProvider.OPENAI,
            excludingModel = "gpt-5-mini"
        )

        val profile = assertNotNull(selected)
        assertEquals("gpt-5-nano", profile.model)
    }
}
