package ai.neopsyke.agent.ego

import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.model.EgoDecision
import ai.neopsyke.agent.model.EgoTrigger
import ai.neopsyke.agent.model.PlannerContext
import ai.neopsyke.instrumentation.AgentInstrumentation
import ai.neopsyke.instrumentation.NoopAgentInstrumentation
import ai.neopsyke.llm.ChatModelClient
import ai.neopsyke.support.buildTestHierarchicalPlanner

/**
 * Test-only shim: creates a HierarchicalEgoPlanner with the same constructor
 * signature as the deleted production LlmEgoPlanner. This allows existing test
 * code to compile and run against the new planner hierarchy transparently.
 */
class LlmEgoPlanner(
    modelClient: ChatModelClient,
    config: AgentConfig = AgentConfig(),
    instrumentation: AgentInstrumentation = NoopAgentInstrumentation,
    onPlannerOutputRepaired: () -> Unit = {},
) : Ego.Planner {
    private val delegate: Ego.Planner = buildTestHierarchicalPlanner(
        modelClient = modelClient,
        config = config,
        instrumentation = instrumentation,
        onPlannerOutputRepaired = onPlannerOutputRepaired,
    )

    override fun decide(trigger: EgoTrigger, context: PlannerContext): EgoDecision =
        delegate.decide(trigger, context)

    override fun resetForInput(rootInputId: String) =
        delegate.resetForInput(rootInputId)
}
