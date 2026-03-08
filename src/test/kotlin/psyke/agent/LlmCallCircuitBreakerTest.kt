package psyke.agent

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import psyke.agent.support.LlmCallCircuitBreaker
import psyke.agent.support.OnTripBehavior

class LlmCallCircuitBreakerTest {

    @Test
    fun `initially not tripped`() {
        val cb = LlmCallCircuitBreaker(tripThreshold = 2, onTripBehavior = OnTripBehavior.BYPASS)
        assertFalse(cb.isTripped())
        assertEquals(0, cb.streak())
    }

    @Test
    fun `trips after reaching threshold`() {
        val cb = LlmCallCircuitBreaker(tripThreshold = 2, onTripBehavior = OnTripBehavior.BYPASS)
        assertFalse(cb.recordParseFailure())
        assertTrue(cb.recordParseFailure())
        assertTrue(cb.isTripped())
        assertEquals(2, cb.streak())
    }

    @Test
    fun `success resets streak`() {
        val cb = LlmCallCircuitBreaker(tripThreshold = 3, onTripBehavior = OnTripBehavior.BYPASS)
        cb.recordParseFailure()
        cb.recordParseFailure()
        assertEquals(2, cb.streak())
        cb.recordSuccess()
        assertEquals(0, cb.streak())
        assertFalse(cb.isTripped())
    }

    @Test
    fun `reset clears streak`() {
        val cb = LlmCallCircuitBreaker(tripThreshold = 2, onTripBehavior = OnTripBehavior.DISABLE)
        cb.recordParseFailure()
        cb.recordParseFailure()
        assertTrue(cb.isTripped())
        cb.reset()
        assertFalse(cb.isTripped())
        assertEquals(0, cb.streak())
    }

    @Test
    fun `exposes onTripBehavior for caller decisions`() {
        val bypass = LlmCallCircuitBreaker(tripThreshold = 1, onTripBehavior = OnTripBehavior.BYPASS)
        val allow = LlmCallCircuitBreaker(tripThreshold = 1, onTripBehavior = OnTripBehavior.ALLOW)
        val disable = LlmCallCircuitBreaker(tripThreshold = 1, onTripBehavior = OnTripBehavior.DISABLE)

        assertEquals(OnTripBehavior.BYPASS, bypass.onTripBehavior)
        assertEquals(OnTripBehavior.ALLOW, allow.onTripBehavior)
        assertEquals(OnTripBehavior.DISABLE, disable.onTripBehavior)
    }

    @Test
    fun `stays tripped on further failures after threshold`() {
        val cb = LlmCallCircuitBreaker(tripThreshold = 2, onTripBehavior = OnTripBehavior.BYPASS)
        cb.recordParseFailure()
        cb.recordParseFailure()
        assertTrue(cb.isTripped())
        assertTrue(cb.recordParseFailure())
        assertEquals(3, cb.streak())
    }

    @Test
    fun `generic failure increments streak`() {
        val cb = LlmCallCircuitBreaker(tripThreshold = 2, onTripBehavior = OnTripBehavior.BYPASS)
        assertFalse(cb.recordFailure())
        assertTrue(cb.recordFailure())
        assertEquals(2, cb.streak())
    }
}
