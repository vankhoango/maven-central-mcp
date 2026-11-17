# Technical Stack and Decisions

This document records intended choices. Exact versions must be verified and pinned during Step 2, using official release notes and compatibility information.

## 0. Selected versions (verified November 2026)

All versions are pinned in `gradle/libs.versions.toml`.

| Component | Version | Why |
|---|---|---|
| Kotlin Gradle plugin | 2.3.21 | `apiVersion`/`languageVersion` 2.3 matches the Kotlin stdlib bundled by the baseline IDE (2026.1 → 2.3.20). |
| IntelliJ Platform Gradle Plugin | 2.20.1 | Latest 2.x release on the Gradle Plugin Portal. Requires Gradle 9.0+. |
| Gradle (wrapper) | 9.5.1 | |
| Baseline platform | IntelliJ IDEA 2026.1 (`sinceBuild` 261, `untilBuild` 262.*) | Lowest platform whose bundled Kotlin stdlib (2.3.20) and coroutines (1.10.2) satisfy MCP SDK 0.9.0 (stdlib 2.2.21, coroutines 1.10.2). Java 21. |
| MCP SDK | `io.modelcontextprotocol:kotlin-sdk-server` 0.9.0 | Latest release on Maven Central. Provides `Application.mcpStreamableHttp` with DNS-rebinding options. |
| Ktor server (CIO, content negotiation) | 3.2.3 | The version SDK 0.9.0 is built against. SDK 0.9.0 does not install ContentNegotiation itself, so the plugin installs it with the SDK's `McpJson`. |
| kotlinx-serialization-json | 1.9.0 | SDK requirement. |
| kotlinx-coroutines | 1.10.2 | Compile/test only; the IDE's copy is used at runtime and the plugin does not bundle it. |
| Version comparison | `org.apache.maven:maven-artifact` 3.9.16 (`ComparableVersion` only, `plexus-utils` excluded) | Maven's own ordering algorithm; Apache-2.0. |
| HTTP client | JDK `java.net.http.HttpClient` | No extra dependency; avoids Ktor client classloader overlap with the IDE. |
| Tests | JUnit 6.1.3, kotlinx-coroutines-test, MCP SDK client 0.9.0 | |

Runtime classpath of the plugin excludes the Kotlin stdlib, kotlinx-coroutines and slf4j-api (all provided by the IDE).

**Data-source decision.** The Solr search API (`search.maven.org/solrsearch/select`) was found to lag far behind the
repository (e.g. Ktor 3.2.0 vs. 3.6.1, Guava 33.4.8 vs. 33.8.0 in November 2026). It is therefore used only for
discovery and timestamps. Version lists and latest/stable resolution use `maven-metadata.xml` from
`repo1.maven.org` (same operator, fixed host, no HTML scraping).

## 1. Language and runtime

- Kotlin/JVM.
- Target the JVM runtime required by the selected IntelliJ Platform baseline.
- Use coroutines for asynchronous network/server work.
- Do not use Kotlin Multiplatform in the plugin itself unless a concrete reuse requirement emerges. The official MCP SDK is multiplatform, but this product is a JVM plugin.

## 2. MCP implementation

Use the official Kotlin SDK:

- Repository: https://github.com/modelcontextprotocol/kotlin-sdk
- Server artifact: `io.modelcontextprotocol:kotlin-sdk-server`
- Avoid manually implementing JSON-RPC or MCP protocol handling.
- Use the SDK's current Streamable HTTP integration.
- Do not use deprecated SSE transport for new development.

The SDK README states that Ktor engine dependencies are not necessarily brought in transitively; declare the server engine explicitly.

Before coding:
- Check the latest stable SDK release and migration notes.
- Inspect the exact APIs in the selected version.
- Confirm whether the SDK's Streamable HTTP helper is available in that version.
- Do not copy API calls from older examples without compiling them against the pinned version.


## 3. IntelliJ Platform

Use the current IntelliJ Platform Gradle Plugin 2.x, not the legacy Gradle IntelliJ Plugin 1.x.

At the time this specification was prepared, official JetBrains documentation showed IntelliJ Platform Gradle Plugin 2.20.1. Re-check before pinning.

Choose a declared minimum platform baseline and verify:
- IntelliJ IDEA compatibility.
- Android Studio compatibility separately.
- Plugin verifier results.
- Kotlin compiler/runtime compatibility.
- Third-party library/classloader compatibility.

Do not claim Android Studio compatibility merely because the plugin builds against IntelliJ IDEA. Add Android Studio to plugin compatibility metadata only after testing an actual supported Android Studio build.

## 4. Build system

- Gradle Kotlin DSL.
- Gradle Wrapper committed to source control.
- Version catalog (`gradle/libs.versions.toml`) for dependency/plugin versions where useful.
- Pin all versions.
- No dynamic dependency versions.
- Configure reproducible builds where practical.

The exact Gradle, Kotlin Gradle Plugin, IntelliJ Platform Gradle Plugin, SDK, and Ktor versions must be selected as a compatible set in Step 2.

## 5. HTTP client and JSON

Prefer a single HTTP client stack where practical. Ktor client can be used if it does not cause problematic version/classloader conflicts with the server stack. Otherwise use Java's built-in `java.net.http.HttpClient`.

For JSON:
- Use the JSON library already compatible with the chosen SDK dependency set where practical.
- Keep repository DTO parsing isolated.
- Be tolerant of optional/missing Maven Central fields.
- Fail safely on malformed required fields.

Avoid pulling in multiple JSON libraries without a clear reason.

## 6. Version comparison

Maven version ordering is not equivalent to lexicographic string ordering.

Evaluate a Maven-aware version comparator compatible with the selected JVM/plugin baseline. Document its behavior and test common qualifier cases.

Do not write a naive `String.compareTo` implementation for semantic version ordering.

## 7. Testing

- JUnit 5 or the test framework recommended by the selected IntelliJ Platform Gradle Plugin template.
- Mock HTTP responses for Maven Central client tests.
- Kotlin coroutine test utilities where required.
- MCP SDK in-memory/channel testing transport if available in the pinned SDK.
- IntelliJ Platform test framework for plugin-specific tests.
- Plugin verification task in CI.

Pin test dependencies as well.

## 9. Distribution and license

- Source hosting: GitHub.
- Plugin distribution: JetBrains Marketplace.
- License target: Apache-2.0, pending repository owner confirmation.
- No paid hosting or external runtime dependency.
- Include third-party license notices if required by included libraries.

## 10. Official references

- Kotlin MCP SDK: https://github.com/modelcontextprotocol/kotlin-sdk
- Kotlin MCP SDK releases: https://github.com/modelcontextprotocol/kotlin-sdk/releases
- IntelliJ Platform Plugin SDK: https://plugins.jetbrains.com/docs/intellij/
- IntelliJ Platform Gradle Plugin: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
- Maven Central search API: https://central.sonatype.org/
