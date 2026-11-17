# Architecture

## 1. High-level design

The first release is a single JetBrains plugin process containing an embedded Ktor server and the official Kotlin MCP SDK server implementation.

```text
External MCP Client
       |
       | Streamable HTTP (loopback)
       v
Kotlin MCP SDK Server
       |
       v
MCP Tool Handlers
       |
       v
Maven Central Services
       |
       v
Maven Central API (HTTPS)
```

The plugin lifecycle owns the server:

```text
IDE application starts
       |
       v
Plugin application service initializes
       |
       v
Read settings / validate port
       |
       v
Start embedded server asynchronously
       |
       v
Expose local endpoint
       |
       v
IDE/plugin disposal -> stop server and cancel jobs
```

## 2. Logical components

### 2.1 IntelliJ plugin layer

Responsibilities:
- Register the plugin descriptor and services.
- Own application-level server lifecycle.
- Read and persist plugin settings using supported IntelliJ APIs.
- Provide a small status/settings UI.
- Report startup and shutdown errors safely.

Must not:
- Contain Maven search query logic.
- Parse Maven Central JSON.
- Implement MCP protocol messages manually.
- Perform blocking network work on the Swing EDT.

### 2.2 MCP server layer

Responsibilities:
- Construct the SDK `Server`.
- Register tool definitions and handlers.
- Mount the SDK Streamable HTTP endpoint into Ktor.
- Convert validated tool arguments to application-service calls.
- Convert domain results/errors to MCP content.

Must not:
- Depend on IntelliJ UI classes.
- Construct arbitrary upstream URLs.
- Own Maven version-comparison policy.

### 2.3 Core layer

Responsibilities:
- Domain models for artifact coordinates, artifact metadata, version entries, and paged results.
- Search and lookup use cases.
- Latest-published/latest-stable resolution policy.
- Input validation independent of MCP.
- Mapping repository responses to domain models.

Must not:
- Depend on IntelliJ Platform APIs.
- Depend on MCP transport classes.
- Depend on Ktor server classes.

### 2.4 Maven Central client

Responsibilities:
- Call the fixed Maven Central search API.
- Encode query parameters.
- Apply timeouts and bounded response handling.
- Parse JSON into transport DTOs.
- Map HTTP and parsing errors into safe domain errors.
- Implement bounded in-memory caching.

Keep repository DTOs separate from public domain models.

## 3. Suggested package layout

Implemented module split (Gradle):

- `core` — packages `core.*` and `maven.*`. Depends only on kotlinx (coroutines, serialization) and `maven-artifact`.
- `mcp-server` — packages `mcp.tool` and `mcp.server`. Adds the MCP SDK and Ktor CIO. Includes the IDE-free
  `McpServerController` that owns start/stop/port-conflict handling, so lifecycle is testable without an IDE.
- root project — package `ide.*`, the IntelliJ plugin. Thin wrappers over the controller.

Test seam: `MavenCentralClient` takes an `HttpFetcher`; tests inject a fake fetcher. Base URLs stay constants.

Suggested packages:

```text
net.khoango.mavencentralmcp
├── core
│   ├── model
│   ├── service
│   ├── version
│   └── error
├── maven
│   ├── api
│   ├── dto
│   ├── mapper
│   └── cache
├── mcp
│   ├── server
│   ├── tool
│   └── schema
└── ide
    ├── service
    ├── settings
    ├── action
    └── ui
```

## 4. Transport and client compatibility

Initial transport is Streamable HTTP at `/mcp`, bound to loopback only.

Important distinction:
- Streamable HTTP clients connect to the local HTTP endpoint.
- STDIO clients launch a process and communicate through stdin/stdout.
- An HTTP server embedded in the IDE is not automatically a STDIO server.

A future standalone launcher may reuse the same core and MCP tool-registration code to provide STDIO. This is explicitly outside the first release.

## 5. Server lifecycle

Use an IntelliJ application-level service (or the current recommended equivalent for the selected platform baseline) as the single owner of:
- Coroutine scope/job.
- Actual bound port.
- Server state.
- Startup/shutdown error state.

Requirements:
- Start off the EDT.
- Do not block IDE startup indefinitely.
- Prevent duplicate starts.
- Handle bind failures and occupied ports.
- Support orderly stop and cancellation.
- Close Ktor resources on disposal.
- Ensure no orphaned non-daemon threads remain.
- Make lifecycle behavior testable without launching the full IDE where possible.

## 6. Local HTTP security

- Bind explicitly to `127.0.0.1`, optionally also `::1`.
- Never bind to wildcard interfaces.
- Configure allowed hosts/origins and DNS-rebinding protection using the APIs of the pinned Kotlin SDK.
- Do not copy old SDK examples that use deprecated configuration properties without checking current APIs.
- Avoid permissive CORS. Native desktop clients generally do not require browser CORS.
- Consider local token authentication after verifying support across target clients.
- Keep the port and endpoint visible to the user.

## 7. Error model

Core errors should be typed, for example:
- `InvalidArgument`
- `ArtifactNotFound`
- `RateLimited`
- `UpstreamUnavailable`
- `UpstreamTimeout`
- `MalformedUpstreamResponse`
- `VersionResolutionUncertain`

MCP handlers translate these into concise, safe messages. Stack traces belong in local diagnostic logs only, not tool responses.

## 8. Test strategy

- Core unit tests: no network and no IDE runtime.
- Maven client tests: mock HTTP engine or local mock server.
- MCP tests: SDK in-memory/channel transport where supported by the pinned SDK.
- Server integration tests: start server on an ephemeral loopback port and exercise initialize/list-tools/call-tool.
- Plugin tests: verify service registration, settings, lifecycle, and disposal.
- Manual tests: MCP Inspector, Claude Desktop, Cursor, and VS Code.

Use test seams for the Maven Central base URL in tests only. Production tool arguments must never control the upstream host.
