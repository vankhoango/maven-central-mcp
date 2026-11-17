package net.khoango.mavencentralmcp.mcp.tool

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.khoango.mavencentralmcp.mcp.TestSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MavenCentralToolsTest {
    private val tools = TestSupport.tools().all().associateBy { it.name }

    private suspend fun call(name: String, args: JsonObject?): CallToolResult = tools.getValue(name).handler(args)

    private fun CallToolResult.text() = (content.single() as TextContent).text

    @Test
    fun `exposes exactly the four specified tools with schemas`() {
        assertEquals(setOf("search_artifacts", "get_artifact_info", "get_latest_version", "get_versions"), tools.keys)
        tools.values.forEach { t ->
            assertTrue(t.description.length > 80, "${t.name} needs a useful description")
            assertEquals(true, t.annotations.readOnlyHint)
            assertEquals(false, t.annotations.destructiveHint)
        }
        assertEquals(listOf("query"), tools.getValue("search_artifacts").inputSchema.required)
        assertEquals(listOf("groupId", "artifactId"), tools.getValue("get_versions").inputSchema.required)
        val limit = tools.getValue("get_versions").inputSchema.properties!!["limit"]!!.jsonObject
        assertEquals("100", limit["maximum"]!!.jsonPrimitive.content)
    }

    @Test
    fun `get_latest_version returns structured and text content`() = runTest {
        val r = call("get_latest_version", buildJsonObject { put("groupId", "com.example"); put("artifactId", "lib") })
        assertNotEquals(true, r.isError)
        val s = r.structuredContent!!
        assertEquals("2.0-RC1", s["latestPublishedVersion"]!!.jsonPrimitive.content)
        assertEquals("1.10", s["latestStableVersion"]!!.jsonPrimitive.content)
        assertTrue(r.text().startsWith("com.example:lib latest published: 2.0-RC1; latest stable: 1.10"))
    }

    @Test
    fun `get_versions paginates`() = runTest {
        val r = call("get_versions", buildJsonObject {
            put("groupId", "com.example"); put("artifactId", "lib"); put("limit", 2); put("includePrereleases", false)
        })
        val s = r.structuredContent!!
        assertEquals(listOf("1.10", "1.9"), s["versions"]!!.jsonArray.map { it.jsonObject["version"]!!.jsonPrimitive.content })
        assertEquals("true", s["hasMore"]!!.jsonPrimitive.content)
    }

    @Test
    fun `search and artifact info succeed`() = runTest {
        val search = call("search_artifacts", buildJsonObject { put("query", "lib") })
        assertEquals(1, search.structuredContent!!["results"]!!.jsonArray.size)
        val info = call("get_artifact_info", buildJsonObject { put("groupId", "com.example"); put("artifactId", "lib") })
        assertEquals("Example Lib", info.structuredContent!!["pom"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `search exposes ranking, descriptions and accepts includeDescriptions`() = runTest {
        val r = call("search_artifacts", buildJsonObject { put("query", "lib") })
        val hit = r.structuredContent!!["results"]!!.jsonArray.single().jsonObject
        assertEquals("1", hit["rank"]!!.jsonPrimitive.content)
        assertTrue(hit["rankingSignals"]!!.jsonArray.isNotEmpty())
        assertEquals("An example.", hit["description"]!!.jsonPrimitive.content)
        assertTrue(r.text().contains("1. com.example:lib - Example Lib"))
        val skipped = call("search_artifacts", buildJsonObject { put("query", "lib"); put("includeDescriptions", false) })
        assertEquals("not_loaded", skipped.structuredContent!!["results"]!!.jsonArray.single().jsonObject["descriptionSource"]!!.jsonPrimitive.content)
        assertEquals(true, call("search_artifacts", buildJsonObject { put("query", "lib"); put("includeDescriptions", "no") }).isError)
    }

    @Test
    fun `get_versions accepts a variant filter and validates it`() = runTest {
        val r = call("get_versions", buildJsonObject {
            put("groupId", "com.example"); put("artifactId", "lib"); put("variant", "none")
        })
        assertNotEquals(true, r.isError)
        assertEquals("none", r.structuredContent!!["variantFilter"]!!.jsonPrimitive.content)
        val bad = call("get_versions", buildJsonObject {
            put("groupId", "com.example"); put("artifactId", "lib"); put("variant", "x\"y")
        })
        assertEquals(true, bad.isError)
        val unknown = call("get_versions", buildJsonObject {
            put("groupId", "com.example"); put("artifactId", "lib"); put("variant", "android")
        })
        assertTrue(unknown.text().startsWith("Unknown variant 'android'"))
    }

    @Test
    fun `get_latest_version includes guidance and stable lines`() = runTest {
        val s = call("get_latest_version", buildJsonObject { put("groupId", "com.example"); put("artifactId", "lib") }).structuredContent!!
        assertTrue(s["guidance"]!!.jsonPrimitive.content.isNotEmpty())
        assertEquals("1.10", s["stableLines"]!!.jsonArray.first().jsonObject["latestStableVersion"]!!.jsonPrimitive.content)
    }

    @Test
    fun `not found is an error result`() = runTest {
        val r = call("get_versions", buildJsonObject { put("groupId", "com.example"); put("artifactId", "missing") })
        assertEquals(true, r.isError)
        assertEquals("Artifact 'com.example:missing' was not found on Maven Central.", r.text())
    }

    @Test
    fun `missing required argument is an actionable error`() = runTest {
        val r = call("get_latest_version", buildJsonObject { put("groupId", "com.example") })
        assertEquals(true, r.isError)
        assertTrue("artifactId" in r.text())
        assertNull(r.structuredContent)
        assertEquals(true, call("search_artifacts", null).isError)
    }

    @Test
    fun `wrong argument types are rejected`() = runTest {
        val badLimit = call("get_versions", buildJsonObject {
            put("groupId", "com.example"); put("artifactId", "lib"); put("limit", "10")
        })
        assertTrue(badLimit.text().contains("'limit' must be an integer"))
        val fractional = call("get_versions", buildJsonObject {
            put("groupId", "com.example"); put("artifactId", "lib"); put("limit", 1.5)
        })
        assertEquals(true, fractional.isError)
        val badBool = call("get_latest_version", buildJsonObject {
            put("groupId", "com.example"); put("artifactId", "lib"); put("includeSnapshots", "yes")
        })
        assertTrue(badBool.text().contains("boolean"))
        val array = call("get_artifact_info", buildJsonObject {
            put("groupId", buildJsonArray { add(JsonPrimitive("x")) }); put("artifactId", "lib")
        })
        assertEquals(true, array.isError)
    }

    @Test
    fun `out of range limits and unknown arguments are rejected`() = runTest {
        val tooMany = call("search_artifacts", buildJsonObject { put("query", "x"); put("limit", 51) })
        assertTrue(tooMany.text().contains("between 1 and 50"))
        val negative = call("search_artifacts", buildJsonObject { put("query", "x"); put("offset", -1) })
        assertEquals(true, negative.isError)
        val typo = call("get_artifact_info", buildJsonObject { put("group_id", "x"); put("artifactId", "lib") })
        assertTrue(typo.text().startsWith("Unknown argument(s): group_id"))
    }

    @Test
    fun `invalid coordinates never reach the network`() = runTest {
        val r = call("get_artifact_info", buildJsonObject { put("groupId", "../../etc"); put("artifactId", "passwd") })
        assertEquals(true, r.isError)
        assertTrue(r.text().contains("groupId"))
    }
}
