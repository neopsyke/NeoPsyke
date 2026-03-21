package freudian.poc.cortex

import freudian.poc.model.UserRequest

interface SensoryCortex {
    fun pollUserRequests(tick: Int): List<UserRequest>
}

class ScriptedSensoryCortex(
    scheduledRequests: List<Pair<Int, String>>,
) : SensoryCortex {
    private val requestsByTick: Map<Int, List<UserRequest>> = scheduledRequests
        .groupBy(
            keySelector = { it.first },
            valueTransform = { UserRequest(content = it.second) }
        )

    override fun pollUserRequests(tick: Int): List<UserRequest> =
        requestsByTick[tick].orEmpty()
}
