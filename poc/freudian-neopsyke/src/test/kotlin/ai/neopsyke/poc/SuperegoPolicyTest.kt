package ai.neopsyke.poc

import ai.neopsyke.poc.config.SuperegoConfig
import ai.neopsyke.poc.model.ActionProposal
import ai.neopsyke.poc.model.ActionType
import ai.neopsyke.poc.model.OriginSource
import ai.neopsyke.poc.superego.DeterministicSuperego
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SuperegoPolicyTest {

    @Test
    fun `id-origin contact user denied when policy disallows it`() {
        val superego = DeterministicSuperego(
            SuperegoConfig(
                allowIdContactUser = false,
                allowedIdActionTypes = setOf("WEB_SEARCH", "REFLECT_INTERNAL")
            )
        )

        val decision = superego.review(
            ActionProposal(
                actionId = "action-1",
                rootImpulseId = "impulse-1",
                needName = "interact_with_user",
                origin = OriginSource.ID,
                type = ActionType.CONTACT_USER,
                summary = "proactive contact",
                payload = "hello",
            )
        )

        assertFalse(decision.allow)
    }

    @Test
    fun `id-origin allowlisted action is allowed`() {
        val superego = DeterministicSuperego(
            SuperegoConfig(
                allowIdContactUser = false,
                allowedIdActionTypes = setOf("WEB_SEARCH", "REFLECT_INTERNAL")
            )
        )

        val decision = superego.review(
            ActionProposal(
                actionId = "action-2",
                rootImpulseId = "impulse-2",
                needName = "learn_something",
                origin = OriginSource.ID,
                type = ActionType.WEB_SEARCH,
                summary = "search",
                payload = "query",
            )
        )

        assertTrue(decision.allow)
    }
}
