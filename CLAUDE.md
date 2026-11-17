# Claude Code Instructions

## Mission

Build **Maven Central MCP**, an open-source JetBrains IDE plugin that hosts a local Model Context Protocol (MCP) server. Any MCP-compatible AI coding agent or external client (e.g., Claude Code, Claude Desktop, Cursor, or VS Code) should be able to connect to the server while the IDE is running.

The project must not require a hosted backend, paid infrastructure, accounts, telemetry, or a database.

Read these documents before making implementation changes:

1. `docs/PROJECT_SPEC.md`
2. `docs/ARCHITECTURE.md`
3. `docs/TECH_STACK.md`
4. `docs/IMPLEMENTATION_PLAN.md`

If instructions conflict, follow this order:
1. Explicit user instructions in the current task.
2. `docs/PROJECT_SPEC.md` for product behavior.
3. `docs/ARCHITECTURE.md` for component boundaries.
4. `docs/TECH_STACK.md` for technology choices.
5. This file for workflow and coding conventions.

## Non-negotiable architecture

- Kotlin/JVM and the IntelliJ Platform SDK.
- Use the official `modelcontextprotocol/kotlin-sdk` project:
  `https://github.com/modelcontextprotocol/kotlin-sdk`
- Use the SDK server artifact (`io.modelcontextprotocol:kotlin-sdk-server`) unless a verified compatibility issue requires another official SDK artifact.
- Embed the MCP server in the IDE plugin process for the first release.
- Expose MCP over **Streamable HTTP**, bound only to loopback (`127.0.0.1` or `::1`).
- Do not expose a public network listener.
- Do not implement the MCP wire protocol manually.
- Keep Maven Central API access and version-resolution logic independent of IntelliJ APIs and MCP transport code.
- Do not add a cloud service, database, login, telemetry, or paid dependency.
- Do not use deprecated MCP SSE transport for new functionality.
- Do not claim STDIO support from the embedded HTTP server. STDIO is a separate transport/process model and is out of scope for the initial release.

## Safety and network requirements

- The HTTP listener must never bind to `0.0.0.0` or a public interface.
- Use a configurable local port, with a safe default and clear handling of port conflicts.
- Validate Host/Origin headers and enable Ktor/SDK DNS-rebinding protections as supported by the pinned SDK/Ktor versions.
- Do not enable permissive CORS (`anyHost`) in production.
- Do not accept arbitrary URLs from MCP tool arguments. The Maven Central base URL must be fixed in code/configuration to prevent SSRF.
- Apply request timeouts, bounded response sizes, and bounded result counts.
- Do not log secrets, full potentially sensitive project paths, or complete request payloads.
- No tool may modify project files in the first release.
- Treat Maven Central response data as untrusted input.

## MCP tool contract

Implement the initial tools exactly as specified in `docs/PROJECT_SPEC.md`:

- `search_artifacts`
- `get_artifact_info`
- `get_latest_version`
- `get_versions`

Tool descriptions and JSON schemas must be explicit and useful to an LLM. Validate all arguments and return actionable errors. Prefer structured content when supported by the pinned SDK; also provide readable text content.

Version selection must be transparent:
- Distinguish newest published version from newest stable version.
- Do not silently label a version "stable" based only on a Maven Central `latestVersion` field.
- Explain the prerelease filtering policy in tool descriptions and documentation.
- Preserve the original version string; do not rewrite Maven versions.

## Engineering workflow

- Before coding, inspect the current repository and all relevant SDK/JetBrains documentation.
- Verify exact dependency coordinates, versions, and APIs against official documentation or source code. Do not invent APIs.
- Pin plugin and library versions. Avoid dynamic versions such as `+`, `latest.release`, or version ranges.
- Keep changes small and aligned with the current implementation-plan step.
- Do not silently broaden scope. Ask the user only when a decision blocks implementation.
- Add or update tests with every behavior change.
- Prefer unit tests for core logic and integration tests for the MCP server.
- Add tests for malformed input, empty results, pagination, timeouts, HTTP errors, prerelease versions, and port conflicts.
- Keep IntelliJ Platform calls out of the core module.
- Use coroutines carefully; cancel server jobs and close Ktor resources during plugin disposal.
- Do not block the Swing event dispatch thread with network I/O or server startup/shutdown.
- Do not use `runBlocking` on the EDT.
- Do not print non-MCP output to STDOUT if a future STDIO transport is added.
- Avoid adding libraries when the JDK, Kotlin standard library, IntelliJ Platform, or selected SDK already provides the needed functionality.

## Build and verification

For every implementation task:
1. Run the relevant unit tests.
2. Run the full test suite when practical.
3. Run the plugin verification task provided by the selected IntelliJ Platform Gradle Plugin version.
4. Run the plugin build task.
5. Report exact commands and results.
6. If a command cannot run because of environment/network limitations, state that clearly; never claim it passed.

Do not change dependency versions merely to make a build pass without explaining the compatibility reason.

## Code quality

- Use idiomatic Kotlin, clear names, small functions, and immutable data by default.
- Public APIs and non-obvious behavior require KDoc.
- Keep tool handlers thin; business rules belong in core services.
- Avoid global mutable state.
- Handle cancellation separately from ordinary failures and rethrow coroutine `CancellationException`.
- Return safe, useful error messages without leaking stack traces to MCP clients.
- Keep README and docs synchronized with actual behavior.

## Scope discipline

The first release is read-only Maven Central discovery. Do not implement dependency editing, vulnerability scanning, license analysis, project dependency scanning, IDE inspections, or automatic upgrades unless explicitly requested later.

When completing a task, summarize:
- files created/changed,
- behavior implemented,
- tests/build commands run,
- known limitations,
- next recommended task.
