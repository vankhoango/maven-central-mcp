package net.khoango.mavencentralmcp.mcp.server

import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttp
import kotlinx.coroutines.runBlocking
import net.khoango.mavencentralmcp.mcp.TestSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import io.ktor.client.engine.cio.CIO as ClientCIO
import java.net.http.HttpClient as JdkHttpClient

/**
 * Simulates an IDE restart: the server stops (connections refused), starts again on the same port with
 * fresh in-memory state, an old session ID is rejected with 404 (the MCP signal to re-initialize), and a
 * new session works.
 */
class ReconnectionTest {
    private val port = TestSupport.freePort()
    private val url = "http://127.0.0.1:$port/mcp"
    private val http = JdkHttpClient.newHttpClient()

    private fun post(body: String, sessionId: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .apply { sessionId?.let { header("mcp-session-id", it) } }
            .POST(HttpRequest.BodyPublishers.ofString(body)).build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private val initialize =
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}"""
    private val listTools = """{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""

    @Test
    fun `clients can reconnect after the server restarts`() = runBlocking {
        val controller = McpServerController(TestSupport.serverFactory())
        try {
            controller.ensureRunning(port)
            val oldSession = post(initialize).headers().firstValue("mcp-session-id").get()
            post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""", oldSession)
            assertEquals(200, post(listTools, oldSession).statusCode())

            controller.stop()
            assertThrows<IOException> { post(listTools, oldSession) }.also {
                assertTrue(it is ConnectException || it.cause is ConnectException, it.toString())
            }

            controller.ensureRunning(port)
            assertEquals(404, post(listTools, oldSession).statusCode(), "stale session must be rejected")

            val ktor = HttpClient(ClientCIO) { install(SSE) }
            try {
                val client = ktor.mcpStreamableHttp(url)
                assertEquals(4, client.listTools().tools.size)
                client.close()
            } finally {
                ktor.close()
            }
        } finally {
            controller.stop()
        }
    }
}
