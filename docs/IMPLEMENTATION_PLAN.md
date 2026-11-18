# Implementation Plan

Work through these stages in order. Do not skip ahead or implement all stages in one unreviewed change.

## Step 1 — Product and architecture specification

Status: Prepared.

Deliverables:
- `CLAUDE.md`
- `README.md`
- `docs/PROJECT_SPEC.md`
- `docs/ARCHITECTURE.md`
- `docs/TECH_STACK.md`
- `docs/IMPLEMENTATION_PLAN.md`

Exit criteria:
- Product scope and non-goals are clear.
- Embedded Streamable HTTP model is explicit.
- No-hosting constraint is explicit.
- Initial MCP tools and semantics are specified.

## Step 2 — Gradle and plugin skeleton

Status: Done.

Deliverables:
- `settings.gradle.kts`
- root `build.gradle.kts`
- `gradle.properties`
- `gradle/libs.versions.toml` if appropriate
- Gradle wrapper
- plugin module build file
- `plugin.xml`
- minimal plugin entry/service
- basic README build instructions

Tasks:
1. Verify compatible versions from official sources.
2. Select package namespace and plugin ID.
3. Select minimum IntelliJ IDEA baseline.
4. Verify Android Studio target compatibility separately.
5. Create the smallest buildable plugin.
6. Run `./gradlew buildPlugin` and plugin verification.

Exit criteria:
- Project imports in IntelliJ IDEA.
- Plugin builds.
- Sandbox IDE can launch the plugin.
- No MCP implementation yet.

## Step 3 — Maven Central core

Status: Done.

Deliverables:
- Domain models.
- Maven Central API client.
- Search and artifact metadata services.
- Version listing and version resolution policy.
- Unit tests with mocked HTTP responses.

Exit criteria:
- Core tests pass without an IDE runtime.
- Search and metadata results are mapped robustly.
- Version policy is documented and tested.

## Step 4 — MCP server

Status: Done.

Deliverables:
- SDK server factory/configuration.
- Four initial MCP tools.
- Tool schemas and handlers.
- Embedded Streamable HTTP endpoint.
- MCP integration tests.

Exit criteria:
- MCP initialize succeeds.
- List-tools returns all expected tools.
- Each tool can be invoked through an MCP client.
- Listener binds only to loopback.

## Step 5 — Plugin lifecycle and settings

Status: Done (UI items 8–10 of docs/MANUAL_TESTING.md still need a manual pass).

Deliverables:
- Application-level server service.
- Startup/shutdown handling.
- Port and enablement settings.
- Status UI/action.
- Error and port-conflict reporting.

Exit criteria:
- Server starts once.
- Server stops on IDE shutdown/disposal.
- No blocking work on EDT.
- Port conflicts do not crash IDE.

## Step 6 — External client setup and manual testing

Status: Partly done: configuration examples and checklist written; verified with curl and the SDK client only. Real third-party clients not yet verified.

Deliverables:
- Claude Desktop configuration example.
- Cursor configuration example.
- VS Code configuration example.
- Manual test checklist.
- Troubleshooting documentation.

Exit criteria:
- At least one external client successfully connects.
- Each additional client is marked tested only after real verification.
- Connection URL and lifecycle limitations are documented.

## Step 7 — Release readiness

Status: Partly done: CI workflow, plugin verification, Apache-2.0 license, Marketplace metadata in `plugin.xml`. Pending: Marketplace listing, screenshots.

Deliverables:
- CI workflow.
- Plugin verification.
- License and notices.
- Marketplace metadata.
- Screenshots if useful.
- Release checklist.

Exit criteria:
- Clean build and tests.
- Plugin verifier passes or documented warnings are understood.
- No public listener.
- No secrets or telemetry.
- README accurately describes compatibility.

## Future work (not first release)

- Standalone STDIO launcher.
- Project dependency scanning.
- Dependency update suggestions.
- Safe build-file edits.
- Vulnerability and license intelligence.
- Additional repository support such as Gradle Plugin Portal or other registries.
