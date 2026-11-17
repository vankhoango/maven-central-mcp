package net.khoango.mavencentralmcp.maven.mapper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
        val p = XmlParsers.parsePom(pom.toByteArray(), "1.0")
        assertEquals("Example", p.name)
        assertEquals("A multi-line description", p.description)
        assertEquals("https://example.com", p.projectUrl)
        assertEquals("https://github.com/example/lib", p.scmUrl)
        assertEquals(listOf("Apache-2.0"), p.licenses)
    }
}
