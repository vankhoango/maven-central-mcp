package net.khoango.mavencentralmcp.mcp.server

import java.util.logging.Filter
import java.util.logging.Logger

/**
 * Keeps a client mistake out of the IDE log.
 *
 * MCP SDK 0.9.0 writes the tool name from every `tools/call` request into the log before it is validated:
 * `FeatureRegistry[Tool]` logs `Getting Tool: "<name>"` (INFO), and for an unknown tool both
 * `FeatureRegistry[Tool]` (INFO) and `Server` (ERROR) log `Tool not found: <name>` before an `isError` result is
 * returned to the client. Inside IntelliJ the SDK's logging reaches java.util.logging (through SLF4J's JUL
 * provider), where the SEVERE record is reported as an IDE error, and the name is client-controlled text that can
 * contain line breaks (forged log lines). This filter drops only these records; every other SDK record passes.
 */
internal object SdkLogFilter {
    /** Logger used by the SDK's `Server` class (kotlin-logging names loggers after the class). */
    const val SDK_SERVER_LOGGER = "io.modelcontextprotocol.kotlin.sdk.server.Server"

    /** Logger the SDK's tool registry creates with `KotlinLogging.logger(name = "FeatureRegistry[Tool]")`. */
    const val SDK_TOOL_REGISTRY_LOGGER = "FeatureRegistry[Tool]"

    /** Message prefixes, per logger, of the records that contain a client-supplied tool name. */
    private val CLIENT_NAME_PREFIXES = mapOf(
        SDK_SERVER_LOGGER to listOf("Tool not found:"),
        SDK_TOOL_REGISTRY_LOGGER to listOf("Tool not found:", "Getting Tool:"),
    )

    // java.util.logging holds loggers weakly; keep strong references so the filters are not lost.
    private val loggers: List<Logger> = CLIENT_NAME_PREFIXES.keys.map(Logger::getLogger)

    private val installed = mutableMapOf<String, Filter>()

    /** Installs the filter on the SDK loggers, keeping any filter that was already there. Idempotent. */
    @Synchronized
    fun install() {
        for (logger in loggers) {
            val current = logger.filter
            if (current != null && current === installed[logger.name]) continue
            val prefixes = CLIENT_NAME_PREFIXES.getValue(logger.name)
            val filter = Filter { record ->
                val message = record.message.orEmpty()
                prefixes.none(message::startsWith) && (current?.isLoggable(record) ?: true)
            }
            logger.filter = filter
            installed[logger.name] = filter
        }
    }
}
