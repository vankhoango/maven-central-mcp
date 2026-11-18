package net.khoango.mavencentralmcp.ide.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.khoango.mavencentralmcp.mcp.server.ServerState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ClientConfigSnippetsTest {
    @Test
    fun `mcpServers snippet is valid json with loopback url`() {
        val url = ClientConfigSnippets.endpointUrl(9909)
        assertEquals("http://127.0.0.1:9909/mcp", url)
        val server = Json.parseToJsonElement(ClientConfigSnippets.mcpServersJson(url))
            .jsonObject["mcpServers"]!!.jsonObject["maven-central"]!!.jsonObject
        assertEquals("http", server["type"]!!.jsonPrimitive.content)
        assertEquals(url, server["url"]!!.jsonPrimitive.content)
    }

    @Test
    fun `describes every state`() {
        assertEquals("Running at http://127.0.0.1:1/mcp", ClientConfigSnippets.describe(ServerState.Running(1, "http://127.0.0.1:1/mcp"), true))
        assertEquals("Disabled", ClientConfigSnippets.describe(ServerState.Stopped, false))
        assertEquals("Stopped", ClientConfigSnippets.describe(ServerState.Stopped, true))
        assertEquals("Not running: boom", ClientConfigSnippets.describe(ServerState.Failed(1, "boom", false), true))
    }
}
