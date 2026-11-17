package net.khoango.mavencentralmcp.mcp.tool

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import net.khoango.mavencentralmcp.core.error.MavenCentralException.InvalidArgument

/**
 * Typed, strict access to MCP tool arguments.
 *
 * Unknown argument names are rejected so that typos (for example `group_id`) produce an actionable
 * error instead of being silently ignored. JSON `null` is treated as "not provided".
 */
internal class ToolArguments(private val raw: JsonObject?, allowed: Set<String>) {

    init {
        val unknown = raw?.keys.orEmpty() - allowed
        if (unknown.isNotEmpty()) {
            throw InvalidArgument(
                "Unknown argument(s): ${unknown.sorted().joinToString()}. Allowed: ${allowed.sorted().joinToString()}.",
            )
        }
    }

    private fun primitive(name: String): JsonPrimitive? = when (val v = raw?.get(name)) {
        null, JsonNull -> null
        is JsonPrimitive -> v
        else -> throw InvalidArgument("'$name' must be a scalar value, not an object or array.")
    }

    fun string(name: String): String? {
        val p = primitive(name) ?: return null
        if (!p.isString) throw InvalidArgument("'$name' must be a string.")
        return p.content
    }

    fun int(name: String): Int? {
        val p = primitive(name) ?: return null
        if (p.isString) throw InvalidArgument("'$name' must be an integer, not a string.")
        val asLong = p.longOrNull
            ?: p.doubleOrNull?.takeIf { it % 1.0 == 0.0 && it in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble() }?.toLong()
            ?: throw InvalidArgument("'$name' must be an integer.")
        if (asLong !in Int.MIN_VALUE..Int.MAX_VALUE) throw InvalidArgument("'$name' is out of range.")
        return asLong.toInt()
    }

    fun boolean(name: String): Boolean? {
        val p = primitive(name) ?: return null
        if (p.isString) throw InvalidArgument("'$name' must be a boolean (true or false), not a string.")
        return p.booleanOrNull ?: throw InvalidArgument("'$name' must be a boolean (true or false).")
    }
}
