package net.khoango.mavencentralmcp.mcp.server

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.logging.Level
import java.util.logging.Logger

/** Observable lifecycle state of the embedded server. */
sealed interface ServerState {
    data object Stopped : ServerState
    data class Starting(val port: Int) : ServerState
    data class Running(val port: Int, val url: String) : ServerState
    data class Failed(val port: Int, val message: String, val portInUse: Boolean) : ServerState
}

/**
 * Owns at most one [EmbeddedMcpHttpServer] and serializes start/stop/restart.
 *
 * IDE-independent so that lifecycle behavior (duplicate starts, port conflicts, shutdown) can be
 * tested without an IDE. All functions suspend and must not be called on a UI thread.
 */
class McpServerController(
    private val serverFactory: McpServerFactory,
    private val httpServerFactory: (Int, McpServerFactory) -> EmbeddedMcpHttpServer = { port, f ->
        EmbeddedMcpHttpServer(port, f)
    },
) {
    private val log = Logger.getLogger(McpServerController::class.java.name)
    private val mutex = Mutex()
    private var current: EmbeddedMcpHttpServer? = null
    private var currentPort: Int? = null

    private val _state = MutableStateFlow<ServerState>(ServerState.Stopped)
    val state: StateFlow<ServerState> = _state.asStateFlow()

    /**
     * Ensures the server is running on [port]. A no-op if it is already running on that port;
     * restarts it if it runs on a different port. Failures are reported via [state], not thrown.
     *
     * If the last attempt on [port] failed, it is not retried unless [retryFailed] is true. This keeps
     * repeated automatic triggers (IDE frame created, each project opened) from re-attempting a busy port
     * and reporting the same failure again; explicit user actions pass `retryFailed = true`.
     */
    suspend fun ensureRunning(port: Int, retryFailed: Boolean = false): ServerState = mutex.withLock {
        if (current != null && currentPort == port) return@withLock _state.value
        val last = _state.value
        if (!retryFailed && last is ServerState.Failed && last.port == port) return@withLock last
        stopLocked()
        startLocked(port)
    }

    /** Stops and starts the server on [port]. */
    suspend fun restart(port: Int): ServerState = mutex.withLock {
        stopLocked()
        startLocked(port)
    }

    /** Stops the server if it is running. */
    suspend fun stop() {
        mutex.withLock { stopLocked() }
    }

    private suspend fun startLocked(port: Int): ServerState {
        if (port !in 1..65535) {
            return ServerState.Failed(port, "Invalid port $port. Use a port between 1024 and 65535.", portInUse = false)
                .also { _state.value = it }
        }
        _state.value = ServerState.Starting(port)
        val server = httpServerFactory(port, serverFactory)
        return try {
            server.start()
            current = server
            currentPort = port
            ServerState.Running(port, server.endpointUrl).also { _state.value = it }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { runCatching { server.stop(0, 500) } }
            _state.value = ServerState.Stopped
            throw e
        } catch (e: Exception) {
            // The engine does not always surface BindException, so confirm with a direct loopback bind check.
            val inUse = e.causes().any { it is BindException } || !isLoopbackPortFree(port)
            val message = if (inUse) {
                "Port $port is already in use (possibly by another IDE instance). Choose another port in " +
                    "Settings | Tools | Maven Central MCP, or close the other program and click Retry."
            } else {
                val root = e.causes().last()
                "Could not start the MCP server on port $port: ${root.javaClass.simpleName}" +
                    (root.message?.take(200)?.let { " ($it)" } ?: "") + ". See the IDE log for details."
            }
            log.log(if (inUse) Level.INFO else Level.WARNING, message, if (inUse) null else e)
            ServerState.Failed(port, message, inUse).also { _state.value = it }
        }
    }

    private suspend fun stopLocked() {
        val server = current ?: run {
            if (_state.value !is ServerState.Failed) _state.value = ServerState.Stopped
            return
        }
        current = null
        currentPort = null
        try {
            withContext(NonCancellable) { server.stop() }
        } catch (e: Exception) {
            log.log(Level.WARNING, "Error while stopping the MCP server", e)
        }
        _state.value = ServerState.Stopped
    }

    private fun isLoopbackPortFree(port: Int): Boolean = try {
        ServerSocket().use { it.bind(InetSocketAddress(EmbeddedMcpHttpServer.LOOPBACK_HOST, port)) }
        true
    } catch (_: IOException) {
        false
    }

    private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause?.takeIf { c -> c !== it } }
}
