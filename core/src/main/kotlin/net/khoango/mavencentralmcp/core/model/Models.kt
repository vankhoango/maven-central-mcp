package net.khoango.mavencentralmcp.core.model

import kotlinx.serialization.Serializable

/** A Maven `groupId:artifactId` pair. Use [ArgumentValidator.coordinates] to build validated instances. */
@Serializable
data class ArtifactCoordinates(val groupId: String, val artifactId: String) {
    override fun toString(): String = "$groupId:$artifactId"
}

/** Parameters for an artifact search. Values are expected to be validated already. */
data class SearchRequest(
    val query: String,
    val groupId: String? = null,
    val artifactId: String? = null,
    val packaging: String? = null,
    val limit: Int,
    val offset: Int,
    /** Whether to load POM name/description for the first results of the page (bounded, cached). */
    val includeDescriptions: Boolean = true,
)

/**
 * One artifact-level search hit.
 *
 * [indexedLatestVersion] is the `latestVersion` field of the Maven Central search index. It is
 * whatever the index last recorded, may include prereleases, and may lag behind the repository.
 */
@Serializable
data class ArtifactSummary(
    val groupId: String,
    val artifactId: String,
    val indexedLatestVersion: String?,
    /** Whether [indexedLatestVersion] is a prerelease under the documented version policy (null if absent). */
    val indexedLatestVersionPrerelease: Boolean? = null,
    val packaging: String?,
    /** Per-field source/status (search-index fields and POM-derived name/description/relocatedTo). */
    val provenance: Map<String, Provenance> = emptyMap(),
    /** Project name from the POM, when descriptions were loaded. */
    val name: String? = null,
    /** Project description from the POM (the search index has no description field). */
    val description: String? = null,
    /**
     * Mirrors `provenance.description`: its source when a description was found (`pom:<g:a:v>` or
     * `parent-pom:<g:a:v>`), otherwise its status: `missing`, `unavailable`, `not_loaded` or `unresolved`.
     */
    val descriptionSource: String = "not_loaded",
    /** Set when the POM declares that this artifact moved to other coordinates. */
    val relocatedTo: String? = null,
    val url: String,
    /** Epoch milliseconds of the indexed latest version, if reported. */
    val lastUpdatedEpochMillis: Long?,
    val lastUpdated: String?,
    val indexedVersionCount: Int?,
    /** 1-based position in this result ordering. */
    val rank: Int = 0,
    /** Factual reasons behind [rank]. Not an endorsement or a claim about who publishes the artifact. */
    val rankingSignals: List<String> = emptyList(),
)

@Serializable
data class SearchResultPage(
    val query: String,
    val totalCount: Long,
    val offset: Int,
    val limit: Int,
    val hasMore: Boolean,
    /** How results are ordered (local relevance ranking or upstream order). */
    val ranking: String,
    val results: List<ArtifactSummary>,
    val notes: List<String> = emptyList(),
)

/**
 * Where a returned value came from, or why it is absent.
 *
 * [status] is one of:
 * - [PRESENT]: a value was found in [source];
 * - [MISSING]: every source that was checked was read successfully and none contains the value;
 * - [UNAVAILABLE]: a source that could contain the value could not be read (HTTP error, timeout, not found);
 * - [NOT_LOADED]: a source that could contain the value was deliberately not requested (request budget);
 * - [UNRESOLVED]: the source contains a Maven `${...}` property expression, which is not evaluated;
 * - [NOT_COMPUTED]: the artifact's own POM lacks the value and [source] (a parent POM) declares one, but Maven
 *   derives the inherited value (e.g. by appending the module path to a URL) in a way that is not reproduced.
 *
 * [source] names the origin: `search-index`, `repository-metadata`, `pom:<g:a:v>`, `parent-pom:<g:a:v>`,
 * `repository-file-timestamp`, or `maven-default`.
 */
@Serializable
data class Provenance(val status: String, val source: String? = null) {
    companion object {
        const val PRESENT = "present"
        const val MISSING = "missing"
        const val UNAVAILABLE = "unavailable"
        const val NOT_LOADED = "not_loaded"
        const val UNRESOLVED = "unresolved"
        const val NOT_COMPUTED = "not_computed"

        const val SEARCH_INDEX = "search-index"
        const val REPOSITORY_METADATA = "repository-metadata"
        const val FILE_TIMESTAMP = "repository-file-timestamp"
        const val MAVEN_DEFAULT = "maven-default"

        fun present(source: String) = Provenance(PRESENT, source)

        /** The one format for POM sources: `pom:<groupId>:<artifactId>:<version>`. */
        fun pomSource(groupId: String, artifactId: String, version: String) = "pom:$groupId:$artifactId:$version"

        /** The one format for inherited values: `parent-pom:<groupId>:<artifactId>:<version>`. */
        fun parentPomSource(groupId: String, artifactId: String, version: String) =
            "parent-pom:$groupId:$artifactId:$version"
    }
}

/** A `<developer>` entry from a POM. E-mail addresses are deliberately not returned. */
@Serializable
data class Developer(
    val id: String? = null,
    val name: String? = null,
    val organization: String? = null,
    val organizationUrl: String? = null,
    val url: String? = null,
    val roles: List<String> = emptyList(),
)

/** A POM `<license>` entry. Either field may be absent upstream; nothing is filled in. */
@Serializable
data class License(val name: String? = null, val url: String? = null)

/** The POM `<organization>` element. */
@Serializable
data class Organization(val name: String? = null, val url: String? = null)

/**
 * Descriptive metadata from the artifact's POM, with Maven inheritance from parent POMs applied to the
 * inherited fields (description, licenses, developers, organization) up to a bounded depth.
 * See [provenance] for the source or absence reason of every field. Values are never invented.
 */
@Serializable
data class PomMetadata(
    val version: String,
    val name: String? = null,
    val description: String? = null,
    val projectUrl: String? = null,
    val scmUrl: String? = null,
    /**
     * `<url>` as declared by the nearest parent POM when the artifact's own POM has none (provenance of
     * `projectUrl` is then `not_computed`). This is the parent's own value, not the artifact's effective URL.
     */
    val parentProjectUrl: String? = null,
    /** `<scm><url>` as declared by the nearest parent POM when the artifact's own POM has none; see [parentProjectUrl]. */
    val parentScmUrl: String? = null,
    /** License names (or the URL when a license has no name); kept for backward compatibility. */
    val licenses: List<String> = emptyList(),
    /** Each license with its name and URL as declared. Same source and provenance (`licenses`) as [licenses]. */
    val licenseDetails: List<License> = emptyList(),
    /** `<packaging>`, or `jar` (Maven's default) when the POM omits it; see provenance. */
    val packaging: String? = null,
    val developers: List<Developer> = emptyList(),
    val organization: Organization? = null,
    /** `groupId:artifactId[:version]` this artifact was relocated to, per the POM's `<relocation>`. */
    val relocation: String? = null,
    val relocationMessage: String? = null,
    /** POMs that were read, child first (`g:a:v`). */
    val pomChain: List<String> = emptyList(),
    /** Per-field source/status for name, description, projectUrl, scmUrl, licenses, packaging, developers, organization, relocation. */
    val provenance: Map<String, Provenance> = emptyMap(),
)

@Serializable
data class ArtifactInfo(
    val groupId: String,
    val artifactId: String,
    /** Packaging of the latest stable version from its POM (or Maven's `jar` default); see provenance. */
    val packaging: String?,
    /** Packaging recorded by the search index for its latest indexed version, if indexed. */
    val indexedPackaging: String? = null,
    /** `latestVersion` from the search index (may lag and may be a prerelease). */
    val indexedLatestVersion: String?,
    val indexedLastUpdated: String?,
    /** `<latest>` element from maven-metadata.xml (most recently deployed, any kind). */
    val repositoryLatestVersion: String?,
    /** `<release>` element from maven-metadata.xml (most recently deployed non-SNAPSHOT; may be a prerelease). */
    val repositoryReleaseVersion: String?,
    val repositoryLastUpdated: String?,
    val versionCount: Int?,
    val pom: PomMetadata?,
    val links: Map<String, String>,
    /** Per-field source/status for the top-level fields (see [Provenance]). */
    val provenance: Map<String, Provenance> = emptyMap(),
    val notes: List<String>,
)

@Serializable
data class VersionEntry(
    val version: String,
    val prerelease: Boolean,
    /** Platform variant label (e.g. `jre`, `android`), or null for a plain release. */
    val variant: String? = null,
    /** Publication time; see [publishedProvenance] for its source or why it is absent. */
    val publishedEpochMillis: Long? = null,
    val published: String? = null,
    val publishedProvenance: Provenance = Provenance(Provenance.NOT_LOADED),
)

@Serializable
data class VersionPage(
    val groupId: String,
    val artifactId: String,
    val totalCount: Int,
    val offset: Int,
    val limit: Int,
    val hasMore: Boolean,
    val includePrereleases: Boolean,
    /** The variant filter applied, if any (`none` = plain releases only). */
    val variantFilter: String? = null,
    /** All variant labels present for this artifact (null label shown as `none`). */
    val availableVariants: List<String> = emptyList(),
    val ordering: String,
    val versions: List<VersionEntry>,
    val notes: List<String>,
)

@Serializable
data class LatestVersionResult(
    val groupId: String,
    val artifactId: String,
    val latestPublishedVersion: String?,
    val latestStableVersion: String?,
    val latestPublishedTimestamp: String?,
    val latestStableTimestamp: String?,
    /** Source/status of the two timestamps (keys `latestPublishedTimestamp`, `latestStableTimestamp`). */
    val timestampProvenance: Map<String, Provenance> = emptyMap(),
    val versionCount: Int,
    val repositoryReleaseVersion: String?,
    val repositoryLastUpdated: String?,
    /** True when the ordering of the top candidates is ambiguous; see [candidates] and [notes]. */
    val uncertain: Boolean,
    /** Competing versions when [uncertain] is true. */
    val candidates: List<String>,
    /** Latest stable/published version per platform variant (e.g. jre and android for Guava). */
    val variants: List<VariantLatest> = emptyList(),
    /** Latest stable version of each of the newest major lines, for projects pinned to a major version. */
    val stableLines: List<StableLine> = emptyList(),
    /** How to choose a version for a project; Maven ordering alone does not decide it. */
    val guidance: String = "",
    val policy: String,
    val notes: List<String>,
)

@Serializable
data class VariantLatest(
    /** Variant label, or `none` for plain releases without a platform qualifier. */
    val variant: String,
    val latestStableVersion: String?,
    val latestPublishedVersion: String?,
    val versionCount: Int,
)

@Serializable
data class StableLine(
    val major: Long,
    val latestStableVersion: String,
)
