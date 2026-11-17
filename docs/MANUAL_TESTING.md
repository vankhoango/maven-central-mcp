# Manual Testing

Run before each release against a sandbox IDE (`./gradlew runIde`) or an IDE with the built zip installed.

## Checklist

1. IDE starts; **Settings | Tools | Maven Central MCP** shows `Running at http://127.0.0.1:47115/mcp`.
2. `tools/list` returns exactly `search_artifacts`, `get_artifact_info`, `get_latest_version`, `get_versions`.
3. `search_artifacts {"query":"okhttp"}` returns real results with `totalCount`.
4. `get_artifact_info {"groupId":"com.google.guava","artifactId":"guava"}` returns POM name/description.
5. `get_latest_version` for `io.ktor:ktor-server-core` returns a stable version and a newer prerelease (if any).
6. `get_versions {"groupId":"com.google.guava","artifactId":"guava","limit":5}` returns 5 versions and `hasMore: true`.
7. `get_versions` with `limit: 500` returns `isError: true` naming `limit`.
8. Occupy the port (e.g. a second IDE), restart: a warning balloon reports the conflict; the IDE keeps working.
9. Change the port in settings and apply: the server moves to the new port.
10. Disable the server: the port is released. Exit the IDE: the port is released.
11. `curl -H "Host: evil.example:47115" -X POST http://127.0.0.1:47115/mcp` returns 403.

## Client verification log

| Client | Version | Date | Result |
|---|---|---|---|
| Raw HTTP (curl), sandbox IntelliJ IDEA 2026.1 (IU-261.22158.277) | curl 8.x | 2026-11-17 | Pass: initialize, tools/list (4 tools), all 4 tools against live Maven Central, invalid args → `isError`, not found → `isError`, session DELETE, foreign Host/Origin → 403. Items 1–7 and 11 above. |
| Kotlin MCP SDK client (automated test `EmbeddedMcpHttpServerTest`) | 0.9.0 | 2026-11-17 | Pass: initialize, tools/list, tools/call over Streamable HTTP. |
| MCP Inspector CLI | — | — | not yet run |
| Claude Code | — | — | not yet run |
| Cursor | — | — | not yet run |
| VS Code | — | — | not yet run |
| Claude Desktop (via bridge) | — | — | not yet run |

Checklist items 8–10 (port conflict balloon, port change, disable/exit) are covered by automated controller tests
but have not yet been exercised by hand in the IDE UI.
