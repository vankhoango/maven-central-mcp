package net.khoango.mavencentralmcp.mcp.server

import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import net.khoango.mavencentralmcp.mcp.TestSupport
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import io.ktor.client.engine.cio.CIO as ClientCIO
import java.net.http.HttpClient as JdkHttpClient

class EmbeddedMcpHttpServerTest {
    private val port = TestSupport.freePort()
    private val server = EmbeddedMcpHttpServer(port, TestSupport.serverFactory())
    private val url = "http://127.0.0.1:$port/mcp"

    @BeforeEach
    fun start() = runBlocking { server.start() }

    @AfterEach
    fun stop() = runBlocking { server.stop() }

    private val initializeBody =
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}"""

    private fun rawPost(host: String, origin: String? = null): String {
        Socket("127.0.0.1", port).use { socket ->
            val body = initializeBody.toByteArray()
            val request = buildString {
                append("POST /mcp HTTP/1.1\r\n")
                append("Host: $host\r\n")
                origin?.let { append("Origin: $it\r\n") }
                append("Content-Type: application/json\r\n")
                append("Accept: application/json, text/event-stream\r\n")
                append("Content-Length: ${body.size}\r\n")
                append("Connection: close\r\n\r\n")
            }
            socket.getOutputStream().apply { write(request.toByteArray()); write(body); flush() }
            return socket.getInputStream().bufferedReader().readLine() ?: ""
        }
    }

    @Test
    fun `endpoint url is loopback`() {
        assertEquals(url, server.endpointUrl)
    }

    @Test
    fun `official SDK client can initialize, list and call tools`() = runBlocking {
        val http = HttpClient(ClientCIO) { install(SSE) }
        try {
            val client = http.mcpStreamableHttp(url)
            assertEquals("maven-central-mcp", client.serverVersion?.name)
            val names = client.listTools().tools.map { it.name }.toSet()
            assertEquals(setOf("search_artifacts", "get_artifact_info", "get_latest_version", "get_versions"), names)

            val result = client.callTool("get_latest_version", mapOf("groupId" to "com.example", "artifactId" to "lib"))
            assertNotEquals(true, result.isError)
            assertEquals("1.10", result.structuredContent!!["latestStableVersion"]!!.jsonPrimitive.content)

            val invalid = client.callTool("get_versions", mapOf("groupId" to "com.example"))
            assertEquals(true, invalid.isError)
            assertTrue((invalid.content.single() as TextContent).text.contains("artifactId"))
            client.close()
        } finally {
            http.close()
        }
    }

    @Test
    fun `accepts loopback host headers`() {
        assertTrue(rawPost("127.0.0.1:$port").startsWith("HTTP/1.1 200"))
        assertTrue(rawPost("localhost:$port").startsWith("HTTP/1.1 200"))
        assertTrue(rawPost("127.0.0.1:$port", origin = "http://localhost:$port").startsWith("HTTP/1.1 200"))
    }

    @Test
    fun `rejects foreign host header (DNS rebinding)`() {
        assertTrue(rawPost("evil.example:$port").startsWith("HTTP/1.1 403"))
        assertTrue(rawPost("127.0.0.1:1").startsWith("HTTP/1.1 403"))
    }

    @Test
    fun `rejects foreign origin`() {
        assertTrue(rawPost("127.0.0.1:$port", origin = "https://evil.example").startsWith("HTTP/1.1 403"))
        assertTrue(rawPost("127.0.0.1:$port", origin = "null").startsWith("HTTP/1.1 403"))
    }

    @Test
    fun `does not emit permissive CORS headers`() {
        val response = JdkHttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(initializeBody)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, response.statusCode(), response.body())
        assertTrue(response.headers().firstValue("Access-Control-Allow-Origin").isEmpty)
    }

    @Test
    fun `rejects oversized request bodies`() {
        Socket("127.0.0.1", port).use { socket ->
            val request = "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${EmbeddedMcpHttpServer.MAX_REQUEST_BYTES + 1}\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().apply { write(request.toByteArray()); flush() }
            assertTrue(socket.getInputStream().bufferedReader().readLine().startsWith("HTTP/1.1 413"))
        }
    }

    @Test
    fun `is not reachable on non-loopback interfaces`() {
        val lanAddress = NetworkInterface.networkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
        assumeTrue(lanAddress != null, "No non-loopback IPv4 interface available")
        assertThrows<IOException> {
            Socket().use { it.connect(InetSocketAddress(lanAddress, port), 1_000) }
        }
    }
}
