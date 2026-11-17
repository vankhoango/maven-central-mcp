package net.khoango.mavencentralmcp.maven.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import net.khoango.mavencentralmcp.core.error.MavenCentralException
import net.khoango.mavencentralmcp.core.model.ArtifactCoordinates
import net.khoango.mavencentralmcp.core.model.PomMetadata
import net.khoango.mavencentralmcp.maven.cache.TtlCache
import net.khoango.mavencentralmcp.maven.dto.SolrArtifactDoc
import net.khoango.mavencentralmcp.maven.dto.SolrEnvelope
import net.khoango.mavencentralmcp.maven.dto.SolrGavDoc
import net.khoango.mavencentralmcp.maven.dto.SolrResponse
import net.khoango.mavencentralmcp.maven.mapper.MavenMetadata
import net.khoango.mavencentralmcp.maven.mapper.XmlParsers
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The only component that talks to Maven Central.
 *
 * All URLs are built here from two fixed base URLs ([SEARCH_URL], [REPOSITORY_URL]) and validated
 * coordinates, so no caller can direct requests to another host. Successful responses are cached
 * in a bounded TTL cache.
 *
 * Data sources:
 * - Search index (Solr): artifact discovery and per-version publication timestamps. Known to lag
 *   behind the repository for some artifacts.
 * - Repository `maven-metadata.xml` and POMs: authoritative version lists and project metadata.
 */
class MavenCentralClient(
    private val fetcher: HttpFetcher = JdkHttpFetcher(),
    cacheTtl: Duration = 10.minutes,
    cacheMaxEntries: Int = 256,
) {
    private val cache = TtlCache<String, ByteArray>(cacheMaxEntries, cacheTtl)
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    /** Artifact-level search. [solrQuery] must already be a safe Solr expression (see [buildSearchQuery]). */
    internal suspend fun searchArtifacts(solrQuery: String, rows: Int, start: Int): SolrResponse<SolrArtifactDoc> =
        getSolr(
            params = linkedMapOf("q" to solrQuery, "rows" to rows.toString(), "start" to start.toString(), "wt" to "json"),
            serializer = SolrEnvelope.serializer(SolrArtifactDoc.serializer()),
        )

    /** Version-level search, newest-indexed first. */
    internal suspend fun searchVersions(
        coordinates: ArtifactCoordinates,
        rows: Int,
        version: String? = null,
    ): SolrResponse<SolrGavDoc> {
        var q = "g:${quote(coordinates.groupId)} AND a:${quote(coordinates.artifactId)}"
        if (version != null) q += " AND v:${quote(version)}"
        return getSolr(
            params = linkedMapOf("q" to q, "core" to "gav", "rows" to rows.toString(), "start" to "0", "wt" to "json"),
            serializer = SolrEnvelope.serializer(SolrGavDoc.serializer()),
        )
    }

    /** Fetches `maven-metadata.xml`; returns null if the artifact directory does not exist. */
    internal suspend fun fetchMetadata(coordinates: ArtifactCoordinates): MavenMetadata? {
        val uri = URI.create("$REPOSITORY_URL/${artifactPath(coordinates)}/maven-metadata.xml")
        val bytes = getBytes(uri, "application/xml, text/xml") ?: return null
        return try {
            XmlParsers.parseMetadata(bytes)
        } catch (e: Exception) {
            throw MavenCentralException.MalformedUpstreamResponse("Maven Central returned unreadable version metadata.", e)
        }
    }

    /**
     * Fetches descriptive metadata from the POM of [version]. Returns null if the POM does not exist or
     * the version string is not safe to embed in a URL path.
     */
    internal suspend fun fetchPom(coordinates: ArtifactCoordinates, version: String): PomMetadata? {
        if (!SAFE_VERSION.matches(version) || ".." in version) return null
        val uri = URI.create(
            "$REPOSITORY_URL/${artifactPath(coordinates)}/$version/${coordinates.artifactId}-$version.pom",
        )
        val bytes = getBytes(uri, "application/xml, text/xml") ?: return null
        return try {
            XmlParsers.parsePom(bytes, version)
        } catch (e: Exception) {
            throw MavenCentralException.MalformedUpstreamResponse("Maven Central returned an unreadable POM.", e)
        }
    }

    private suspend fun <T> getSolr(
        params: Map<String, String>,
        serializer: KSerializer<SolrEnvelope<T>>,
    ): SolrResponse<T> {
        val query = params.entries.joinToString("&") { (k, v) -> "$k=${encode(v)}" }
        val bytes = getBytes(URI.create("$SEARCH_URL?$query"), "application/json", badRequest = INVALID_SEARCH_EXPRESSION)
            ?: throw MavenCentralException.UpstreamUnavailable("Maven Central search endpoint was not found.")
        val envelope = try {
            json.decodeFromString(serializer, bytes.toString(StandardCharsets.UTF_8))
        } catch (e: SerializationException) {
            throw MavenCentralException.MalformedUpstreamResponse("Maven Central search returned malformed JSON.", e)
        } catch (e: IllegalArgumentException) {
            throw MavenCentralException.MalformedUpstreamResponse("Maven Central search returned malformed JSON.", e)
        }
        return envelope.response
            ?: throw MavenCentralException.MalformedUpstreamResponse("Maven Central search response had no 'response' object.")
    }

    /**
     * GET with caching and error mapping. Returns null for HTTP 404.
     *
     * Transient failures (connection errors, HTTP 502/503/504) are retried once after a short pause.
     * Timeouts and HTTP 429 are not retried, so a call never waits much longer than one request timeout
     * and rate limits are respected.
     */
    private suspend fun getBytes(uri: URI, accept: String, badRequest: String? = null): ByteArray? {
        val key = uri.toString()
        cache.get(key)?.let { return it }
        var attempt = 1
        while (true) {
            try {
                return fetchOnce(uri, accept, key, badRequest)
            } catch (e: TransientFailure) {
                if (attempt >= MAX_ATTEMPTS) throw e.mapped
                attempt++
                delay(RETRY_DELAY)
            }
        }
    }

    /** Signals a failure worth one retry; [mapped] is what the caller sees if retries are exhausted. */
    private class TransientFailure(val mapped: MavenCentralException) : Exception(null, null, false, false)

    private suspend fun fetchOnce(uri: URI, accept: String, key: String, badRequest: String?): ByteArray? {
        val response = try {
            fetcher.get(uri, accept)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ResponseTooLargeException) {
            throw MavenCentralException.MalformedUpstreamResponse("Maven Central response exceeded the size limit.", e)
        } catch (e: HttpTimeoutException) {
            throw MavenCentralException.UpstreamTimeout(e)
        } catch (e: IOException) {
            throw TransientFailure(
                MavenCentralException.UpstreamUnavailable("Could not reach Maven Central. Check the network connection.", e),
            )
        }
        return when (val status = response.status) {
            in 200..299 -> response.body.also { cache.put(key, it) }
            404 -> null
            400 -> if (badRequest != null) {
                throw MavenCentralException.InvalidArgument(badRequest)
            } else {
                throw MavenCentralException.UpstreamUnavailable("Maven Central rejected the request (HTTP 400).")
            }
            429 -> throw MavenCentralException.RateLimited(parseRetryAfter(response.header("Retry-After")))
            in 500..599 -> {
                val error = MavenCentralException.UpstreamUnavailable(
                    "Maven Central is temporarily unavailable (HTTP $status). Retry later.",
                )
                throw if (status in 502..504) TransientFailure(error) else error
            }
            else -> throw MavenCentralException.UpstreamUnavailable("Maven Central returned unexpected HTTP status $status.")
        }
    }

    companion object {
        /** Fixed search endpoint. Never derived from tool arguments. */
        const val SEARCH_URL = "https://search.maven.org/solrsearch/select"

        /** Fixed repository root. Never derived from tool arguments. */
        const val REPOSITORY_URL = "https://repo1.maven.org/maven2"

        private const val MAX_ATTEMPTS = 2
        private val RETRY_DELAY = 300.milliseconds

        internal const val INVALID_SEARCH_EXPRESSION =
            "Maven Central rejected the search expression. Use plain words (e.g. 'jackson databind'), an exact " +
                "'groupId:artifactId', or field syntax such as 'g:com.squareup*' or 'a:okhttp'."

        private val SAFE_VERSION =Regex("^[A-Za-z0-9_][A-Za-z0-9_.\\-+]*$")

        internal fun artifactPath(c: ArtifactCoordinates): String = "${c.groupId.replace('.', '/')}/${c.artifactId}"

        private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

        /** Quotes a value as a Solr phrase, escaping backslashes and quotes. */
        internal fun quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        /**
         * Builds the Solr expression for an artifact search: the user's query (free text or a Solr
         * expression such as `g:com.google*`) combined with exact field filters.
         */
        internal fun buildSearchQuery(query: String?, groupId: String?, artifactId: String?, packaging: String?): String {
            val filters = buildList {
                groupId?.let { add("g:${quote(it)}") }
                artifactId?.let { add("a:${quote(it)}") }
                packaging?.let { add("p:${quote(it)}") }
            }
            return when {
                query == null -> filters.joinToString(" AND ")
                filters.isEmpty() -> query
                else -> (listOf("($query)") + filters).joinToString(" AND ")
            }
        }

        /** Parses `Retry-After` given in seconds; HTTP-date values are ignored. Capped at one hour. */
        internal fun parseRetryAfter(value: String?): Duration? =
            value?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.coerceAtMost(3600)?.seconds
    }
}
