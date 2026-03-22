package ai.neopsyke.memory.pgvector

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DockerPgvectorBootstrapTest {
    private val config = PgvectorMemoryProviderConfig.fromEnv(
        mapOf("EMBEDDING_API_KEY" to "test-key")
    )

    @Test
    fun `parse local target accepts localhost jdbc url`() {
        val target = DockerPgvectorBootstrap.parseLocalTarget("jdbc:postgresql://localhost:5433/mydb")

        assertEquals("localhost", target.host)
        assertEquals(5433, target.port)
        assertEquals("mydb", target.database)
    }

    @Test
    fun `parse local target rejects non local host`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            DockerPgvectorBootstrap.parseLocalTarget("jdbc:postgresql://db.example.com:5432/mydb")
        }

        assertContains(ex.message.orEmpty(), "local PostgreSQL URLs")
    }

    @Test
    fun `build docker run command uses configured image volume and credentials`() {
        val command = DockerPgvectorBootstrap.buildDockerRunCommand(
            config.copy(
                dbUser = "memory-user",
                dbPassword = "memory-pass",
                bootstrapDockerImage = "pgvector/pgvector:pg17",
                bootstrapContainerName = "provider-db",
                bootstrapVolumeName = "provider-data",
            ),
            LocalPgvectorTarget(host = "127.0.0.1", port = 5544, database = "memory_db")
        )

        assertEquals(
            listOf(
                "docker",
                "run",
                "-d",
                "--name",
                "provider-db",
                "-e",
                "POSTGRES_DB=memory_db",
                "-e",
                "POSTGRES_USER=memory-user",
                "-e",
                "POSTGRES_PASSWORD=memory-pass",
                "-p",
                "5544:5432",
                "-v",
                "provider-data:/var/lib/postgresql/data",
                "pgvector/pgvector:pg17",
            ),
            command
        )
    }
}
