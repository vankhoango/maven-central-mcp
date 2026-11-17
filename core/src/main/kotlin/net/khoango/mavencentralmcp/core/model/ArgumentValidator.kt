package net.khoango.mavencentralmcp.core.model

import net.khoango.mavencentralmcp.core.error.MavenCentralException.InvalidArgument

/**
 * Transport-independent argument validation and limits.
 *
 * Coordinates are restricted to the character set Maven Central accepts for group and artifact IDs.
 * This also guarantees they can be safely embedded in repository URL paths and search expressions.
 */
object ArgumentValidator {
    const val MAX_QUERY_LENGTH = 200
    const val MAX_ID_LENGTH = 200

    const val SEARCH_DEFAULT_LIMIT = 10
    const val SEARCH_MAX_LIMIT = 50
    const val SEARCH_MAX_OFFSET = 5_000

    const val VERSIONS_DEFAULT_LIMIT = 20
    const val VERSIONS_MAX_LIMIT = 100
    const val VERSIONS_MAX_OFFSET = 100_000

    private val ID_PATTERN = Regex("^[A-Za-z0-9_][A-Za-z0-9_.\\-]*$")
    private val PACKAGING_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9_.\\-]{0,49}$")

    fun coordinates(groupId: String?, artifactId: String?): ArtifactCoordinates =
        ArtifactCoordinates(groupId = id("groupId", groupId), artifactId = id("artifactId", artifactId))

    /** Validates a required group or artifact ID. */
    fun id(name: String, value: String?): String {
        val v = value?.trim()
        if (v.isNullOrEmpty()) throw InvalidArgument("'$name' is required and must be a non-empty string.")
        if (v.length > MAX_ID_LENGTH) throw InvalidArgument("'$name' must be at most $MAX_ID_LENGTH characters.")
        if (!ID_PATTERN.matches(v) || v.endsWith(".") || ".." in v) {
            throw InvalidArgument(
                "'$name' must be an exact Maven identifier (letters, digits, '.', '-', '_'), " +
                    "for example 'com.google.guava'. Got an invalid value.",
            )
        }
        return v
    }

    /** Validates an optional group or artifact ID filter; blank means absent. */
    fun optionalId(name: String, value: String?): String? = if (value.isNullOrBlank()) null else id(name, value)

    fun optionalPackaging(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val v = value.trim()
        if (!PACKAGING_PATTERN.matches(v)) {
            throw InvalidArgument("'packaging' must be a simple packaging name such as 'jar', 'pom', 'aar' or 'bundle'.")
        }
        return v
    }

    private val VARIANT_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9\\-]{0,49}$")

    /** Validates an optional platform-variant label (as reported by get_versions); blank means absent. */
    fun optionalVariant(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val v = value.trim()
        if (!VARIANT_PATTERN.matches(v)) {
            throw InvalidArgument("'variant' must be a variant label such as 'jre', 'android' or 'none'.")
        }
        return v.lowercase()
    }

    fun query(value: String?): String {
        val v = value?.trim()
        if (v.isNullOrEmpty()) throw InvalidArgument("'query' is required and must be a non-empty string.")
        if (v.length > MAX_QUERY_LENGTH) throw InvalidArgument("'query' must be at most $MAX_QUERY_LENGTH characters.")
        if (v.any { it.isISOControl() }) throw InvalidArgument("'query' must not contain control characters.")
        return v
    }

    fun limit(value: Int?, default: Int, max: Int): Int {
        val v = value ?: return default
        if (v < 1 || v > max) throw InvalidArgument("'limit' must be between 1 and $max.")
        return v
    }

    fun offset(value: Int?, max: Int): Int {
        val v = value ?: return 0
        if (v < 0 || v > max) throw InvalidArgument("'offset' must be between 0 and $max.")
        return v
    }
}
