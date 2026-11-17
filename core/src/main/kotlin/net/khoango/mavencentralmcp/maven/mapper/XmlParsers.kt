package net.khoango.mavencentralmcp.maven.mapper

import net.khoango.mavencentralmcp.core.model.PomMetadata
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** Parsed `maven-metadata.xml` for one artifact. Versions keep their published spelling and order. */
internal data class MavenMetadata(
    val latest: String?,
    val release: String?,
    val lastUpdated: String?,
    val versions: List<String>,
)

/**
 * Hardened XML parsing for repository metadata and POMs.
 * DOCTYPEs and external entities are rejected to prevent XXE and entity-expansion attacks.
 */
internal object XmlParsers {

    private fun factory(): DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        isXIncludeAware = false
        isExpandEntityReferences = false
        isNamespaceAware = false
    }

    private fun parseRoot(bytes: ByteArray): Element {
        val builder = factory().newDocumentBuilder()
        builder.setErrorHandler(null)
        return builder.parse(InputSource(ByteArrayInputStream(bytes))).documentElement
    }

    private fun Element.children(name: String): List<Element> {
        val nodes = childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }.filter { it.tagName == name }
    }

    private fun Element.child(name: String): Element? = children(name).firstOrNull()

    private fun Element.childText(name: String): String? =
        child(name)?.textContent?.trim()?.takeIf { it.isNotEmpty() }

    /** @throws Exception on malformed XML; callers map this to a domain error. */
    fun parseMetadata(bytes: ByteArray): MavenMetadata {
        val root = parseRoot(bytes)
        require(root.tagName == "metadata") { "Unexpected root element" }
        val versioning = root.child("versioning")
        val versions = versioning?.child("versions")?.children("version")
            ?.mapNotNull { it.textContent?.trim()?.takeIf(String::isNotEmpty) }
            ?.distinct()
            .orEmpty()
        return MavenMetadata(
            latest = versioning?.childText("latest"),
            release = versioning?.childText("release"),
            lastUpdated = versioning?.childText("lastUpdated"),
            versions = versions,
        )
    }

    /** Extracts descriptive project fields from a POM. Values are untrusted and length-capped. */
    fun parsePom(bytes: ByteArray, version: String): PomMetadata {
        val root = parseRoot(bytes)
        require(root.tagName == "project") { "Unexpected root element" }
        val licenses = root.child("licenses")?.children("license")
            ?.mapNotNull { it.childText("name") ?: it.childText("url") }
            ?.map { it.cap(200) }
            ?.take(10)
            .orEmpty()
        // <distributionManagement><relocation> marks an artifact that moved; omitted parts default to the original.
        val relocation = root.child("distributionManagement")?.child("relocation")
        val relocatedTo = relocation?.let { r ->
            val g = r.childText("groupId") ?: root.childText("groupId") ?: root.child("parent")?.childText("groupId")
            val a = r.childText("artifactId") ?: root.childText("artifactId")
            listOfNotNull(g, a, r.childText("version")).joinToString(":").cap(300)
        }
        return PomMetadata(
            version = version,
            name = root.childText("name")?.cap(300),
            description = root.childText("description")?.collapseWhitespace()?.cap(2_000),
            projectUrl = root.childText("url")?.cap(500),
            scmUrl = root.child("scm")?.childText("url")?.cap(500),
            licenses = licenses,
            relocation = relocatedTo,
            relocationMessage = relocation?.childText("message")?.collapseWhitespace()?.cap(500),
        )
    }

    private fun String.collapseWhitespace(): String = replace(Regex("\\s+"), " ").trim()

    private fun String.cap(max: Int): String = if (length <= max) this else take(max - 1) + "…"
}
