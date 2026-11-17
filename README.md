# Maven Central MCP

A JetBrains IDE plugin that runs a local [Model Context Protocol](https://modelcontextprotocol.io) (MCP) server so
AI clients can search Maven Central and pick dependency versions. No hosted backend, account, database or telemetry.

- Transport: MCP **Streamable HTTP** at `http://127.0.0.1:47115/mcp` (port configurable).
- Listens on the loopback interface only. Read-only; never reads or modifies project files.
- Available only while the IDE is running. There is no STDIO mode.

## Tools

| Tool | Purpose |
|---|---|
| `search_artifacts` | Search by name (`query`, optional exact `groupId`/`artifactId`/`packaging`, `limit` ≤ 50, `offset`). Returns `totalCount` and `hasMore`. |
| `get_artifact_info` | Metadata for one `groupId` + `artifactId`: POM name/description/URL/SCM/licenses, version count, raw "latest" markers, links. |
| `get_latest_version` | `latestPublishedVersion` (may be a prerelease) and `latestStableVersion`, plus timestamps, `uncertain`/`candidates`, and the policy text. |
| `get_versions` | Versions newest-first (`limit` ≤ 100, `offset`, `includePrereleases`), each with a `prerelease` flag and timestamp when known. |

Every result has a readable text summary and `structuredContent` JSON. Invalid arguments return `isError: true`
with a message naming the bad field; unknown argument names are rejected.

### Version policy

- **Ordering** uses Apache Maven's `ComparableVersion` (Maven's own algorithm), never string order. Version strings
  are returned exactly as published.
- **Prerelease**: any qualifier token `alpha`, `beta`, `milestone`, `rc`, `cr`, `preview`, `pre`, `ea`, `eap`,
  `snapshot`, `dev`, `nightly`, `incubating`, `experimental`, or a single `a`/`b`/`m` followed by a number
  (`1.0a1`, `2.0-b3`, `5.0.M2`). `-jre`, `-android`, `.Final`, `.RELEASE`, `.GA` are stable.
- **Latest stable** = highest non-prerelease version. If several stable variants share one release (for example
  `33.8.0-jre` and `33.8.0-android`), `uncertain` is `true` and `candidates` lists them.
- `includeSnapshots` only allows `-SNAPSHOT` versions (if any are listed) to be `latestPublishedVersion`. Maven
  Central does not normally host snapshots.

### Data sources

| Data | Source |
|---|---|
| Search, packaging, publication timestamps | `https://search.maven.org/solrsearch/select` |
| Version lists, latest/stable resolution | `https://repo1.maven.org/maven2/<group>/<artifact>/maven-metadata.xml` |
| Name, description, licenses, SCM | The POM of the latest stable version on `repo1.maven.org` |

The search index can lag far behind the repository (in November 2026 it reported Ktor `3.2.0` while the repository
had `3.6.1`). Search results label this field `indexedLatestVersion`; use `get_latest_version` for real answers.
Both hosts are fixed in code; tool arguments cannot change them. Responses are cached in memory for 10 minutes.

## Install

1. Build the plugin (see below) or take `maven-central-mcp-<version>.zip` from `build/distributions/`.
2. In the IDE: **Settings | Plugins | ⚙ | Install Plugin from Disk…**, choose the zip, restart if asked.
3. **Settings | Tools | Maven Central MCP** shows the status and port. **Tools | Maven Central MCP** has
   *Show Server Status*, *Copy Client Configuration* and *Restart Server*.

Compatibility: IntelliJ IDEA 2026.1–2026.2 (builds 261–262.*). Other IntelliJ-based IDEs on those builds should
work because the plugin only depends on the core platform, but only IntelliJ IDEA has been tested. Android Studio
has **not** been tested.

## Client configuration

Use `http://127.0.0.1:<port>/mcp`. Prefer `127.0.0.1` over `localhost`, because `localhost` may resolve to IPv6
`::1`, where the server does not listen.

**Claude Code**

```bash
claude mcp add --transport http maven-central http://127.0.0.1:47115/mcp
```

**Cursor** (`~/.cursor/mcp.json` or `.cursor/mcp.json`) and **Claude Code** project file (`.mcp.json`):

```json
{
  "mcpServers": {
    "maven-central": { "type": "http", "url": "http://127.0.0.1:47115/mcp" }
  }
}
```

**VS Code** (`.vscode/mcp.json`):

```json
{
  "servers": {
    "maven-central": { "type": "http", "url": "http://127.0.0.1:47115/mcp" }
  }
}
```

**Claude Desktop** reads only STDIO servers from `claude_desktop_config.json`. To use this HTTP server you need an
STDIO-to-HTTP bridge such as the third-party `mcp-remote` package; this is not tested by this project.

**MCP Inspector** (command shape per the Inspector docs; not yet run by this project)

```bash
npx @modelcontextprotocol/inspector --cli http://127.0.0.1:47115/mcp --transport http --method tools/list
```

Client verification status is tracked in [docs/MANUAL_TESTING.md](docs/MANUAL_TESTING.md).

## Security

- Binds to `127.0.0.1` only; never to `0.0.0.0` or a LAN address.
- Rejects requests whose `Host` is not `127.0.0.1:<port>`, `localhost:<port>` or `[::1]:<port>` (DNS rebinding),
  and requests carrying any non-loopback `Origin`. The SDK's own DNS-rebinding check is also enabled. No CORS.
- Request bodies over 1 MiB are rejected; upstream responses over 4 MiB are rejected; timeouts are 5 s (connect)
  and 15 s (request); result counts are capped.
- XML from Maven Central is parsed with DOCTYPEs and external entities disabled.
- No bearer token: any local process can call the server, but the tools are read-only and only return public
  Maven Central data, so a token would add client setup cost without protecting anything.

## Troubleshooting

| Symptom | Fix |
|---|---|
| "Port 47115 is already in use" | Choose another port in **Settings | Tools | Maven Central MCP**, apply, update the client URL. |
| Client cannot connect | Is the IDE running? Check **Tools | Maven Central MCP | Show Server Status**. Use `127.0.0.1`, not `localhost`. |
| `403 Forbidden` | The client sent a non-loopback `Host` or `Origin` (for example through a proxy). Connect directly. |
| `406 Not Acceptable` | The client must send `Accept: application/json, text/event-stream` (Streamable HTTP). |
| A search shows an old `indexedLatestVersion` | Expected index lag; call `get_latest_version`. |

## Build from source

Requires JDK 21+ (the build itself was verified with JDK 25 and Gradle 9.5.1 via the wrapper).

```bash
./gradlew test
```

```bash
./gradlew buildPlugin
```

```bash
./gradlew verifyPlugin
```

```bash
./gradlew runIde
```

Modules: `core` (Maven Central client, version policy; no IntelliJ/MCP code), `mcp-server` (tools and embedded
server on the official [Kotlin MCP SDK](https://github.com/modelcontextprotocol/kotlin-sdk)), and the root plugin
module (settings, lifecycle, actions). See [docs/](docs/) for the specification and architecture.

## License

Intended license: Apache-2.0, pending maintainer confirmation before the first public release.
