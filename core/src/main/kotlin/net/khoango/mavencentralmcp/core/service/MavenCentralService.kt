package net.khoango.mavencentralmcp.core.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import net.khoango.mavencentralmcp.core.error.MavenCentralException
import net.khoango.mavencentralmcp.core.model.ArgumentValidator
import net.khoango.mavencentralmcp.core.model.ArtifactCoordinates
import net.khoango.mavencentralmcp.core.model.ArtifactInfo
import net.khoango.mavencentralmcp.core.model.ArtifactSummary
import net.khoango.mavencentralmcp.core.model.LatestVersionResult
import net.khoango.mavencentralmcp.core.model.PomMetadata
import net.khoango.mavencentralmcp.core.model.SearchRequest
import net.khoango.mavencentralmcp.core.model.VariantLatest
import net.khoango.mavencentralmcp.core.model.StableLine
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

    /**
     * Searches Maven Central. Empty results are a normal, successful outcome.
     *
     * Free-text queries are re-ranked locally (see [SearchRanking]); search expressions keep the index order.
     * A `groupId:artifactId` query is treated as an exact coordinate lookup and falls back to repository
     * metadata when the (lagging) search index does not know the artifact. POM name/description are loaded
     * for at most [DESCRIPTION_LIMIT] results per call.
     */
    suspend fun search(request: SearchRequest): SearchResultPage {
        val notes = mutableListOf<String>()
        val coordinate = parseCoordinateQuery(request.query)
        if (coordinate != null &&
            (request.groupId?.let { it != coordinate.groupId } == true ||
                request.artifactId?.let { it != coordinate.artifactId } == true)
        ) {
            throw MavenCentralException.InvalidArgument(
                "'query' is the coordinate '$coordinate' but conflicts with the groupId/artifactId filters.",
            )
        }
        val groupId = coordinate?.groupId ?: request.groupId
        val artifactId = coordinate?.artifactId ?: request.artifactId
        val solrQuery = MavenCentralClient.buildSearchQuery(
            if (coordinate != null) null else request.query, groupId, artifactId, request.packaging,
        )
        val terms = if (coordinate == null) SearchRanking.terms(request.query) else null
        val rerank = terms != null && request.offset + request.limit <= SearchRanking.POOL_SIZE

        val response = client.searchArtifacts(
            solrQuery,
            rows = if (rerank) SearchRanking.POOL_SIZE else request.limit,
            start = if (rerank) 0 else request.offset,
        )
        val candidates = response.docs.orEmpty().mapIndexedNotNull { i, doc ->
            val g = doc.g?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
            val a = doc.a?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
            doc to SearchRanking.Candidate(g, a, doc.versionCount, doc.timestamp, i)
        }
        val ordered: List<Pair<SolrArtifactDoc, List<String>>> = if (terms != null && rerank) {
            val docByCandidate = candidates.associate { it.second to it.first }
            SearchRanking.rank(terms, candidates.map { it.second })
                .drop(request.offset).take(request.limit)
                .map { docByCandidate.getValue(it.candidate) to it.signals }
        } else {
            candidates.take(request.limit).map { it.first to emptyList() }
        }
        var total = response.numFound ?: ordered.size.toLong()
        var results = ordered.mapIndexed { i, (doc, signals) ->
            toSummary(doc).copy(rank = request.offset + i + 1, rankingSignals = signals)
        }

        if (coordinate != null && results.isEmpty() && request.offset == 0) {
            repositoryOnlySummary(coordinate)?.let {
                results = listOf(it)
                total = 1
                notes += "'$coordinate' is not in the search index (the index lags behind the repository); " +
                    "this result comes from repository metadata."
            }
        }
        if (rerank && (response.numFound ?: 0) > SearchRanking.POOL_SIZE) {
            notes += "Only the top ${SearchRanking.POOL_SIZE} of ${response.numFound} index matches were re-ranked. " +
                "Add words or a groupId/artifactId filter to narrow the search."
        }
        if (terms != null && !rerank) {
            notes += "Results past position ${SearchRanking.POOL_SIZE} are in search-index order, not re-ranked."
        }
        if (results.size > 1) {
            notes += "Several publishers can use the same artifactId. Ranking signals are facts, not endorsements; " +
                "confirm the groupId with the project's documentation before adding a dependency."
        }
        if (results.isNotEmpty()) {
            notes += "indexedLatestVersion comes from the search index, can lag behind the repository and may be a " +
                "prerelease. Use get_latest_version before recommending a version."
        }

        results = when {
            results.isEmpty() -> results
            request.includeDescriptions -> withDescriptions(results, notes)
            else -> results.also { notes += "Descriptions were not requested (includeDescriptions=false)." }
        }

        return SearchResultPage(
            query = request.query,
            totalCount = total,
            offset = request.offset,
            limit = request.limit,
            hasMore = request.offset + results.size < total,
            ranking = if (rerank) SearchRanking.DESCRIPTION else SearchRanking.UPSTREAM_DESCRIPTION,
            results = results,
            notes = notes,
        )
    }

    /** Loads POM name/description/relocation for the first [DESCRIPTION_LIMIT] results concurrently. */
    private suspend fun withDescriptions(
        results: List<ArtifactSummary>,
        notes: MutableList<String>,
    ): List<ArtifactSummary> = coroutineScope {
        val head = results.take(DESCRIPTION_LIMIT).map { summary ->
            async {
                val version = summary.indexedLatestVersion
                    ?: return@async summary.copy(descriptionSource = "unavailable")
                val pom = try {
                    client.fetchPom(ArtifactCoordinates(summary.groupId, summary.artifactId), version)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: MavenCentralException) {
                    null
                }
                if (pom == null) {
                    summary.copy(descriptionSource = "unavailable")
                } else {
                    summary.copy(
                        name = pom.name,
                        description = pom.description,
                        descriptionSource = "pom:$version",
                        relocatedTo = pom.relocation,
                    )
                }
            }
        }.awaitAll()
        if (head.any { it.descriptionSource == "unavailable" }) {
            notes += "Some descriptions could not be loaded (POM missing or unreachable); description is null for those."
        }
        if (head.any { it.descriptionSource.startsWith("pom:") && it.description == null }) {
            notes += "Some POMs have no <description> (it may be inherited from a parent POM, which is not resolved)."
        }
        if (head.any { it.relocatedTo != null }) {
            notes += "relocatedTo means the publisher moved the artifact to new coordinates; prefer the new coordinates."
        }
        if (results.size > DESCRIPTION_LIMIT) {
            notes += "Descriptions are loaded for the first $DESCRIPTION_LIMIT results only; " +
                "call get_artifact_info for the others."
        }
        head + results.drop(DESCRIPTION_LIMIT)
    }

    /** Builds a search result from maven-metadata.xml for an artifact missing from the search index. */
    private suspend fun repositoryOnlySummary(c: ArtifactCoordinates): ArtifactSummary? {
        val metadata = client.fetchMetadata(c) ?: return null
        return ArtifactSummary(
            groupId = c.groupId,
            artifactId = c.artifactId,
            indexedLatestVersion = metadata.release ?: metadata.latest,
            packaging = null,
            url = centralUrl(c.groupId, c.artifactId),
            lastUpdatedEpochMillis = null,
            lastUpdated = metadata.lastUpdated?.let(::formatMetadataTimestamp),
            indexedVersionCount = metadata.versions.size,
            rank = 1,
            rankingSignals = listOf("exact coordinate match (repository metadata)"),
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
        variant: String? = null,
    ): VersionPage {
        val metadata = requireMetadata(coordinates)
        val all = VersionPolicy.sortNewestFirst(metadata.versions)
        val availableVariants = all.map { variantLabel(it) }.distinct().sorted()
        val wanted = variant?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (wanted != null && wanted !in availableVariants) {
            throw MavenCentralException.InvalidArgument(
                "Unknown variant '$wanted' for $coordinates. Available: ${availableVariants.joinToString()}.",
            )
        }
        val filtered = all
            .filter { includePrereleases || !VersionPolicy.isPrerelease(it) }
            .filter { wanted == null || variantLabel(it) == wanted }
        val page = filtered.drop(offset).take(limit)

        val notes = mutableListOf<String>()
        if (availableVariants.size > 1 && wanted == null) {
            notes += "This artifact publishes platform variants (${availableVariants.joinToString()}); they are " +
                "parallel builds, not newer/older versions of each other. Filter with 'variant' to list one."
        }
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
            variantFilter = wanted,
            availableVariants = availableVariants,
            ordering = ORDERING_DESCRIPTION,
            versions = page.map { v ->
                val ts = timestamps[v]
                VersionEntry(v, VersionPolicy.isPrerelease(v), VersionPolicy.variantOf(v), ts, ts?.let(::formatEpochMillis))
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
            notes += "The newest release is published as several platform variants (${candidates.joinToString()}). " +
                "They are equally valid; latestStableVersion shows the one Maven ordering puts last, which only " +
                "reflects qualifier spelling. Pick the variant for the target platform (see 'variants')."
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
            variants = variantSummary(eligible),
            stableLines = stableLines(eligible),
            guidance = VERSION_GUIDANCE,
            policy = VersionPolicy.POLICY_DESCRIPTION,
            notes = notes,
        )
    }

    /** Latest stable/published per variant, newest variants first; variants with no stable release are omitted. */
    private fun variantSummary(versions: List<String>): List<VariantLatest> {
        val byVariant = versions.groupBy { variantLabel(it) }
        if (byVariant.size < 2) return emptyList()
        return byVariant.mapNotNull { (label, vs) ->
            val stable = VersionPolicy.pickNewest(vs.filterNot(VersionPolicy::isPrerelease)).version ?: return@mapNotNull null
            VariantLatest(label, stable, VersionPolicy.pickNewest(vs).version, vs.size)
        }.sortedWith { x, y -> VersionPolicy.MAVEN_ORDER.compare(y.latestStableVersion!!, x.latestStableVersion!!) }
            .take(MAX_VARIANTS)
    }

    /** Latest stable release of each of the newest major lines (e.g. 4.1.2 and 3.5.8 for Spring Boot). */
    private fun stableLines(versions: List<String>): List<StableLine> =
        versions.filterNot(VersionPolicy::isPrerelease)
            .mapNotNull { v -> VersionPolicy.majorOf(v)?.let { it to v } }
            .groupBy({ it.first }, { it.second })
            .map { (major, vs) -> StableLine(major, VersionPolicy.pickNewest(vs).version!!) }
            .sortedByDescending { it.major }
            .take(MAX_STABLE_LINES)

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

    /** Maps a document whose g/a were already checked to be non-blank. */
    private fun toSummary(doc: SolrArtifactDoc): ArtifactSummary {
        val g = doc.g!!
        val a = doc.a!!
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
        /** Maximum number of POMs fetched per search call to fill in descriptions. */
        const val DESCRIPTION_LIMIT = 5

        /** Field names of the search API; `field:value` with one of these is a search expression. */
        private val SEARCH_FIELDS = setOf("g", "a", "v", "p", "c", "l", "ec", "fc", "id", "text", "tags", "1")
        private val COORDINATE_QUERY = Regex("^([A-Za-z0-9_][A-Za-z0-9_.\\-]*):([A-Za-z0-9_][A-Za-z0-9_.\\-]*)$")

        /** Parses `groupId:artifactId` (not a `field:value` search expression), or returns null. */
        internal fun parseCoordinateQuery(query: String): ArtifactCoordinates? {
            val m = COORDINATE_QUERY.matchEntire(query.trim()) ?: return null
            val (g, a) = m.destructured
            if (g.lowercase() in SEARCH_FIELDS) return null
            return runCatching { ArgumentValidator.coordinates(g, a) }.getOrNull()
        }

        private const val MAX_VARIANTS = 8
        private const val MAX_STABLE_LINES = 5

        /** Label used for versions without a platform variant qualifier. */
        const val NO_VARIANT = "none"

        internal fun variantLabel(version: String): String = VersionPolicy.variantOf(version) ?: NO_VARIANT

        const val VERSION_GUIDANCE =
            "Maven ordering alone does not pick the right version for a project. Use the variant that matches the " +
                "target platform (e.g. Guava -jre for JVM, -android for Android); stay on the major line the project " +
                "already uses unless upgrading deliberately (see stableLines); respect versions managed by a BOM or " +
                "platform (Spring Boot, Kotlin, AndroidX); prefer latestStableVersion over prereleases unless asked."

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
