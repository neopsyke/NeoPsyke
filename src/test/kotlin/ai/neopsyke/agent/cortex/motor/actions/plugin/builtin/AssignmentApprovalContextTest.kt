package ai.neopsyke.agent.cortex.motor.actions.plugin.builtin

import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.cortex.motor.actions.ActionPluginFactoryContext
import ai.neopsyke.agent.cortex.motor.actions.NoopReflectionMemoryRecorder
import ai.neopsyke.agent.ego.planner.model.AssignmentCommand
import ai.neopsyke.agent.ego.planner.model.AssignmentPlanStepPayload
import ai.neopsyke.agent.ego.planner.model.SerializedAssignmentCommand
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AssignmentApprovalContextTest {

    private val mapper = jacksonObjectMapper()

    private fun buildPlugin(): AssignmentOperationActionPlugin {
        val context = ActionPluginFactoryContext(
            config = AgentConfig(),
            webSearchActionHandler = null,
            fetchTool = null,
            output = {},
            reflectionMemoryRecorder = NoopReflectionMemoryRecorder,
        )
        return AssignmentOperationActionPlugin(context)
    }

    @Test
    fun `buildApprovalContext renders plan steps for create command`() {
        val plugin = buildPlugin()

        val command = AssignmentCommand.Create(
            title = "Weather reminder",
            instruction = "Check weather daily",
            planSteps = listOf(
                AssignmentPlanStepPayload(
                    id = "step-1",
                    description = "Search for Hamburg weather",
                    acceptanceCriteria = "Weather data retrieved",
                    groundingRequirement = "required",
                    produces = setOf("weather_data"),
                ),
                AssignmentPlanStepPayload(
                    id = "step-2",
                    description = "Send summary to user",
                    requires = setOf("weather_data"),
                    produces = setOf("user_delivery"),
                ),
            ),
        )

        val payload = mapper.writeValueAsString(SerializedAssignmentCommand.fromAssignmentCommand(command))
        val entries = plugin.buildApprovalContext(payload)

        assertEquals(1, entries.size)
        assertEquals("Plan", entries[0].label)
        assertTrue(entries[0].content.contains("Search for Hamburg weather"))
        assertTrue(entries[0].content.contains("Send summary to user"))
        assertTrue(entries[0].content.contains("weather_data"), "Should include produces keys")
    }

    @Test
    fun `buildApprovalContext returns empty for non-create commands`() {
        val plugin = buildPlugin()

        val command = AssignmentCommand.List
        val payload = mapper.writeValueAsString(SerializedAssignmentCommand.fromAssignmentCommand(command))
        val entries = plugin.buildApprovalContext(payload)

        assertTrue(entries.isEmpty())
    }
}
