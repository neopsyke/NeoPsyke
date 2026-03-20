package ai.neopsyke.instrumentation

data class PhaseTiming(
    val phaseName: String,
    val durationMs: Long,
)

data class TaskPhaseTimings(
    val taskType: String,
    val rootInputId: String?,
    val totalDurationMs: Long,
    val phases: List<PhaseTiming>,
    val timestampMs: Long = System.currentTimeMillis(),
)

class PhaseTimingCollector(
    private val taskType: String,
    private val rootInputId: String?,
) {
    private val startMs = System.currentTimeMillis()
    private val phases = mutableListOf<PhaseTiming>()
    private var phaseStartMs: Long = startMs
    private var currentPhase: String? = null

    fun startPhase(name: String) {
        endCurrentPhase()
        currentPhase = name
        phaseStartMs = System.currentTimeMillis()
    }

    fun endCurrentPhase() {
        val phase = currentPhase ?: return
        val elapsed = System.currentTimeMillis() - phaseStartMs
        phases.add(PhaseTiming(phase, elapsed))
        currentPhase = null
    }

    fun build(): TaskPhaseTimings {
        endCurrentPhase()
        return TaskPhaseTimings(
            taskType = taskType,
            rootInputId = rootInputId,
            totalDurationMs = System.currentTimeMillis() - startMs,
            phases = phases.toList(),
        )
    }
}
