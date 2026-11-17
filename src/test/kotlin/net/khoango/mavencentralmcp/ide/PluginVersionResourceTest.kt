package net.khoango.mavencentralmcp.ide

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Properties

class PluginVersionResourceTest {
    @Test
    fun `plugin version resource is expanded at build time`() {
        val stream = javaClass.getResourceAsStream("/net/khoango/mavencentralmcp/plugin-version.properties")
        assertNotNull(stream)
        val version = stream!!.use { Properties().apply { load(it) }.getProperty("version") }
        assertTrue(version.matches(Regex("\\d+\\.\\d+\\.\\d+.*")), "unexpanded version: $version")
    }
}
