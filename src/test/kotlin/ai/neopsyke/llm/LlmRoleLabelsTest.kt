package ai.neopsyke.llm

import kotlin.test.Test
import kotlin.test.assertEquals

class LlmRoleLabelsTest {

    @Test
    fun `grounding classifier call site is classified separately from planner traffic`() {
        assertEquals(
            LlmRoleLabels.GROUNDING_CLASSIFIER,
            LlmRoleLabels.classify(
                actor = "ego",
                callSite = "grounding_classifier",
                actionType = null,
            )
        )
    }
}
