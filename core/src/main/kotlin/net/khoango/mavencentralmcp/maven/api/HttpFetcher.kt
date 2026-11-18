package net.khoango.mavencentralmcp.maven.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Minimal HTTP response used by [MavenCentralClient]. */
class FetchResponse(val status: Int, val body: ByteArray, private val headers: Map<String, List<String>>) {
    /** Case-insensitive single header lookup. */
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
}

/**
 * GET-only HTTP abstraction. [MavenCentralClient] only ever passes URIs it built itself from fixed
 * Maven Central base URLs, so implementations never see caller-controlled hosts.
 *
 * Implementations must throw [ResponseTooLargeException] when the body exceeds their size limit and
 * may throw [java.io.IOException] (including [java.net.http.HttpTimeoutException]) on network failures.
 */
fun interface HttpFetcher {
    suspend fun get(uri: URI, accept: String): FetchResponse

    /** HEAD request; only status and headers are meaningful. Defaults to [get] for simple implementations. */
    suspend fun head(uri: URI): FetchResponse = get(uri, "*/*")
}

class ResponseTooLargeException(limit: Long) : java.io.IOException("Response exceeded $limit bytes")

/**
 * [HttpFetcher] backed by the JDK HTTP client with connect/request timeouts, no redirects
 * (so the upstream host cannot be changed), and a bounded response body size.
 */
class JdkHttpFetcher(
    private val connectTimeout: Duration = Duration.ofSeconds(5),
    private val requestTimeout: Duration = Duration.ofSeconds(15),
    private val maxBodyBytes: Long = DEFAULT_MAX_BODY_BYTES,
    private val userAgent: String = "maven-central-mcp",
) : HttpFetcher {

    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
    }

    override suspend fun get(uri: URI, accept: String): FetchResponse {
        val request = HttpRequest.newBuilder(uri)
            .timeout(requestTimeout)
            .header("Accept", accept)
            .header("User-Agent", userAgent)
            .GET()
            .build()
        val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()).await()
        val body = withContext(Dispatchers.IO) {
            response.body().use { readBounded(it, maxBodyBytes) }
        }
        return FetchResponse(response.statusCode(), body, response.headers().map())
    }

    override suspend fun head(uri: URI): FetchResponse {
        val request = HttpRequest.newBuilder(uri)
            .timeout(requestTimeout)
            .header("User-Agent", userAgent)
            .method("HEAD", HttpRequest.BodyPublishers.noBody())
            .build()
        val response = client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).await()
        return FetchResponse(response.statusCode(), ByteArray(0), response.headers().map())
    }

    companion object {
        const val DEFAULT_MAX_BODY_BYTES: Long = 4L * 1024 * 1024

        internal fun readBounded(input: InputStream, limit: Long): ByteArray {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > limit) throw ResponseTooLargeException(limit)
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }
    }
}
