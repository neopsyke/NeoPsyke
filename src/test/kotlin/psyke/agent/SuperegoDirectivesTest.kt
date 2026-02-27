package psyke.agent

import kotlin.test.Test
import kotlin.test.assertEquals

class SuperegoDirectivesTest {
    @Test
    fun `load parses one directive per line and ignores comments and blanks`() {
        val directives = SuperegoDirectives.load("/superego/test-directives.txt")

        assertEquals(
            listOf(
                "No slurs or hate speech.",
                "No threats or harassment."
            ),
            directives
        )
    }

    @Test
    fun `load falls back to defaults when resource is empty`() {
        val directives = SuperegoDirectives.load("/superego/empty-directives.txt")

        assertEquals(SuperegoGatekeeper.DEFAULT_DIRECTIVES, directives)
    }

    @Test
    fun `load falls back to defaults when resource is missing`() {
        val directives = SuperegoDirectives.load("/superego/missing-directives.txt")

        assertEquals(SuperegoGatekeeper.DEFAULT_DIRECTIVES, directives)
    }
}
