package net.khoango.mavencentralmcp.core.service

import kotlinx.coroutines.test.runTest
import net.khoango.mavencentralmcp.core.model.ArtifactCoordinates
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI

/** Regression tests for the search_artifacts integration findings (group IDs, provenance, pagination). */
class SearchQualityTest {

    private fun doc(id: String, versions: Int? = 10, v: String = "1.0", ts: Long = 1_700_000_000_000): String {
        val (g, a) = id.split(":")
        return """{"g":"$g","a":"$a","latestVersion":"$v","p":"jar","timestamp":$ts""" +
            (versions?.let { ""","versionCount":$it""" } ?: "") + "}"
    }

    private fun solr(total: Int, docs: List<String>) = """{"response":{"numFound":$total,"start":0,"docs":[${docs.joinToString(",")}]}}"""

    private fun service(route: (URI) -> FetchResponse) = FakeFetcher(route = route).let { MavenCentralService(MavenCentralClient(it)) to it }

    private suspend fun search(query: String, pool: String, limit: Int = 10, offset: Int = 0, groupId: String? = null) =
        service { uri -> if (uri.host == "search.maven.org") ok(pool) else status(404) }.first
            .search(SearchRequest(query, groupId = groupId, limit = limit, offset = offset, includeDescriptions = false))

    /** The real index order for 'com.fasterxml.jackson.core' (November 2026, 10 matches). */
    private val jacksonCorePool = solr(
        10,
        listOf(
            doc("com.liferay:com.fasterxml.jackson.core", versions = 30),
            doc("com.fasterxml.jackson.core:jackson-databind", versions = 208),
            doc("org.bedework.deploy:bw-wfmodules-com-fasterxml-jackson-core-annotations", versions = 12),
            doc("org.bedework.deploy:bw-wfmodules-com-fasterxml-jackson-core-jackson-core", versions = 12),
            doc("org.bedework.deploy:bw-wfmodules-com-fasterxml-jackson-core-databind", versions = 12),
            doc("com.fasterxml.jackson.core:jackson-annotations", versions = 190),
            doc("com.fasterxml.jackson.core:jackson-core", versions = 200),
            doc("org.apache.servicemix.bundles:com.fasterxml.jackson.core.databind", versions = 5),
            doc("io.github.example:jackson-core-shaded", versions = 2),
            doc("com.liferay:com.fasterxml.jackson.core.databind", versions = 3),
        ),
    )

    // ---- 1. Group ID recognition -------------------------------------------------------------------------------

    @Test
    fun `exact groupId query ranks that group's artifacts first`() = runTest {
        val results = search("com.fasterxml.jackson.core", jacksonCorePool).results
        assertEquals(
            setOf("jackson-databind", "jackson-core", "jackson-annotations"),
            results.take(3).map { it.artifactId }.toSet(),
        )
        assertTrue(results.take(3).all { it.groupId == "com.fasterxml.jackson.core" })
        assertTrue(results.first().rankingSignals.contains("groupId exactly matches the query"))
    }

    @Test
    fun `an unrelated artifact whose artifactId equals the groupId query does not win`() = runTest {
        val results = search("com.fasterxml.jackson.core", jacksonCorePool).results
        val liferay = results.indexOfFirst { it.groupId == "com.liferay" && it.artifactId == "com.fasterxml.jackson.core" }
        assertTrue(liferay >= 3, "liferay repackage ranked at index $liferay")
        assertTrue(results[liferay].rankingSignals.any { "weak signal" in it })
        assertEquals(10, results.size) // nothing is filtered out
    }

    @Test
    fun `partial groupId query prefers the namespace and its subgroups`() = runTest {
        val pool = solr(
            5,
            listOf(
                doc("com.liferay:com.fasterxml.jackson", versions = 40),
                doc("org.example:jackson-tools", versions = 80),
                doc("com.fasterxml.jackson.core:jackson-databind", versions = 208),
                doc("com.fasterxml.jackson:jackson-bom", versions = 150),
                doc("com.fasterxml.jackson.module:jackson-module-kotlin", versions = 120),
            ),
        )
        val ranked = search("com.fasterxml.jackson", pool).results.map { "${it.groupId}:${it.artifactId}" }
        assertEquals("com.fasterxml.jackson:jackson-bom", ranked.first())
        assertEquals(
            setOf("com.fasterxml.jackson.core:jackson-databind", "com.fasterxml.jackson.module:jackson-module-kotlin"),
            ranked.subList(1, 3).toSet(),
        )
    }

    @Test
    fun `ordinary artifact-name searches are unchanged`() = runTest {
        val pool = solr(
            3,
            listOf(
                doc("org.wso2.orbit.com.squareup.okhttp:okhttp", versions = 4),
                doc("com.squareup.okhttp3:okhttp", versions = 96),
                doc("com.avito.android:okhttp", versions = 52),
            ),
        )
        val okhttp = search("okhttp", pool).results
        assertEquals("com.squareup.okhttp3", okhttp.first().groupId)
        assertTrue(okhttp.first().rankingSignals.contains("artifactId exactly matches the query"))

        // A dotted query that is not a group in the pool stays an ordinary free-text search.
        val dotted = solr(2, listOf(doc("io.other:jackson-databind-ext", 50), doc("com.fasterxml.jackson.core:jackson-databind", 208)))
        val top = search("jackson.databind", dotted).results.first()
        assertEquals("jackson-databind", top.artifactId)
        assertTrue(top.rankingSignals.contains("artifactId exactly matches the query"))
    }

    @Test
    fun `generic reverse-domain tokens are not ranking signals`() = runTest {
        val results = search("com core", solr(1, listOf(doc("com.example:core-utils")))).results
        assertFalse(results.single().rankingSignals.any { it == "groupId contains 'com'" })
    }

    @Test
    fun `explicit g syntax keeps index order but explains each position`() = runTest {
        val pool = solr(3, jacksonCorePool.let { listOf(doc("com.fasterxml.jackson.core:jackson-annotations", 190), doc("com.fasterxml.jackson.core:jackson-databind", 208)) })
        val (svc, fetcher) = service { uri -> if (uri.host == "search.maven.org") ok(pool) else status(404) }
        val page = svc.search(SearchRequest("g:com.fasterxml.jackson.core", limit = 10, offset = 0, includeDescriptions = false))
        assertEquals("g:com.fasterxml.jackson.core", fetcher.requests.single().param("q"))
        assertEquals(listOf("jackson-annotations", "jackson-databind"), page.results.map { it.artifactId })
        page.results.forEachIndexed { i, r ->
            assertTrue(r.rankingSignals.isNotEmpty())
            assertEquals("position ${i + 1} in search-index order (not re-ranked)", r.rankingSignals.first())
        }
        assertTrue(page.ranking.startsWith("Maven Central search index order"))
        // All results share one group and distinct artifactIds: no "several publishers" warning.
        assertTrue(page.notes.none { "Several publishers" in it })
    }

    @Test
    fun `coordinate query is not described as a groupId filter`() = runTest {
        val pool = solr(1, listOf(doc("org.springframework.boot:spring-boot-starter-web", 252, "3.5.3")))
        val (svc, fetcher) = service { uri -> if (uri.host == "search.maven.org") ok(pool) else status(404) }
        val r = svc.search(
            SearchRequest("org.springframework.boot:spring-boot-starter-web", limit = 10, offset = 0, includeDescriptions = false),
        ).results.single()
        assertEquals("g:\"org.springframework.boot\" AND a:\"spring-boot-starter-web\"", fetcher.requests.single().param("q"))
        assertTrue(r.rankingSignals.contains("exact match for the groupId:artifactId query"), r.rankingSignals.toString())
        assertFalse(r.rankingSignals.any { "filter" in it }, r.rankingSignals.toString())
    }

    @Test
    fun `groupId filter is applied exactly and reported as a signal`() = runTest {
        val pool = solr(1, listOf(doc("com.fasterxml.jackson.core:jackson-databind", 208)))
        val (svc, fetcher) = service { uri -> if (uri.host == "search.maven.org") ok(pool) else status(404) }
        // Expression + filter: index order, filter signal.
        val page = svc.search(
            SearchRequest("a:jackson*", groupId = "com.fasterxml.jackson.core", limit = 10, offset = 0, includeDescriptions = false),
        )
        assertEquals("(a:jackson*) AND g:\"com.fasterxml.jackson.core\"", fetcher.requests.single().param("q"))
        assertTrue(page.results.single().rankingSignals.contains("groupId matches the groupId filter"))
        // Free text + filter: re-ranked within the filtered set.
        val reranked = search("databind", pool, groupId = "com.fasterxml.jackson.core").results.single()
        assertTrue(reranked.rankingSignals.contains("artifactId contains all query terms"))
    }

    // ---- 2 + 3. Source labels and description provenance --------------------------------------------------------

    private val pomLabel = Regex("^(pom|parent-pom):[^:\\s,]+:[^:\\s,]+:[^:\\s,]+$")

    /** Every source that mentions a POM uses the one shared format (lists and maven-default text included). */
    private fun assertPomLabels(sources: Collection<String?>) {
        sources.filterNotNull().forEach { source ->
            val pomRefs = Regex("(?:parent-)?pom[: ][^,)\\s]*").findAll(source).map { it.value }.toList()
            pomRefs.forEach { assertTrue(pomLabel.matches(it), "bad POM source label '$it' in '$source'") }
        }
    }

    /** The Bedework case: name in the POM, no own description, parent declared, packaging pom. */
    private val bedeworkPom = "<project><parent><artifactId>bw-wfmodules-jackson-core</artifactId>" +
        "<groupId>org.bedework.deploy</groupId><version>1.0.10</version></parent>" +
        "<artifactId>bw-wfmodules-com-fasterxml-jackson-core-annotations</artifactId><packaging>pom</packaging>" +
        "<name>Bedework wfmodules: com fasterxml jackson core annotations</name></project>"

    private fun bedeworkRepo(parent: () -> FetchResponse) = FakeFetcher { uri: URI ->
        when {
            uri.host == "search.maven.org" ->
                ok(solr(1, listOf(doc("org.bedework.deploy:bw-wfmodules-com-fasterxml-jackson-core-annotations", 12, "1.0.10"))))
            uri.path.endsWith("bw-wfmodules-com-fasterxml-jackson-core-annotations-1.0.10.pom") -> ok(bedeworkPom)
            uri.path.endsWith("bw-wfmodules-jackson-core-1.0.10.pom") -> parent()
            else -> status(404)
        }
    }

    @Test
    fun `Bedework annotations - inherited description is attributed to the parent in both fields`() = runTest {
        val fetcher = bedeworkRepo { ok("<project><artifactId>bw-wfmodules-jackson-core</artifactId><description>Jackson core modules for WildFly</description></project>") }
        val hit = MavenCentralService(MavenCentralClient(fetcher))
            .search(SearchRequest("bedework annotations", limit = 5, offset = 0)).results.single()
        val expected = "parent-pom:org.bedework.deploy:bw-wfmodules-jackson-core:1.0.10"
        assertEquals("Jackson core modules for WildFly", hit.description)
        assertEquals(expected, hit.descriptionSource)
        assertEquals(Provenance.present(expected), hit.provenance["description"])
        assertEquals(Provenance.present("pom:org.bedework.deploy:bw-wfmodules-com-fasterxml-jackson-core-annotations:1.0.10"), hit.provenance["name"])
        assertPomLabels(hit.provenance.values.map { it.source })
    }

    @Test
    fun `Bedework annotations - unreadable parent is unavailable in both fields, never pom-sourced`() = runTest {
        val hit = MavenCentralService(MavenCentralClient(bedeworkRepo { status(500) }))
            .search(SearchRequest("bedework annotations", limit = 5, offset = 0)).results.single()
        assertNull(hit.description)
        assertEquals("unavailable", hit.descriptionSource)
        assertEquals(Provenance.UNAVAILABLE, hit.provenance.getValue("description").status)
        assertEquals("parent-pom:org.bedework.deploy:bw-wfmodules-jackson-core:1.0.10", hit.provenance.getValue("description").source)
    }

    @Test
    fun `descriptionSource and provenance agree for every loading state and page position`() = runTest {
        val docs = (1..8).map { doc("com.example$it:lib", 10) }
        val (svc, _) = service { uri ->
            when {
                uri.host == "search.maven.org" -> ok(solr(8, docs))
                uri.path.contains("/com/example1/") -> ok("<project><description>One</description></project>")
                uri.path.contains("/com/example2/") -> ok("<project/>")
                uri.path.contains("/com/example3/") -> status(503)
                uri.path.contains("/com/example4/") -> ok("<project><description>\${project.name}</description></project>")
                else -> ok("<project><description>Other</description></project>")
            }
        }
        for (offset in listOf(0, 3)) {
            val results = svc.search(SearchRequest("lib", limit = 8, offset = offset)).results
            results.forEach { r ->
                val p = r.provenance.getValue("description")
                val expected = if (p.status == Provenance.PRESENT) p.source else p.status
                assertEquals(expected, r.descriptionSource, "${r.groupId} at offset $offset")
                if (r.descriptionSource.startsWith("pom:")) assertEquals(Provenance.PRESENT, p.status)
                assertPomLabels(r.provenance.values.map { it.source })
            }
            if (offset == 0) {
                val by = results.associateBy { it.groupId }
                assertEquals("pom:com.example1:lib:1.0", by.getValue("com.example1").descriptionSource)
                assertEquals("missing", by.getValue("com.example2").descriptionSource)
                assertEquals("unavailable", by.getValue("com.example3").descriptionSource)
                assertEquals("unresolved", by.getValue("com.example4").descriptionSource)
                assertEquals("not_loaded", results.last().descriptionSource)
            }
            // Positions 1-5 of every page are loaded, the rest are not_loaded, regardless of offset.
            assertTrue(results.take(5).none { it.descriptionSource == "not_loaded" })
            assertTrue(results.drop(5).all { it.descriptionSource == "not_loaded" })
        }
    }

    @Test
    fun `artifact info uses the same POM label format everywhere`() = runTest {
        val fetcher = FakeFetcher { uri: URI ->
            when {
                uri.host == "search.maven.org" -> ok(solr(0, emptyList()))
                uri.path.endsWith("maven-metadata.xml") -> ok(metadataXml(listOf("2.19.0")))
                uri.path.endsWith("jackson-databind-2.19.0.pom") -> ok(
                    "<project><parent><groupId>com.fasterxml.jackson</groupId><artifactId>jackson-base</artifactId>" +
                        "<version>2.19.0</version></parent><artifactId>jackson-databind</artifactId></project>",
                )
                uri.path.endsWith("jackson-base-2.19.0.pom") -> ok("<project><artifactId>jackson-base</artifactId></project>")
                else -> status(404)
            }
        }
        val info = MavenCentralService(MavenCentralClient(fetcher))
            .getArtifactInfo(ArtifactCoordinates("com.fasterxml.jackson.core", "jackson-databind"))
        assertEquals(Provenance.present("pom:com.fasterxml.jackson.core:jackson-databind:2.19.0"), info.provenance["pom"])
        assertEquals(
            "pom:com.fasterxml.jackson.core:jackson-databind:2.19.0, parent-pom:com.fasterxml.jackson:jackson-base:2.19.0",
            info.pom!!.provenance.getValue("licenses").source,
        )
        assertPomLabels(info.provenance.values.map { it.source } + info.pom.provenance.values.map { it.source })
    }

    // ---- 4. Pagination -----------------------------------------------------------------------------------------

    private val tenResults = solr(10, (1..10).map { doc("com.example$it:lib", it) })

    private suspend fun page(offset: Int, limit: Int = 10, pool: String = tenResults) =
        service { uri -> if (uri.host == "search.maven.org") ok(pool) else status(404) }.let { (svc, fetcher) ->
            svc.search(SearchRequest("lib", limit = limit, offset = offset, includeDescriptions = false)) to fetcher
        }

    @Test
    fun `offset equal to totalCount returns an empty page`() = runTest {
        val (p, _) = page(offset = 10)
        assertTrue(p.results.isEmpty())
        assertEquals(10, p.totalCount)
        assertFalse(p.hasMore)
        assertTrue(p.notes.any { "offset 10 is at or beyond totalCount 10" in it })
    }

    @Test
    fun `offset beyond totalCount returns an empty page`() = runTest {
        val (p, _) = page(offset = 15)
        assertTrue(p.results.isEmpty())
        assertEquals(10, p.totalCount)
        assertFalse(p.hasMore)
    }

    @Test
    fun `offset 200 with only 10 results makes no claim about positions beyond 100`() = runTest {
        val (p, fetcher) = page(offset = 200)
        assertTrue(p.results.isEmpty())
        assertEquals(10, p.totalCount)
        assertFalse(p.hasMore)
        assertTrue(p.notes.none { "position 101" in it || "past position" in it || "top 100" in it }, p.notes.toString())
        assertEquals(1, fetcher.requests.size, "no deep index request when the offset is past totalCount")
    }

    @Test
    fun `pagination across the re-ranked pool boundary has no gaps or duplicates`() = runTest {
        val poolDocs = (0 until 100).map { doc("com.pool$it:lib", 10) }
        val deepDocs = (100 until 105).map { doc("com.deep$it:lib", 10) }
        val (svc, fetcher) = service { uri ->
            if (uri.param("start") == "0") ok(solr(150, poolDocs)) else ok(solr(150, deepDocs))
        }
        val first = svc.search(SearchRequest("lib", limit = 95, offset = 0, includeDescriptions = false))
        val second = svc.search(SearchRequest("lib", limit = 10, offset = 95, includeDescriptions = false))
        assertEquals((96..105).toList(), second.results.map { it.rank })
        assertEquals(5, second.results.count { it.groupId.startsWith("com.pool") })
        assertEquals(5, second.results.count { it.groupId.startsWith("com.deep") })
        assertTrue((first.results + second.results).map { it.groupId }.let { it.size == it.toSet().size })
        assertEquals(SearchRanking.MIXED_DESCRIPTION, second.ranking)
        assertTrue(second.notes.any { "position 101 onward" in it })
        assertTrue(second.hasMore)
        assertEquals("100", fetcher.requests.last().param("start"))
        assertEquals("5", fetcher.requests.last().param("rows"))
        assertTrue(second.results.last().rankingSignals.first().startsWith("position 105 in search-index order"))
    }

    @Test
    fun `normal pagination inside the pool`() = runTest {
        val (p1, _) = page(offset = 0, limit = 4)
        val (p2, _) = page(offset = 4, limit = 4)
        val (p3, _) = page(offset = 8, limit = 4)
        assertEquals(listOf(4, 4, 2), listOf(p1, p2, p3).map { it.results.size })
        assertEquals(listOf(true, true, false), listOf(p1, p2, p3).map { it.hasMore })
        assertEquals(10, (p1.results + p2.results + p3.results).map { it.groupId }.toSet().size)
        assertTrue(p1.notes.none { "top 100" in it }, "only 10 matches: nothing is cut off")
    }

    // ---- 5. Result metadata --------------------------------------------------------------------------------------

    @Test
    fun `prerelease indicator for indexedLatestVersion keeps the original string`() = runTest {
        val pool = solr(2, listOf(doc("com.squareup.okhttp3:okhttp", 96, "5.0.0-alpha.16"), doc("com.example:okhttp-x", 3, "1.2.3")))
        val by = search("okhttp", pool).results.associateBy { it.artifactId }
        assertEquals("5.0.0-alpha.16", by.getValue("okhttp").indexedLatestVersion)
        assertEquals(true, by.getValue("okhttp").indexedLatestVersionPrerelease)
        assertEquals(false, by.getValue("okhttp-x").indexedLatestVersionPrerelease)
    }

    @Test
    fun `skipped descriptions are consistently not loaded`() = runTest {
        val r = search("okhttp", solr(1, listOf(doc("com.squareup.okhttp3:okhttp", 96, "5.0.0")))).results.single()
        assertEquals("not_loaded", r.descriptionSource)
        assertEquals(Provenance(Provenance.NOT_LOADED, "pom:com.squareup.okhttp3:okhttp:5.0.0"), r.provenance["description"])
    }
}
