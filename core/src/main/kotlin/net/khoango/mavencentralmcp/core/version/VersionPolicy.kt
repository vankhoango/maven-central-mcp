package net.khoango.mavencentralmcp.core.version

import org.apache.maven.artifact.versioning.ComparableVersion

/**
 * The documented version policy used by all tools.
 *
 * - **Ordering** uses Apache Maven's own `ComparableVersion` (the algorithm Maven uses to compare
 *   versions), never lexicographic string comparison.
 * - **Prerelease classification** is a token-based policy. A version is a prerelease if, after
 *   splitting on `.`, `-`, `_`, `+` and letter/digit boundaries (case-insensitive), any token is one of
 *   [PRERELEASE_WORDS], or it contains a single-letter `a`, `b` or `m` token immediately followed by a
 *   digit (for example `1.0a1`, `2.0-b3`, `5.0.M2`).
 * - Version strings are always preserved exactly as published.
 */
object VersionPolicy {

    /** Qualifier words that mark a prerelease. This is a policy, not a universal definition. */
    val PRERELEASE_WORDS: Set<String> = setOf(
        "alpha", "beta", "milestone", "rc", "cr", "preview", "pre", "ea", "eap",
        "snapshot", "dev", "nightly", "incubating", "experimental",
    )

    private val SHORT_PRERELEASE_LETTERS = setOf("a", "b", "m")

    /** Human-readable policy statement included in tool output and documentation. */
    val POLICY_DESCRIPTION: String =
        "Ordering: Apache Maven ComparableVersion (Maven's own algorithm), not string order. " +
            "Prerelease: a version is a prerelease if any qualifier token (case-insensitive, split on " +
            "'.', '-', '_', '+' and letter/digit boundaries) is one of ${PRERELEASE_WORDS.sorted().joinToString(", ")}, " +
            "or a single letter a/b/m directly followed by a number (e.g. 1.0a1, 2.0-b3, 5.0.M2). " +
            "Qualifiers such as -jre, -android, .Final, .RELEASE or .GA are treated as stable. " +
            "'Latest stable' is the highest non-prerelease version by Maven ordering."

    private sealed interface Token {
        data class Word(val text: String, val followedByDigit: Boolean) : Token
        data class Number(val text: String) : Token
    }

    private fun tokenize(version: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val raw = Regex("[A-Za-z]+|[0-9]+").findAll(version)
        for (match in raw) {
            val text = match.value
            if (text[0].isDigit()) {
                tokens += Token.Number(text)
            } else {
                val next = version.getOrNull(match.range.last + 1)
                tokens += Token.Word(text.lowercase(), followedByDigit = next?.isDigit() == true)
            }
        }
        return tokens
    }

    /** True if [version] is a prerelease according to the documented policy. */
    fun isPrerelease(version: String): Boolean = tokenize(version).any { token ->
        token is Token.Word && (
            token.text in PRERELEASE_WORDS ||
                (token.text in SHORT_PRERELEASE_LETTERS && token.followedByDigit)
            )
    }

    /** True if [version] is a Maven SNAPSHOT version. */
    fun isSnapshot(version: String): Boolean = version.endsWith("-SNAPSHOT", ignoreCase = true) ||
        version.equals("SNAPSHOT", ignoreCase = true)

    /** Comparator implementing Maven version ordering (ascending). */
    val MAVEN_ORDER: Comparator<String> = Comparator { a, b -> ComparableVersion(a).compareTo(ComparableVersion(b)) }

    /** Returns [versions] sorted newest first by Maven ordering. Ties keep their original relative order. */
    fun sortNewestFirst(versions: List<String>): List<String> = versions.sortedWith(MAVEN_ORDER.reversed())

    /**
     * Leading numeric release components with trailing zeros removed, e.g. `33.8.0-jre` -> `[33, 8]`.
     * Used to detect equally-ranked variants such as `-jre` and `-android` builds of one release.
     */
    internal fun numericBase(version: String): List<Long> {
        val numbers = mutableListOf<Long>()
        for (token in tokenize(version)) {
            if (token !is Token.Number) break
            numbers += token.text.toLongOrNull() ?: break
        }
        while (numbers.isNotEmpty() && numbers.last() == 0L) numbers.removeAt(numbers.lastIndex)
        return numbers
    }

    /**
     * Result of picking the newest version from a candidate set.
     *
     * @property version the newest version by Maven ordering, or null for an empty set.
     * @property competing other versions with the same numeric release (e.g. `33.8.0-android` next to
     *   `33.8.0-jre`) whose relative order depends only on qualifier spelling. Empty when unambiguous.
     */
    data class Pick(val version: String?, val competing: List<String>)

    /** Picks the newest of [versions] by Maven ordering and reports equally-ranked variants. */
    fun pickNewest(versions: Collection<String>): Pick {
        if (versions.isEmpty()) return Pick(null, emptyList())
        val newest = versions.maxWith(MAVEN_ORDER)
        val base = numericBase(newest)
        // Prerelease qualifiers (alpha < beta < rc ...) have a well-defined Maven order, so only stable
        // variants of the same numeric release (e.g. -jre vs -android) are considered ambiguous.
        val competing = if (base.isEmpty() || isPrerelease(newest)) {
            emptyList()
        } else {
            versions.filter { it != newest && !isPrerelease(it) && numericBase(it) == base }
        }
        return Pick(newest, sortNewestFirst(competing))
    }
}
