# Manual Testing

Run before each release against a sandbox IDE (`./gradlew runIde`) or an IDE with the built zip installed.

## Checklist

1. IDE starts; **Settings | Tools | Maven Central MCP** shows `Running at http://127.0.0.1:<port>/mcp`, and
   `idea.log` contains `Maven Central MCP server listening at …`.
2. `tools/list` returns exactly `search_artifacts`, `get_artifact_info`, `get_latest_version`, `get_versions`.
3. `search_artifacts {"query":"okhttp"}` ranks `com.squareup.okhttp3:okhttp` first, with `rankingSignals` and a
   POM description; `{"query":"jackson databind"}` ranks `com.fasterxml.jackson.core:jackson-databind` first.
4. `search_artifacts {"query":"mysql-connector-java"}` shows `relocatedTo: com.mysql:mysql-connector-j`.
5. `get_artifact_info {"groupId":"com.google.guava","artifactId":"guava"}` returns POM name/description.
6. `get_latest_version` for Guava reports `uncertain: true` and `variants` jre/android/none; for
   `org.springframework.boot:spring-boot` a prerelease `latestPublishedVersion`, a stable `latestStableVersion` and
   `stableLines` per major.
7. `get_versions {"groupId":"com.google.guava","artifactId":"guava","variant":"android","limit":3}` returns only
   `-android` versions; `limit: 500` or `variant: "ios"` return `isError: true`.
7a. `get_versions` for `io.ktor:ktor-server-core` (limit 30): recent versions dated with source
   `repository-file-timestamp`, older ones `search-index`; no blank date without a provenance status.
7b. `get_artifact_info` for `com.fasterxml.jackson.core:jackson-databind`: developers/organization with source
   `parent-pom …jackson-bom…`; for `com.squareup.okhttp3:okhttp`: packaging `jar` with source `maven-default …`;
   no `@` in developer data.
8. Hold the port with another process, start the IDE: one warning balloon (with **Retry**) and one
   `server not started: Port … is already in use` line in `idea.log`; the IDE keeps working. `idea.log` has no
   `SEVERE` entry and no `Plugin to blame: Maven Central MCP` (search for `SEVERE`, not only `ERROR`), and the
   status bar shows no IDE error indicator.
9. Change the port in settings and apply: the server moves to the new port.
10. Disable the server: the port is released. Exit the IDE: connections are refused.
11. Restart the IDE: a request with the old `mcp-session-id` returns 404 `Session not found`; a new `initialize`
    works.
12. `curl -H "Host: evil.example:<port>" -X POST http://127.0.0.1:<port>/mcp` returns 403.
13. `tools/call` with an unknown tool name returns `isError: true` ("Tool … not found") and adds no `SEVERE` entry
    to `idea.log`.

## Verification log

| What | Version | Date | Result |
|---|---|---|---|
| Raw HTTP (curl), sandbox IntelliJ IDEA 2026.1 (IU-261.22158.277) | curl 8.x | 2026-11-17 | Pass: items 1–2, 5, 7 (limit), 12; all four tools against live Maven Central. |
| Python stdlib Streamable HTTP client, sandbox IDEA 2026.1, port 9909 | — | 2026-11-18 | Pass: items 1–7 and 11 against live Maven Central. |
| Python client, sandbox IDEA 2026.1, port 9909: dates and POM provenance | — | 2026-11-18 | Pass: items 7a–7b; Guava/Ktor/Spring Boot dates, Guava/Jackson/OkHttp/Spring Boot/JUnit metadata. The search index timed out on some calls; affected fields reported `unavailable` with fallbacks. |
| Port conflict at IDE start (port held by another process), sandbox IDEA 2026.1 | — | 2026-11-18 | Corrected by the release-candidate run: exactly one `server not started` warning, but the same log also held a `SEVERE … Unhandled exception … BindException` with `Plugin to blame: Maven Central MCP` (the check searched for `ERROR` only). Fixed afterwards; see below. |
| IDE stop → port released, connections refused | — | 2026-11-18 | Pass (process stop, not a graceful UI exit). |
| Kotlin MCP SDK client (automated `EmbeddedMcpHttpServerTest`, `ReconnectionTest`) | 0.9.0 | 2026-11-18 | Pass: initialize, tools/list, tools/call, reconnect after server restart. |
| User integration test (IntelliJ, port 9909) | — | 2026-11-18 | Reported by user: connects, all four tools callable, connection fails when IntelliJ exits. |
| Release candidate: built zip in clean profiles of IDEA 2026.1 (IU-261.22158.277) and 2026.2.3 (IU-262.10968.63), port 9909 | — | 2026-11-18 | Pass: items 1–2, 8 (log only), 10 (exit; disable via profile setting, not the UI), 11 (restart), 12–13; auto-start off leaves only the IDE's own 127.0.0.1:63343 listener; `[::1]` not bound; graceful exit logs `server stopped`, frees the port. Port conflict and unknown-tool checks repeated after the fixes: one WARN, no `SEVERE`, no client tool names in `idea.log`. |
| Claude Code CLI 2.1.302 (user-scope HTTP entry) | 2.1.302 | 2026-11-18 | Partial: `claude mcp get` reports Connected and the four tools are discovered; tool execution not run (CLI not logged in). |
| Claude Code agent session in the Claude desktop app (tools called by the agent, not via the app UI) | — | 2026-11-18 | Pass: all four tools against live Maven Central, feature search, invalid coordinate and unknown artifact return clean errors; disconnect on IDE exit, reconnect after restart. |
| MCP Inspector CLI, Cursor, VS Code, Antigravity, Claude Desktop app UI | — | — | not yet run by this project |

Items 9–10 (port change and disable via the settings UI) are covered by automated controller tests but have not been
clicked through by hand.
