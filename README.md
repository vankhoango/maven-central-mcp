# Maven Central MCP

**Find the right Maven dependencies faster, with less AI context overhead.**

Maven Central MCP is a JetBrains IDE plugin that runs a local [Model Context Protocol (MCP)](https://modelcontextprotocol.io) server, giving AI coding assistants direct access to Maven Central.

Search libraries, inspect dependency metadata, and discover versions without relying on lengthy web searches or manually browsing POM files.

No hosted backend, account, database, or telemetry.

## Why Maven Central MCP?

Whether you're building Java applications, Kotlin projects, or Android apps, choosing the right dependency is part of everyday development.

Maven Central MCP connects your AI coding assistant directly to Maven Central, helping it:

- **Find libraries faster** — Discover dependencies by name, group ID, or functionality instead of searching through multiple websites.
- **Save AI tokens** — Get focused, structured dependency information without filling conversations with lengthy web pages and irrelevant search results.
- **Choose the right versions** — Check the latest stable release, prereleases, platform variants, and version history.
- **Make better dependency decisions** — Inspect licenses, descriptions, developers, and project metadata before adding a dependency.
- **Stay in your IDE workflow** — Use Maven Central discovery through AI assistants connected to IntelliJ IDEA or Android Studio.

Less time searching for dependencies. Less unnecessary AI context. More time building software.

## Features

- **Artifact search** — Find libraries by artifact name, group ID, or description keywords.
- **Feature-based discovery** — Search for libraries using functionality, such as speech recognition, JSON serialization, or database migration.
- **Dependency metadata** — Inspect descriptions, licenses, developers, SCM information, and more.
- **Version discovery** — Find the latest stable and published versions.
- **Version history** — Browse versions, prereleases, platform variants, and publication dates.
- **Transparent results** — See ranking signals, data provenance, and search-index limitations.

## MCP Tools

| Tool | Description |
|---|---|
| `search_artifacts` | Search Maven Central artifacts |
| `get_artifact_info` | Retrieve artifact metadata |
| `get_latest_version` | Find the latest stable and published versions |
| `get_versions` | List available versions |

## Requirements

- IntelliJ IDEA 2026.1–2026.2
- An MCP-compatible AI client
- IntelliJ IDEA must be running while the MCP server is in use

Other IntelliJ-based IDEs may work, but have not been officially tested. Android Studio compatibility has not yet been verified.

## Installation

1. Download the plugin ZIP from [GitHub Releases](https://github.com/vankhoango/maven-central-mcp/releases).
2. Open IntelliJ IDEA → **Settings → Plugins**.
3. Select the gear icon → **Install Plugin from Disk...**
4. Choose the downloaded ZIP and restart the IDE if requested.

## Connect an MCP Client

The plugin starts a local Streamable HTTP server:

```text
http://127.0.0.1:9909/mcp
```

The port is configurable in **Settings → Tools → Maven Central MCP**.

### Claude Code

```bash
claude mcp add --transport http maven-central http://127.0.0.1:9909/mcp
```

### Cursor and Claude Code project configuration

Add the following to `~/.cursor/mcp.json`, `.cursor/mcp.json`, or `.mcp.json`:

```json
{
  "mcpServers": {
    "maven-central": {
      "type": "http",
      "url": "http://127.0.0.1:9909/mcp"
    }
  }
}
```

### VS Code

Add the following to `.vscode/mcp.json`:

```json
{
  "servers": {
    "maven-central": {
      "type": "http",
      "url": "http://127.0.0.1:9909/mcp"
    }
  }
}
```

For other MCP clients, configure a Streamable HTTP server using the same endpoint.

**Note:** Claude Desktop uses a different connection configuration. Connecting it to this HTTP server requires an STDIO-to-HTTP bridge such as `mcp-remote`. This setup has not yet been tested by this project.

## Security and Privacy

- **Local only** — Listens on `127.0.0.1`, never exposing a LAN or public endpoint.
- **Read-only** — Does not read or modify your project files.
- **Public data only** — Retrieves publicly available Maven Central information.
- **No accounts or credentials** — No authentication setup is required.
- **No telemetry** — No tracking or analytics.

The MCP server runs only while the IDE is running.

## Documentation

See the [`docs/`](docs/) directory for technical details:

- Search behavior and ranking
- Feature-search implementation and limitations
- Version ordering and prerelease policy
- Metadata sources and provenance
- Client configuration
- Troubleshooting and manual testing
- Architecture and specifications

## Build from Source

Requires JDK 21+.

Run the following commands from the project root:

```bash
# Run tests
./gradlew test

# Build the plugin distribution
./gradlew buildPlugin

# Verify plugin compatibility
./gradlew verifyPlugin

# Launch a development IDE instance
./gradlew runIde
```

The plugin distribution is generated in:

```text
build/distributions/
```

## License

Licensed under the [Apache License, Version 2.0](LICENSE).