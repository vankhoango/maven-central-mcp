package net.khoango.mavencentralmcp.core.service

import kotlinx.coroutines.test.runTest
import net.khoango.mavencentralmcp.core.model.ArtifactCoordinates
import net.khoango.mavencentralmcp.core.model.Provenance
import net.khoango.mavencentralmcp.maven.api.FakeFetcher
import net.khoango.mavencentralmcp.maven.api.FakeFetcher.Companion.metadataXml
import net.khoango.mavencentralmcp.maven.api.FakeFetcher.Companion.ok
import net.khoango.mavencentralmcp.maven.api.FakeFetcher.Companion.param
import net.khoango.mavencentralmcp.maven.api.FakeFetcher.Companion.status
import net.khoango.mavencentralmcp.maven.api.MavenCentralClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * Reproduces the integration finding "get_versions returns blank dates": the search index stopped indexing
 * new versions, so the newest versions (the first page) had no date. Dates now fall back to the POM file's
 * Last-Modified header, and every entry states its source or why it is absent.
 */
class PublicationDatesTest {
    private val lib = ArtifactCoordinates("com.example", "lib")

    private fun gav(vararg entries: Pair<String, Long>, total: Int = entries.size) =
        """{"response":{"numFound":$total,"docs":[""" +
            entries.joinToString(",") { (v, ts) -> """{"g":"com.example","a":"lib","v":"$v","timestamp":$ts}""" } + "]}}"

    private fun lastModified(date: String) = FakeFetcher.ok("", "Last-Modified" to date)

    @Test
    fun `versions newer than the stale index get the repository file timestamp`() = runTest {
        val fetcher = FakeFetcher(
            headRoute = { uri -> if ("/2.0/" in uri.path) lastModified("Mon, 02 Nov 2026 18:06:39 GMT") else status(404) },
        ) { uri ->
            if (uri.host == "repo1.maven.org") ok(metadataXml(listOf("1.0", "2.0"))) else ok(gav("1.0" to 1_600_000_000_000))
        }
        val page = MavenCentralService(MavenCentralClient(fetcher)).getVersions(lib, 10, 0, true)
        val byVersion = page.versions.associateBy { it.version }
        assertEquals("2026-11-02T18:06:39Z", byVersion.getValue("2.0").published)
        assertEquals(Provenance.present(Provenance.FILE_TIMESTAMP), byVersion.getValue("2.0").publishedProvenance)
        assertEquals("2020-09-13T12:26:40Z", byVersion.getValue("1.0").published)
        assertEquals(Provenance.present(Provenance.SEARCH_INDEX), byVersion.getValue("1.0").publishedProvenance)
        // Only the version missing from the index needed a HEAD request.
        assertEquals(1, fetcher.headRequests.size)
        assertTrue(page.notes.any { "Last-Modified" in it })
    }

    @Test
    fun `missing POM or missing header is reported as unavailable, never invented`() = runTest {
        val fetcher = FakeFetcher(
            headRoute = { uri -> if ("/2.0/" in uri.path) status(404) else ok("") },
        ) { uri -> if (uri.host == "repo1.maven.org") ok(metadataXml(listOf("1.0", "2.0"))) else ok(gav()) }
        val versions = MavenCentralService(MavenCentralClient(fetcher)).getVersions(lib, 10, 0, true).versions
        versions.forEach {
            assertNull(it.published, it.version)
            assertEquals(Provenance(Provenance.UNAVAILABLE, Provenance.FILE_TIMESTAMP), it.publishedProvenance)
        }
    }

    @Test
    fun `repository lookups are bounded and the rest are marked not loaded`() = runTest {
        val versions = (1..40).map { "1.$it" }
        val fetcher = FakeFetcher(headRoute = { lastModified("Mon, 02 Nov 2026 18:06:39 GMT") }) { uri ->
            if (uri.host == "repo1.maven.org") ok(metadataXml(versions)) else ok(gav())
        }
        val page = MavenCentralService(MavenCentralClient(fetcher)).getVersions(lib, 40, 0, true)
        assertEquals(PublicationDates.VERSIONS_FILE_TIMESTAMP_BUDGET, fetcher.headRequests.size)
        assertEquals(PublicationDates.VERSIONS_FILE_TIMESTAMP_BUDGET, page.versions.count { it.published != null })
        assertEquals(
            40 - PublicationDates.VERSIONS_FILE_TIMESTAMP_BUDGET,
            page.versions.count { it.publishedProvenance.status == Provenance.NOT_LOADED },
        )
        assertTrue(page.notes.any { "were not loaded" in it })
    }

    @Test
    fun `index is paged beyond 200 versions`() = runTest {
        val first = (1..200).map { "1.$it" to 1_600_000_000_000 }.toTypedArray()
        val fetcher = FakeFetcher(headRoute = { status(404) }) { uri: URI ->
            when {
                uri.host == "repo1.maven.org" -> ok(metadataXml(listOf("0.1", "1.1")))
                uri.param("start") == "0" -> ok(gav(*first, total = 201))
                else -> ok(gav("0.1" to 1_500_000_000_000, total = 201))
            }
        }
        val page = MavenCentralService(MavenCentralClient(fetcher)).getVersions(lib, 10, 0, true)
        assertEquals("2017-07-14T02:40:00Z", page.versions.single { it.version == "0.1" }.published)
        assertEquals(listOf("0", "200"), fetcher.requests.filter { it.host == "search.maven.org" }.map { it.param("start") })
        assertTrue(fetcher.headRequests.isEmpty())
    }

    @Test
    fun `latest version timestamps carry provenance`() = runTest {
        val fetcher = FakeFetcher(headRoute = { lastModified("Mon, 02 Nov 2026 18:06:39 GMT") }) { uri ->
            if (uri.host == "repo1.maven.org") ok(metadataXml(listOf("1.0", "2.0-RC1"))) else ok(gav("1.0" to 1_600_000_000_000))
        }
        val r = MavenCentralService(MavenCentralClient(fetcher)).getLatestVersion(lib, false)
        assertEquals("2026-11-02T18:06:39Z", r.latestPublishedTimestamp)
        assertEquals(Provenance.FILE_TIMESTAMP, r.timestampProvenance.getValue("latestPublishedTimestamp").source)
        assertEquals(Provenance.SEARCH_INDEX, r.timestampProvenance.getValue("latestStableTimestamp").source)
    }

    @Test
    fun `http date parsing`() {
        assertEquals(1_793_642_799_000, MavenCentralClient.parseHttpDate("Mon, 02 Nov 2026 18:06:39 GMT"))
        assertNull(MavenCentralClient.parseHttpDate("yesterday"))
        assertNull(MavenCentralClient.parseHttpDate(null))
    }
}
