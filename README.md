# Maven Central MCP

A JetBrains IDE plugin that runs a local [Model Context Protocol](https://modelcontextprotocol.io) (MCP) server so
AI clients can search Maven Central and pick dependency versions. No hosted backend, account, database or telemetry.

- Transport: MCP **Streamable HTTP** at `http://127.0.0.1:47115/mcp` (port configurable).
- Listens on the loopback interface only. Read-only; never reads or modifies project files.
- Available only while the IDE is running. There is no STDIO mode.

## Tools

| Tool | Purpose |
|---|---|
| `search_artifacts` | Search (`query`, optional exact `groupId`/`artifactId`/`packaging`, `limit` ≤ 50, `offset`, `includeDescriptions`). Returns ranked results with `rankingSignals`, POM `name`/`description`/`relocatedTo` for the first 5, `totalCount`, `hasMore`. |
| `get_artifact_info` | Metadata for one `groupId` + `artifactId`: POM name/description/URL/SCM/licenses/packaging/developers/organization/relocation (with parent-POM inheritance), version count, raw "latest" markers, links, and per-field `provenance`. |
| `get_latest_version` | `latestPublishedVersion` (may be a prerelease), `latestStableVersion`, per-platform `variants`, per-major `stableLines`, `uncertain`/`candidates`, `guidance` and the policy text. |
| `get_versions` | Versions newest-first (`limit` ≤ 100, `offset`, `includePrereleases`, `variant`), each with `prerelease`, `variant` and a publication date with `publishedProvenance`; `availableVariants` lists the labels. |

Every result has a readable text summary and `structuredContent` JSON. Invalid arguments return `isError: true`
with a message naming the bad field; unknown argument names are rejected.

### Search ranking

The search index orders by its own text score, so forks and repackages often come first (it lists a WSO2 repackage
of `okhttp` above `com.squareup.okhttp3:okhttp`). For plain-word queries the plugin re-ranks the top 100 index
matches by: exact groupId match (for groupId-shaped queries), exact artifactId match (query words joined with `-`),
artifactId prefix/term match, a non-generic query word in the groupId (`com`, `org`, `io`, … are ignored), number of
published versions (capped), recency, then index order. Each result lists these facts in `rankingSignals`. Ranking
never labels an artifact "official"; the notes tell the client to confirm the groupId.

- **groupId-shaped queries** (`com.fasterxml.jackson.core`): artifacts in exactly that group rank first, then its
  subgroups (`com.fasterxml.jackson` also boosts `com.fasterxml.jackson.core`). An unrelated artifact whose
  artifactId equals the query (`com.liferay:com.fasterxml.jackson.core`) only gets a weak signal. The query is never
  turned into a filter, and a dotted query whose group is not among the matches (`jackson.databind`) is ranked as
  ordinary free text.
- **Pagination**: positions 1–100 are re-ranked; later positions keep index order (signal `position N in
  search-index order`). A page crossing position 100 combines both without gaps or duplicates. An offset at or past
  `totalCount` returns an empty page with the correct `totalCount` and `hasMore: false`.

- `groupId:artifactId` queries are exact lookups, and fall back to repository metadata if the index lacks the
  artifact. Search expressions (`g:io.ktor*`, `a:okhttp`, `AND`/`OR`, quotes) keep the index order.
- Search expressions and index-ordered positions still carry signals: index position, a matching `groupId`
  filter, versions indexed, recency.
- `indexedLatestVersionPrerelease` classifies `indexedLatestVersion` with the version policy below; the version
  string is returned unchanged.
- The search index has no description field. Name and description come from the POM of `indexedLatestVersion` for
  the first 5 results of each page (one cached request each; skip with `includeDescriptions: false`). If that POM
  has no description but declares a parent, up to 2 parent POMs are read. `descriptionSource` always mirrors
  `provenance.description`: the source (`pom:<g:a:v>` or `parent-pom:<g:a:v>`) when found, otherwise the status
  (`missing`, `unavailable`, `not_loaded`, `unresolved`).

### Version policy

- **Ordering** uses Apache Maven's `ComparableVersion` (Maven's own algorithm), never string order. Version strings
  are returned exactly as published.
- **Prerelease**: any qualifier token `alpha`, `beta`, `milestone`, `rc`, `cr`, `preview`, `pre`, `ea`, `eap`,
  `snapshot`, `dev`, `nightly`, `incubating`, `experimental`, `canary`, `test`, or `a`/`b`/`m`/`pr` followed by a
  number (`1.0a1`, `2.0-b3`, `5.0.M2`, Jackson `2.9.0.pr1`). `Final`, `GA`, `RELEASE`, `SP1`, `SEC01` are plain
  releases.
- **Platform variants**: other qualifiers (Guava `-jre`/`-android`, mssql-jdbc `.jre8`/`.jre11`, PostgreSQL
  `.jre7`/`-jdbc4`) are separate, equally valid builds. `get_latest_version.variants` gives the latest stable and
  published version of each; `get_versions` labels each version and can filter by `variant` (`none` = unqualified).
- **Latest stable** = highest non-prerelease version by Maven ordering. When the newest release exists in several
  variants, `uncertain` is `true` and `candidates` lists them; the Maven order between `-jre` and `-android` only
  reflects spelling.
- Maven ordering alone does not choose a version for a project. `guidance` says to match the platform variant, stay
  on the project's major line unless upgrading deliberately (`stableLines`), and respect BOM-managed versions.
- `includeSnapshots` only allows `-SNAPSHOT` versions (if any are listed) to be `latestPublishedVersion`. Maven
  Central does not normally host snapshots.

### Data sources and provenance

| Data | Source (`provenance.source`) |
|---|---|
| Search hits, `indexedLatestVersion`, `indexedPackaging`, `indexedVersionCount` | Search index `search.maven.org/solrsearch/select` (`search-index`) |
| Version lists, latest/stable resolution, `repository*` markers | `repo1.maven.org/maven2/<group>/<artifact>/maven-metadata.xml` (`repository-metadata`) |
| Name, description, URLs, licenses, packaging, developers, organization, relocation | POM of the latest stable version (`pom <g:a:v>`), parents for inherited fields (`parent-pom <g:a:v>`) |
| Packaging when the POM declares none | Maven's default `jar` (`maven-default`) |
| Publication dates | Search index, else the POM file's HTTP `Last-Modified` on the repository (`repository-file-timestamp`) |

POM sources always use one format: `pom:<groupId>:<artifactId>:<version>` and
`parent-pom:<groupId>:<artifactId>:<version>`.

Every `provenance` entry has a `status`: `present`; `missing` (every relevant source was read and none declares the
value); `unavailable` (a source could not be read, e.g. the index timed out or a parent POM is unreachable);
`not_loaded` (not requested to bound network use); or `unresolved` (the value is a `${...}` Maven property, which is
not evaluated). Values are never guessed.

**POM inheritance.** `description`, `licenses`, `developers` and `organization` follow Maven inheritance through up
to 3 parent POMs (e.g. Jackson's developers come from `jackson-bom`, 3 levels up). `name` and `packaging` are not
inherited by Maven. `url` and `scm.url` are read from the artifact's own POM only, because Maven derives the
inherited value by appending the module path, which is not reproduced here. Developer e-mail addresses are not
returned.

**Publication dates.** The search index stores a publication time per version but stopped indexing new versions
around mid-2025, which is why dates for recent versions were blank. Versions it lacks get the upload time of their
POM file (`Last-Modified`, one cached HEAD request each, at most 25 per `get_versions` call). On 12 sampled versions
this matched the index time within one second. It is the upload time, not a declared release date. Versions over
the budget are `not_loaded`; failed lookups are `unavailable`.

The search index can lag far behind the repository (in November 2026 it reported Ktor `3.2.0` while the repository
had `3.6.1`). Search results label this field `indexedLatestVersion`; use `get_latest_version` for real answers.
Both hosts are fixed in code; tool arguments cannot change them. Responses are cached in memory for 10 minutes.
Connection errors and HTTP 502/503/504 are retried once; timeouts and HTTP 429 are not.

### Response changes in 0.1.0 development builds

- `search_artifacts`: `descriptionSource` changed from `pom:<version>` to the provenance source (`pom:<g:a:v>`,
  `parent-pom:<g:a:v>`) or status; added `indexedLatestVersionPrerelease`; `rankingSignals` is no longer empty for
  search expressions and index-ordered positions; every result now has `provenance` for `name`, `description` and
  `relocatedTo`.
- All tools: POM provenance sources changed from `pom <g:a:v>` to `pom:<g:a:v>` (and `parent-pom:<g:a:v>`).

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
| "Port … is already in use" balloon (one per conflict) | Another program, often a second IDE with this plugin, holds the port. Close it and click **Retry**, or choose another port in **Settings | Tools | Maven Central MCP**, apply, and update the client URL. Startup failures are also logged in `idea.log`. |
| Client cannot connect | Is the IDE running? Check **Tools | Maven Central MCP | Show Server Status**. Use `127.0.0.1`, not `localhost`. |
| Client stopped working after an IDE restart | Sessions do not survive a restart: the server answers an old session ID with `404 Session not found`, which tells MCP clients to start a new session. Clients that only connect at their own startup (for example when the IDE was closed then) need a manual reconnect, e.g. `/mcp` in Claude Code. |
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
