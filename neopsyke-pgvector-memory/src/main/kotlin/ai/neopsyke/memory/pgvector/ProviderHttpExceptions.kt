package ai.neopsyke.memory.pgvector

class ProviderBadRequestException(
    val errorCode: String,
    override val message: String,
) : RuntimeException(message)

class ProviderDependencyException(
    val errorCode: String,
    override val message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

