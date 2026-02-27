package psyke.agent

import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

object SuperegoDirectives {
    const val RESOURCE_PATH: String = "/superego/directives.txt"

    fun load(resourcePath: String = RESOURCE_PATH): List<String> {
        val stream = SuperegoDirectives::class.java.getResourceAsStream(resourcePath)
        if (stream == null) {
            logger.warn { "Superego directives resource '$resourcePath' not found; using defaults." }
            return SuperegoGatekeeper.DEFAULT_DIRECTIVES
        }

        val directives = stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toList()
        }

        if (directives.isEmpty()) {
            logger.warn { "Superego directives resource '$resourcePath' has no directives; using defaults." }
            return SuperegoGatekeeper.DEFAULT_DIRECTIVES
        }

        logger.info { "Loaded ${directives.size} superego directive(s) from '$resourcePath'." }
        return directives
    }
}
