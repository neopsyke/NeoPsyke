package psyke.agent

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.test.Test
import kotlin.test.assertTrue

class AgentProviderAbstractionGuardTest {
    private val agentSourceRoot: Path = Paths.get("src/main/kotlin/psyke/agent")
    private val forbiddenPatterns: List<Regex> = listOf(
        Regex("""\bLlmProvider\b"""),
        Regex("""\bproviderLabel\b"""),
        Regex("""(?i)\bgroq\b"""),
        Regex("""(?i)\bgpt-oss\b"""),
    )

    @Test
    fun `agent code stays free of provider specific branching tokens`() {
        val failures = mutableListOf<String>()

        Files.walk(agentSourceRoot).use { paths ->
            paths
                .filter { path -> Files.isRegularFile(path) && path.toString().endsWith(".kt") }
                .forEach { path ->
                    val source = Files.readString(path)
                    forbiddenPatterns.forEach { pattern ->
                        if (pattern.containsMatchIn(source)) {
                            failures += "${path.invariantSeparatorsPathString}: matches forbidden pattern ${pattern.pattern}"
                        }
                    }
                }
        }

        assertTrue(
            failures.isEmpty(),
            "Agent layer contains provider-specific branching tokens:\n${failures.joinToString("\n")}"
        )
    }
}
