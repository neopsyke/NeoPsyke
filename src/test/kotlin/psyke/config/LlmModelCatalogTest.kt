package psyke.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class LlmModelCatalogTest {
    @Test
    fun `cheapestProfileForProvider returns cheapest distinct model`() {
        val catalog = LlmModelCatalog.defaults()

        val selected = catalog.cheapestProfileForProvider(
            provider = LlmProvider.OPENAI,
            excludingModel = "gpt-5-mini"
        )

        val profile = assertNotNull(selected)
        assertEquals("gpt-5-nano", profile.model)
    }
}

