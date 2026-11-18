# Project Specification

## 1. Product summary

**Maven Central MCP** is an open-source JetBrains plugin for IntelliJ-based IDEs. It embeds a local MCP server so external MCP-compatible AI clients can search Maven Central and inspect artifact versions.

The plugin is intended for developers using IntelliJ IDEA and Android Studio. It does not require a vendor-operated backend.

## 2. Goals

- Let external MCP clients discover Maven artifacts.
- Let an AI client retrieve artifact metadata and published versions.
- Let an AI client distinguish the newest published version from the newest stable version.
- Make setup understandable to developers without requiring them to run a separate hosted service.
- Keep the implementation small, secure, testable, and maintainable.
- Publish the source code under Apache-2.0 and distribute the plugin through JetBrains Marketplace.

## 3. Non-goals for the first release

- A public/hosted MCP endpoint.
- A database or locally maintained Maven index.
- User accounts, authentication against a vendor service, billing, or telemetry.
- Editing `pom.xml`, `build.gradle`, or `build.gradle.kts`.
- Automatically upgrading dependencies.
- Vulnerability, CVE, license, or supply-chain analysis.
- Scanning every module in an IDE project.
- A custom AI chat interface.
- A standalone CLI or STDIO launcher.
- Guaranteeing availability when the IDE is closed.

## 4. Runtime model

- The plugin starts an embedded MCP server when enabled/started by the IDE.
- Initial transport: MCP Streamable HTTP.
- Default bind address: loopback only (`127.0.0.1`; IPv6 loopback may be supported).
- Default endpoint path: `/mcp`.
- The port is configurable. The plugin must show the actual listening URL and handle conflicts gracefully.
- The server stops cleanly when the plugin/application is disposed or the IDE exits.
- If the IDE is not running, the embedded server is unavailable.
- The first release does not implement STDIO. Documentation must not imply that an HTTP endpoint can be used as an STDIO server.

## 5. Target clients

Target clients include Claude Desktop, Cursor, and VS Code, subject to each client's support for remote/local Streamable HTTP MCP connections and its configuration format at release time.

Do not promise compatibility with every client version. Maintain tested setup examples in the README and record the client versions used for manual verification.

## 6. Initial MCP tools

### 6.1 `search_artifacts`

Purpose: Search Maven Central artifacts.

Inputs:
- `query` (string, required): user-facing search phrase or supported Maven search expression.
- `groupId` (string, optional): exact or supported group filter.
- `artifactId` (string, optional): exact or supported artifact filter.
- `packaging` (string, optional): optional packaging filter if supported by the underlying API.
- `limit` (integer, optional): default 10; enforce a conservative maximum (e.g. 50).
- `offset` (integer, optional): default 0; non-negative.

Output fields per result, when available:
- `groupId`
- `artifactId`
- `latestVersion` (as reported by the artifact-level search result; clearly label its meaning)
- `packaging`
- `description`
- `url`
- `lastUpdated` or equivalent timestamp

Requirements:
- Support pagination.
- Return total result count when available.
- Empty results are a successful response with an empty result list.
- Never claim that artifact-level `latestVersion` is necessarily the latest stable version.

### 6.2 `get_artifact_info`

Purpose: Retrieve metadata for one exact Maven coordinate.

Inputs:
- `groupId` (string, required)
- `artifactId` (string, required)

Output:
- Exact coordinates.
- Available descriptive metadata returned by Maven Central.
- Latest version reported by the artifact-level endpoint, clearly labeled.
- Links or metadata fields when present.

If the artifact does not exist, return a clear not-found result rather than a fabricated object.

### 6.3 `get_latest_version`

Purpose: Resolve the newest published version and newest stable version for an artifact.

Inputs:
- `groupId` (string, required)
- `artifactId` (string, required)
- `includeSnapshots` (boolean, optional; default false)

Output:
- `groupId`
- `artifactId`
- `latestPublishedVersion`
- `latestStableVersion` (nullable if no stable version can be identified)
- `latestPublishedTimestamp` when available
- `versionCount` when available
- A short explanation of the stable/prerelease policy.

Important:
- The tool must inspect the version list and apply an explicit, tested policy.
- Do not assume Maven versions are lexicographically sortable.
- Do not treat the Maven Central `latestVersion` field as a definitive stable version.
- Snapshot versions are normally not hosted as ordinary released artifacts on Maven Central; keep the flag semantics honest and document what it actually controls.
- If version ordering is ambiguous, return the candidates and explain uncertainty rather than inventing a definitive answer.

### 6.4 `get_versions`

Purpose: List versions published for an artifact.

Inputs:
- `groupId` (string, required)
- `artifactId` (string, required)
- `limit` (integer, optional; default 20; maximum 100)
- `offset` (integer, optional; default 0)
- `includePrereleases` (boolean, optional; default true)

Output:
- Coordinates.
- Version strings.
- Published timestamps when available.
- Pagination metadata when available.

Requirements:
- Preserve Maven's original version strings.
- Do not sort versions lexicographically.
- Clearly state whether the API's order is being preserved or a local comparator is used.

## 7. Version semantics

The product must distinguish:
- **Latest published**: newest version according to the repository's version metadata/order, not necessarily stable.
- **Latest stable**: newest version that passes the project's documented prerelease classifier and version ordering policy.

Prerelease qualifiers commonly include (case-insensitive) `alpha`, `a`, `beta`, `b`, `milestone`, `m`, `rc`, `cr`, `preview`, `pre`, `ea`, `snapshot`, and similar tokens. This list is a policy, not a complete formal definition of every ecosystem's qualifier.

Use a Maven-aware version comparison implementation where possible. Verify dependency compatibility and licensing before adding one. If no robust comparator is selected, do not write a naive string comparator and claim correctness. Return uncertainty or use repository-provided ordering with an explicit caveat.

## 8. Maven Central data source

Use the public Sonatype/Maven Central search API (commonly `https://search.maven.org/solrsearch/select`) for artifact discovery and version metadata.

Implementation requirements:
- Keep API endpoint construction in one client class.
- Use URL encoding for query parameters.
- Set connect/request timeouts.
- Bound response size and result count.
- Handle HTTP 429, 5xx, malformed JSON, and network failures.
- Respect server-provided retry guidance where practical.
- Do not scrape HTML pages.
- Do not send arbitrary user-provided URLs to the HTTP client.
- Cache safe GET responses with bounded TTL and bounded memory.

Before implementation, verify the current API query syntax and fields from authoritative Sonatype documentation. Do not assume old Solr fields still behave exactly as expected.

## 9. Plugin user experience

Initial UI should remain minimal:
- A settings page or configurable component for enable/disable and port.
- A status action/tool window showing whether the server is running and the endpoint URL.
- A copy-configuration action if practical.
- Clear startup/port-conflict errors.
- No complex artifact browser in the first release.

The server should not start multiple times if the settings UI or actions are opened repeatedly.

## 10. Security and privacy

- Bind only to loopback.
- Apply Host validation and DNS-rebinding protection supported by the selected SDK/Ktor versions.
- Do not use `anyHost()` in production.
- Do not expose the listener to LAN or public networks.
- Consider a generated local bearer token if client compatibility allows it; document client configuration implications.
- Do not accept arbitrary upstream URLs.
- No telemetry by default.
- No project file reads in the first release.
- No project file modifications.
- Avoid logging full MCP arguments unless sanitized and necessary.

## 11. Quality requirements

- Unit-test core parsing, version classification, ordering, pagination, and error mapping.
- Test MCP tool schemas and handlers.
- Test server startup, shutdown, and port conflicts.
- Test malformed inputs and bounded limits.
- Provide manual connection instructions for at least one client initially, then expand.
- Verify plugin compatibility against explicitly declared IntelliJ IDEA and Android Studio baselines.
- Do not claim Android Studio compatibility until verified against an Android Studio build.

## 12. Acceptance criteria for first usable release

1. Plugin builds successfully.
2. Plugin can be launched in a sandbox IDE.
3. Server starts on loopback and reports its endpoint.
4. MCP Inspector or an equivalent client can initialize and list the four tools.
5. `search_artifacts` returns real Maven Central results.
6. `get_artifact_info` returns metadata for a known artifact.
7. `get_latest_version` distinguishes latest published and latest stable according to documented policy.
8. `get_versions` returns a bounded, paginated version list.
9. Invalid arguments produce useful errors.
10. Server shuts down cleanly.
11. Port conflicts do not crash the IDE.
12. No cloud service or user account is required.
