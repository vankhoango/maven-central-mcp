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
    /** Not provided by the search index; reserved for richer sources. */
    val description: String? = null,
    val url: String,
    /** Epoch milliseconds of the indexed latest version, if reported. */
    val lastUpdatedEpochMillis: Long?,
    val lastUpdated: String?,
    val indexedVersionCount: Int?,
)

@Serializable
data class SearchResultPage(
    val query: String,
    val totalCount: Long,
    val offset: Int,
    val limit: Int,
    val hasMore: Boolean,
    val results: List<ArtifactSummary>,
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
    val policy: String,
    val notes: List<String>,
)
