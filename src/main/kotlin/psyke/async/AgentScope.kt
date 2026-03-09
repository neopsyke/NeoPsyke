package psyke.async

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

fun agentScope(name: String): CoroutineScope {
    val handler = CoroutineExceptionHandler { context, throwable ->
        val coroutineName = context[CoroutineName]?.name ?: "unnamed"
        logger.error(throwable) { "Uncaught exception in coroutine '$coroutineName' (scope=$name)" }
    }
    return CoroutineScope(SupervisorJob() + handler + CoroutineName(name))
}
