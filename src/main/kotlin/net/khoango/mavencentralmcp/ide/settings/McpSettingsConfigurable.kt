package net.khoango.mavencentralmcp.ide.settings

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import net.khoango.mavencentralmcp.ide.service.McpServerService
import net.khoango.mavencentralmcp.ide.ui.ClientConfigSnippets
import java.awt.datatransfer.StringSelection
import javax.swing.JLabel

/** Settings | Tools | Maven Central MCP. */
class McpSettingsConfigurable : BoundConfigurable("Maven Central MCP") {

    private val settings = McpSettings.getInstance()
    private var uiScope: CoroutineScope? = null

    override fun createPanel(): DialogPanel {
        val statusLabel = JLabel()
        val service = McpServerService.getInstance()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT + ModalityState.any().asContextElement())
        uiScope = scope
        scope.launch {
            service.state.collect { statusLabel.text = ClientConfigSnippets.describe(it, settings.enabled) }
        }

        return panel {
            row {
                checkBox("Enable MCP server")
                    .bindSelected(settings::enabled)
                    .comment("Runs a local MCP server (Streamable HTTP) while the IDE is open.")
            }
            row("Port:") {
                intTextField(McpSettings.MIN_PORT..McpSettings.MAX_PORT)
                    .bindIntText(settings::port)
                    .comment("Listens on 127.0.0.1 only. Endpoint: http://127.0.0.1:&lt;port&gt;/mcp")
            }
            row("Status:") { cell(statusLabel) }
            row {
                button("Restart Server") { service.restart() }
                button("Copy Client Configuration") {
                    val json = ClientConfigSnippets.mcpServersJson(ClientConfigSnippets.endpointUrl(settings.port))
                    CopyPasteManager.getInstance().setContents(StringSelection(json))
                }.comment("Copies an mcpServers JSON snippet (Cursor, Claude Code). Apply port changes first.")
            }
        }
    }

    override fun apply() {
        super.apply()
        McpServerService.getInstance().applySettings()
    }

    override fun disposeUIResources() {
        uiScope?.cancel()
        uiScope = null
        super.disposeUIResources()
    }
}
