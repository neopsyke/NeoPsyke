package freudian.poc

import freudian.poc.ego.ImpulseLifecycleTracker
import freudian.poc.model.ImpulseResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ImpulseLifecycleTrackerTest {

    @Test
    fun `noop branch does not deny while another branch is pending`() {
        val tracker = ImpulseLifecycleTracker()
        val rootImpulseId = "root-1"
        tracker.start(rootImpulseId = rootImpulseId, needName = "be_useful", initialThoughtCount = 2)

        // First branch finishes with no action.
        val firstCompletion = tracker.completeThought(rootImpulseId)
        assertNull(firstCompletion, "Lifecycle should remain open because one branch is still pending")

        tracker.registerAction(rootImpulseId)
        tracker.completeThought(rootImpulseId)

        // Finalize after action branch completes.
        val finalFeedback = tracker.completeAction(rootImpulseId, executed = true)
        assertNotNull(finalFeedback)
        assertEquals(ImpulseResult.ACCEPTED, finalFeedback.result)
    }

    @Test
    fun `all branches completed without executed action returns denied`() {
        val tracker = ImpulseLifecycleTracker()
        val rootImpulseId = "root-2"
        tracker.start(rootImpulseId = rootImpulseId, needName = "learn_something", initialThoughtCount = 2)

        assertNull(tracker.completeThought(rootImpulseId))
        val feedback = tracker.completeThought(rootImpulseId)

        assertNotNull(feedback)
        assertEquals(ImpulseResult.DENIED, feedback.result)
    }
}
