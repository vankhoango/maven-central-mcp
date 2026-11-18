package net.khoango.mavencentralmcp.mcp.server

import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.khoango.mavencentralmcp.core.service.MavenCentralService
import net.khoango.mavencentralmcp.maven.api.FetchResponse
import net.khoango.mavencentralmcp.maven.api.HttpFetcher
import net.khoango.mavencentralmcp.maven.api.MavenCentralClient
import net.khoango.mavencentralmcp.mcp.TestSupport
import net.khoango.mavencentralmcp.mcp.tool.MavenCentralTools
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import io.ktor.client.engine.cio.CIO as ClientCIO

/**
 * Controlled failure environment: a real embedded MCP server on an ephemeral loopback port, backed by an upstream
 * whose description-keyword (tags:) searches always fail with HTTP 500 while name searches work.
 */
class FeatureSearchFailureTest {
    private val keywordCalls = AtomicInteger()

    private val upstream = HttpFetcher { uri, _ ->
        val query = java.net.URLDecoder.decode(uri.rawQuery.orEmpty(), Charsets.UTF_8)
        when {
            uri.host == "search.maven.org" && "tags:" in query -> {
                keywordCalls.incrementAndGet()
                FetchResponse(500, ByteArray(0), emptyMap())
            }
            uri.host == "search.maven.org" -> FetchResponse(
                200,
                """{"response":{"numFound":1,"start":0,"docs":[{"g":"com.bihe0832.android","a":"lib-speech-recognition","latestVersion":"6.26.2","p":"aar","timestamp":1700000000000,"versionCount":40}]}}""".toByteArray(),
                emptyMap(),
            )
            else -> FetchResponse(404, ByteArray(0), emptyMap())
        }
    }

    @Test
    fun `keyword failure returns name results, says so, and keeps MCP working`() = runBlocking {
        val port = TestSupport.freePort()
        val server = EmbeddedMcpHttpServer(port, McpServerFactory(MavenCentralTools(MavenCentralService(MavenCentralClient(upstream))), "test"))
        server.start()
        val http = HttpClient(ClientCIO) { install(SSE) }
        try {
            val client = http.mcpStreamableHttp("http://127.0.0.1:$port/mcp")
            val result = client.callTool("search_artifacts", mapOf("query" to "speech recognition", "includeDescriptions" to false))
            assertNotEquals(true, result.isError)
            val s = result.structuredContent!!
            assertEquals("unavailable", s["descriptionKeywordMatching"]!!.jsonPrimitive.content)
            assertEquals("1", s["totalCount"]!!.jsonPrimitive.content) // name matches only
            assertEquals("1", s["nameMatchCount"]!!.jsonPrimitive.content)
            assertEquals("null", s["descriptionKeywordMatchCount"]!!.toString())
            assertEquals("lib-speech-recognition", s["results"]!!.jsonArray.single().jsonObject["artifactId"]!!.jsonPrimitive.content)
            assertTrue(s["notes"]!!.jsonArray.any { "Description-keyword matching failed and was skipped" in it.jsonPrimitive.content })
            assertTrue(keywordCalls.get() >= 1)

            // The session keeps working after the failure.
            val again = client.callTool("get_versions", mapOf("groupId" to "com.example", "artifactId" to "missing"))
            assertEquals(true, again.isError)
            assertTrue((again.content.single() as TextContent).text.contains("not found"))
            assertEquals(4, client.listTools().tools.size)
            client.close()
        } finally {
            http.close()
            server.stop()
        }
    }
}
