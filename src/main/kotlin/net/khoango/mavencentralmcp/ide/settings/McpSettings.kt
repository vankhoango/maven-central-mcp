package net.khoango.mavencentralmcp.ide.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * Application-level plugin settings. Not roamed: the port is specific to this machine.
 */
@Service(Service.Level.APP)
@State(
    name = "MavenCentralMcpSettings",
    storages = [Storage("mavenCentralMcp.xml", roamingType = RoamingType.DISABLED)],
)
class McpSettings : SimplePersistentStateComponent<McpSettings.SettingsState>(SettingsState()) {

    class SettingsState : BaseState() {
        var enabled by property(true)
        var port by property(DEFAULT_PORT)
    }

    var enabled: Boolean
        get() = state.enabled
        set(value) {
            state.enabled = value
        }

    var port: Int
        get() = state.port
        set(value) {
            state.port = value
        }

    companion object {
        const val DEFAULT_PORT = 47115
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535

        fun getInstance(): McpSettings = service()
    }
}
