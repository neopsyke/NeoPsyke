package ai.neopsyke.instrumentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhaseTimingCollectorTest {
    @Test
    fun `build returns empty phases when none started`() {
        val collector = PhaseTimingCollector("input", "root-1")
        val result = collector.build()
        assertEquals("input", result.taskType)
        assertEquals("root-1", result.rootInputId)
        assertTrue(result.phases.isEmpty())
        assertTrue(result.totalDurationMs >= 0)
    }

    @Test
    fun `phases accumulate in order`() {
        val collector = PhaseTimingCollector("thought", null)
        collector.startPhase("alpha")
        Thread.sleep(5)
        collector.startPhase("beta")
        Thread.sleep(5)
        val result = collector.build()
        assertEquals("thought", result.taskType)
        assertEquals(null, result.rootInputId)
        assertEquals(2, result.phases.size)
        assertEquals("alpha", result.phases[0].phaseName)
        assertEquals("beta", result.phases[1].phaseName)
        assertTrue(result.phases[0].durationMs >= 0)
        assertTrue(result.phases[1].durationMs >= 0)
    }

    @Test
    fun `startPhase auto-ends current phase`() {
        val collector = PhaseTimingCollector("action", "root-2")
        collector.startPhase("first")
        collector.startPhase("second")
        collector.startPhase("third")
        val result = collector.build()
        assertEquals(3, result.phases.size)
        assertEquals(listOf("first", "second", "third"), result.phases.map { it.phaseName })
    }

    @Test
    fun `endCurrentPhase is idempotent when no phase active`() {
        val collector = PhaseTimingCollector("input", null)
        collector.endCurrentPhase()
        collector.endCurrentPhase()
        val result = collector.build()
        assertTrue(result.phases.isEmpty())
    }

    @Test
    fun `build ends current phase before returning`() {
        val collector = PhaseTimingCollector("input", "root-3")
        collector.startPhase("only")
        val result = collector.build()
        assertEquals(1, result.phases.size)
        assertEquals("only", result.phases[0].phaseName)
    }

    @Test
    fun `totalDurationMs covers full collector lifetime`() {
        val collector = PhaseTimingCollector("input", null)
        collector.startPhase("a")
        Thread.sleep(10)
        collector.endCurrentPhase()
        Thread.sleep(10)
        val result = collector.build()
        assertTrue(result.totalDurationMs >= 15)
        assertEquals(1, result.phases.size)
    }
}
