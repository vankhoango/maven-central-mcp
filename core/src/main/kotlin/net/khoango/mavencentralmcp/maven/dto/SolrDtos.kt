package net.khoango.mavencentralmcp.maven.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Transport DTOs for `https://search.maven.org/solrsearch/select?wt=json`.
 * Every field is optional so that schema drift degrades gracefully; the mapper decides what is required.
 */
@Serializable
internal data class SolrEnvelope<T>(val response: SolrResponse<T>? = null)

@Serializable
internal data class SolrResponse<T>(
    val numFound: Long? = null,
    val start: Long? = null,
    val docs: List<T>? = null,
)

/** Document from the default (artifact-level) core. */
@Serializable
internal data class SolrArtifactDoc(
    val id: String? = null,
    val g: String? = null,
    val a: String? = null,
    val latestVersion: String? = null,
    @SerialName("p") val packaging: String? = null,
    val timestamp: Long? = null,
    val versionCount: Int? = null,
)

/** Document from the `gav` (version-level) core. */
@Serializable
internal data class SolrGavDoc(
    val g: String? = null,
    val a: String? = null,
    val v: String? = null,
    @SerialName("p") val packaging: String? = null,
    val timestamp: Long? = null,
)
