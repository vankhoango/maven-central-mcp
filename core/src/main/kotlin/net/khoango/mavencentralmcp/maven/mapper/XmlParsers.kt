package net.khoango.mavencentralmcp.maven.mapper

import net.khoango.mavencentralmcp.core.model.Developer
import net.khoango.mavencentralmcp.core.model.Organization
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

/** `<parent>` coordinates declared by a POM. */
internal data class ParentRef(val groupId: String, val artifactId: String, val version: String) {
    override fun toString() = "$groupId:$artifactId:$version"
}

/** Fields of a single POM file before inheritance. Null means "element absent in this file". */
internal data class RawPom(
    val parent: ParentRef?,
    val name: String?,
    val description: String?,
    val url: String?,
    val scmUrl: String?,
    val packaging: String?,
    val licenses: List<String>?,
    val developers: List<Developer>?,
    val organization: Organization?,
    val relocation: String?,
    val relocationMessage: String?,
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

    /**
     * Extracts project fields from one POM file, without inheritance. A null field or list means the element
     * is absent from this file; inheritance is applied by the caller. Values are untrusted and length-capped.
     */
    fun parsePom(bytes: ByteArray): RawPom {
        val root = parseRoot(bytes)
        require(root.tagName == "project") { "Unexpected root element" }
        val licenses = root.child("licenses")?.children("license")
            ?.mapNotNull { it.childText("name") ?: it.childText("url") }
            ?.map { it.cap(200) }
            ?.take(MAX_ITEMS)
        val developers = root.child("developers")?.children("developer")
            ?.map { d ->
                Developer(
                    id = d.childText("id")?.cap(100),
                    name = d.childText("name")?.cap(200),
                    organization = d.childText("organization")?.cap(200),
                    organizationUrl = d.childText("organizationUrl")?.cap(500),
                    url = d.childText("url")?.cap(500),
                    roles = d.child("roles")?.children("role")?.mapNotNull { it.textContent?.trim()?.ifEmpty { null }?.cap(100) }
                        ?.take(MAX_ITEMS).orEmpty(),
                )
            }
            ?.filter { it.id != null || it.name != null || it.organization != null }
            ?.take(MAX_ITEMS)
        val organization = root.child("organization")?.let { o ->
            Organization(o.childText("name")?.cap(200), o.childText("url")?.cap(500))
                .takeIf { it.name != null || it.url != null }
        }
        val parent = root.child("parent")?.let { p ->
            val g = p.childText("groupId")
            val a = p.childText("artifactId")
            val v = p.childText("version")
            if (g != null && a != null && v != null) ParentRef(g, a, v) else null
        }
        // <distributionManagement><relocation> marks an artifact that moved; omitted parts default to the original.
        val relocation = root.child("distributionManagement")?.child("relocation")
        val relocatedTo = relocation?.let { r ->
            val g = r.childText("groupId") ?: root.childText("groupId") ?: parent?.groupId
            val a = r.childText("artifactId") ?: root.childText("artifactId")
            listOfNotNull(g, a, r.childText("version")).joinToString(":").cap(300)
        }
        return RawPom(
            parent = parent,
            name = root.childText("name")?.cap(300),
            description = root.childText("description")?.collapseWhitespace()?.cap(2_000),
            url = root.childText("url")?.cap(500),
            scmUrl = root.child("scm")?.childText("url")?.cap(500),
            packaging = root.childText("packaging")?.cap(50),
            licenses = licenses,
            developers = developers,
            organization = organization,
            relocation = relocatedTo,
            relocationMessage = relocation?.childText("message")?.collapseWhitespace()?.cap(500),
        )
    }

    private const val MAX_ITEMS = 20

    private fun String.collapseWhitespace(): String = replace(Regex("\\s+"), " ").trim()

    private fun String.cap(max: Int): String = if (length <= max) this else take(max - 1) + "…"
}
