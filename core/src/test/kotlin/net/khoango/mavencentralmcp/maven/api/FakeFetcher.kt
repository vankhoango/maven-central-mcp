package net.khoango.mavencentralmcp.maven.api

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Test double that answers requests from a routing function and records every requested URI.
 *
 * Description-keyword searches (search queries using the `tags:` field) are answered by [keywordRoute] and
 * recorded in [keywordRequests] instead of [requests]. By default they return no matches, so tests that are not
 * about keyword search see exactly the name-search behavior and request counts they always did.
 */
class FakeFetcher(
    private val headRoute: ((URI) -> FetchResponse)? = null,
    private val keywordRoute: (URI) -> FetchResponse = { ok(EMPTY_SEARCH) },
    private val route: (URI) -> FetchResponse,
) : HttpFetcher {
    val requests = CopyOnWriteArrayList<URI>()
    val headRequests = CopyOnWriteArrayList<URI>()
    val keywordRequests = CopyOnWriteArrayList<URI>()

    override suspend fun get(uri: URI, accept: String): FetchResponse {
        if (uri.host == "search.maven.org" && uri.param("q")?.contains("tags:") == true) {
            keywordRequests += uri
            return keywordRoute(uri)
        }
        requests += uri
        return route(uri)
    }

    override suspend fun head(uri: URI): FetchResponse {
        headRequests += uri
        return headRoute?.invoke(uri) ?: route(uri)
    }

    companion object {
        const val EMPTY_SEARCH = """{"response":{"numFound":0,"start":0,"docs":[]}}"""

        fun ok(body: String, vararg headers: Pair<String, String>) =
            FetchResponse(200, body.toByteArray(), headers.associate { it.first to listOf(it.second) })

        fun status(code: Int, vararg headers: Pair<String, String>) =
            FetchResponse(code, ByteArray(0), headers.associate { it.first to listOf(it.second) })

        /** Decoded query parameter of a search request. */
        fun URI.param(name: String): String? = rawQuery?.split("&")
            ?.map { it.split("=", limit = 2) }
            ?.firstOrNull { it[0] == name }
            ?.getOrNull(1)
            ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }

        fun metadataXml(versions: List<String>, release: String? = versions.lastOrNull()) = """
            <metadata><versioning>
              ${release?.let { "<latest>$it</latest><release>$it</release>" } ?: ""}
              <versions>${versions.joinToString("") { "<version>$it</version>" }}</versions>
              <lastUpdated>20261102180748</lastUpdated>
            </versioning></metadata>
        """.trimIndent()
    }
}
