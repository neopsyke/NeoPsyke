package ai.neopsyke.poc

import ai.neopsyke.poc.config.IdConfig
import ai.neopsyke.poc.config.NeedConfig
import ai.neopsyke.poc.id.IdModule
import ai.neopsyke.poc.instrumentation.InMemoryEventLogger
import ai.neopsyke.poc.model.ImpulseFeedback
import ai.neopsyke.poc.model.ImpulseResult
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class IdModuleTest {

    @Test
    fun `module does not emit second impulse while first is pending`() {
        val eventLogger = InMemoryEventLogger()
        val idModule = IdModule(
            config = IdConfig(
                enabled = true,
                triggerThreshold = 0.1,
                triggerProbability = 1.0,
                maxConsecutiveDenials = 3,
                backoffTicks = 2,
                needs = mapOf(
                    "be_useful" to NeedConfig(growthRate = 0.2, impulseMessage = "be useful")
                )
            ),
            random = Random(1),
            eventLogger = eventLogger,
        )

        val firstImpulse = idModule.tick(tick = 0)
        assertNotNull(firstImpulse)

        // Should be blocked by pending lifecycle gate even if threshold is met again.
        val blockedImpulse = idModule.tick(tick = 1)
        assertNull(blockedImpulse)

        idModule.onImpulseFeedback(
            tick = 1,
            feedback = ImpulseFeedback(
                rootImpulseId = firstImpulse.rootImpulseId,
                needName = firstImpulse.needName,
                result = ImpulseResult.DENIED,
            )
        )

        val secondImpulse = idModule.tick(tick = 2)
        assertNotNull(secondImpulse)
    }

    @Test
    fun `max consecutive denials triggers backoff`() {
        val eventLogger = InMemoryEventLogger()
        val idModule = IdModule(
            config = IdConfig(
                enabled = true,
                triggerThreshold = 0.1,
                triggerProbability = 1.0,
                maxConsecutiveDenials = 2,
                backoffTicks = 3,
                needs = mapOf(
                    "be_useful" to NeedConfig(growthRate = 0.4, impulseMessage = "be useful")
                )
            ),
            random = Random(2),
            eventLogger = eventLogger,
        )

        val impulseOne = idModule.tick(0)
        assertNotNull(impulseOne)
        idModule.onImpulseFeedback(0, ImpulseFeedback(impulseOne.rootImpulseId, impulseOne.needName, ImpulseResult.DENIED))

        val impulseTwo = idModule.tick(1)
        assertNotNull(impulseTwo)
        idModule.onImpulseFeedback(1, ImpulseFeedback(impulseTwo.rootImpulseId, impulseTwo.needName, ImpulseResult.DENIED))

        // Backoff should block immediate next emission.
        val blocked = idModule.tick(2)
        assertNull(blocked)

        // After backoff expires, impulse can be emitted again.
        var recovered = idModule.tick(3)
        var tick = 4
        while (recovered == null && tick <= 12) {
            recovered = idModule.tick(tick)
            tick += 1
        }
        assertNotNull(recovered)
    }
}
