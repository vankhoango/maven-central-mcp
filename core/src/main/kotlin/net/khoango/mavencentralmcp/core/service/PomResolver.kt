package net.khoango.mavencentralmcp.core.service

import kotlinx.coroutines.CancellationException
import net.khoango.mavencentralmcp.core.error.MavenCentralException
import net.khoango.mavencentralmcp.core.model.ArgumentValidator
import net.khoango.mavencentralmcp.core.model.ArtifactCoordinates
import net.khoango.mavencentralmcp.core.model.PomMetadata
import net.khoango.mavencentralmcp.core.model.Provenance
import net.khoango.mavencentralmcp.maven.api.MavenCentralClient
import net.khoango.mavencentralmcp.maven.mapper.RawPom

/**
 * Reads a POM and, up to [maxParents] levels, its parent POMs, then applies Maven inheritance to the
 * fields that Maven inherits verbatim: `description`, `licenses`, `developers` and `organization`.
 *
 * Deliberately not inherited: `name` and `packaging` (Maven does not inherit them) and `url`/`scm.url`
 * (Maven inherits them with the module path appended, which is not reproduced here). When only an ancestor
 * declares `url`/`scm.url`, the field is [Provenance.NOT_COMPUTED], not missing, and the ancestor's declared
 * value is exposed separately as `parentProjectUrl`/`parentScmUrl`. Property expressions
 * such as `${project.url}` are not evaluated; such values are reported as [Provenance.UNRESOLVED].
 * Every parent fetch is a cached GET on the fixed repository host.
 */
internal class PomResolver(private val client: MavenCentralClient) {

    /** Returns null if the artifact's own POM does not exist. Errors reading the own POM propagate. */
    suspend fun resolve(coordinates: ArtifactCoordinates, version: String, maxParents: Int): PomMetadata? {
        val own = client.fetchRawPom(coordinates, version) ?: return null
        val chain = mutableListOf(Loaded(coordinates.groupId, coordinates.artifactId, version, own))
        // Why the chain ends: COMPLETE (no further parent), or the parent was unavailable / not loaded.
        var chainEnd = Provenance.PRESENT
        while (true) {
            val ref = chain.last().pom.parent ?: break
            if (chain.size > maxParents) {
                chainEnd = Provenance.NOT_LOADED
                break
            }
            val parentCoordinates = runCatching { ArgumentValidator.coordinates(ref.groupId, ref.artifactId) }.getOrNull()
            val parent = try {
                parentCoordinates?.let { client.fetchRawPom(it, ref.version) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: MavenCentralException) {
                null
            }
            if (parent == null) {
                chainEnd = Provenance.UNAVAILABLE
                break
            }
            chain += Loaded(ref.groupId, ref.artifactId, ref.version, parent)
        }

        val provenance = linkedMapOf<String, Provenance>()
        fun sourceOf(index: Int) = chain[index].let {
            if (index == 0) Provenance.pomSource(it.groupId, it.artifactId, it.version)
            else Provenance.parentPomSource(it.groupId, it.artifactId, it.version)
        }
        val ownSource = sourceOf(0)
        val absentEverywhere = when (chainEnd) {
            Provenance.PRESENT -> Provenance(Provenance.MISSING, chain.indices.joinToString(", ") { sourceOf(it) })
            else -> chain.last().pom.parent!!.let { Provenance(chainEnd, Provenance.parentPomSource(it.groupId, it.artifactId, it.version)) }
        }

        /** Own-POM-only string field. */
        fun own(field: String, value: String?): String? = when {
            value == null -> null.also { provenance[field] = Provenance(Provenance.MISSING, ownSource) }
            "\${" in value -> null.also { provenance[field] = Provenance(Provenance.UNRESOLVED, ownSource) }
            else -> value.also { provenance[field] = Provenance.present(ownSource) }
        }

        /** Inherited field: first POM in the chain (child first) that declares it wins, as in Maven. */
        fun <T : Any> inherited(field: String, select: (RawPom) -> T?, unresolved: (T) -> Boolean = { false }): T? {
            val index = chain.indexOfFirst { select(it.pom) != null }
            if (index < 0) {
                provenance[field] = absentEverywhere
                return null
            }
            val value = select(chain[index].pom)!!
            if (unresolved(value)) {
                provenance[field] = Provenance(Provenance.UNRESOLVED, sourceOf(index))
                return null
            }
            provenance[field] = Provenance.present(sourceOf(index))
            return value
        }

        /**
         * URL field that Maven inherits only in derived form (parent URL plus the module path), which is not
         * reproduced. If the artifact's own POM lacks it but an ancestor declares it, the field is reported as
         * [Provenance.NOT_COMPUTED] with that ancestor as source, and the ancestor's value is returned separately
         * via [declaredInParent]; it is never presented as the artifact's own value. Returns (value, parentValue).
         */
        fun derivedUrl(field: String, select: (RawPom) -> String?): Pair<String?, String?> {
            val ownValue = select(own)
            if (ownValue != null) return own(field, ownValue) to null
            val index = (1 until chain.size).firstOrNull { select(chain[it].pom) != null }
            if (index == null) {
                provenance[field] = absentEverywhere
                return null to null
            }
            provenance[field] = Provenance(Provenance.NOT_COMPUTED, sourceOf(index))
            return null to select(chain[index].pom)?.takeUnless { "\${" in it }
        }

        val name = own("name", own.name)
        val description = inherited("description", RawPom::description) { "\${" in it }
        val (projectUrl, parentProjectUrl) = derivedUrl("projectUrl", RawPom::url)
        val (scmUrl, parentScmUrl) = derivedUrl("scmUrl", RawPom::scmUrl)
        val licenses = inherited("licenses", RawPom::licenses)
        val packaging = when (val p = own.packaging) {
            null -> "jar".also {
                provenance["packaging"] = Provenance.present("${Provenance.MAVEN_DEFAULT} (no <packaging> in $ownSource)")
            }
            else -> own("packaging", p)
        }
        val developers = inherited("developers", RawPom::developers)
        val organization = inherited("organization", RawPom::organization)
        val relocation = own("relocation", own.relocation)

        return PomMetadata(
            version = version,
            name = name,
            description = description,
            projectUrl = projectUrl,
            scmUrl = scmUrl,
            parentProjectUrl = parentProjectUrl,
            parentScmUrl = parentScmUrl,
            licenses = licenses.orEmpty().mapNotNull { it.name ?: it.url },
            licenseDetails = licenses.orEmpty(),
            packaging = packaging,
            developers = developers.orEmpty(),
            organization = organization,
            relocation = relocation,
            relocationMessage = own.relocationMessage,
            pomChain = chain.map { "${it.groupId}:${it.artifactId}:${it.version}" },
            provenance = provenance,
        )
    }

    private class Loaded(val groupId: String, val artifactId: String, val version: String, val pom: RawPom)

    companion object {
        /** Parent levels read by get_artifact_info (Jackson needs 3: jackson-base, jackson-parent, oss-parent). */
        const val INFO_MAX_PARENTS = 3
    }
}
