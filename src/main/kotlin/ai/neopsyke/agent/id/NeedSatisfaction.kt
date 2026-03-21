package ai.neopsyke.agent.id

import ai.neopsyke.agent.model.ActionEffect

data class NeedSatisfactionVerdict(
    val satisfied: Boolean,
    val requiredEffects: Set<ActionEffect>,
    val observedEffects: Set<ActionEffect>,
) {
    val matchedEffects: Set<ActionEffect>
        get() = requiredEffects.intersect(observedEffects)
}

fun NeedConfig.evaluateSatisfaction(observedEffects: Set<ActionEffect>): NeedSatisfactionVerdict =
    NeedSatisfactionVerdict(
        satisfied = satisfactionEffectsAnyOf.any { it in observedEffects },
        requiredEffects = satisfactionEffectsAnyOf,
        observedEffects = observedEffects,
    )
