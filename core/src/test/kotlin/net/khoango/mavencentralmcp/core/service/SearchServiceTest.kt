package net.khoango.mavencentralmcp.core.service

import kotlinx.coroutines.test.runTest
import net.khoango.mavencentralmcp.core.error.MavenCentralException
import net.khoango.mavencentralmcp.core.model.Provenance
import net.khoango.mavencentralmcp.core.model.SearchRequest
import net.khoango.mavencentralmcp.maven.api.FakeFetcher
import net.khoango.mavencentralmcp.maven.api.FakeFetcher.Companion.metadataXml
import net.khoango.mavencentralmcp.maven.api.FakeFetcher.Companion.ok
import net.khoango.mavencentralmcp.maven.api.FakeFetcher.Companion.param
import net.khoango.mavencentralmcp.maven.api.FakeFetcher.Companion.status
import net.khoango.mavencentralmcp.maven.api.FetchResponse
import net.khoango.mavencentralmcp.maven.api.MavenCentralClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URI

class SearchServiceTest {

    private fun doc(g: String, a: String, versions: Int, v: String = "1.0") =
        """{"g":"$g","a":"$a","latestVersion":"$v","p":"jar","timestamp":1700000000000,"versionCount":$versions}"""

    private fun solr(total: Int, vararg docs: String) = """{"response":{"numFound":$total,"start":0,"docs":[${docs.joinToString(",")}]}}"""

    private val okhttpPool = solr(
        593,
        doc("org.wso2.orbit.com.squareup.okhttp", "okhttp", 4, "4.12.0.wso2v5"),
        doc("com.squareup.okhttp3", "okhttp", 96, "5.0.0-alpha.16"),
        doc("com.avito.android", "okhttp", 52),
    )

    private fun pom(name: String, description: String? = null, relocation: String = "") =
        "<project><name>$name</name>${description?.let { "<description>$it</description>" } ?: ""}$relocation</project>"

    private fun setup(route: (URI) -> FetchResponse): Pair<MavenCentralService, FakeFetcher> {
        val fetcher = FakeFetcher(route = route)
        return MavenCentralService(MavenCentralClient(fetcher)) to fetcher
    }

    @Test
    fun `free-text search re-ranks a bounded pool and explains why`() = runTest {
        val (svc, fetcher) = setup { uri -> if (uri.host == "search.maven.org") ok(okhttpPool) else status(404) }
        val page = svc.search(SearchRequest("okhttp", limit = 3, offset = 0))
        assertEquals("com.squareup.okhttp3", page.results.first().groupId)
        assertEquals(listOf(1, 2, 3), page.results.map { it.rank })
        assertTrue(page.results.first().rankingSignals.contains("artifactId exactly matches the query"))
        assertTrue(page.ranking.contains("does not verify the publisher"))
        assertTrue(page.notes.any { "Several publishers" in it })
        assertTrue(page.notes.any { "top 100 of 593" in it })
        assertEquals("100", fetcher.requests.first { it.host == "search.maven.org" }.param("rows"))
    }

    @Test
    fun `descriptions come from POMs for the first five results only`() = runTest {
        val docs = (1..7).map { doc("com.example$it", "lib", 10) }.toTypedArray()
        val (svc, fetcher) = setup { uri ->
            when {
                uri.host == "search.maven.org" -> ok(solr(7, *docs))
                uri.path.endsWith(".pom") -> ok(pom("Lib", "Does things"))
                else -> status(404)
            }
        }
        val page = svc.search(SearchRequest("lib", limit = 7, offset = 0))
        assertEquals(5, fetcher.requests.count { it.path.endsWith(".pom") })
        assertEquals(List(5) { "Does things" } + listOf(null, null), page.results.map { it.description })
        assertEquals("pom:com.example1:lib:1.0", page.results[0].descriptionSource)
        assertEquals("not_loaded", page.results[6].descriptionSource)
        assertTrue(page.notes.any { "first 5 results of each page" in it })
    }

    @Test
    fun `descriptions can be skipped`() = runTest {
        val (svc, fetcher) = setup { uri -> if (uri.host == "search.maven.org") ok(okhttpPool) else ok(pom("x")) }
        val page = svc.search(SearchRequest("okhttp", limit = 3, offset = 0, includeDescriptions = false))
        assertTrue(fetcher.requests.none { it.path.endsWith(".pom") })
        assertTrue(page.results.all { it.description == null && it.descriptionSource == "not_loaded" })
    }

    @Test
    fun `missing POMs and missing descriptions are handled gracefully`() = runTest {
        val (svc, _) = setup { uri ->
            when {
                uri.host == "search.maven.org" -> ok(okhttpPool)
                uri.path.contains("/com/squareup/") -> ok(pom("OkHttp")) // no <description>
                uri.path.contains("/com/avito/") -> status(503)
                else -> status(404)
            }
        }
        val byGroup = svc.search(SearchRequest("okhttp", limit = 3, offset = 0)).results.associateBy { it.groupId }
        assertEquals("OkHttp", byGroup.getValue("com.squareup.okhttp3").name)
        assertNull(byGroup.getValue("com.squareup.okhttp3").description)
        // The POM was read and has no description and no parent: missing (not not_loaded).
        assertEquals("missing", byGroup.getValue("com.squareup.okhttp3").descriptionSource)
        assertEquals(
            Provenance(Provenance.MISSING, "pom:com.squareup.okhttp3:okhttp:5.0.0-alpha.16"),
            byGroup.getValue("com.squareup.okhttp3").provenance["description"],
        )
        assertEquals("unavailable", byGroup.getValue("com.avito.android").descriptionSource)
    }

    @Test
    fun `relocation declared in the POM is surfaced`() = runTest {
        val relocation = "<distributionManagement><relocation><groupId>com.mysql</groupId>" +
            "<artifactId>mysql-connector-j</artifactId></relocation></distributionManagement>"
        val (svc, _) = setup { uri ->
            if (uri.host == "search.maven.org") ok(solr(1, doc("mysql", "mysql-connector-java", 100, "8.0.33")))
            else ok(pom("MySQL Connector/J", relocation = relocation))
        }
        val hit = svc.search(SearchRequest("mysql-connector-java", limit = 1, offset = 0)).results.single()
        assertEquals("com.mysql:mysql-connector-j", hit.relocatedTo)
    }

    @Test
    fun `coordinate query becomes an exact lookup`() = runTest {
        val (svc, fetcher) = setup { uri ->
            if (uri.host == "search.maven.org") ok(solr(1, doc("com.google.guava", "guava", 150))) else status(404)
        }
        val page = svc.search(SearchRequest("com.google.guava:guava", limit = 10, offset = 0))
        assertEquals(1, page.results.size)
        assertEquals("g:\"com.google.guava\" AND a:\"guava\"", fetcher.requests.first().param("q"))
    }

    @Test
    fun `coordinate missing from the stale index falls back to repository metadata`() = runTest {
        val (svc, _) = setup { uri ->
            when {
                uri.host == "search.maven.org" -> ok(solr(0))
                uri.path.endsWith("maven-metadata.xml") -> ok(metadataXml(listOf("1.0", "1.1")))
                else -> status(404)
            }
        }
        val page = svc.search(SearchRequest("com.example:brand-new", limit = 10, offset = 0))
        val hit = page.results.single()
        assertEquals("1.1", hit.indexedLatestVersion)
        assertEquals(listOf("exact coordinate match (repository metadata)"), hit.rankingSignals)
        assertTrue(page.notes.any { "not in the search index" in it })
    }

    @Test
    fun `field expressions are not treated as coordinates`() {
        assertNull(MavenCentralService.parseCoordinateQuery("g:com.google.guava"))
        assertNull(MavenCentralService.parseCoordinateQuery("a:guava"))
        assertEquals("junit:junit", MavenCentralService.parseCoordinateQuery("junit:junit").toString())
    }

    @Test
    fun `conflicting coordinate and filters are rejected`() = runTest {
        val (svc, _) = setup { ok(solr(0)) }
        assertThrows<MavenCentralException.InvalidArgument> {
            svc.search(SearchRequest("com.a:b", groupId = "com.other", limit = 10, offset = 0))
        }
    }

    @Test
    fun `deep pages beyond the re-ranked pool use index order`() = runTest {
        val (svc, fetcher) = setup { uri -> if (uri.host == "search.maven.org") ok(okhttpPool) else status(404) }
        val page = svc.search(SearchRequest("okhttp", limit = 10, offset = 150, includeDescriptions = false))
        assertEquals(listOf("0", "150"), fetcher.requests.map { it.param("start") })
        assertTrue(page.ranking.startsWith("Maven Central search index order"))
        assertTrue(page.notes.any { "not re-ranked" in it })
        assertTrue(page.results.first().rankingSignals.first().startsWith("position 151 in search-index order"))
    }

    @Test
    fun `rejected search expression becomes an actionable argument error`() = runTest {
        val (svc, _) = setup { status(400) }
        val e = assertThrows<MavenCentralException.InvalidArgument> {
            svc.search(SearchRequest("g:(broken", limit = 10, offset = 0))
        }
        assertTrue(e.message!!.contains("groupId:artifactId"))
    }
}
