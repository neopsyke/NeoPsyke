package ai.neopsyke

import ai.neopsyke.agent.model.PrincipalRole
import ai.neopsyke.agent.model.InstructionTrust
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class AppModeRunnersShutdownCloseGuardTest {
    @Test
    fun `freud live conversation context is owner trusted`() {
        val context = AppModeRunners.freudLiveConversationContext()

        assertEquals("freud-live", context.sessionId)
        assertEquals("freud-live-user", context.interlocutor.id)
        assertEquals(PrincipalRole.OWNER, context.security.principal.role)
        assertEquals(InstructionTrust.TRUSTED_INSTRUCTION, context.security.instructionTrust)
        assertEquals("freud-live", context.security.channel.provider)
        assertEquals("freud-live", context.security.channel.channelId)
    }

    @Test
    fun `shutdown guard closes registered resources exactly once`() {
        val first = CountingCloseable()
        val second = CountingCloseable()

        val guard = AppModeRunners.ShutdownCloseGuard("test")
        guard.register(first)
        guard.register(second)

        guard.close()
        guard.close()

        assertEquals(1, first.closeCount.get())
        assertEquals(1, second.closeCount.get())
    }

    @Test
    fun `shutdown guard closes resources registered after closure immediately`() {
        val late = CountingCloseable()

        val guard = AppModeRunners.ShutdownCloseGuard("late-test")
        guard.close()
        guard.register(late)

        assertEquals(1, late.closeCount.get())
    }

    private class CountingCloseable : AutoCloseable {
        val closeCount = AtomicInteger(0)

        override fun close() {
            closeCount.incrementAndGet()
        }
    }
}
