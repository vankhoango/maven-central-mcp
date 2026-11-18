package net.khoango.mavencentralmcp.mcp.tool

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import net.khoango.mavencentralmcp.core.error.MavenCentralException
import net.khoango.mavencentralmcp.core.model.ArgumentValidator
import net.khoango.mavencentralmcp.core.model.ArtifactInfo
import net.khoango.mavencentralmcp.core.model.LatestVersionResult
import net.khoango.mavencentralmcp.core.model.SearchRequest
import net.khoango.mavencentralmcp.core.model.SearchResultPage
import net.khoango.mavencentralmcp.core.model.VersionPage
import net.khoango.mavencentralmcp.core.service.MavenCentralService
import net.khoango.mavencentralmcp.core.version.VersionPolicy
import java.util.logging.Level
import java.util.logging.Logger

/** Transport-independent description of one MCP tool. */
class ToolDefinition(
    val name: String,
    val title: String,
    val description: String,
    val inputSchema: ToolSchema,
    val annotations: ToolAnnotations,
    val handler: suspend (JsonObject?) -> CallToolResult,
)

/**
 * The four read-only Maven Central tools. Handlers are thin: they validate arguments, call
 * [MavenCentralService], and convert results or typed errors to MCP results.
 */
class MavenCentralTools(private val service: MavenCentralService) {

    private val log = Logger.getLogger(MavenCentralTools::class.java.name)

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = true
    }

    fun all(): List<ToolDefinition> = listOf(searchArtifacts(), getArtifactInfo(), getLatestVersion(), getVersions())

    private val readOnly = ToolAnnotations(
        readOnlyHint = true,
        destructiveHint = false,
        idempotentHint = true,
        openWorldHint = true,
    )

    private fun searchArtifacts() = ToolDefinition(
        name = SEARCH_ARTIFACTS,
        title = "Search Maven Central artifacts",
        description = """
            Search Maven Central for artifacts by name or by feature. Plain words (e.g. 'jackson databind', 'okhttp',
            'speech recognition') match groupId/artifactId tokens and also description keywords: the search index's
            keywords derived from each POM description, so feature searches find libraries whose names do not contain
            the words (marked by a 'description keywords contain all query terms' signal). Every word must match;
            words are matched literally, so for features also try synonyms (e.g. 'speech-to-text', 'transcription').
            'nameMatchCount' and 'descriptionKeywordMatchCount' split totalCount; 'descriptionKeywordMatching' is
            applied, unavailable (keyword query failed: name matches only), no_meaningful_words or not_applicable.
            Up to 100 name matches plus 100 description-keyword matches are re-ranked locally: for a groupId-shaped
            query (e.g. 'com.fasterxml.jackson.core') artifacts in exactly that group come first; otherwise exact
            artifactId match first, then a query term in the groupId, number of published versions and recency.
            Every result lists its 'rankingSignals' (index-ordered results state their index position).
            Ranking is a heuristic and does NOT prove who publishes an artifact; several publishers often share an
            artifactId (forks, repackages), so compare groupId, indexedVersionCount, description and relocatedTo.
            An exact 'groupId:artifactId' query looks up that coordinate. Search expressions such as 'g:com.squareup*'
            or 'a:okhttp' are passed through in index order. Optional groupId/artifactId/packaging filters are exact.
            POM name/description are loaded for the first ${MavenCentralService.DESCRIPTION_LIMIT} results of each page
            (from up to ${MavenCentralService.SEARCH_MAX_PARENTS} parent POMs if inherited); descriptionSource is the
            source (pom:<g:a:v> or parent-pom:<g:a:v>) or the status missing/unavailable/not_loaded/unresolved. Paginated (limit default ${ArgumentValidator.SEARCH_DEFAULT_LIMIT},
            max ${ArgumentValidator.SEARCH_MAX_LIMIT}) with totalCount; an empty 'results' list means nothing matched.
            'indexedLatestVersion' comes from the search index, can lag behind the repository and may be a prerelease
            ('indexedLatestVersionPrerelease');
            call get_latest_version before recommending a version.
        """.trimIndent(),
        inputSchema = schema(
            required = listOf("query"),
            "query" to stringProp("Search words naming an artifact or a feature ('okhttp', 'jackson databind', 'speech recognition'), an exact 'groupId:artifactId', or a search expression ('g:io.ktor AND a:ktor-server*'). Max ${ArgumentValidator.MAX_QUERY_LENGTH} chars."),
            "groupId" to stringProp("Optional exact groupId filter, e.g. 'com.squareup.okhttp3'."),
            "artifactId" to stringProp("Optional exact artifactId filter, e.g. 'okhttp'."),
            "packaging" to stringProp("Optional packaging filter, e.g. 'jar', 'aar', 'pom', 'bundle'."),
            "limit" to intProp("Maximum results to return.", ArgumentValidator.SEARCH_DEFAULT_LIMIT, 1, ArgumentValidator.SEARCH_MAX_LIMIT),
            "offset" to intProp("Number of results to skip for pagination.", 0, 0, ArgumentValidator.SEARCH_MAX_OFFSET),
            "includeDescriptions" to boolProp("Load POM name/description for the first ${MavenCentralService.DESCRIPTION_LIMIT} results (one cached request each).", true),
        ),
        annotations = readOnly,
    ) { raw ->
        respond(SearchResultPage.serializer(), { page ->
            "Found ${page.totalCount} artifact(s); showing ${page.results.size} from offset ${page.offset}" +
                (if (page.hasMore) " (more available)." else ".") +
                page.results.joinToString("") { r ->
                    "\n${r.rank}. ${r.groupId}:${r.artifactId}" + (r.name?.let { " - $it" } ?: "") +
                        (r.indexedVersionCount?.let { " ($it versions)" } ?: "") +
                        (r.relocatedTo?.let { " [relocated to $it]" } ?: "")
                }
        }) {
            val args = ToolArguments(raw, setOf("query", "groupId", "artifactId", "packaging", "limit", "offset", "includeDescriptions"))
            service.search(
                SearchRequest(
                    query = ArgumentValidator.query(args.string("query")),
                    groupId = ArgumentValidator.optionalId("groupId", args.string("groupId")),
                    artifactId = ArgumentValidator.optionalId("artifactId", args.string("artifactId")),
                    packaging = ArgumentValidator.optionalPackaging(args.string("packaging")),
                    limit = ArgumentValidator.limit(args.int("limit"), ArgumentValidator.SEARCH_DEFAULT_LIMIT, ArgumentValidator.SEARCH_MAX_LIMIT),
                    offset = ArgumentValidator.offset(args.int("offset"), ArgumentValidator.SEARCH_MAX_OFFSET),
                    includeDescriptions = args.boolean("includeDescriptions") ?: true,
                ),
            )
        }
    }

    private fun getArtifactInfo() = ToolDefinition(
        name = GET_ARTIFACT_INFO,
        title = "Get Maven artifact metadata",
        description = """
            Get metadata for one exact Maven coordinate (groupId + artifactId), from the POM of the latest stable
            version: name, description, project URL, SCM URL, licenses (names in 'licenses', name+URL in
            'licenseDetails'), packaging, developers (without e-mail),
            organization and relocation; plus version count, repository and search-index 'latest' markers, and links.
            description/licenses/developers/organization follow Maven inheritance through up to 3 parent POMs.
            'provenance' (top level and in 'pom') gives each field's source (pom:<g:a:v>, parent-pom:<g:a:v>,
            repository-metadata, search-index, maven-default) or why it is absent: missing (checked, not declared),
            unavailable (source could not be read), not_loaded (not requested), unresolved (unevaluated ${'$'}{...}),
            not_computed (only a parent POM declares projectUrl/scmUrl; its own value is in parentProjectUrl/parentScmUrl,
            but the artifact's derived URL is not computed).
            'packaging' comes from the POM (Maven's 'jar' default when undeclared, labelled maven-default);
            'indexedPackaging' is the search index's value. 'releaseVariants' lists the platform variants of the latest
            stable release (e.g. 33.8.0-jre, 33.8.0-android), the described one first; it is absent when that release
            has a single form. Timestamps are ISO-8601 UTC with millisecond precision ('.000' for second-precision
            sources). Returns an error result if the artifact does not exist.
            The 'indexedLatestVersion', 'repositoryLatestVersion' and 'repositoryReleaseVersion' fields are raw
            repository markers and are NOT guaranteed to be stable; use get_latest_version for that.
        """.trimIndent(),
        inputSchema = coordinateSchema(),
        annotations = readOnly,
    ) { raw ->
        respond(ArtifactInfo.serializer(), { info ->
            "${info.groupId}:${info.artifactId}" + (info.pom?.name?.let { " - $it" } ?: "") +
                (info.versionCount?.let { " ($it versions)" } ?: "")
        }) {
            val args = ToolArguments(raw, setOf("groupId", "artifactId"))
            service.getArtifactInfo(ArgumentValidator.coordinates(args.string("groupId"), args.string("artifactId")))
        }
    }

    private fun getLatestVersion() = ToolDefinition(
        name = GET_LATEST_VERSION,
        title = "Resolve latest published and latest stable version",
        description = """
            Resolve the newest published version and the newest stable version of a Maven artifact from the
            authoritative repository metadata (maven-metadata.xml), using Maven's own version ordering.
            latestPublishedVersion may be a prerelease; latestStableVersion excludes prereleases and is null if none exist.
            Platform variants (e.g. Guava -jre/-android, mssql-jdbc .jre8/.jre11) are separate, equally valid builds:
            'variants' gives the latest stable and published version of each, and 'uncertain' is true when the newest
            release exists in several variants ('candidates'). 'stableLines' gives the latest stable version per major
            line. Maven ordering alone does not choose a version for a project; follow 'guidance'.
            Prerelease policy: ${VersionPolicy.POLICY_DESCRIPTION}
            includeSnapshots (default false): whether -SNAPSHOT versions, if listed, may be latestPublishedVersion.
            Maven Central does not normally host snapshots.
        """.trimIndent(),
        inputSchema = coordinateSchema(
            "includeSnapshots" to boolProp("Allow -SNAPSHOT versions to be reported as latestPublishedVersion.", false),
        ),
        annotations = readOnly,
    ) { raw ->
        respond(LatestVersionResult.serializer(), { r ->
            "${r.groupId}:${r.artifactId} latest published: ${r.latestPublishedVersion ?: "none"}; " +
                "latest stable: ${r.latestStableVersion ?: "none"}" +
                (if (r.uncertain) " (released in several platform variants, see 'variants')" else "") +
                r.variants.joinToString("") { "\n  variant ${it.variant}: stable ${it.latestStableVersion ?: "none"}" }
        }) {
            val args = ToolArguments(raw, setOf("groupId", "artifactId", "includeSnapshots"))
            service.getLatestVersion(
                ArgumentValidator.coordinates(args.string("groupId"), args.string("artifactId")),
                includeSnapshots = args.boolean("includeSnapshots") ?: false,
            )
        }
    }

    private fun getVersions() = ToolDefinition(
        name = GET_VERSIONS,
        title = "List Maven artifact versions",
        description = """
            List versions of a Maven artifact, newest first by Maven version ordering (Apache Maven ComparableVersion,
            computed locally; not lexicographic and not deploy order). Version strings are returned exactly as published.
            Each entry has a 'prerelease' flag (same policy as get_latest_version), a platform 'variant' label (null for
            plain releases) and a publication date with 'publishedProvenance': from the search index (which stopped
            indexing new versions around mid-2025), else the upload time of the version's POM file on the repository
            (repository-file-timestamp, up to 25 lookups per call); otherwise status unavailable or not_loaded, never
            guessed. These are upload times, not declared release dates. 'availableVariants' lists the
            labels; pass 'variant' (e.g. 'android', or 'none' for plain releases) to list one variant only. Paginated: limit default ${ArgumentValidator.VERSIONS_DEFAULT_LIMIT}, max ${ArgumentValidator.VERSIONS_MAX_LIMIT}; totalCount and hasMore are returned.
        """.trimIndent(),
        inputSchema = coordinateSchema(
            "limit" to intProp("Maximum versions to return.", ArgumentValidator.VERSIONS_DEFAULT_LIMIT, 1, ArgumentValidator.VERSIONS_MAX_LIMIT),
            "offset" to intProp("Number of versions to skip for pagination.", 0, 0, ArgumentValidator.VERSIONS_MAX_OFFSET),
            "includePrereleases" to boolProp("Include alpha/beta/RC/milestone/etc. versions.", true),
            "variant" to stringProp("Optional platform variant to list, e.g. 'jre', 'android', or 'none' for unqualified releases."),
        ),
        annotations = readOnly,
    ) { raw ->
        respond(VersionPage.serializer(), { p ->
            "${p.groupId}:${p.artifactId}: ${p.versions.size} of ${p.totalCount} version(s) from offset ${p.offset}" +
                (if (p.hasMore) " (more available)." else ".")
        }) {
            val args = ToolArguments(raw, setOf("groupId", "artifactId", "limit", "offset", "includePrereleases", "variant"))
            val coordinates = ArgumentValidator.coordinates(args.string("groupId"), args.string("artifactId"))
            service.getVersions(
                coordinates,
                limit = ArgumentValidator.limit(args.int("limit"), ArgumentValidator.VERSIONS_DEFAULT_LIMIT, ArgumentValidator.VERSIONS_MAX_LIMIT),
                offset = ArgumentValidator.offset(args.int("offset"), ArgumentValidator.VERSIONS_MAX_OFFSET),
                includePrereleases = args.boolean("includePrereleases") ?: true,
                variant = ArgumentValidator.optionalVariant(args.string("variant")),
            )
        }
    }

    /**
     * Runs [block] and converts the outcome to a [CallToolResult]: structured content plus a readable
     * summary on success, or a safe error message with `isError = true` on failure.
     */
    private suspend fun <T> respond(serializer: KSerializer<T>, summary: (T) -> String, block: suspend () -> T): CallToolResult =
        try {
            val result = block()
            val element = json.encodeToJsonElement(serializer, result).jsonObject
            CallToolResult(
                content = listOf(TextContent(summary(result) + "\n\n" + json.encodeToString(JsonObject.serializer(), element))),
                structuredContent = element,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: MavenCentralException) {
            log.log(Level.FINE, "Tool call failed: ${e.javaClass.simpleName}")
            errorResult(e.message ?: "Request failed.")
        } catch (e: Exception) {
            log.log(Level.WARNING, "Unexpected tool failure", e)
            errorResult("Internal error while processing the request. See the IDE log for details.")
        }

    companion object {
        const val SEARCH_ARTIFACTS = "search_artifacts"
        const val GET_ARTIFACT_INFO = "get_artifact_info"
        const val GET_LATEST_VERSION = "get_latest_version"
        const val GET_VERSIONS = "get_versions"

        internal fun errorResult(message: String) = CallToolResult(content = listOf(TextContent(message)), isError = true)

        private fun schema(required: List<String>, vararg props: Pair<String, JsonObject>) = ToolSchema(
            properties = buildJsonObject { props.forEach { (k, v) -> put(k, v) } },
            required = required,
        )

        private fun coordinateSchema(vararg extra: Pair<String, JsonObject>) = schema(
            listOf("groupId", "artifactId"),
            "groupId" to stringProp("Exact Maven groupId, e.g. 'com.google.guava'."),
            "artifactId" to stringProp("Exact Maven artifactId, e.g. 'guava'."),
            *extra,
        )

        private fun stringProp(description: String) = buildJsonObject {
            put("type", "string")
            put("description", description)
        }

        private fun intProp(description: String, default: Int, min: Int, max: Int) = buildJsonObject {
            put("type", "integer")
            put("description", description)
            put("default", default)
            put("minimum", min)
            put("maximum", max)
        }

        private fun boolProp(description: String, default: Boolean) = buildJsonObject {
            put("type", "boolean")
            put("description", description)
            put("default", default)
        }
    }
}
