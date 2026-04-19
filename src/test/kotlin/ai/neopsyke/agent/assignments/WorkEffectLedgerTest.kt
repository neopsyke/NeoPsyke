package ai.neopsyke.agent.assignments

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkEffectLedgerTest {

    @Test
    fun `confirmed effect is idempotent across restarts`() {
        val root = Files.createTempDirectory("psyke-work-effect-ledger")
        try {
            val ledgerPath = root.resolve("effect-ledger.json")
            val ledger = WorkEffectLedger(ledgerPath)
            val effectIntentId = ledger.deriveEffectIntentId(
                workItemId = "work-1",
                planRevision = 2,
                stepId = "step-1",
                logicalEffectKey = "contact_user",
            )

            assertTrue(
                ledger.recordIntent(
                    effectIntentId = effectIntentId,
                    actionType = "contact_user",
                    effectClass = EffectClass.EXTERNAL_MUTATING,
                )
            )
            ledger.confirmEffect(effectIntentId)
            assertTrue(ledger.isEffectCompleted(effectIntentId))

            val reloaded = WorkEffectLedger(ledgerPath)
            reloaded.load()
            assertTrue(reloaded.isEffectCompleted(effectIntentId))
            assertFalse(
                reloaded.recordIntent(
                    effectIntentId = effectIntentId,
                    actionType = "contact_user",
                    effectClass = EffectClass.EXTERNAL_MUTATING,
                )
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `recovery reconciliation marks pending intents as uncertain`() {
        val root = Files.createTempDirectory("psyke-work-effect-ledger-reconcile")
        try {
            val ledger = WorkEffectLedger(root.resolve("effect-ledger.json"))
            val pendingId = ledger.deriveEffectIntentId(
                workItemId = "work-2",
                planRevision = 1,
                stepId = "step-1",
                logicalEffectKey = "contact_user",
            )
            assertTrue(
                ledger.recordIntent(
                    effectIntentId = pendingId,
                    actionType = "contact_user",
                    effectClass = EffectClass.EXTERNAL_MUTATING,
                )
            )

            val reconciled = ledger.reconcileOnRecovery()
            assertEquals(listOf(pendingId), reconciled)
            assertFalse(ledger.isEffectCompleted(pendingId))
            assertTrue(ledger.reconcileOnRecovery().isEmpty())
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
