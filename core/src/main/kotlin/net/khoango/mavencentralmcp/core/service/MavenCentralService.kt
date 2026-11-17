package net.khoango.mavencentralmcp.core.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import net.khoango.mavencentralmcp.core.error.MavenCentralException
import net.khoango.mavencentralmcp.core.model.ArtifactCoordinates
import net.khoango.mavencentralmcp.core.model.ArtifactInfo
import net.khoango.mavencentralmcp.core.model.ArtifactSummary
import net.khoango.mavencentralmcp.core.model.LatestVersionResult
import net.khoango.mavencentralmcp.core.model.PomMetadata
import net.khoango.mavencentralmcp.core.model.SearchRequest
import net.khoango.mavencentralmcp.core.model.SearchResultPage
import net.khoango.mavencentralmcp.core.model.VersionEntry
import net.khoango.mavencentralmcp.core.model.VersionPage
import net.khoango.mavencentralmcp.core.version.VersionPolicy
import net.khoango.mavencentralmcp.maven.api.MavenCentralClient
import net.khoango.mavencentralmcp.maven.dto.SolrArtifactDoc
import net.khoango.mavencentralmcp.maven.mapper.MavenMetadata
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Use cases behind the MCP tools. Independent of IntelliJ and of the MCP transport.
 *
 * Arguments are expected to be validated with
 * [net.khoango.mavencentralmcp.core.model.ArgumentValidator] beforehand.
 */
class MavenCentralService(private val client: MavenCentralClient = MavenCentralClient()) {

    /** Searches the Maven Central index. Empty results are a normal, successful outcome. */
    suspend fun search(request: SearchRequest): SearchResultPage {
        val solrQuery = MavenCentralClient.buildSearchQuery(
            request.query, request.groupId, request.artifactId, request.packaging,
        )
        val response = client.searchArtifacts(solrQuery, rows = request.limit, start = request.offset)
        val results = response.docs.orEmpty().mapNotNull(::toSummary).take(request.limit)
        val total = response.numFound ?: results.size.toLong()
        return SearchResultPage(
            query = request.query,
            totalCount = total,
            offset = request.offset,
            limit = request.limit,
            hasMore = request.offset + results.size < total,
            results = results,
        )
    }

    /** Metadata for one exact coordinate. Throws [MavenCentralException.ArtifactNotFound] if unknown. */
    suspend fun getArtifactInfo(coordinates: ArtifactCoordinates): ArtifactInfo = coroutineScope {
        val notes = mutableListOf<String>()
        val indexed = async { bestEffort(notes, "search index") { findIndexedArtifact(coordinates) } }
        val metadata = client.fetchMetadata(coordinates)
        val doc = indexed.await()
        if (metadata == null && doc == null) {
            throw MavenCentralException.ArtifactNotFound(coordinates.groupId, coordinates.artifactId)
        }

        val versions = metadata?.versions.orEmpty()
        val stable = VersionPolicy.pickNewest(versions.filterNot(VersionPolicy::isPrerelease)).version
        val pomVersion = stable ?: metadata?.release ?: metadata?.latest ?: doc?.latestVersion
        val pom: PomMetadata? = pomVersion?.let { v ->
            bestEffort(notes, "POM") { client.fetchPom(coordinates, v) }
        }

        if (metadata == null) {
            notes += "Repository metadata (maven-metadata.xml) was not found; only search-index data is shown."
        }
        if (doc == null) {
            notes += "The artifact is not present in the search index (the index can lag behind the repository)."
        }
        if (doc?.latestVersion != null && metadata?.release != null && doc.latestVersion != metadata.release) {
            notes += "The search index reports '${doc.latestVersion}' but repository metadata reports " +
                "'${metadata.release}'; the search index can lag behind. Prefer get_latest_version."
        }
        if (pom != null) notes += "Descriptive fields come from the POM of version ${pom.version}; inherited parent-POM values are not resolved."
        notes += "indexedLatestVersion and repositoryReleaseVersion are not guaranteed to be stable versions; use get_latest_version."

        ArtifactInfo(
            groupId = coordinates.groupId,
            artifactId = coordinates.artifactId,
            packaging = doc?.packaging,
            indexedLatestVersion = doc?.latestVersion,
            indexedLastUpdated = doc?.timestamp?.let(::formatEpochMillis),
            repositoryLatestVersion = metadata?.latest,
            repositoryReleaseVersion = metadata?.release,
            repositoryLastUpdated = metadata?.lastUpdated?.let(::formatMetadataTimestamp),
            versionCount = metadata?.versions?.size ?: doc?.versionCount,
            pom = pom,
            links = links(coordinates),
            notes = notes,
        )
    }

    /** Lists versions newest-first by Maven ordering, with optional prerelease filtering. */
    suspend fun getVersions(
        coordinates: ArtifactCoordinates,
        limit: Int,
        offset: Int,
        includePrereleases: Boolean,
    ): VersionPage {
        val metadata = requireMetadata(coordinates)
        val all = VersionPolicy.sortNewestFirst(metadata.versions)
        val filtered = if (includePrereleases) all else all.filterNot(VersionPolicy::isPrerelease)
        val page = filtered.drop(offset).take(limit)

        val notes = mutableListOf<String>()
        val timestamps = if (page.isEmpty()) emptyMap() else bestEffort(notes, "publication timestamps") {
            indexedTimestamps(coordinates)
        }.orEmpty()
        if (page.any { it !in timestamps }) {
            notes += "Publication timestamps come from the search index; versions it has not indexed have no timestamp."
        }
        if (offset >= filtered.size && filtered.isNotEmpty()) {
            notes += "offset $offset is past the end of the list (${filtered.size} versions)."
        }

        return VersionPage(
            groupId = coordinates.groupId,
            artifactId = coordinates.artifactId,
            totalCount = filtered.size,
            offset = offset,
            limit = limit,
            hasMore = offset + page.size < filtered.size,
            includePrereleases = includePrereleases,
            ordering = ORDERING_DESCRIPTION,
            versions = page.map { v ->
                val ts = timestamps[v]
                VersionEntry(v, VersionPolicy.isPrerelease(v), ts, ts?.let(::formatEpochMillis))
            },
            notes = notes,
        )
    }

    /** Resolves the newest published and newest stable versions according to [VersionPolicy]. */
    suspend fun getLatestVersion(coordinates: ArtifactCoordinates, includeSnapshots: Boolean): LatestVersionResult {
        val metadata = requireMetadata(coordinates)
        val eligible = metadata.versions.filter { includeSnapshots || !VersionPolicy.isSnapshot(it) }
        val published = VersionPolicy.pickNewest(eligible)
        val stable = VersionPolicy.pickNewest(eligible.filterNot(VersionPolicy::isPrerelease))

        val notes = mutableListOf<String>()
        val candidates = (listOfNotNull(stable.version).takeIf { stable.competing.isNotEmpty() }.orEmpty() +
            stable.competing + published.competing).distinct()
        val uncertain = candidates.isNotEmpty()
        if (uncertain) {
            notes += "Several versions share the same numeric release and differ only by a non-prerelease qualifier " +
                "(${candidates.joinToString()}). Maven ordering ranks them by qualifier spelling only; " +
                "choose the variant that fits the target platform (for example -jre vs -android)."
        }
        val release = metadata.release
        if (release != null && published.version != null && release != published.version) {
            notes += "The repository's <release> marker is '$release' (the most recently deployed release), which " +
                "differs from the highest version by Maven ordering ('${published.version}'). This usually means an " +
                "older line received a later maintenance release."
        }
        if (eligible.isEmpty()) notes += "No versions are listed in the repository metadata."
        if (stable.version == null && eligible.isNotEmpty()) {
            notes += "No version passed the stable-version policy; every listed version is a prerelease."
        }
        notes += SNAPSHOT_NOTE

        val timestamps = if (eligible.isEmpty()) emptyMap() else bestEffort(notes, "publication timestamps") {
            indexedTimestamps(coordinates)
        }.orEmpty()

        return LatestVersionResult(
            groupId = coordinates.groupId,
            artifactId = coordinates.artifactId,
            latestPublishedVersion = published.version,
            latestStableVersion = stable.version,
            latestPublishedTimestamp = published.version?.let(timestamps::get)?.let(::formatEpochMillis),
            latestStableTimestamp = stable.version?.let(timestamps::get)?.let(::formatEpochMillis),
            versionCount = eligible.size,
            repositoryReleaseVersion = release,
            repositoryLastUpdated = metadata.lastUpdated?.let(::formatMetadataTimestamp),
            uncertain = uncertain,
            candidates = candidates,
            policy = VersionPolicy.POLICY_DESCRIPTION,
            notes = notes,
        )
    }

    private suspend fun requireMetadata(c: ArtifactCoordinates): MavenMetadata =
        client.fetchMetadata(c) ?: throw MavenCentralException.ArtifactNotFound(c.groupId, c.artifactId)

    private suspend fun findIndexedArtifact(c: ArtifactCoordinates): SolrArtifactDoc? {
        val q = "g:${MavenCentralClient.quote(c.groupId)} AND a:${MavenCentralClient.quote(c.artifactId)}"
        return client.searchArtifacts(q, rows = 1, start = 0).docs.orEmpty()
            .firstOrNull { it.g == c.groupId && it.a == c.artifactId }
    }

    private suspend fun indexedTimestamps(c: ArtifactCoordinates): Map<String, Long> =
        client.searchVersions(c, rows = TIMESTAMP_LOOKUP_ROWS).docs.orEmpty()
            .filter { it.g == c.groupId && it.a == c.artifactId }
            .mapNotNull { doc -> doc.v?.let { v -> doc.timestamp?.let { v to it } } }
            .toMap()

    /** Runs an optional enrichment step; failures become a note instead of failing the whole call. */
    private suspend fun <T> bestEffort(notes: MutableList<String>, what: String, block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: MavenCentralException) {
        synchronized(notes) { notes += "Could not load $what: ${e.message}" }
        null
    }

    private fun toSummary(doc: SolrArtifactDoc): ArtifactSummary? {
        val g = doc.g?.takeIf { it.isNotBlank() } ?: return null
        val a = doc.a?.takeIf { it.isNotBlank() } ?: return null
        return ArtifactSummary(
            groupId = g,
            artifactId = a,
            indexedLatestVersion = doc.latestVersion,
            packaging = doc.packaging,
            url = centralUrl(g, a),
            lastUpdatedEpochMillis = doc.timestamp,
            lastUpdated = doc.timestamp?.let(::formatEpochMillis),
            indexedVersionCount = doc.versionCount,
        )
    }

    companion object {
        /** Number of version documents requested from the search index to look up publication timestamps. */
        const val TIMESTAMP_LOOKUP_ROWS = 200

        const val ORDERING_DESCRIPTION =
            "Newest first by Maven version ordering (Apache Maven ComparableVersion), computed locally from the " +
                "repository's maven-metadata.xml. Not lexicographic, and not the repository's deploy order."

        const val SNAPSHOT_NOTE =
            "Maven Central's release repository does not normally host SNAPSHOT versions. includeSnapshots only " +
                "controls whether versions ending in -SNAPSHOT, if any are listed, are eligible for " +
                "latestPublishedVersion; latestStableVersion never includes them."

        internal fun centralUrl(g: String, a: String) = "https://central.sonatype.com/artifact/$g/$a"

        internal fun links(c: ArtifactCoordinates): Map<String, String> = mapOf(
            "centralPortal" to centralUrl(c.groupId, c.artifactId),
            "repositoryDirectory" to "${MavenCentralClient.REPOSITORY_URL}/${MavenCentralClient.artifactPath(c)}/",
        )

        internal fun formatEpochMillis(millis: Long): String = Instant.ofEpochMilli(millis).toString()

        private val METADATA_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

        /** Converts maven-metadata `lastUpdated` (yyyyMMddHHmmss, UTC) to ISO-8601; returns raw on failure. */
        internal fun formatMetadataTimestamp(raw: String): String = try {
            LocalDateTime.parse(raw, METADATA_TS).toInstant(ZoneOffset.UTC).toString()
        } catch (_: Exception) {
            raw
        }
    }
}
