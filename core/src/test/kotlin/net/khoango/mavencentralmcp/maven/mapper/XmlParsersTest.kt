package net.khoango.mavencentralmcp.maven.mapper

import net.khoango.mavencentralmcp.core.model.License
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class XmlParsersTest {

    @Test
    fun `parses maven metadata preserving deploy order`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <metadata>
              <groupId>com.example</groupId><artifactId>lib</artifactId>
              <versioning>
                <latest>2.0-RC1</latest><release>2.0-RC1</release>
                <versions><version>1.0</version><version>1.10</version><version>1.9</version><version>2.0-RC1</version></versions>
                <lastUpdated>20261102180748</lastUpdated>
              </versioning>
            </metadata>
        """.trimIndent()
        val m = XmlParsers.parseMetadata(xml.toByteArray())
        assertEquals(listOf("1.0", "1.10", "1.9", "2.0-RC1"), m.versions)
        assertEquals("2.0-RC1", m.release)
        assertEquals("20261102180748", m.lastUpdated)
    }

    @Test
    fun `tolerates metadata without versioning`() {
        val m = XmlParsers.parseMetadata("<metadata><groupId>g</groupId></metadata>".toByteArray())
        assertEquals(emptyList<String>(), m.versions)
        assertNull(m.release)
    }

    @Test
    fun `rejects doctype to prevent XXE`() {
        val xxe = """
            <?xml version="1.0"?>
            <!DOCTYPE metadata [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <metadata><versioning><latest>&xxe;</latest></versioning></metadata>
        """.trimIndent()
        assertThrows<Exception> { XmlParsers.parseMetadata(xxe.toByteArray()) }
    }

    @Test
    fun `rejects malformed xml and wrong root`() {
        assertThrows<Exception> { XmlParsers.parseMetadata("<metadata><versioning>".toByteArray()) }
        assertThrows<Exception> { XmlParsers.parseMetadata("<html></html>".toByteArray()) }
    }

    @Test
    fun `parses relocation with defaults from the original coordinates`() {
        val pom = """
            <project><groupId>mysql</groupId><artifactId>mysql-connector-java</artifactId>
              <distributionManagement><relocation>
                <groupId>com.mysql</groupId><artifactId>mysql-connector-j</artifactId>
                <message>MySQL Connector/J artifacts moved to reverse-DNS compliant Maven 2+ coordinates.</message>
              </relocation></distributionManagement>
            </project>
        """.trimIndent()
        val p = XmlParsers.parsePom(pom.toByteArray())
        assertEquals("com.mysql:mysql-connector-j", p.relocation)
        assertEquals("MySQL Connector/J artifacts moved to reverse-DNS compliant Maven 2+ coordinates.", p.relocationMessage)

        val onlyVersion = "<project><groupId>g</groupId><artifactId>a</artifactId><distributionManagement>" +
            "<relocation><version>2.0</version></relocation></distributionManagement></project>"
        assertEquals("g:a:2.0", XmlParsers.parsePom(onlyVersion.toByteArray()).relocation)
        assertNull(XmlParsers.parsePom("<project/>".toByteArray()).relocation)
    }

    @Test
    fun `parses developers, organization, packaging and parent without e-mail`() {
        val pom = """
            <project>
              <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>2</version></parent>
              <packaging>aar</packaging>
              <organization><name>Example Inc.</name><url>https://example.com</url></organization>
              <developers>
                <developer><id>a</id><name>Ann</name><email>ann@example.com</email><url>https://ann.dev</url>
                  <organizationUrl>https://example.com</organizationUrl><roles><role>dev</role><role>lead</role></roles></developer>
                <developer><email>only-email@example.com</email></developer>
              </developers>
            </project>
        """.trimIndent()
        val p = XmlParsers.parsePom(pom.toByteArray())
        assertEquals("com.example:parent:2", p.parent.toString())
        assertEquals("aar", p.packaging)
        assertEquals("Example Inc.", p.organization?.name)
        val dev = p.developers!!.single() // the e-mail-only entry has no identifying fields left and is dropped
        assertEquals("Ann", dev.name)
        assertEquals(listOf("dev", "lead"), dev.roles)
        assertTrue(dev.toString().contains("@").not())
    }

    @Test
    fun `parses license names and URLs without inventing missing parts`() {
        val pom = """
            <project><licenses>
              <license><name>Apache License, Version 2.0</name><url>https://www.apache.org/licenses/LICENSE-2.0</url></license>
              <license><url>https://opensource.org/licenses/MIT</url></license>
              <license><name>Proprietary</name></license>
              <license><distribution>repo</distribution></license>
            </licenses></project>
        """.trimIndent()
        assertEquals(
            listOf(
                License("Apache License, Version 2.0", "https://www.apache.org/licenses/LICENSE-2.0"),
                License(null, "https://opensource.org/licenses/MIT"),
                License("Proprietary", null),
            ),
            XmlParsers.parsePom(pom.toByteArray()).licenses,
        )
    }

    @Test
    fun `absent elements are null, not empty`() {
        val p = XmlParsers.parsePom("<project/>".toByteArray())
        assertNull(p.packaging)
        assertNull(p.developers)
        assertNull(p.licenses)
        assertNull(p.organization)
        assertNull(p.parent)
    }

    @Test
    fun `parses top-level pom fields only`() {
        val pom = """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <name>Example</name>
              <description>
                A   multi-line
                description
              </description>
              <url>https://example.com</url>
              <licenses><license><name>Apache-2.0</name></license></licenses>
              <scm><url>https://github.com/example/lib</url></scm>
              <dependencies><dependency><description>nested, ignored</description></dependency></dependencies>
            </project>
        """.trimIndent()
        val p = XmlParsers.parsePom(pom.toByteArray())
        assertEquals("Example", p.name)
        assertEquals("A multi-line description", p.description)
        assertEquals("https://example.com", p.url)
        assertEquals("https://github.com/example/lib", p.scmUrl)
        assertEquals(listOf(License("Apache-2.0", null)), p.licenses)
    }
}
