package net.khoango.mavencentralmcp.mcp.server

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import net.khoango.mavencentralmcp.mcp.TestSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

class McpServerControllerTest {
    private val created = AtomicInteger()
    private val controller = McpServerController(TestSupport.serverFactory()) { port, factory ->
        created.incrementAndGet()
        EmbeddedMcpHttpServer(port, factory)
    }

    private fun canBind(port: Int): Boolean =
        runCatching { ServerSocket(port, 50, InetAddress.getByName("127.0.0.1")).close() }.isSuccess

    @Test
    fun `starts once even when requested concurrently`() = runBlocking {
        val port = TestSupport.freePort()
        try {
            val states = (1..5).map { async { controller.ensureRunning(port) } }.awaitAll()
            states.forEach { assertInstanceOf(ServerState.Running::class.java, it) }
            assertEquals(1, created.get())
            assertEquals("http://127.0.0.1:$port/mcp", (controller.state.value as ServerState.Running).url)
        } finally {
            controller.stop()
        }
    }

    @Test
    fun `stop releases the port`() = runBlocking {
        val port = TestSupport.freePort()
        controller.ensureRunning(port)
        assertTrue(!canBind(port))
        controller.stop()
        assertEquals(ServerState.Stopped, controller.state.value)
        assertTrue(canBind(port))
    }

    @Test
    fun `port conflict is reported, not thrown, and recovers`() = runBlocking {
        val port = TestSupport.freePort()
        val blocker = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
        try {
            val state = controller.ensureRunning(port)
            assertInstanceOf(ServerState.Failed::class.java, state)
            state as ServerState.Failed
            assertTrue(state.portInUse, state.message)
            assertTrue(state.message.contains("already in use"))
        } finally {
            blocker.close()
        }
        try {
            assertInstanceOf(ServerState.Running::class.java, controller.restart(port))
        } finally {
            controller.stop()
        }
    }

    @Test
    fun `automatic triggers do not retry a failed port, explicit retry does`() = runBlocking {
        val port = TestSupport.freePort()
        val blocker = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
        val failed = try {
            controller.ensureRunning(port).also { assertInstanceOf(ServerState.Failed::class.java, it) }
        } finally {
            blocker.close()
        }
        assertEquals(1, created.get())
        // A second automatic trigger (e.g. a project opening) returns the same failure without a new attempt,
        // so the IDE shows one notification per conflict.
        assertTrue(controller.ensureRunning(port) === failed)
        assertEquals(1, created.get())
        try {
            assertInstanceOf(ServerState.Running::class.java, controller.ensureRunning(port, retryFailed = true))
            assertEquals(2, created.get())
        } finally {
            controller.stop()
        }
    }

    @Test
    fun `port conflict is always reported as port in use, never escapes as a cancellation`() = runBlocking {
        // Ktor CIO reports bind failures either as BindException or as a cancelled server job, depending on
        // timing. Repeat to exercise both paths.
        val port = TestSupport.freePort()
        ServerSocket(port, 50, InetAddress.getByName("127.0.0.1")).use {
            repeat(15) { attempt ->
                val state = controller.ensureRunning(port, retryFailed = true)
                assertInstanceOf(ServerState.Failed::class.java, state, "attempt $attempt")
                assertTrue((state as ServerState.Failed).portInUse, "attempt $attempt: ${state.message}")
            }
        }
    }

    @Test
    fun `changing port restarts on the new port`() = runBlocking {
        val first = TestSupport.freePort()
        val second = TestSupport.freePort()
        try {
            controller.ensureRunning(first)
            val state = controller.ensureRunning(second) as ServerState.Running
            assertEquals(second, state.port)
            assertTrue(canBind(first))
        } finally {
            controller.stop()
        }
    }

    @Test
    fun `invalid port fails gracefully`() = runBlocking {
        val state = controller.ensureRunning(70_000)
        assertInstanceOf(ServerState.Failed::class.java, state)
        assertEquals(0, created.get())
    }
}
