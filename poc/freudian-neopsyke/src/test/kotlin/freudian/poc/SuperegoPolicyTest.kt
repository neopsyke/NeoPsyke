package freudian.poc

import freudian.poc.config.SuperegoConfig
import freudian.poc.model.ActionProposal
import freudian.poc.model.ActionType
import freudian.poc.model.OriginSource
import freudian.poc.superego.DeterministicSuperego
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SuperegoPolicyTest {

    @Test
    fun `id-origin user message denied when policy disallows it`() {
        val superego = DeterministicSuperego(
            SuperegoConfig(
                allowIdUserMessages = false,
                allowedIdActionTypes = setOf("WEB_LOOKUP", "INTERNAL_REFLECTION")
            )
        )

        val decision = superego.review(
            ActionProposal(
                actionId = "action-1",
                rootImpulseId = "impulse-1",
                needName = "interact_with_user",
                origin = OriginSource.ID,
                type = ActionType.USER_MESSAGE,
                summary = "proactive message",
                payload = "hello",
            )
        )

        assertFalse(decision.allow)
    }

    @Test
    fun `id-origin allowlisted action is allowed`() {
        val superego = DeterministicSuperego(
            SuperegoConfig(
                allowIdUserMessages = false,
                allowedIdActionTypes = setOf("WEB_LOOKUP", "INTERNAL_REFLECTION")
            )
        )

        val decision = superego.review(
            ActionProposal(
                actionId = "action-2",
                rootImpulseId = "impulse-2",
                needName = "learn_something",
                origin = OriginSource.ID,
                type = ActionType.WEB_LOOKUP,
                summary = "lookup",
                payload = "query",
            )
        )

        assertTrue(decision.allow)
    }
}
