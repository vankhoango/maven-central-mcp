package net.khoango.mavencentralmcp.core.service

import kotlinx.coroutines.test.runTest
import net.khoango.mavencentralmcp.core.model.ArtifactCoordinates
import net.khoango.mavencentralmcp.core.model.Developer
import net.khoango.mavencentralmcp.core.model.Organization
import net.khoango.mavencentralmcp.core.model.Provenance
import net.khoango.mavencentralmcp.maven.api.FakeFetcher
import net.khoango.mavencentralmcp.maven.api.FakeFetcher.Companion.metadataXml
import net.khoango.mavencentralmcp.maven.api.FakeFetcher.Companion.ok
import net.khoango.mavencentralmcp.maven.api.FakeFetcher.Companion.status
import net.khoango.mavencentralmcp.maven.api.FetchResponse
import net.khoango.mavencentralmcp.maven.api.MavenCentralClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI

/** POM inheritance, packaging provenance and field-level provenance for get_artifact_info. */
class PomProvenanceTest {
    private val lib = ArtifactCoordinates("com.example", "lib")

    private fun parent(g: String, a: String, v: String) = "<parent><groupId>$g</groupId><artifactId>$a</artifactId><version>$v</version></parent>"

    /** Guava-like: the artifact POM declares packaging, name and description; licenses/developers live in the parent. */
    private val childPom = "<project>${parent("com.example", "lib-parent", "1.0")}" +
        "<artifactId>lib</artifactId><packaging>bundle</packaging><name>Lib</name><description>Own description</description>" +
        "<url>https://example.com/lib</url></project>"
    private val parentPom = "<project>${parent("org.example", "oss-parent", "9")}<artifactId>lib-parent</artifactId>" +
        "<name>Parent name (not inherited)</name><url>https://example.com</url>" +
        "<licenses><license><name>Apache-2.0</name><url>https://www.apache.org/licenses/LICENSE-2.0</url></license></licenses>" +
        "<developers><developer><id>jdoe</id><name>Jane Doe</name><email>jane@example.com</email>" +
        "<organization>Example Inc.</organization><roles><role>lead</role></roles></developer></developers></project>"
    private val grandParentPom = "<project><artifactId>oss-parent</artifactId>" +
        "<organization><name>Example Foundation</name><url>https://example.org</url></organization></project>"

    private fun repo(
        child: String = childPom,
        parentResponse: () -> FetchResponse = { ok(parentPom) },
        grandParentResponse: () -> FetchResponse = { ok(grandParentPom) },
        index: String = """{"response":{"numFound":1,"docs":[{"g":"com.example","a":"lib","latestVersion":"1.0","p":"jar","timestamp":1}]}}""",
    ) = FakeFetcher { uri: URI ->
        when {
            uri.host == "search.maven.org" -> ok(index)
            uri.path.endsWith("/com/example/lib/maven-metadata.xml") -> ok(metadataXml(listOf("1.0")))
            uri.path.endsWith("/lib-1.0.pom") -> ok(child)
            uri.path.endsWith("/lib-parent-1.0.pom") -> parentResponse()
            uri.path.endsWith("/oss-parent-9.pom") -> grandParentResponse()
            else -> status(404)
        }
    }

    private suspend fun info(fetcher: FakeFetcher) = MavenCentralService(MavenCentralClient(fetcher)).getArtifactInfo(lib)

    @Test
    fun `inherited fields come from parent POMs with their source named`() = runTest {
        val pom = info(repo()).pom!!
        assertEquals("Own description", pom.description)
        assertEquals(Provenance.present("pom:com.example:lib:1.0"), pom.provenance["description"])
        assertEquals(listOf("Apache-2.0"), pom.licenses) // unchanged, backward-compatible name list
        assertEquals(
            listOf(net.khoango.mavencentralmcp.core.model.License("Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0")),
            pom.licenseDetails,
        )
        assertEquals(Provenance.present("parent-pom:com.example:lib-parent:1.0"), pom.provenance["licenses"])
        assertEquals(listOf(Developer(id = "jdoe", name = "Jane Doe", organization = "Example Inc.", roles = listOf("lead"))), pom.developers)
        assertEquals(Organization("Example Foundation", "https://example.org"), pom.organization)
        assertEquals(Provenance.present("parent-pom:org.example:oss-parent:9"), pom.provenance["organization"])
        assertEquals(listOf("com.example:lib:1.0", "com.example:lib-parent:1.0", "org.example:oss-parent:9"), pom.pomChain)
    }

    @Test
    fun `name and urls are not inherited and missing values are not invented`() = runTest {
        val child = "<project>${parent("com.example", "lib-parent", "1.0")}<artifactId>lib</artifactId></project>"
        val pom = info(repo(child = child)).pom!!
        assertNull(pom.name)
        assertEquals(Provenance(Provenance.MISSING, "pom:com.example:lib:1.0"), pom.provenance["name"])
        assertNull(pom.projectUrl)
        assertNull(pom.scmUrl)
        // Only the parent declares <url>: not the artifact's value, but not "missing" either.
        assertEquals(Provenance(Provenance.NOT_COMPUTED, "parent-pom:com.example:lib-parent:1.0"), pom.provenance["projectUrl"])
        assertEquals("https://example.com", pom.parentProjectUrl)
        // No POM in the complete chain declares <scm>: genuinely missing.
        assertEquals(Provenance.MISSING, pom.provenance.getValue("scmUrl").status)
        assertNull(pom.parentScmUrl)
        // description is inheritable but no POM in the complete chain has one.
        assertNull(pom.description)
        assertEquals(Provenance.MISSING, pom.provenance.getValue("description").status)
    }

    // ---- URL fields that Maven inherits only in derived form (Guava regression) -----------------------------------

    private val noUrlChild = "<project>${parent("com.example", "lib-parent", "1.0")}<artifactId>lib</artifactId></project>"

    @Test
    fun `Guava case - scm url declared only in the direct parent is not_computed, never missing`() = runTest {
        val parentWithScm = "<project><artifactId>lib-parent</artifactId><url>https://github.com/example/lib</url>" +
            "<scm><connection>scm:git:https://github.com/example/lib.git</connection><url>https://github.com/example/lib</url></scm></project>"
        val pom = info(repo(child = noUrlChild, parentResponse = { ok(parentWithScm) })).pom!!
        assertNull(pom.scmUrl)
        assertNull(pom.projectUrl)
        assertEquals(Provenance(Provenance.NOT_COMPUTED, "parent-pom:com.example:lib-parent:1.0"), pom.provenance["scmUrl"])
        assertEquals(Provenance(Provenance.NOT_COMPUTED, "parent-pom:com.example:lib-parent:1.0"), pom.provenance["projectUrl"])
        assertEquals("https://github.com/example/lib", pom.parentScmUrl)
        assertEquals("https://github.com/example/lib", pom.parentProjectUrl)
    }

    @Test
    fun `url declared only in a more distant ancestor names that ancestor`() = runTest {
        val middle = "<project>${parent("org.example", "oss-parent", "9")}<artifactId>lib-parent</artifactId></project>"
        val top = "<project><artifactId>oss-parent</artifactId><scm><url>https://scm.example.org/root</url></scm></project>"
        val pom = info(repo(child = noUrlChild, parentResponse = { ok(middle) }, grandParentResponse = { ok(top) })).pom!!
        assertEquals(Provenance(Provenance.NOT_COMPUTED, "parent-pom:org.example:oss-parent:9"), pom.provenance["scmUrl"])
        assertEquals("https://scm.example.org/root", pom.parentScmUrl)
        // Nobody declares <url>: missing after the complete chain was read.
        assertEquals(
            Provenance(Provenance.MISSING, "pom:com.example:lib:1.0, parent-pom:com.example:lib-parent:1.0, parent-pom:org.example:oss-parent:9"),
            pom.provenance["projectUrl"],
        )
        assertNull(pom.parentProjectUrl)
    }

    @Test
    fun `unreachable parent makes absent urls unavailable, not missing`() = runTest {
        val pom = info(repo(child = noUrlChild, parentResponse = { status(500) })).pom!!
        assertEquals(Provenance(Provenance.UNAVAILABLE, "parent-pom:com.example:lib-parent:1.0"), pom.provenance["scmUrl"])
        assertEquals(Provenance.UNAVAILABLE, pom.provenance.getValue("projectUrl").status)
        assertNull(pom.parentScmUrl)
    }

    @Test
    fun `own urls stay present and parent values are not reported`() = runTest {
        val child = "<project>${parent("com.example", "lib-parent", "1.0")}<artifactId>lib</artifactId>" +
            "<url>https://lib.example</url><scm><url>https://scm.example/lib</url></scm></project>"
        val pom = info(repo(child = child)).pom!!
        assertEquals("https://lib.example", pom.projectUrl)
        assertEquals("https://scm.example/lib", pom.scmUrl)
        assertEquals(Provenance.present("pom:com.example:lib:1.0"), pom.provenance["scmUrl"])
        assertNull(pom.parentProjectUrl)
        assertNull(pom.parentScmUrl)
    }

    @Test
    fun `parent url with a property expression is not returned as a value`() = runTest {
        val parentWithProperty = "<project><artifactId>lib-parent</artifactId><url>\${project.scm.url}</url></project>"
        val pom = info(repo(child = noUrlChild, parentResponse = { ok(parentWithProperty) })).pom!!
        assertEquals(Provenance(Provenance.NOT_COMPUTED, "parent-pom:com.example:lib-parent:1.0"), pom.provenance["projectUrl"])
        assertNull(pom.parentProjectUrl)
    }

    @Test
    fun `developer e-mail addresses are never returned`() = runTest {
        val json = kotlinx.serialization.json.Json.encodeToString(
            net.khoango.mavencentralmcp.core.model.ArtifactInfo.serializer(), info(repo()),
        )
        assertTrue("jane@example.com" !in json)
    }

    @Test
    fun `unreachable parent makes inherited fields unavailable, not missing`() = runTest {
        val pom = info(repo(parentResponse = { status(500) })).pom!!
        assertEquals(Provenance.UNAVAILABLE, pom.provenance.getValue("licenses").status)
        assertEquals(Provenance.UNAVAILABLE, pom.provenance.getValue("developers").status)
        assertEquals("Own description", pom.description)
        assertEquals(listOf("com.example:lib:1.0"), pom.pomChain)
    }

    @Test
    fun `parent depth is bounded and the remainder is not loaded`() = runTest {
        // oss-parent declares yet another parent; with 3 levels read, a 4th is not requested.
        val deepGrandParent = "<project>${parent("org.example", "root", "1")}<artifactId>oss-parent</artifactId></project>"
        val deep = "<project>${parent("org.example", "oss-parent", "9")}<artifactId>lib-parent</artifactId></project>"
        val chain3 = FakeFetcher { uri: URI ->
            when {
                uri.host == "search.maven.org" -> ok("""{"response":{"numFound":0,"docs":[]}}""")
                uri.path.endsWith("maven-metadata.xml") -> ok(metadataXml(listOf("1.0")))
                uri.path.endsWith("/lib-1.0.pom") -> ok(childPom)
                uri.path.endsWith("/lib-parent-1.0.pom") -> ok(deep)
                uri.path.endsWith("/oss-parent-9.pom") -> ok(deepGrandParent)
                uri.path.endsWith("/root-1.pom") -> ok("<project>${parent("org.example", "root2", "1")}<artifactId>root</artifactId></project>")
                else -> status(404)
            }
        }
        val pom = info(chain3).pom!!
        assertEquals(4, pom.pomChain.size)
        assertEquals(Provenance.NOT_LOADED, pom.provenance.getValue("licenses").status)
        assertTrue(chain3.requests.none { it.path.endsWith("/root2-1.pom") })
    }

    @Test
    fun `property expressions are reported as unresolved`() = runTest {
        val child = "<project><artifactId>lib</artifactId><name>\${project.artifactId}</name>" +
            "<description>\${project.name} library</description></project>"
        val pom = info(repo(child = child)).pom!!
        assertNull(pom.name)
        assertNull(pom.description)
        assertEquals(Provenance.UNRESOLVED, pom.provenance.getValue("name").status)
        assertEquals(Provenance.UNRESOLVED, pom.provenance.getValue("description").status)
    }

    @Test
    fun `packaging comes from the POM, distinct from the index value`() = runTest {
        val r = info(repo())
        assertEquals("bundle", r.packaging)
        assertEquals("jar", r.indexedPackaging)
        assertEquals(Provenance.present("pom:com.example:lib:1.0"), r.provenance["packaging"])
        assertEquals(Provenance.present(Provenance.SEARCH_INDEX), r.provenance["indexedPackaging"])
    }

    @Test
    fun `absent packaging is labelled as the Maven default`() = runTest {
        val r = info(repo(child = "<project><artifactId>lib</artifactId></project>"))
        assertEquals("jar", r.packaging)
        val p = r.provenance.getValue("packaging")
        assertEquals(Provenance.PRESENT, p.status)
        assertTrue(p.source!!.startsWith(Provenance.MAVEN_DEFAULT))
    }

    @Test
    fun `top-level provenance distinguishes missing from unavailable`() = runTest {
        val notIndexed = info(repo(index = """{"response":{"numFound":0,"docs":[]}}"""))
        assertEquals(Provenance(Provenance.MISSING, Provenance.SEARCH_INDEX), notIndexed.provenance["indexedLatestVersion"])

        val failed = MavenCentralService(MavenCentralClient(FakeFetcher { uri ->
            when {
                uri.host == "search.maven.org" -> status(500)
                uri.path.endsWith("maven-metadata.xml") -> ok(metadataXml(listOf("1.0")))
                else -> status(404)
            }
        })).getArtifactInfo(lib)
        assertEquals(Provenance(Provenance.UNAVAILABLE, Provenance.SEARCH_INDEX), failed.provenance["indexedLatestVersion"])
        assertEquals(Provenance.present(Provenance.REPOSITORY_METADATA), failed.provenance["repositoryReleaseVersion"])
        // The POM itself returned 404: unavailable, and packaging is not guessed.
        assertNull(failed.packaging)
        assertEquals(Provenance.UNAVAILABLE, failed.provenance.getValue("pom").status)
        assertEquals(Provenance.UNAVAILABLE, failed.provenance.getValue("packaging").status)
    }

    @Test
    fun `search reads up to two parent POMs when the own POM has no description`() = runTest {
        val child = "<project>${parent("com.example", "lib-parent", "1.0")}<artifactId>lib</artifactId><name>Lib</name></project>"
        val fetcher = repo(child = child)
        val hit = MavenCentralService(MavenCentralClient(fetcher))
            .search(net.khoango.mavencentralmcp.core.model.SearchRequest("lib", limit = 1, offset = 0)).results.single()
        assertEquals("Lib", hit.name)
        // Neither the POM nor its two parents declare a description: missing, and both fields agree.
        assertEquals(Provenance.MISSING, hit.provenance.getValue("description").status)
        assertEquals("missing", hit.descriptionSource)
        assertEquals(Provenance.present(Provenance.SEARCH_INDEX), hit.provenance["indexedLatestVersion"])
        // The fixture's index document has no versionCount: reported as missing, not zero.
        assertNull(hit.indexedVersionCount)
        assertEquals(Provenance(Provenance.MISSING, Provenance.SEARCH_INDEX), hit.provenance["indexedVersionCount"])
        assertTrue(fetcher.requests.any { it.path.endsWith("/lib-parent-1.0.pom") })
        assertTrue(fetcher.requests.any { it.path.endsWith("/oss-parent-9.pom") })
    }
}
