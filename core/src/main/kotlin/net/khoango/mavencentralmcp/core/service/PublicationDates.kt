package net.khoango.mavencentralmcp.core.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import net.khoango.mavencentralmcp.core.error.MavenCentralException
import net.khoango.mavencentralmcp.core.model.ArtifactCoordinates
import net.khoango.mavencentralmcp.core.model.Provenance
import net.khoango.mavencentralmcp.maven.api.MavenCentralClient

/**
 * Resolves publication dates for versions.
 *
 * 1. The search index (`gav` core) stores a publication timestamp per version, but stopped indexing new
 *    versions (in November 2026 its newest Guava was 33.4.8 and newest Ktor 3.2.0). It is paged until all
 *    of an artifact's indexed versions are read, up to [MAX_INDEX_PAGES].
 * 2. For versions the index lacks, the HTTP `Last-Modified` header of the version's POM on the repository is
 *    used (one cached HEAD request each, at most [fileTimestampBudget] per call). On sampled versions it
 *    matched the index timestamp to within one second; it is the upload time of the file, not a declared
 *    release date.
 * Versions beyond the budget are reported as [Provenance.NOT_LOADED]; failed lookups as [Provenance.UNAVAILABLE].
 */
internal class PublicationDates(private val client: MavenCentralClient) {

    data class Result(val epochMillis: Long?, val provenance: Provenance)

    suspend fun lookup(
        coordinates: ArtifactCoordinates,
        versions: List<String>,
        fileTimestampBudget: Int,
        notes: MutableList<String>,
    ): Map<String, Result> {
        if (versions.isEmpty()) return emptyMap()
        val indexed = try {
            indexedTimestamps(coordinates)
        } catch (e: CancellationException) {
            throw e
        } catch (e: MavenCentralException) {
            notes += "The search index could not be read for publication dates: ${e.message}"
            emptyMap()
        }
        val results = linkedMapOf<String, Result>()
        val missing = mutableListOf<String>()
        for (v in versions) {
            val ts = indexed[v]
            if (ts != null) results[v] = Result(ts, Provenance.present(Provenance.SEARCH_INDEX)) else missing += v
        }
        val toFetch = missing.take(fileTimestampBudget)
        val semaphore = Semaphore(MAX_CONCURRENT_HEAD)
        val fetched = coroutineScope {
            toFetch.map { v ->
                async {
                    semaphore.withPermit {
                        v to try {
                            client.fetchPomLastModified(coordinates, v)
                                ?.let { Result(it, Provenance.present(Provenance.FILE_TIMESTAMP)) }
                                ?: Result(null, Provenance(Provenance.UNAVAILABLE, Provenance.FILE_TIMESTAMP))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: MavenCentralException) {
                            Result(null, Provenance(Provenance.UNAVAILABLE, Provenance.FILE_TIMESTAMP))
                        }
                    }
                }
            }.awaitAll()
        }.toMap()
        results += fetched
        val skipped = missing.drop(fileTimestampBudget)
        skipped.forEach { results[it] = Result(null, Provenance(Provenance.NOT_LOADED, Provenance.FILE_TIMESTAMP)) }

        if (fetched.values.any { it.provenance.status == Provenance.PRESENT }) {
            notes += "Some dates are the upload time of the version's POM file on the repository (HTTP Last-Modified), " +
                "used because the search index does not know those versions."
        }
        if (skipped.isNotEmpty()) {
            notes += "${skipped.size} date(s) were not loaded (at most $fileTimestampBudget repository lookups per call); " +
                "request a smaller page to get them."
        }
        if (results.values.any { it.provenance.status == Provenance.UNAVAILABLE }) {
            notes += "Some dates are unavailable: neither the search index nor the repository file timestamp could provide them."
        }
        return results.toMap()
    }

    private suspend fun indexedTimestamps(c: ArtifactCoordinates): Map<String, Long> {
        val result = mutableMapOf<String, Long>()
        var start = 0
        repeat(MAX_INDEX_PAGES) {
            val page = client.searchVersions(c, rows = INDEX_PAGE_SIZE, start = start)
            val docs = page.docs.orEmpty()
            docs.filter { it.g == c.groupId && it.a == c.artifactId }
                .forEach { doc -> doc.v?.let { v -> doc.timestamp?.let { result[v] = it } } }
            start += docs.size
            if (docs.size < INDEX_PAGE_SIZE || start >= (page.numFound ?: 0)) return result
        }
        return result
    }

    companion object {
        const val INDEX_PAGE_SIZE = 200
        const val MAX_INDEX_PAGES = 5
        private const val MAX_CONCURRENT_HEAD = 6

        /** Repository timestamp lookups per get_versions call. */
        const val VERSIONS_FILE_TIMESTAMP_BUDGET = 25
    }
}
