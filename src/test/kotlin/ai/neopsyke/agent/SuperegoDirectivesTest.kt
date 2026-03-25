package ai.neopsyke.agent

import ai.neopsyke.agent.actions.ActionPluginFactoryContext
import ai.neopsyke.agent.actions.ActionRegistry
import ai.neopsyke.agent.actions.NoopReflectionMemoryRecorder
import ai.neopsyke.agent.config.AgentConfig
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SuperegoDirectivesTest {

    private val config = AgentConfig()

    /** Registry with all ServiceLoader-discovered plugins (null tool deps are fine for directive tests). */
    private val registry: ActionRegistry = ActionRegistry.discover(
        ActionPluginFactoryContext(
            config = config,
            webSearchActionHandler = null,
            fetchTool = null,
            output = {},
            reflectionMemoryRecorder = NoopReflectionMemoryRecorder,
        )
    )

    @Test
    fun `for action includes general and action specific directives`() {
        val directives = SuperegoPolicy.forAction(ActionType.WEBSITE_FETCH, registry)

        assertTrue(directives.general.isNotEmpty())
        assertTrue(directives.actionSpecific.isNotEmpty())
        assertTrue(directives.all.size >= directives.general.size)
    }

    @Test
    fun `action specific directives differ by action type`() {
        val answer = SuperegoPolicy.forAction(ActionType.CONTACT_USER, registry).actionSpecific
        val fetch = SuperegoPolicy.forAction(ActionType.WEBSITE_FETCH, registry).actionSpecific

        assertTrue(answer != fetch)
        assertTrue(answer.any { it.contains("CONTACT_USER", ignoreCase = true) })
        assertTrue(fetch.any { it.contains("FETCH", ignoreCase = true) })
    }

    @Test
    fun `all directives contains deduplicated union`() {
        val all = SuperegoPolicy.allDirectives(registry)
        val expected = (registry.actionTypes() + ActionType.entries)
            .flatMap { SuperegoPolicy.forAction(it, registry).all }
            .distinct()

        assertTrue(all == expected)
        assertTrue(all.isNotEmpty())
    }

    @Test
    fun `general directives are always included in every action type`() {
        val general = SuperegoPolicy.GENERAL_DIRECTIVES
        ActionType.entries.forEach { actionType ->
            val all = SuperegoPolicy.forAction(actionType, registry).all
            general.forEach { directive ->
                assertTrue(
                    all.contains(directive),
                    "Expected general directive missing for action=$actionType"
                )
            }
        }
    }

    @Test
    fun `fetch includes url safety directive`() {
        val fetch = SuperegoPolicy.forAction(ActionType.WEBSITE_FETCH, registry).actionSpecific
        assertTrue(
            fetch.any { it.contains("HTTPS", ignoreCase = true) }
        )
    }

    @Test
    fun `plugin directives are the single source of truth`() {
        // Verify that all plugin-registered actions provide directives via the plugin,
        // not via any inline fallback.
        registry.actionTypes().forEach { actionType ->
            val descriptor = registry.descriptor(actionType)!!
            val pluginDirectives = descriptor.superegoDirectives
            val resolved = SuperegoPolicy.forAction(actionType, registry).actionSpecific
            assertTrue(
                pluginDirectives == resolved,
                "Directives for $actionType should come from the plugin descriptor, not from an inline fallback."
            )
        }
    }

}
