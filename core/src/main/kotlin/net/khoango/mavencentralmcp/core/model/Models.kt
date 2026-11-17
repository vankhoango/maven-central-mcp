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
    val packaging: String?,
    /** Project name from the POM, when descriptions were loaded. */
    val name: String? = null,
    /** Project description from the POM (the search index has no description field). */
    val description: String? = null,
    /**
     * Where [name]/[description] came from: `pom:<version>` if the POM was read (fields may still be null if
     * the POM has none), `not_loaded` if this result was outside the description budget, or `unavailable`.
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

/** Descriptive metadata from the artifact's POM. All fields are optional upstream data. */
@Serializable
data class PomMetadata(
    val version: String,
    val name: String? = null,
    val description: String? = null,
    val projectUrl: String? = null,
    val scmUrl: String? = null,
    val licenses: List<String> = emptyList(),
    /** `groupId:artifactId[:version]` this artifact was relocated to, per the POM's `<relocation>`. */
    val relocation: String? = null,
    val relocationMessage: String? = null,
)

@Serializable
data class ArtifactInfo(
    val groupId: String,
    val artifactId: String,
    /** Packaging reported by the search index, if indexed. */
    val packaging: String?,
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
    val notes: List<String>,
)

@Serializable
data class VersionEntry(
    val version: String,
    val prerelease: Boolean,
    /** Platform variant label (e.g. `jre`, `android`), or null for a plain release. */
    val variant: String? = null,
    /** Publication time from the search index, if the index knows this version. */
    val publishedEpochMillis: Long? = null,
    val published: String? = null,
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
