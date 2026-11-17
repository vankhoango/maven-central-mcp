package net.khoango.mavencentralmcp.mcp.server

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import net.khoango.mavencentralmcp.mcp.tool.MavenCentralTools

/** Builds SDK [Server] instances exposing the Maven Central tools. */
class McpServerFactory(private val tools: MavenCentralTools, private val serverVersion: String) {

    /** Creates a new [Server]. The Streamable HTTP integration creates one per client session. */
    fun create(): Server = Server(
        serverInfo = Implementation(name = SERVER_NAME, version = serverVersion, title = "Maven Central MCP"),
        options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
        instructions = INSTRUCTIONS,
    ) {
        this@McpServerFactory.tools.all().forEach { def ->
            addTool(
                name = def.name,
                description = def.description,
                inputSchema = def.inputSchema,
                title = def.title,
                toolAnnotations = def.annotations,
            ) { request -> def.handler(request.arguments) }
        }
    }

    companion object {
        const val SERVER_NAME = "maven-central-mcp"

        const val INSTRUCTIONS =
            "Read-only access to Maven Central. Use search_artifacts to discover coordinates, get_artifact_info " +
                "for metadata, get_latest_version to choose a dependency version (it distinguishes latest published " +
                "from latest stable), and get_versions to list versions. Search-index 'latest' fields can lag; prefer " +
                "get_latest_version when recommending a version."
    }
}
