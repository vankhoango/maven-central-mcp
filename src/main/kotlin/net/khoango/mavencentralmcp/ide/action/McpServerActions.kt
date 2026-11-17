package net.khoango.mavencentralmcp.ide.action

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import net.khoango.mavencentralmcp.ide.service.McpServerService
import net.khoango.mavencentralmcp.ide.settings.McpSettings
import net.khoango.mavencentralmcp.ide.ui.ClientConfigSnippets
import net.khoango.mavencentralmcp.mcp.server.ServerState
import java.awt.datatransfer.StringSelection

/** Tools | Maven Central MCP | Show Server Status. */
class ShowServerStatusAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val state = McpServerService.getInstance().state.value
        val type = if (state is ServerState.Failed) NotificationType.WARNING else NotificationType.INFORMATION
        NotificationGroupManager.getInstance()
            .getNotificationGroup(McpServerService.NOTIFICATION_GROUP)
            .createNotification("Maven Central MCP", ClientConfigSnippets.describe(state, McpSettings.getInstance().enabled), type)
            .notify(e.project)
    }
}

/** Tools | Maven Central MCP | Copy Client Configuration. */
class CopyClientConfigAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val state = McpServerService.getInstance().state.value
        val url = (state as? ServerState.Running)?.url ?: ClientConfigSnippets.endpointUrl(McpSettings.getInstance().port)
        CopyPasteManager.getInstance().setContents(StringSelection(ClientConfigSnippets.mcpServersJson(url)))
        NotificationGroupManager.getInstance()
            .getNotificationGroup(McpServerService.NOTIFICATION_GROUP)
            .createNotification("MCP client configuration copied", url, NotificationType.INFORMATION)
            .notify(e.project)
    }
}

/** Tools | Maven Central MCP | Restart Server. */
class RestartServerAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        McpServerService.getInstance().restart()
    }
}
