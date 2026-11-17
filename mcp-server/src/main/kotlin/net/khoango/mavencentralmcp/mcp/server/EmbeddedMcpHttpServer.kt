package net.khoango.mavencentralmcp.mcp.server

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Embedded MCP Streamable HTTP server bound to the IPv4 loopback interface only.
 *
 * Security measures:
 * - Binds to [LOOPBACK_HOST] only; never a wildcard or public interface.
 * - Rejects requests whose `Host` header is not a loopback name with the bound port (DNS rebinding).
 * - Rejects requests that carry a non-loopback `Origin` header (browser-initiated cross-site requests).
 *   Requests without `Origin` (native MCP clients) are allowed.
 * - The SDK's own DNS-rebinding protection is enabled with the same host allow-list.
 * - Request bodies with a declared size above [MAX_REQUEST_BYTES] are rejected.
 * - No CORS headers are emitted.
 */
class EmbeddedMcpHttpServer(
    private val port: Int,
    private val serverFactory: McpServerFactory,
    private val path: String = DEFAULT_PATH,
) {
    init {
        require(port in 1..65535) { "port must be between 1 and 65535" }
    }

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    /** The URL clients should connect to. */
    val endpointUrl: String get() = "http://$LOOPBACK_HOST:$port$path"

    /**
     * Starts listening. Throws [ServerStartException] if the server cannot start (for example when the
     * port is in use); rethrows [CancellationException] only when the caller itself was cancelled.
     * Not thread-safe; callers serialize start/stop.
     */
    suspend fun start() {
        check(server == null) { "Server already started" }
        val created = embeddedServer(CIO, port = port, host = LOOPBACK_HOST) { configure() }
        try {
            created.startSuspend(wait = false)
        } catch (e: Throwable) {
            withContext(NonCancellable) { runCatching { created.stopSuspend(0, 500) } }
            // Ktor CIO reports a bind failure either as BindException or as a cancellation of its own
            // server job. Only a cancellation of *our* coroutine is a real cancellation.
            if (e is CancellationException && !currentCoroutineContext().isActive) throw e
            throw ServerStartException(port, e)
        }
        server = created
    }

    /** Stops the server and releases the port. Safe to call when not started. */
    suspend fun stop(gracePeriodMillis: Long = 200, timeoutMillis: Long = 2_000) {
        val s = server ?: return
        server = null
        s.stopSuspend(gracePeriodMillis, timeoutMillis)
    }

    private fun Application.configure() {
        val allowedHosts = allowedHostHeaders(port)
        val allowedOrigins = allowedHosts.map { "http://$it" }.toSet()

        intercept(ApplicationCallPipeline.Plugins) {
            val host = call.request.headers[HttpHeaders.Host]?.lowercase()
            val origin = call.request.headers[HttpHeaders.Origin]?.lowercase()
            val contentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            val rejection = when {
                host == null || host !in allowedHosts -> HttpStatusCode.Forbidden to "Forbidden: invalid Host header."
                origin != null && origin !in allowedOrigins -> HttpStatusCode.Forbidden to "Forbidden: invalid Origin header."
                contentLength != null && contentLength > MAX_REQUEST_BYTES ->
                    HttpStatusCode.PayloadTooLarge to "Request body too large."
                else -> null
            }
            if (rejection != null) {
                call.respondText(rejection.second, status = rejection.first)
                finish()
            }
        }

        // SDK 0.9.0's JSON-response mode calls respond(JSONRPCMessage) and relies on the application
        // to provide ContentNegotiation with the SDK's own Json configuration.
        install(ContentNegotiation) { json(McpJson) }

        mcpStreamableHttp(
            path = path,
            enableDnsRebindingProtection = true,
            allowedHosts = allowedHosts,
            // Native MCP clients do not send Origin; the interceptor above validates it when present.
            allowedOrigins = null,
        ) {
            serverFactory.create()
        }
    }

    /** The embedded server failed to start on [port]; [cause] is the engine's original failure. */
    class ServerStartException(val port: Int, cause: Throwable) :
        Exception("Embedded MCP server failed to start on port $port", cause)

    companion object {
        const val LOOPBACK_HOST = "127.0.0.1"
        const val DEFAULT_PATH = "/mcp"
        const val MAX_REQUEST_BYTES = 1L * 1024 * 1024

        /** Host header values accepted for [port] (lower-case). */
        fun allowedHostHeaders(port: Int): List<String> = listOf("127.0.0.1:$port", "localhost:$port", "[::1]:$port")
    }
}
