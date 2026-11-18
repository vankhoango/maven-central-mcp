package net.khoango.mavencentralmcp.mcp.server

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import net.khoango.mavencentralmcp.mcp.TestSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Release-candidate findings (November 2026): failures that are already reported cleanly must not also reach
 * the IDE as unhandled errors.
 */
class ServerErrorReportingTest {

    @Test
    fun `port conflict does not leak an unhandled coroutine exception`() = runBlocking {
        // IntelliJ logs exceptions that reach the global coroutine handler as plugin errors ("Plugin to blame").
        // Outside the IDE they end at the default uncaught-exception handler, which this test captures.
        val leaked = Collections.synchronizedList(mutableListOf<Throwable>())
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> leaked += e }
        val port = TestSupport.freePort()
        val blocker = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
        try {
            val server = EmbeddedMcpHttpServer(port, TestSupport.serverFactory())
            assertThrows<EmbeddedMcpHttpServer.ServerStartException> { runBlocking { server.start() } }
            delay(1_000) // the engine's own job fails asynchronously
            assertEquals(emptyList<String>(), leaked.map { "${it.javaClass.name}: ${it.message}" })
        } finally {
            blocker.close()
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test
    fun `unknown tool records from the SDK are filtered, other SDK records pass`() {
        SdkLogFilter.install()
        // MCP SDK 0.9.0 Server.handleCallTool: logger.error { "Tool not found: ${requestParams.name}" }
        assertEquals(
            listOf("Prompt not found: x", "Closing session abc"),
            recordsPassing(SdkLogFilter.SDK_SERVER_LOGGER, "Tool not found: no_such_tool\nforged line"),
        )
        // FeatureRegistry.get: logger.info { "$featureType not found: \"$key\"" }
        assertEquals(
            listOf("Prompt not found: x", "Closing session abc"),
            recordsPassing(SdkLogFilter.SDK_TOOL_REGISTRY_LOGGER, "Tool not found: \"fake\nINFO - forged\""),
        )
        // FeatureRegistry.get: logger.info { "Getting $featureType: \"$key\"" }, logged for every tools/call
        assertEquals(
            listOf("Prompt not found: x", "Closing session abc"),
            recordsPassing(SdkLogFilter.SDK_TOOL_REGISTRY_LOGGER, "Getting Tool: \"fake\nINFO - forged\""),
        )
        // Records about the plugin's own registered tools still pass.
        assertEquals(
            listOf("Added Tool: \"search_artifacts\"", "Prompt not found: x", "Closing session abc"),
            recordsPassing(SdkLogFilter.SDK_TOOL_REGISTRY_LOGGER, "Added Tool: \"search_artifacts\""),
        )
    }

    private fun recordsPassing(loggerName: String, firstMessage: String): List<String> {
        val logger = Logger.getLogger(loggerName)
        val seen = Collections.synchronizedList(mutableListOf<String>())
        val capture = object : Handler() {
            override fun publish(record: LogRecord) { seen += record.message }
            override fun flush() = Unit
            override fun close() = Unit
        }
        val useParent = logger.useParentHandlers
        logger.useParentHandlers = false
        logger.addHandler(capture)
        try {
            logger.log(Level.INFO, firstMessage)
            logger.log(Level.SEVERE, "Prompt not found: x")
            logger.log(Level.INFO, "Closing session abc")
            return seen.toList()
        } finally {
            logger.removeHandler(capture)
            logger.useParentHandlers = useParent
        }
    }

    @Test
    fun `installing the filter twice keeps a single filter`() {
        val names = listOf(SdkLogFilter.SDK_SERVER_LOGGER, SdkLogFilter.SDK_TOOL_REGISTRY_LOGGER)
        SdkLogFilter.install()
        val first = names.map { Logger.getLogger(it).filter }
        SdkLogFilter.install()
        names.zip(first).forEach { (name, filter) -> assertTrue(filter === Logger.getLogger(name).filter, name) }
    }
}
