rootProject.name = "maven-central-mcp"

// core:       Maven Central client, domain model, version policy. No IntelliJ, no MCP, no Ktor.
// mcp-server: MCP tools + embedded Streamable HTTP server (official Kotlin MCP SDK + Ktor CIO). No IntelliJ.
// root:       the IntelliJ Platform plugin (settings, lifecycle, actions).
include(":core", ":mcp-server")
