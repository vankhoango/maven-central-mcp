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
import net.khoango.mavencentralmcp.core.model.Provenance
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

    private val poms = PomResolver(client)
    private val dates = PublicationDates(client)

    /**
     * Searches Maven Central. Empty results are a normal, successful outcome.
     *
     * Free-text queries are re-ranked locally (see [SearchRanking]) within the top [SearchRanking.POOL_SIZE]
     * index matches; positions beyond the pool, search expressions and pure filter queries keep the index order.
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
        // Trailing sentence punctuation ('json parser!') is not part of the search; '!'/'?' would otherwise be operators.
        val query = if (coordinate == null) SearchRanking.normalize(request.query) else request.query
        if (query != request.query.trim()) notes += "Trailing punctuation was ignored; searched for '$query'."
        if (coordinate == null) {
            SearchRanking.wordsWithDroppedSymbols(query).takeIf { it.isNotEmpty() }?.let { words ->
                notes += "The search index ignores symbols such as + and #, so ${words.joinToString { "'$it'" }} " +
                    "matched only the letters and digits (e.g. 'c++' and 'c#' both match 'c'). Results may not reflect " +
                    "the intended meaning; consider spelling it out (e.g. 'cpp', 'csharp')."
            }
        }
        val solrQuery = MavenCentralClient.buildSearchQuery(
            if (coordinate != null) null else query, groupId, artifactId, request.packaging,
        )
        val terms = if (coordinate == null) SearchRanking.terms(query) else null
        val offset = request.offset
        val limit = request.limit
        val pageEnd = offset + limit

        val ordered = mutableListOf<Pair<SolrArtifactDoc, List<String>>>()
        var total: Long
        var reranked = 0
        var indexOrdered = 0
        var keywordTotal = 0L
        var keywordOnPage = 0
        var poolBoundary = SearchRanking.POOL_SIZE
        var nameMatchCount: Long? = null
        var keywordMatching = KEYWORDS_NOT_APPLICABLE
        if (terms != null) {
            // Two disjoint result sets: name matches (the search index's own query) and description-keyword-only
            // matches (see SearchRanking.keywordQuery). Each contributes its top POOL_SIZE to one re-ranked pool.
            val groupLookup = isExistingGroup(query, notes)
            val keywordExpression = if (groupLookup) null else SearchRanking.keywordQuery(query)
            when {
                groupLookup -> notes += "'$query' is a groupId in the search index (it or its subgroups have artifacts), " +
                    "so it was searched by name only, without description-keyword matching."
                keywordExpression == null -> {
                    keywordMatching = KEYWORDS_NO_TERMS
                    notes += "Description-keyword matching was not applied: after removing grammatical words and " +
                        "punctuation, the query has no meaningful word. Only name matches are returned."
                }
                else -> keywordMatching = KEYWORDS_APPLIED
            }
            val keywordSolr = keywordExpression?.let { MavenCentralClient.buildSearchQuery(it, groupId, artifactId, request.packaging) }
            val (namePool, keywordPool) = coroutineScope {
                val keyword = keywordSolr?.let { q ->
                    async {
                        try {
                            client.searchArtifacts(q, rows = SearchRanking.POOL_SIZE, start = 0)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: MavenCentralException) {
                            keywordMatching = KEYWORDS_UNAVAILABLE
                            notes += "Description-keyword matching failed and was skipped (${e.message}); totalCount and " +
                                "results include name matches only. Retry later for feature-search results."
                            null
                        }
                    }
                }
                client.searchArtifacts(solrQuery, rows = SearchRanking.POOL_SIZE, start = 0) to keyword?.await()
            }
            val nameDocs = namePool.docs.orEmpty()
            val keywordDocs = keywordPool?.docs.orEmpty()
            val nameTotal = namePool.numFound ?: nameDocs.size.toLong()
            keywordTotal = keywordPool?.numFound ?: keywordDocs.size.toLong()
            total = nameTotal + keywordTotal
            nameMatchCount = nameTotal

            val nameCandidates = candidatesOf(nameDocs, firstPosition = 0)
            val nameKeys = nameCandidates.map { it.second.key() }.toSet()
            val allKeywordCandidates = candidatesOf(keywordDocs, firstPosition = nameDocs.size)
            val keywordCandidates = allKeywordCandidates.filter { it.second.key() !in nameKeys } // disjoint by construction
            val overlap = allKeywordCandidates.size - keywordCandidates.size
            if (overlap > 0) {
                keywordTotal -= overlap
                total -= overlap
            }
            val keywordKeys = keywordCandidates.map { it.second.key() }.toSet()
            val candidates = nameCandidates + keywordCandidates
            val poolSize = candidates.size
            val docByCandidate = candidates.associate { it.second to it.first }
            if (offset < poolSize) {
                SearchRanking.rank(
                    terms, candidates.map { it.second }, SearchRanking.groupQuery(query), keywordKeys,
                    phrase = SearchRanking.isPhraseQuery(query),
                )
                    .drop(offset).take(limit)
                    .forEach { ordered += docByCandidate.getValue(it.candidate) to it.signals }
                reranked = ordered.size
            }
            keywordOnPage = ordered.count { (doc, _) -> "${doc.g}:${doc.a}" in keywordKeys }

            // Positions past the pool: remaining name matches in index order, then remaining keyword matches.
            val deepStart = maxOf(offset, poolSize)
            val deepEnd = minOf(pageEnd.toLong(), total).toInt()
            if (deepStart < deepEnd) {
                val nameRemaining = (nameTotal - nameDocs.size).coerceAtLeast(0).toInt()
                val nameFrom = deepStart - poolSize
                val nameTo = minOf(deepEnd - poolSize, nameRemaining)
                // Guard against an index that returns already-pooled documents again (never seen live).
                val seen = candidates.map { it.second.key() }.toMutableSet()
                fun List<SolrArtifactDoc>.unseen() = filter { seen.add("${it.g}:${it.a}") }
                if (nameFrom < nameTo) {
                    val start = nameDocs.size + nameFrom
                    val deep = client.searchArtifacts(solrQuery, rows = nameTo - nameFrom, start = start)
                    ordered += upstreamOrdered(deep.docs.orEmpty().unseen(), deepStart, groupId, coordinateQuery = false)
                }
                val kwFrom = maxOf(nameFrom, nameRemaining) - nameRemaining
                val kwTo = deepEnd - poolSize - nameRemaining
                if (keywordSolr != null && kwFrom < kwTo) {
                    val start = keywordDocs.size + kwFrom
                    val deep = client.searchArtifacts(keywordSolr, rows = kwTo - kwFrom, start = start).docs.orEmpty().unseen()
                    ordered += upstreamOrdered(
                        deep, poolSize + nameRemaining + kwFrom, groupId,
                        coordinateQuery = false, descriptionKeywordMatch = true,
                    )
                    keywordOnPage += deep.size
                }
                indexOrdered = ordered.size - reranked
            }
            poolBoundary = poolSize
        } else {
            val page = client.searchArtifacts(solrQuery, rows = limit, start = offset)
            total = page.numFound ?: page.docs.orEmpty().size.toLong()
            ordered += upstreamOrdered(page.docs.orEmpty(), offset, groupId, coordinate != null)
            indexOrdered = ordered.size
        }

        var results = ordered.mapIndexed { i, (doc, signals) ->
            toSummary(doc).copy(rank = offset + i + 1, rankingSignals = signals)
        }
        if (coordinate != null && results.isEmpty() && offset == 0) {
            repositoryOnlySummary(coordinate)?.let {
                results = listOf(it)
                total = 1
                notes += "'$coordinate' is not in the search index (the index lags behind the repository); " +
                    "this result comes from repository metadata."
            }
        }

        // Notes describe the result set actually returned.
        if (offset >= total && total > 0) {
            notes += "offset $offset is at or beyond totalCount $total; there are no results on this page. " +
                "Use an offset below $total."
        }
        if (reranked > 0 && total > poolBoundary) {
            notes += "Only the top $poolBoundary of $total matches are re-ranked (up to ${SearchRanking.POOL_SIZE} name " +
                "matches and ${SearchRanking.POOL_SIZE} description-keyword matches). Add words or a groupId/artifactId " +
                "filter to narrow the search."
        }
        if (terms != null && indexOrdered > 0) {
            notes += "Results from position ${poolBoundary + 1} onward are in search-index order (not re-ranked): " +
                "remaining name matches first, then remaining description-keyword matches."
        }
        if (keywordTotal > 0) {
            notes += "$keywordTotal of $total matches were found through description keywords (the search index's tags, " +
                "derived from POM descriptions), not names" + (if (keywordOnPage > 0) "; $keywordOnPage on this page" else "") +
                ". They carry the signal '${SearchRanking.DESCRIPTION_KEYWORD_SIGNAL}'."
        }
        if (keywordMatching == KEYWORDS_APPLIED && total < limit) {
            notes += "Words are matched literally against names and description keywords. For a feature search, also " +
                "try synonyms or related terms (for example 'speech recognition', 'speech-to-text', 'transcription')."
        }
        val sharedArtifactIds = results.groupBy { it.artifactId }.filterValues { hits -> hits.map { it.groupId }.distinct().size > 1 }
        if (sharedArtifactIds.isNotEmpty()) {
            notes += "Several publishers use the same artifactId here (${sharedArtifactIds.keys.take(3).joinToString()}). Ranking signals are facts, not endorsements; " +
                "confirm the groupId with the project's documentation before adding a dependency."
        }
        if (results.isNotEmpty()) {
            notes += "indexedLatestVersion comes from the search index, can lag behind the repository and may be a " +
                "prerelease (see indexedLatestVersionPrerelease). Use get_latest_version before recommending a version."
        }

        results = when {
            results.isEmpty() -> results
            request.includeDescriptions -> withDescriptions(results, notes)
            else -> results.map { it.withPomFieldsNotLoaded() }
                .also { notes += "Descriptions were not requested (includeDescriptions=false)." }
        }

        return SearchResultPage(
            query = request.query,
            totalCount = total,
            offset = offset,
            limit = limit,
            hasMore = offset + results.size < total,
            ranking = when {
                reranked > 0 && indexOrdered > 0 -> SearchRanking.MIXED_DESCRIPTION
                reranked > 0 -> SearchRanking.DESCRIPTION
                indexOrdered > 0 -> SearchRanking.UPSTREAM_DESCRIPTION
                terms != null -> SearchRanking.DESCRIPTION
                else -> SearchRanking.UPSTREAM_DESCRIPTION
            },
            results = results,
            nameMatchCount = nameMatchCount ?: total,
            descriptionKeywordMatchCount = if (keywordMatching == KEYWORDS_APPLIED) keywordTotal else null,
            descriptionKeywordMatching = keywordMatching,
            notes = notes,
        )
    }

    /**
     * True if [query] is a single dotted token (e.g. `net.sf.json-lib`, `edu.illinois`, `io.ktor`) for which the
     * search index has artifacts in that group or a subgroup. Natural-language dotted queries (`speech.to.text`,
     * `node.js`) have none and are searched as words. If the check itself fails, the query is treated as words and
     * a note says the classification could not be verified.
     */
    private suspend fun isExistingGroup(query: String, notes: MutableList<String>): Boolean {
        val group = SearchRanking.groupQuery(query) ?: return false
        return try {
            (client.searchArtifacts(SearchRanking.groupExistsQuery(group), rows = 0, start = 0).numFound ?: 0) > 0
        } catch (e: CancellationException) {
            throw e
        } catch (e: MavenCentralException) {
            notes += "Could not check whether '$query' is a groupId (${e.message}); it was searched as words."
            false
        }
    }

    private fun candidatesOf(docs: List<SolrArtifactDoc>, firstPosition: Int) = docs.mapIndexedNotNull { i, doc ->
        val g = doc.g?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
        val a = doc.a?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
        doc to SearchRanking.Candidate(g, a, doc.versionCount, doc.timestamp, firstPosition + i)
    }

    /** Index-ordered results with factual signals; [start] is the 0-based index position of the first doc. */
    private fun upstreamOrdered(
        docs: List<SolrArtifactDoc>,
        start: Int,
        groupFilter: String?,
        coordinateQuery: Boolean,
        descriptionKeywordMatch: Boolean = false,
    ) = candidatesOf(docs, start).let { candidates ->
        val newest = candidates.mapNotNull { it.second.timestamp }.maxOrNull()
        candidates.map { (doc, c) ->
            doc to SearchRanking.upstreamSignals(c, c.upstreamPosition + 1, newest, groupFilter, coordinateQuery, descriptionKeywordMatch)
        }
    }

    private fun SearchRanking.Candidate.key() = "$groupId:$artifactId"

    /**
     * Loads POM name/description/relocation for the first [DESCRIPTION_LIMIT] results of the page concurrently.
     * If a POM has no description of its own but declares a parent, up to [SEARCH_MAX_PARENTS] parent POMs are
     * read for the inherited description. `descriptionSource` always equals `provenance.description`'s source
     * when present, or its status otherwise.
     */
    private suspend fun withDescriptions(
        results: List<ArtifactSummary>,
        notes: MutableList<String>,
    ): List<ArtifactSummary> = coroutineScope {
        val head = results.take(DESCRIPTION_LIMIT).map { summary -> async { loadPomFields(summary) } }.awaitAll()
        when {
            head.any { it.provenance["description"]?.status == Provenance.UNAVAILABLE } ->
                notes += "Some descriptions could not be loaded (POM or parent POM missing or unreachable)."
        }
        if (head.any { it.provenance["description"]?.source?.startsWith("parent-pom:") == true }) {
            notes += "Some descriptions are inherited from a parent POM (descriptionSource parent-pom:...)."
        }
        if (head.any { it.provenance["description"]?.status == Provenance.NOT_LOADED }) {
            notes += "Some descriptions may be inherited from parent POMs beyond the $SEARCH_MAX_PARENTS levels search " +
                "reads (status not_loaded); get_artifact_info reads deeper."
        }
        if (head.any { it.relocatedTo != null }) {
            notes += "relocatedTo means the publisher moved the artifact to new coordinates; prefer the new coordinates."
        }
        if (results.size > DESCRIPTION_LIMIT) {
            notes += "Descriptions are loaded for the first $DESCRIPTION_LIMIT results of each page only " +
                "(descriptionSource not_loaded for the rest); call get_artifact_info for the others."
        }
        head + results.drop(DESCRIPTION_LIMIT).map { it.withPomFieldsNotLoaded() }
    }

    private suspend fun loadPomFields(summary: ArtifactSummary): ArtifactSummary {
        val version = summary.indexedLatestVersion
            ?: return summary.withPomFields(Provenance(Provenance.UNAVAILABLE, Provenance.SEARCH_INDEX))
        val coordinates = ArtifactCoordinates(summary.groupId, summary.artifactId)
        val pomSource = Provenance.pomSource(summary.groupId, summary.artifactId, version)
        val pom = try {
            // Own POM first; parents only when the description could be inherited (cached GETs).
            poms.resolve(coordinates, version, maxParents = 0)?.let { own ->
                if (own.provenance["description"]?.status == Provenance.NOT_LOADED) {
                    poms.resolve(coordinates, version, maxParents = SEARCH_MAX_PARENTS)
                } else {
                    own
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: MavenCentralException) {
            null
        } ?: return summary.withPomFields(Provenance(Provenance.UNAVAILABLE, pomSource))
        return summary.copy(
            name = pom.name,
            description = pom.description,
            relocatedTo = pom.relocation,
        ).withPomProvenance(
            name = pom.provenance.getValue("name"),
            description = pom.provenance.getValue("description"),
            relocatedTo = pom.provenance.getValue("relocation"),
        )
    }

    private fun ArtifactSummary.withPomFieldsNotLoaded(): ArtifactSummary = withPomFields(
        Provenance(Provenance.NOT_LOADED, indexedLatestVersion?.let { Provenance.pomSource(groupId, artifactId, it) }),
    )

    private fun ArtifactSummary.withPomFields(all: Provenance) = withPomProvenance(all, all, all)

    /** Sets POM-derived provenance and keeps `descriptionSource` consistent with `provenance.description`. */
    private fun ArtifactSummary.withPomProvenance(name: Provenance, description: Provenance, relocatedTo: Provenance) =
        copy(
            descriptionSource = descriptionSourceOf(description),
            provenance = provenance + mapOf("name" to name, "description" to description, "relocatedTo" to relocatedTo),
        )

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
            indexedLatestVersionPrerelease = (metadata.release ?: metadata.latest)?.let(VersionPolicy::isPrerelease),
            rank = 1,
            rankingSignals = listOf("exact coordinate match (repository metadata)"),
            provenance = mapOf(
                "indexedLatestVersion" to Provenance.present(Provenance.REPOSITORY_METADATA),
                "packaging" to Provenance(Provenance.NOT_LOADED, (metadata.release ?: metadata.latest)?.let { Provenance.pomSource(c.groupId, c.artifactId, it) }),
                "lastUpdated" to Provenance.present(Provenance.REPOSITORY_METADATA),
                "indexedVersionCount" to Provenance.present(Provenance.REPOSITORY_METADATA),
            ),
        )
    }

    /**
     * Metadata for one exact coordinate. Throws [MavenCentralException.ArtifactNotFound] if unknown.
     * Reads repository metadata, the search index, the POM of the latest stable version and up to
     * [PomResolver.INFO_MAX_PARENTS] parent POMs (all cached). Every top-level field has a provenance entry.
     */
    suspend fun getArtifactInfo(coordinates: ArtifactCoordinates): ArtifactInfo = coroutineScope {
        val notes = mutableListOf<String>()
        val indexed = async {
            try {
                Result.success(findIndexedArtifact(coordinates))
            } catch (e: CancellationException) {
                throw e
            } catch (e: MavenCentralException) {
                Result.failure(e)
            }
        }
        val metadata = client.fetchMetadata(coordinates)
        val indexResult = indexed.await()
        val doc = indexResult.getOrNull()
        indexResult.exceptionOrNull()?.let { notes += "Could not load search index: ${it.message}" }
        if (metadata == null && doc == null) {
            throw MavenCentralException.ArtifactNotFound(coordinates.groupId, coordinates.artifactId)
        }

        val versions = metadata?.versions.orEmpty()
        val stablePick = VersionPolicy.pickNewest(versions.filterNot(VersionPolicy::isPrerelease))
        val stable = stablePick.version
        // Same release published as several platform variants (Guava 33.8.0-jre / 33.8.0-android). Derived from the
        // version list already loaded, with the same detection get_latest_version uses; null when there are none.
        val releaseVariants = stable?.takeIf { stablePick.competing.isNotEmpty() }?.let { listOf(it) + stablePick.competing }
        val pomVersion = stable ?: metadata?.release ?: metadata?.latest ?: doc?.latestVersion
        var pomFailure: String? = null
        val pom: PomMetadata? = pomVersion?.let { v ->
            try {
                poms.resolve(coordinates, v, PomResolver.INFO_MAX_PARENTS).also {
                    if (it == null) pomFailure = "not found"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: MavenCentralException) {
                pomFailure = e.message
                null
            }
        }
        pomFailure?.let { notes += "Could not load the POM of $pomVersion: $it" }

        // Provenance of the top-level fields.
        val indexStatus = when {
            indexResult.isFailure -> Provenance(Provenance.UNAVAILABLE, Provenance.SEARCH_INDEX)
            doc == null -> Provenance(Provenance.MISSING, Provenance.SEARCH_INDEX)
            else -> null
        }
        fun fromIndex(value: Any?) = indexStatus
            ?: if (value != null) Provenance.present(Provenance.SEARCH_INDEX) else Provenance(Provenance.MISSING, Provenance.SEARCH_INDEX)
        fun fromMetadata(value: Any?) = if (metadata != null && value != null) {
            Provenance.present(Provenance.REPOSITORY_METADATA)
        } else {
            Provenance(Provenance.MISSING, Provenance.REPOSITORY_METADATA)
        }
        val pomSource = pomVersion?.let { Provenance.pomSource(coordinates.groupId, coordinates.artifactId, it) }
        val pomProvenance = when {
            pom != null -> Provenance.present(Provenance.pomSource(coordinates.groupId, coordinates.artifactId, pom.version))
            pomVersion == null -> Provenance(Provenance.MISSING, pomSource)
            else -> Provenance(Provenance.UNAVAILABLE, pomSource)
        }
        val packagingProvenance = pom?.provenance?.get("packaging") ?: pomProvenance
        val provenance = linkedMapOf(
            "packaging" to packagingProvenance,
            "indexedPackaging" to fromIndex(doc?.packaging),
            "indexedLatestVersion" to fromIndex(doc?.latestVersion),
            "indexedLastUpdated" to fromIndex(doc?.timestamp),
            "repositoryLatestVersion" to fromMetadata(metadata?.latest),
            "repositoryReleaseVersion" to fromMetadata(metadata?.release),
            "repositoryLastUpdated" to fromMetadata(metadata?.lastUpdated),
            "versionCount" to if (metadata != null) Provenance.present(Provenance.REPOSITORY_METADATA) else fromIndex(doc?.versionCount),
            "pom" to pomProvenance,
        )

        if (metadata == null) {
            notes += "Repository metadata (maven-metadata.xml) was not found; only search-index data is shown."
        }
        if (doc == null && indexResult.isSuccess) {
            notes += "The artifact is not present in the search index (the index can lag behind the repository)."
        }
        if (doc?.latestVersion != null && metadata?.release != null && doc.latestVersion != metadata.release) {
            notes += "The search index reports '${doc.latestVersion}' but repository metadata reports " +
                "'${metadata.release}'; the search index can lag behind. Prefer get_latest_version."
        }
        if (pom != null) {
            notes += "POM fields describe version ${pom.version}. description, licenses, developers and organization " +
                "follow Maven inheritance through up to ${PomResolver.INFO_MAX_PARENTS} parent POMs; name and packaging " +
                "come from the artifact's own POM. projectUrl and scmUrl are the artifact's own POM values; when its POM " +
                "has none, the nearest parent POM's declared value is returned separately as parentProjectUrl / " +
                "parentScmUrl (Maven would derive the artifact's URL from it by appending the module path, which is not " +
                "reproduced). See pom.provenance."
            if (pom.scmUrl == null && pom.parentScmUrl != null) {
                notes += "scmUrl is null because the artifact's own POM declares no <scm>; SCM information is still " +
                    "available from parent POM ${pom.provenance["scmUrl"]?.source?.removePrefix("parent-pom:")}: " +
                    "parentScmUrl = ${pom.parentScmUrl}."
            }
            if (pom.provenance.values.any { it.status == Provenance.UNRESOLVED }) {
                notes += "Some POM values contain \${...} property expressions, which are not evaluated (status unresolved)."
            }
            notes += "Developer e-mail addresses are not returned."
        }
        if (releaseVariants != null) {
            notes += "The latest stable release is published in several platform variants " +
                "(${releaseVariants.joinToString()}); POM fields describe $pomVersion. Choose the variant for the " +
                "target platform; get_latest_version lists the latest version of every variant."
        }
        notes += "indexedLatestVersion and repositoryReleaseVersion are not guaranteed to be stable versions; use get_latest_version."

        ArtifactInfo(
            groupId = coordinates.groupId,
            artifactId = coordinates.artifactId,
            packaging = pom?.packaging,
            indexedPackaging = doc?.packaging,
            indexedLatestVersion = doc?.latestVersion,
            indexedLastUpdated = doc?.timestamp?.let(::formatEpochMillis),
            repositoryLatestVersion = metadata?.latest,
            repositoryReleaseVersion = metadata?.release,
            repositoryLastUpdated = metadata?.lastUpdated?.let(::formatMetadataTimestamp),
            versionCount = metadata?.versions?.size ?: doc?.versionCount,
            pom = pom,
            releaseVariants = releaseVariants,
            links = links(coordinates),
            provenance = provenance,
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
        val published = dates.lookup(coordinates, page, PublicationDates.VERSIONS_FILE_TIMESTAMP_BUDGET, notes)
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
                val date = published.getValue(v)
                VersionEntry(
                    version = v,
                    prerelease = VersionPolicy.isPrerelease(v),
                    variant = VersionPolicy.variantOf(v),
                    publishedEpochMillis = date.epochMillis,
                    published = date.epochMillis?.let(::formatEpochMillis),
                    publishedProvenance = date.provenance,
                )
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

        val timestamps = dates.lookup(
            coordinates, listOfNotNull(published.version, stable.version).distinct(), fileTimestampBudget = 2, notes,
        )
        val noVersion = Provenance(Provenance.MISSING, Provenance.REPOSITORY_METADATA)

        return LatestVersionResult(
            groupId = coordinates.groupId,
            artifactId = coordinates.artifactId,
            latestPublishedVersion = published.version,
            latestStableVersion = stable.version,
            latestPublishedTimestamp = published.version?.let { timestamps[it]?.epochMillis }?.let(::formatEpochMillis),
            latestStableTimestamp = stable.version?.let { timestamps[it]?.epochMillis }?.let(::formatEpochMillis),
            timestampProvenance = mapOf(
                "latestPublishedTimestamp" to (published.version?.let { timestamps[it]?.provenance } ?: noVersion),
                "latestStableTimestamp" to (stable.version?.let { timestamps[it]?.provenance } ?: noVersion),
            ),
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

    /** Maps a document whose g/a were already checked to be non-blank. */
    private fun toSummary(doc: SolrArtifactDoc): ArtifactSummary {
        val g = doc.g!!
        val a = doc.a!!
        return ArtifactSummary(
            groupId = g,
            artifactId = a,
            indexedLatestVersion = doc.latestVersion,
            indexedLatestVersionPrerelease = doc.latestVersion?.let(VersionPolicy::isPrerelease),
            packaging = doc.packaging,
            url = centralUrl(g, a),
            lastUpdatedEpochMillis = doc.timestamp,
            lastUpdated = doc.timestamp?.let(::formatEpochMillis),
            indexedVersionCount = doc.versionCount,
            provenance = mapOf(
                "indexedLatestVersion" to indexField(doc.latestVersion),
                "packaging" to indexField(doc.packaging),
                "lastUpdated" to indexField(doc.timestamp),
                "indexedVersionCount" to indexField(doc.versionCount),
            ),
        )
    }

    private fun indexField(value: Any?) =
        if (value != null) Provenance.present(Provenance.SEARCH_INDEX) else Provenance(Provenance.MISSING, Provenance.SEARCH_INDEX)

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

        /** `descriptionKeywordMatching` values: keyword matches were searched and are included in totalCount. */
        const val KEYWORDS_APPLIED = "applied"

        /** The keyword query failed; totalCount and results contain name matches only. */
        const val KEYWORDS_UNAVAILABLE = "unavailable"

        /** The query has no meaningful word after removing grammatical words and punctuation. */
        const val KEYWORDS_NO_TERMS = "no_meaningful_words"

        /** Search expressions, `groupId:artifactId` lookups and reverse-domain groupId queries use name search only. */
        const val KEYWORDS_NOT_APPLICABLE = "not_applicable"

        /** Parent POM levels search reads, only when a result's own POM has no description. */
        const val SEARCH_MAX_PARENTS = 2

        /**
         * `descriptionSource` value for a description provenance: the source label when a description was found
         * (`pom:g:a:v` or `parent-pom:g:a:v`), otherwise the status (`missing`, `unavailable`, `not_loaded`,
         * `unresolved`). This keeps `descriptionSource` and `provenance.description` from ever disagreeing.
         */
        internal fun descriptionSourceOf(p: Provenance): String =
            if (p.status == Provenance.PRESENT && p.source != null) p.source else p.status

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

        /**
         * One timestamp format for every source: ISO-8601 UTC with exactly three fraction digits. All sources are
         * at most millisecond-precise (search index: milliseconds; POM `Last-Modified` and maven-metadata
         * `lastUpdated`: whole seconds), so nothing is discarded; whole-second values show `.000`. Which source a
         * timestamp came from is stated by its provenance, not by its format.
         */
        private val ISO_UTC_MILLIS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        internal fun formatEpochMillis(millis: Long): String = ISO_UTC_MILLIS.format(Instant.ofEpochMilli(millis))

        private val METADATA_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

        /** Converts maven-metadata `lastUpdated` (yyyyMMddHHmmss, UTC) to ISO-8601; returns raw on failure. */
        internal fun formatMetadataTimestamp(raw: String): String = try {
            ISO_UTC_MILLIS.format(LocalDateTime.parse(raw, METADATA_TS).toInstant(ZoneOffset.UTC))
        } catch (_: Exception) {
            raw
        }
    }
}
