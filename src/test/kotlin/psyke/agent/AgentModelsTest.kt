package psyke.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentModelsTest {
    @Test
    fun `urgency parser maps known values and defaults to medium`() {
        assertEquals(Urgency.HIGH, Urgency.fromRaw("high"))
        assertEquals(Urgency.LOW, Urgency.fromRaw("LOW"))
        assertEquals(Urgency.MEDIUM, Urgency.fromRaw("unknown"))
        assertEquals(Urgency.MEDIUM, Urgency.fromRaw(null))
    }

    @Test
    fun `action type parser maps valid values only`() {
        assertEquals(ActionType.WEB_SEARCH, ActionType.fromRaw("web_search"))
        assertEquals(ActionType.ANSWER, ActionType.fromRaw("answer"))
        assertNull(ActionType.fromRaw("search"))
        assertNull(ActionType.fromRaw(null))
    }
}
