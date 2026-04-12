package ai.neopsyke.agent.durablework

import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.model.GroundingRequirement
import ai.neopsyke.support.StubChatModelClient
import java.nio.file.Paths
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class LlmWorkPlanBuilderGroundingRequirementTest {

    @Test
    fun `goal planner maps grounding_requirement per step`() {
        val llm = StubChatModelClient()
        llm.enqueueRawResponse(
            """
            {
              "steps": [
                {
                  "id": "step-a",
                  "description": "Check today's weather",
                  "acceptance_criteria": "Fresh source cited",
                  "grounding_requirement": "required"
                },
                {
                  "id": "step-b",
                  "description": "Summarize findings",
                  "acceptance_criteria": "Concise answer",
                  "grounding_requirement": "not_required"
                }
              ]
            }
            """.trimIndent()
        )
        val planner = LlmWorkPlanBuilder(modelClient = llm, config = AgentConfig())

        val plan = planner.generatePlan(workItem())

        assertEquals(2, plan.steps.size)
        assertEquals(GroundingRequirement.REQUIRED, plan.steps[0].groundingRequirement)
        assertEquals(GroundingRequirement.NOT_REQUIRED, plan.steps[1].groundingRequirement)
    }

    @Test
    fun `unknown grounding_requirement falls back to not_required`() {
        val llm = StubChatModelClient()
        llm.enqueueRawResponse(
            """
            {
              "steps": [
                {
                  "description": "Check weather",
                  "acceptance_criteria": "Has answer",
                  "grounding_requirement": "sometimes"
                }
              ]
            }
            """.trimIndent()
        )
        val planner = LlmWorkPlanBuilder(modelClient = llm, config = AgentConfig())

        val plan = planner.generatePlan(workItem())

        assertEquals(1, plan.steps.size)
        assertEquals(GroundingRequirement.NOT_REQUIRED, plan.steps[0].groundingRequirement)
    }

    private fun workItem(): WorkItem =
        WorkItem(
            id = "g-1",
            title = "Weather briefing",
            instruction = "Tell me today's weather and summarize it.",
            status = WorkItemStatus.ACTIVE,
            priority = WorkItemPriority.MEDIUM,
            plan = WorkItemPlan.empty(),
            completionCriteria = "User gets a clear answer",
            createdAt = Instant.now(),
            workspacePath = Paths.get("."),
        )
}

