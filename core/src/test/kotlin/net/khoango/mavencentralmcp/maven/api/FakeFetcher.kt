package net.khoango.mavencentralmcp.maven.api

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/** Test double that answers requests from a routing function and records every requested URI. */
class FakeFetcher(private val route: (URI) -> FetchResponse) : HttpFetcher {
    val requests = CopyOnWriteArrayList<URI>()

    override suspend fun get(uri: URI, accept: String): FetchResponse {
        requests += uri
        return route(uri)
    }

    companion object {
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
