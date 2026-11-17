package net.khoango.mavencentralmcp.ide.service

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** Starts the server once the IDE frame is created (covers the welcome screen). */
class McpServerAppListener : AppLifecycleListener {
    override fun appFrameCreated(commandLineArgs: MutableList<String>) {
        McpServerService.getInstance().applySettings()
    }
}

/**
 * Also requests a start when a project opens. This covers dynamic plugin installation, where the
 * application listener above does not fire. The call is idempotent.
 */
class McpServerProjectActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        McpServerService.getInstance().applySettings()
    }
}
