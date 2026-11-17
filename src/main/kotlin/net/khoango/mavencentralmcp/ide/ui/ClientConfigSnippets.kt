package net.khoango.mavencentralmcp.ide.ui

import net.khoango.mavencentralmcp.mcp.server.ServerState

/** Text helpers for the status UI and the "copy client configuration" action. Pure, IDE-independent. */
object ClientConfigSnippets {
    const val SERVER_KEY = "maven-central"

    /** `mcpServers` JSON accepted by Cursor (`.cursor/mcp.json`) and Claude Code (`.mcp.json`). */
    fun mcpServersJson(url: String): String = """
        {
          "mcpServers": {
            "$SERVER_KEY": {
              "type": "http",
              "url": "$url"
            }
          }
        }
    """.trimIndent()

    fun endpointUrl(port: Int): String = "http://127.0.0.1:$port/mcp"

    fun describe(state: ServerState, enabled: Boolean): String = when (state) {
        is ServerState.Running -> "Running at ${state.url}"
        is ServerState.Starting -> "Starting on port ${state.port}…"
        is ServerState.Failed -> "Not running: ${state.message}"
        ServerState.Stopped -> if (enabled) "Stopped" else "Disabled"
    }
}
