package ai.neopsyke.agent.assignments

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Ledger for tracking side-effect intents within assignment activations.
 *
 * ## effectIntentId contract
 *
 * `effectIntentId = workItemId + planRevision + stepId + logicalEffectKey`
 *
 * The `logicalEffectKey` must be stable across retries for the same logical
 * side effect. Mutating action families must either provide a stable key or
 * declare that they support only staged/manual-review handling.
 *
 * ## Phase-1 policy
 *
 * - Observe actions may retry freely.
 * - Internal stateful actions may retry only with the same effectIntentId.
 * - External mutating actions must either use an integration-level idempotency
 *   key or degrade to staged/manual-review semantics.
 */
class WorkEffectLedger(private val path: Path) {

    private val mapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    private val entries = mutableMapOf<String, EffectIntentEntry>()

    fun load() {
        if (!Files.exists(path)) return
        try {
            val loaded = mapper.readValue<List<EffectIntentEntry>>(Files.readString(path))
            for (entry in loaded) {
                entries[entry.effectIntentId] = entry
            }
        } catch (e: Exception) {
            logger.warn { "Failed to load effect ledger: ${e.message}" }
        }
    }

    fun save() {
        Files.createDirectories(path.parent)
        Files.writeString(
            path,
            mapper.writerWithDefaultPrettyPrinter().writeValueAsString(entries.values.toList()),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
    }

    /**
     * Derive the effectIntentId from its components.
     */
    fun deriveEffectIntentId(
        workItemId: String,
        planRevision: Int,
        stepId: String,
        logicalEffectKey: String,
    ): String = "$workItemId:$planRevision:$stepId:$logicalEffectKey"

    /**
     * Record a new effect intent. Returns false if this logical effect has
     * already been completed (idempotency guard).
     */
    fun recordIntent(
        effectIntentId: String,
        actionType: String,
        effectClass: EffectClass,
    ): Boolean {
        val existing = entries[effectIntentId]
        if (existing != null && existing.status == EffectStatus.CONFIRMED) {
            logger.info { "Effect already confirmed, skipping: $effectIntentId" }
            return false
        }
        entries[effectIntentId] = EffectIntentEntry(
            effectIntentId = effectIntentId,
            actionType = actionType,
            effectClass = effectClass,
            status = EffectStatus.PENDING,
            recordedAt = Instant.now(),
        )
        save()
        return true
    }

    /**
     * Confirm that an effect was successfully executed.
     */
    fun confirmEffect(effectIntentId: String) {
        val entry = entries[effectIntentId] ?: return
        entries[effectIntentId] = entry.copy(
            status = EffectStatus.CONFIRMED,
            confirmedAt = Instant.now(),
        )
        save()
    }

    /**
     * Mark an effect as abandoned (e.g., step failed or was skipped).
     */
    fun abandonEffect(effectIntentId: String, reason: String) {
        val entry = entries[effectIntentId] ?: return
        entries[effectIntentId] = entry.copy(
            status = EffectStatus.ABANDONED,
            abandonedReason = reason,
        )
        save()
    }

    /**
     * Mark an effect as uncertain (e.g., crash between intent and confirmation).
     */
    fun markUncertain(effectIntentId: String, reason: String) {
        val entry = entries[effectIntentId] ?: return
        entries[effectIntentId] = entry.copy(
            status = EffectStatus.UNCERTAIN,
            abandonedReason = reason,
        )
        save()
    }

    /**
     * Check if this logical effect has already been completed.
     */
    fun isEffectCompleted(effectIntentId: String): Boolean =
        entries[effectIntentId]?.status == EffectStatus.CONFIRMED

    /**
     * Recovery-time reconciliation: mark all pending effects as uncertain.
     */
    fun reconcileOnRecovery(): List<String> {
        var reconciled = 0
        val reconciledIds = mutableListOf<String>()
        for ((id, entry) in entries) {
            if (entry.status == EffectStatus.PENDING) {
                entries[id] = entry.copy(
                    status = EffectStatus.UNCERTAIN,
                    abandonedReason = "recovery_reconciliation",
                )
                reconciledIds += id
                reconciled++
            }
        }
        if (reconciled > 0) {
            logger.info { "Reconciled $reconciled pending effects to uncertain on recovery" }
            save()
        }
        return reconciledIds
    }

    /**
     * Determine if an action with the given effect class needs staged handling
     * in the assignment context.
     */
    fun requiresStagedHandling(effectClass: EffectClass): Boolean =
        effectClass == EffectClass.EXTERNAL_MUTATING
}

data class EffectIntentEntry(
    val effectIntentId: String,
    val actionType: String,
    val effectClass: EffectClass,
    val status: EffectStatus,
    val recordedAt: Instant = Instant.now(),
    val confirmedAt: Instant? = null,
    val abandonedReason: String? = null,
)

enum class EffectStatus {
    PENDING,
    CONFIRMED,
    ABANDONED,
    UNCERTAIN,
}

enum class EffectClass {
    OBSERVE,
    INTERNAL_STATEFUL,
    EXTERNAL_MUTATING,
}
