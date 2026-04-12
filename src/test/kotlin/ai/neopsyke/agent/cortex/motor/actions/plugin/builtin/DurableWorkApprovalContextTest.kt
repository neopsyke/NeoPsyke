package ai.neopsyke.agent.cortex.motor.actions.plugin.builtin

import ai.neopsyke.agent.config.AgentConfig
import ai.neopsyke.agent.cortex.motor.actions.ActionPluginFactoryContext
import ai.neopsyke.agent.cortex.motor.actions.NoopReflectionMemoryRecorder
import ai.neopsyke.agent.ego.planner.model.DurableWorkCommand
import ai.neopsyke.agent.ego.planner.model.DurableWorkPlanStepPayload
import ai.neopsyke.agent.ego.planner.model.SerializedDurableWorkCommand
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DurableWorkApprovalContextTest {

    private val mapper = jacksonObjectMapper()

    private fun buildPlugin(): DurableWorkOperationActionPlugin {
        val context = ActionPluginFactoryContext(
            config = AgentConfig(),
            webSearchActionHandler = null,
            fetchTool = null,
            output = {},
            reflectionMemoryRecorder = NoopReflectionMemoryRecorder,
        )
        return DurableWorkOperationActionPlugin(context)
    }

    @Test
    fun `buildApprovalContext renders plan steps for create command`() {
        val plugin = buildPlugin()

        val command = DurableWorkCommand.Create(
            title = "Weather reminder",
            instruction = "Check weather daily",
            planSteps = listOf(
                DurableWorkPlanStepPayload(
                    id = "step-1",
                    description = "Search for Hamburg weather",
                    acceptanceCriteria = "Weather data retrieved",
                    groundingRequirement = "required",
                    produces = setOf("weather_data"),
                ),
                DurableWorkPlanStepPayload(
                    id = "step-2",
                    description = "Send summary to user",
                    requires = setOf("weather_data"),
                    produces = setOf("user_delivery"),
                ),
            ),
        )

        val payload = mapper.writeValueAsString(SerializedDurableWorkCommand.fromDurableWorkCommand(command))
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

        val command = DurableWorkCommand.List
        val payload = mapper.writeValueAsString(SerializedDurableWorkCommand.fromDurableWorkCommand(command))
        val entries = plugin.buildApprovalContext(payload)

        assertTrue(entries.isEmpty())
    }
}
