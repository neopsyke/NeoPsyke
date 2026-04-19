package ai.neopsyke.agent.assignments

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlanStepDependencyTest {

    @Test
    fun `step with no requires is immediately eligible`() {
        val step = step("s1", requires = emptySet(), produces = setOf("key-a"))
        assertTrue(isStepReady(step, producedKeys = emptySet(), completedStepIds = emptySet()))
    }

    @Test
    fun `step requiring a produced key becomes ready when key exists`() {
        val step = step("s2", requires = setOf("key-a"))
        assertFalse(isStepReady(step, producedKeys = emptySet(), completedStepIds = emptySet()))
        assertTrue(isStepReady(step, producedKeys = setOf("key-a"), completedStepIds = emptySet()))
    }

    @Test
    fun `step requiring a step ID becomes ready when that step is done`() {
        val step = step("s3", requires = setOf("s1"))
        assertFalse(isStepReady(step, producedKeys = emptySet(), completedStepIds = emptySet()))
        assertTrue(isStepReady(step, producedKeys = emptySet(), completedStepIds = setOf("s1")))
    }

    @Test
    fun `step requiring both key and step ID needs both`() {
        val step = step("s4", requires = setOf("s1", "key-x"))
        assertFalse(isStepReady(step, producedKeys = emptySet(), completedStepIds = setOf("s1")))
        assertFalse(isStepReady(step, producedKeys = setOf("key-x"), completedStepIds = emptySet()))
        assertTrue(isStepReady(step, producedKeys = setOf("key-x"), completedStepIds = setOf("s1")))
    }

    @Test
    fun `step lifecycle transitions follow expected order`() {
        assertEquals(StepStatus.PENDING, StepStatus.entries.first())
        assertEquals(StepStatus.SKIPPED, StepStatus.entries.last())
    }

    @Test
    fun `assignment plan empty factory creates valid empty plan`() {
        val plan = WorkItemPlan.empty()
        assertTrue(plan.steps.isEmpty())
        assertEquals(Instant.EPOCH, plan.generatedAt)
    }

    @Test
    fun `assignment status enum covers all lifecycle states`() {
        val expected = setOf(
            "CREATED",
            "PLANNING",
            "ACTIVE",
            "BLOCKED",
            "SUSPENDED",
            "COMPLETED",
            "FAILED",
            "STALLED",
            "NEEDS_ATTENTION",
            "RETIRED",
        )
        assertEquals(expected, WorkItemStatus.entries.map { it.name }.toSet())
    }

    @Test
    fun `wait condition types cover all expected kinds`() {
        val expected = setOf("TIMER", "EXTERNAL_EVENT", "CONDITION_CHECK", "CRON", "ASYNC_OPERATION")
        assertEquals(expected, WaitConditionType.entries.map { it.name }.toSet())
    }

    @Test
    fun `tier1 summary captures essential assignment state`() {
        val summary = WorkItemTier1Summary(
            workItemId = "proj-1",
            title = "Test Assignment",
            status = WorkItemStatus.ACTIVE,
            priority = WorkItemPriority.HIGH,
            currentStepDescription = "Running tests",
            blockers = emptyList(),
            lastWorkedAt = Instant.now(),
        )
        assertEquals("proj-1", summary.workItemId)
        assertEquals(WorkItemStatus.ACTIVE, summary.status)
        assertTrue(summary.blockers.isEmpty())
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun step(
        id: String,
        requires: Set<String> = emptySet(),
        produces: Set<String> = emptySet(),
    ) = PlanStep(
        id = id,
        description = "step $id",
        status = StepStatus.PENDING,
        acceptanceCriteria = "verify step $id",
        requires = requires,
        produces = produces,
    )

    /**
     * Pure dependency resolution: a PENDING step becomes READY when all its
     * `requires` entries are satisfied — either as a completed step ID or
     * a produced state key.
     */
    private fun isStepReady(
        step: PlanStep,
        producedKeys: Set<String>,
        completedStepIds: Set<String>,
    ): Boolean = step.requires.all { req ->
        req in completedStepIds || req in producedKeys
    }
}
