package net.khoango.mavencentralmcp.mcp

import net.khoango.mavencentralmcp.core.service.MavenCentralService
import net.khoango.mavencentralmcp.maven.api.FetchResponse
import net.khoango.mavencentralmcp.maven.api.HttpFetcher
import net.khoango.mavencentralmcp.maven.api.MavenCentralClient
import net.khoango.mavencentralmcp.mcp.server.McpServerFactory
import net.khoango.mavencentralmcp.mcp.tool.MavenCentralTools
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI

/** Canned Maven Central responses so MCP tests never touch the network. */
object TestSupport {
    private fun ok(body: String) = FetchResponse(200, body.toByteArray(), emptyMap())
    private val notFound = FetchResponse(404, ByteArray(0), emptyMap())

    val fakeFetcher = HttpFetcher { uri: URI, _ ->
        val path = uri.path
        when {
            path == "/maven2/com/example/lib/maven-metadata.xml" -> ok(
                """<metadata><versioning><latest>2.0-RC1</latest><release>2.0-RC1</release>
                   <versions><version>1.0</version><version>1.9</version><version>1.10</version><version>2.0-RC1</version></versions>
                   <lastUpdated>20261102180748</lastUpdated></versioning></metadata>""",
            )
            path.endsWith(".pom") && path.startsWith("/maven2/com/example/lib/") ->
                ok("<project><name>Example Lib</name><description>An example.</description></project>")
            uri.host == "search.maven.org" && uri.rawQuery.contains("core=gav") ->
                ok("""{"response":{"numFound":1,"docs":[{"g":"com.example","a":"lib","v":"1.10","timestamp":1600000000000}]}}""")
            uri.host == "search.maven.org" -> ok(
                """{"response":{"numFound":1,"start":0,"docs":[{"g":"com.example","a":"lib","latestVersion":"2.0-RC1","p":"jar","timestamp":1700000000000,"versionCount":4}]}}""",
            )
            else -> notFound
        }
    }

    fun tools() = MavenCentralTools(MavenCentralService(MavenCentralClient(fakeFetcher)))

    fun serverFactory() = McpServerFactory(tools(), "test")

    fun freePort(): Int = ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { it.localPort }
}
