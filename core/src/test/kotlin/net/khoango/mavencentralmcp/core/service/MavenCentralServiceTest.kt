package net.khoango.mavencentralmcp.core.service

import kotlinx.coroutines.test.runTest
import net.khoango.mavencentralmcp.core.error.MavenCentralException
import net.khoango.mavencentralmcp.core.model.ArtifactCoordinates
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
import org.junit.jupiter.api.assertThrows
import java.net.URI

class MavenCentralServiceTest {
    private val lib = ArtifactCoordinates("com.example", "lib")

    private fun service(route: (URI) -> FetchResponse) = MavenCentralService(MavenCentralClient(FakeFetcher(route)))

    private val emptySearch = """{"response":{"numFound":0,"start":0,"docs":[]}}"""

    private fun gavDocs(vararg versions: Pair<String, Long>) = """{"response":{"numFound":${versions.size},"docs":[""" +
        versions.joinToString(",") { (v, ts) -> """{"g":"com.example","a":"lib","v":"$v","timestamp":$ts}""" } + "]}}"

    @Test
    fun `search maps results and pagination`() = runTest {
        val body = """{"response":{"numFound":3,"start":0,"docs":[
            {"id":"com.other:lib-extra","g":"com.other","a":"lib-extra","latestVersion":"1.0","p":"jar","timestamp":1700000000000,"versionCount":2},
            {"id":"com.example:lib","g":"com.example","a":"lib","latestVersion":"2.0-RC1","p":"jar","timestamp":1700000000000,"versionCount":12},
            {"id":"broken"}
        ]}}"""
        val page = service { uri -> if (uri.host == "search.maven.org") ok(body) else status(404) }
            .search(SearchRequest("lib", limit = 1, offset = 1))
        assertEquals(3, page.totalCount)
        val hit = page.results.single()
        assertEquals("com.other:lib-extra", "${hit.groupId}:${hit.artifactId}") // second after re-ranking
        assertEquals(2, hit.rank)
        assertEquals("https://central.sonatype.com/artifact/com.other/lib-extra", hit.url)
        assertEquals("2023-11-14T22:13:20Z", hit.lastUpdated)
        assertTrue(page.hasMore)
    }

    @Test
    fun `empty search is a successful empty page`() = runTest {
        val page = service { ok(emptySearch) }.search(SearchRequest("nothing-matches", limit = 10, offset = 0))
        assertEquals(0, page.totalCount)
        assertTrue(page.results.isEmpty())
        assertFalse(page.hasMore)
    }

    @Test
    fun `latest version distinguishes published and stable`() = runTest {
        val svc = service { uri ->
            if (uri.host == "repo1.maven.org") ok(metadataXml(listOf("1.0", "1.9", "1.10", "2.0-RC1"), release = "2.0-RC1"))
            else ok(gavDocs("2.0-RC1" to 1_700_000_000_000, "1.10" to 1_600_000_000_000))
        }
        val r = svc.getLatestVersion(lib, includeSnapshots = false)
        assertEquals("2.0-RC1", r.latestPublishedVersion)
        assertEquals("1.10", r.latestStableVersion)
        assertEquals("2023-11-14T22:13:20Z", r.latestPublishedTimestamp)
        assertEquals("2020-09-13T12:26:40Z", r.latestStableTimestamp)
        assertEquals(4, r.versionCount)
        assertFalse(r.uncertain)
        assertTrue(r.policy.contains("ComparableVersion"))
    }

    @Test
    fun `latest version reports ambiguous platform variants`() = runTest {
        val svc = service { uri ->
            if (uri.host == "repo1.maven.org") ok(metadataXml(listOf("33.8.0-android", "33.8.0-jre")))
            else ok(emptySearch)
        }
        val r = svc.getLatestVersion(lib, includeSnapshots = false)
        assertTrue(r.uncertain)
        assertEquals(listOf("33.8.0-jre", "33.8.0-android"), r.candidates)
    }

    @Test
    fun `release marker differing from Maven order is explained`() = runTest {
        val svc = service { uri ->
            if (uri.host == "repo1.maven.org") ok(metadataXml(listOf("1.0", "2.0", "1.1"), release = "1.1"))
            else ok(emptySearch)
        }
        val r = svc.getLatestVersion(lib, includeSnapshots = false)
        assertEquals("2.0", r.latestStableVersion)
        assertTrue(r.notes.any { "maintenance release" in it })
    }

    @Test
    fun `snapshots are excluded unless requested and never stable`() = runTest {
        val svc = service { uri ->
            if (uri.host == "repo1.maven.org") ok(metadataXml(listOf("1.0", "1.1-SNAPSHOT"))) else ok(emptySearch)
        }
        assertEquals("1.0", svc.getLatestVersion(lib, includeSnapshots = false).latestPublishedVersion)
        val withSnapshots = svc.getLatestVersion(lib, includeSnapshots = true)
        assertEquals("1.1-SNAPSHOT", withSnapshots.latestPublishedVersion)
        assertEquals("1.0", withSnapshots.latestStableVersion)
    }

    @Test
    fun `only prereleases yields null stable version`() = runTest {
        val svc = service { uri ->
            if (uri.host == "repo1.maven.org") ok(metadataXml(listOf("1.0-alpha1", "1.0-beta1"))) else ok(emptySearch)
        }
        val r = svc.getLatestVersion(lib, includeSnapshots = false)
        assertEquals("1.0-beta1", r.latestPublishedVersion)
        assertNull(r.latestStableVersion)
    }

    @Test
    fun `missing artifact is reported as not found`() = runTest {
        val svc = service { uri -> if (uri.host == "repo1.maven.org") status(404) else ok(emptySearch) }
        assertThrows<MavenCentralException.ArtifactNotFound> { svc.getLatestVersion(lib, false) }
        assertThrows<MavenCentralException.ArtifactNotFound> { svc.getVersions(lib, 10, 0, true) }
        assertThrows<MavenCentralException.ArtifactNotFound> { svc.getArtifactInfo(lib) }
    }

    @Test
    fun `versions are Maven ordered, filtered and paginated`() = runTest {
        val svc = service { uri ->
            if (uri.host == "repo1.maven.org") ok(metadataXml(listOf("1.0", "1.10", "1.9", "2.0-beta1", "1.2")))
            else ok(gavDocs("1.10" to 1_600_000_000_000))
        }
        val all = svc.getVersions(lib, limit = 2, offset = 0, includePrereleases = true)
        assertEquals(listOf("2.0-beta1", "1.10"), all.versions.map { it.version })
        assertTrue(all.versions[0].prerelease)
        assertEquals(5, all.totalCount)
        assertTrue(all.hasMore)
        assertEquals("2020-09-13T12:26:40Z", all.versions[1].published)

        val stable = svc.getVersions(lib, limit = 10, offset = 1, includePrereleases = false)
        assertEquals(listOf("1.9", "1.2", "1.0"), stable.versions.map { it.version })
        assertEquals(4, stable.totalCount)
        assertFalse(stable.hasMore)
    }

    @Test
    fun `timestamp enrichment failure does not fail version listing`() = runTest {
        val svc = service { uri ->
            if (uri.host == "repo1.maven.org") ok(metadataXml(listOf("1.0"))) else status(503)
        }
        val page = svc.getVersions(lib, 10, 0, true)
        assertEquals(listOf("1.0"), page.versions.map { it.version })
        assertTrue(page.notes.any { it.startsWith("Could not load publication timestamps") })
    }

    @Test
    fun `artifact info combines index, metadata and pom`() = runTest {
        val svc = service { uri ->
            when {
                uri.path.endsWith("maven-metadata.xml") -> ok(metadataXml(listOf("1.0", "2.0-RC1"), release = "2.0-RC1"))
                uri.path.endsWith(".pom") -> ok("<project><name>Lib</name><description>Does things</description></project>")
                uri.param("q")?.startsWith("g:") == true -> ok(
                    """{"response":{"numFound":1,"docs":[{"g":"com.example","a":"lib","latestVersion":"1.0","p":"jar","timestamp":1}]}}""",
                )
                else -> status(404)
            }
        }
        val info = svc.getArtifactInfo(lib)
        assertEquals("1.0", info.indexedLatestVersion)
        assertEquals("2.0-RC1", info.repositoryReleaseVersion)
        assertEquals("1.0", info.pom?.version) // POM of the latest stable version
        assertEquals("Does things", info.pom?.description)
        assertEquals(2, info.versionCount)
        assertEquals("2026-11-02T18:07:48Z", info.repositoryLastUpdated)
        assertTrue(info.notes.any { "lag" in it })
    }

    @Test
    fun `artifact info survives index outage`() = runTest {
        val svc = service { uri ->
            when {
                uri.path.endsWith("maven-metadata.xml") -> ok(metadataXml(listOf("1.0")))
                uri.path.endsWith(".pom") -> status(404)
                else -> status(500)
            }
        }
        val info = svc.getArtifactInfo(lib)
        assertNull(info.indexedLatestVersion)
        assertTrue(info.notes.any { it.startsWith("Could not load search index") })
    }

    private val guavaLike = listOf(
        "21.0", "22.0", "22.0-android", "23.0", "23.0-android", "33.7.2-jre", "33.7.2-android",
        "33.8.0-android", "33.8.0-jre", "34.0.0-rc1-jre",
    )

    @Test
    fun `latest version reports each platform variant separately`() = runTest {
        val svc = service { uri -> if (uri.host == "repo1.maven.org") ok(metadataXml(guavaLike)) else ok(emptySearch) }
        val r = svc.getLatestVersion(lib, includeSnapshots = false)
        assertEquals("34.0.0-rc1-jre", r.latestPublishedVersion)
        assertTrue(r.uncertain)
        val variants = r.variants.associateBy { it.variant }
        assertEquals(setOf("jre", "android", "none"), variants.keys)
        assertEquals("33.8.0-jre", variants.getValue("jre").latestStableVersion)
        assertEquals("34.0.0-rc1-jre", variants.getValue("jre").latestPublishedVersion)
        assertEquals("33.8.0-android", variants.getValue("android").latestStableVersion)
        assertEquals("23.0", variants.getValue("none").latestStableVersion)
        assertTrue(r.guidance.contains("Maven ordering alone does not pick"))
        assertTrue(r.notes.any { "equally valid" in it })
    }

    @Test
    fun `stable lines give the latest release per major version`() = runTest {
        val svc = service { uri ->
            if (uri.host == "repo1.maven.org") ok(metadataXml(listOf("2.7.18", "3.4.0", "3.5.8", "4.0.0", "4.1.2", "4.2.0-RC2")))
            else ok(emptySearch)
        }
        val r = svc.getLatestVersion(lib, includeSnapshots = false)
        assertEquals(listOf(4L to "4.1.2", 3L to "3.5.8", 2L to "2.7.18"), r.stableLines.map { it.major to it.latestStableVersion })
        assertTrue(r.variants.isEmpty())
        assertFalse(r.uncertain)
    }

    @Test
    fun `get_versions labels and filters variants`() = runTest {
        val svc = service { uri -> if (uri.host == "repo1.maven.org") ok(metadataXml(guavaLike)) else ok(emptySearch) }
        val all = svc.getVersions(lib, limit = 3, offset = 0, includePrereleases = false)
        assertEquals(listOf("android", "jre", "none"), all.availableVariants)
        assertEquals(listOf("jre", "android", "jre"), all.versions.map { it.variant })
        assertTrue(all.notes.any { "parallel builds" in it })

        val android = svc.getVersions(lib, limit = 10, offset = 0, includePrereleases = true, variant = "android")
        assertEquals(listOf("33.8.0-android", "33.7.2-android", "23.0-android", "22.0-android"), android.versions.map { it.version })
        val plain = svc.getVersions(lib, limit = 10, offset = 0, includePrereleases = true, variant = "none")
        assertEquals(listOf("23.0", "22.0", "21.0"), plain.versions.map { it.version })
        assertThrows<MavenCentralException.InvalidArgument> {
            svc.getVersions(lib, limit = 10, offset = 0, includePrereleases = true, variant = "ios")
        }
    }

    @Test
    fun `metadata timestamp formatting falls back to raw value`() {
        assertEquals("garbage", MavenCentralService.formatMetadataTimestamp("garbage"))
    }
}
