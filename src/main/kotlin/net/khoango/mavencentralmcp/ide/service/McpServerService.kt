package net.khoango.mavencentralmcp.ide.service

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.ShowSettingsUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.khoango.mavencentralmcp.core.service.MavenCentralService
import net.khoango.mavencentralmcp.ide.settings.McpSettings
import net.khoango.mavencentralmcp.ide.settings.McpSettingsConfigurable
import net.khoango.mavencentralmcp.mcp.server.McpServerController
import net.khoango.mavencentralmcp.mcp.server.McpServerFactory
import net.khoango.mavencentralmcp.mcp.server.ServerState
import net.khoango.mavencentralmcp.mcp.tool.MavenCentralTools
import kotlin.concurrent.thread

/**
 * Application-level owner of the embedded MCP server.
 *
 * All lifecycle work runs on [Dispatchers.IO] inside the service's coroutine scope; nothing here
 * blocks the EDT. The underlying [McpServerController] serializes start/stop, so repeated calls
 * (startup, settings apply, actions) never start a second server.
 */
@Service(Service.Level.APP)
class McpServerService(private val scope: CoroutineScope) : Disposable {

    private val log = logger<McpServerService>()

    private val controller: McpServerController by lazy {
        McpServerController(McpServerFactory(MavenCentralTools(MavenCentralService()), pluginVersion()))
    }

    val state: StateFlow<ServerState> get() = controller.state

    /**
     * Starts, restarts (port changed) or stops the server to match the current settings.
     *
     * @param userInitiated true for an explicit settings Apply, which retries a port that failed before;
     *   automatic triggers (IDE startup, project open) do not, so one conflict produces one notification.
     */
    fun applySettings(userInitiated: Boolean = false) {
        val settings = McpSettings.getInstance()
        val enabled = settings.enabled
        val port = settings.port
        scope.launch(Dispatchers.IO) {
            if (enabled) {
                val before = controller.state.value
                notifyIfNewFailure(before, controller.ensureRunning(port, retryFailed = userInitiated))
            } else {
                controller.stop()
            }
        }
    }

    /** Restarts the server with the current settings (or stops it if disabled). */
    fun restart() {
        val settings = McpSettings.getInstance()
        val enabled = settings.enabled
        val port = settings.port
        scope.launch(Dispatchers.IO) {
            if (enabled) {
                val before = controller.state.value
                notifyIfNewFailure(before, controller.restart(port))
            } else {
                controller.stop()
            }
        }
    }

    /**
     * Logs the outcome to idea.log and notifies only when this call produced a new failure, not when it
     * returned an earlier one unchanged.
     */
    private fun notifyIfNewFailure(before: ServerState, after: ServerState) {
        if (after === before) return
        when (after) {
            is ServerState.Running -> log.info("Maven Central MCP server listening at ${after.url}")
            is ServerState.Failed -> log.warn("Maven Central MCP server not started: ${after.message}")
            else -> Unit
        }
        if (after !is ServerState.Failed) return
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification("Maven Central MCP server not started", after.message, NotificationType.WARNING)
            .addAction(NotificationAction.createSimpleExpiring("Retry") { restart() })
            .addAction(NotificationAction.createSimpleExpiring("Open settings") {
                ShowSettingsUtil.getInstance().showSettingsDialog(null, McpSettingsConfigurable::class.java)
            })
            .notify(null)
    }

    override fun dispose() {
        // The service scope is cancelled on disposal; stop the server on a short-lived background
        // thread so the port is released without blocking the EDT during IDE shutdown/plugin unload.
        if (state.value is ServerState.Stopped) return
        thread(name = "Maven Central MCP shutdown", isDaemon = true) {
            try {
                runBlocking { controller.stop() }
                log.info("Maven Central MCP server stopped (service disposed)")
            } catch (e: Exception) {
                log.warn("Failed to stop the MCP server cleanly", e)
            }
        }
    }

    /** Plugin version written into `plugin-version.properties` at build time (avoids internal platform APIs). */
    private fun pluginVersion(): String = runCatching {
        McpServerService::class.java.getResourceAsStream("/net/khoango/mavencentralmcp/plugin-version.properties")
            ?.use { stream -> java.util.Properties().apply { load(stream) }.getProperty("version") }
    }.getOrNull() ?: "unknown"

    companion object {
        const val NOTIFICATION_GROUP = "Maven Central MCP"

        fun getInstance(): McpServerService = service()
    }
}
