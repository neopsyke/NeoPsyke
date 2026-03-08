package psyke.agent

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SuperegoDirectivesTest {
    @Test
    fun `for action includes general and action specific directives`() {
        val directives = SuperegoPolicy.forAction(ActionType.MCP_FETCH)

        assertTrue(directives.general.isNotEmpty())
        assertTrue(directives.actionSpecific.isNotEmpty())
        assertTrue(directives.all.size >= directives.general.size)
    }

    @Test
    fun `action specific directives differ by action type`() {
        val answer = SuperegoPolicy.forAction(ActionType.ANSWER).actionSpecific
        val fetch = SuperegoPolicy.forAction(ActionType.MCP_FETCH).actionSpecific

        assertTrue(answer != fetch)
        assertTrue(answer.any { it.contains("ANSWER", ignoreCase = true) })
        assertTrue(fetch.any { it.contains("MCP_FETCH", ignoreCase = true) })
    }

    @Test
    fun `all directives contains deduplicated union`() {
        val all = SuperegoPolicy.allDirectives()
        val expected = ActionType.entries
            .flatMap { SuperegoPolicy.forAction(it).all }
            .distinct()

        assertTrue(all == expected)
        assertTrue(all.isNotEmpty())
    }

    @Test
    fun `general directives are always included`() {
        val general = SuperegoPolicy.GENERAL_DIRECTIVES
        assertFalse(general.any { it.contains("redundant or low-value", ignoreCase = true) })
        ActionType.entries.forEach { actionType ->
            val all = SuperegoPolicy.forAction(actionType).all
            general.forEach { directive ->
                assertTrue(
                    all.contains(directive),
                    "Expected general directive missing for action=$actionType"
                )
            }
        }
    }

    @Test
    fun `mcp fetch includes url safety directive`() {
        val fetch = SuperegoPolicy.forAction(ActionType.MCP_FETCH).actionSpecific
        assertTrue(
            fetch.any { it.contains("HTTPS", ignoreCase = true) }
        )
    }
}
