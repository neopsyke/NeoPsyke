package psyke.mcp.memory.tools

import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.TextContent

fun errorResult(message: String): CallToolResult =
    CallToolResult(
        content = listOf(TextContent(text = message)),
        isError = true,
    )
