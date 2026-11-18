package net.khoango.mavencentralmcp.core.service

import kotlinx.coroutines.test.runTest
import net.khoango.mavencentralmcp.core.model.ArtifactCoordinates
import net.khoango.mavencentralmcp.maven.api.FakeFetcher
import net.khoango.mavencentralmcp.maven.api.FakeFetcher.Companion.metadataXml
import net.khoango.mavencentralmcp.maven.api.FakeFetcher.Companion.ok
import net.khoango.mavencentralmcp.maven.api.FakeFetcher.Companion.status
import net.khoango.mavencentralmcp.maven.api.MavenCentralClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI

/** SCM wording, release variant hint and timestamp format (Guava live-test follow-up, November 2026). */
class MetadataPolishTest {

    private fun repo(g: String, a: String, versions: List<String>, ownPom: String, parentPom: String? = null) = FakeFetcher { uri: URI ->
        val path = uri.path
        when {
            uri.host == "search.maven.org" -> ok("""{"response":{"numFound":0,"docs":[]}}""")
            path.endsWith("/maven-metadata.xml") -> ok(metadataXml(versions))
            path.contains("/${a}-parent/") && parentPom != null -> ok(parentPom)
            path.contains("/$a/") && path.endsWith(".pom") -> ok(ownPom)
            else -> status(404)
        }
    }

    // Raw Guava files (live): guava-33.8.0-jre.pom has no <scm>; guava-parent declares
    // <scm><url>https://github.com/google/guava</url></scm>. Versions mirror Guava's jre/android/unqualified lines.
    private val guavaVersions = listOf("22.0", "23.0", "23.0-android", "33.7.2-android", "33.7.2-jre", "33.8.0-android", "33.8.0-jre", "34.0.0-rc1-jre")
    private val guavaPom = "<project><parent><groupId>com.google.guava</groupId><artifactId>guava-parent</artifactId>" +
        "<version>33.8.0-jre</version></parent><artifactId>guava</artifactId><packaging>bundle</packaging>" +
        "<url>https://github.com/google/guava</url></project>"
    private val guavaParent = "<project><artifactId>guava-parent</artifactId><url>https://github.com/google/guava</url>" +
        "<scm><url>https://github.com/google/guava</url></scm></project>"

    private suspend fun guava(): Pair<net.khoango.mavencentralmcp.core.model.ArtifactInfo, FakeFetcher> {
        val f = repo("com.google.guava", "guava", guavaVersions, guavaPom, guavaParent)
        return MavenCentralService(MavenCentralClient(f)).getArtifactInfo(ArtifactCoordinates("com.google.guava", "guava")) to f
    }

    @Test
    fun `Guava - release variants are exposed with the POM version first`() = runTest {
        val (info, _) = guava()
        assertEquals("33.8.0-jre", info.pom!!.version)
        assertEquals(listOf("33.8.0-jre", "33.8.0-android"), info.releaseVariants)
        assertTrue(info.notes.any { "several platform variants (33.8.0-jre, 33.8.0-android)" in it && "get_latest_version" in it })
    }

    @Test
    fun `Guava - variant hint costs no extra upstream request`() = runTest {
        val (_, f) = guava()
        assertEquals(1, f.requests.count { it.path.endsWith("/maven-metadata.xml") })
        assertTrue(f.requests.none { "core=gav" in it.rawQuery.orEmpty() }) // no version-index lookup
    }

    @Test
    fun `Guava - scm wording separates own and parent values`() = runTest {
        val (info, _) = guava()
        assertNull(info.pom!!.scmUrl)
        assertEquals("https://github.com/google/guava", info.pom.parentScmUrl)
        val general = info.notes.single { it.startsWith("POM fields describe version 33.8.0-jre") }
        assertTrue("projectUrl and scmUrl are the artifact's own POM values" in general)
        assertTrue("parentProjectUrl / parentScmUrl" in general)
        assertTrue(info.notes.any {
            it.startsWith("scmUrl is null because the artifact's own POM declares no <scm>") &&
                "com.google.guava:guava-parent:33.8.0-jre" in it && "parentScmUrl = https://github.com/google/guava" in it
        })
    }

    @Test
    fun `artifact without variants has no hint`() = runTest {
        // Spring Boot style: plain versions plus a prerelease; its own POM declares <scm>.
        val pom = "<project><artifactId>spring-boot</artifactId><scm><url>https://github.com/spring-projects/spring-boot</url></scm></project>"
        val f = repo("org.springframework.boot", "spring-boot", listOf("3.5.8", "4.1.2", "4.2.0-RC2"), pom)
        val info = MavenCentralService(MavenCentralClient(f)).getArtifactInfo(ArtifactCoordinates("org.springframework.boot", "spring-boot"))
        assertNull(info.releaseVariants)
        assertTrue(info.notes.none { "platform variants" in it })
        assertTrue(info.notes.none { it.startsWith("scmUrl is null") })
        assertEquals("https://github.com/spring-projects/spring-boot", info.pom!!.scmUrl)
    }

    @Test
    fun `historic variants do not create a hint for a single-form current release`() = runTest {
        // PostgreSQL style: old .jre7/.jre6 builds, current release published once.
        val pom = "<project><artifactId>postgresql</artifactId></project>"
        val f = repo("org.postgresql", "postgresql", listOf("42.2.27.jre6", "42.2.29.jre7", "42.2.29", "42.7.14"), pom)
        val info = MavenCentralService(MavenCentralClient(f)).getArtifactInfo(ArtifactCoordinates("org.postgresql", "postgresql"))
        assertEquals("42.7.14", info.pom!!.version)
        assertNull(info.releaseVariants)
    }

    @Test
    fun `timestamps use one millisecond-precision format without losing precision`() {
        assertEquals("2025-04-14T17:25:22.422Z", MavenCentralService.formatEpochMillis(1_744_651_522_422)) // search index
        assertEquals("2026-11-02T18:06:39.000Z", MavenCentralService.formatEpochMillis(1_793_642_799_000)) // POM Last-Modified
        assertEquals("2026-11-02T18:07:48.000Z", MavenCentralService.formatMetadataTimestamp("20261102180748")) // maven-metadata
        assertEquals("garbage", MavenCentralService.formatMetadataTimestamp("garbage"))
        assertEquals(1_744_651_522_422, java.time.Instant.parse(MavenCentralService.formatEpochMillis(1_744_651_522_422)).toEpochMilli())
    }
}
