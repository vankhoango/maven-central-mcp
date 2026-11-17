package net.khoango.mavencentralmcp.core.version

import org.apache.maven.artifact.versioning.ComparableVersion

/**
 * The documented version policy used by all tools.
 *
 * - **Ordering** uses Apache Maven's own `ComparableVersion` (the algorithm Maven uses to compare
 *   versions), never lexicographic string comparison.
 * - **Prerelease classification** is a token-based policy. A version is a prerelease if, after
 *   splitting on `.`, `-`, `_`, `+` and letter/digit boundaries (case-insensitive), any token is one of
 *   [PRERELEASE_WORDS], or it contains `a`, `b`, `m` or `pr` immediately followed by a digit
 *   (for example `1.0a1`, `2.0-b3`, `5.0.M2`, `2.9.0.pr1`).
 * - **Platform variants** (see [variantOf]) such as Guava's `-jre`/`-android` or mssql-jdbc's
 *   `.jre8`/`.jre11` are parallel builds of one release, not prereleases and not inferior to each other.
 * - Version strings are always preserved exactly as published.
 */
object VersionPolicy {

    /** Qualifier words that mark a prerelease. This is a policy, not a universal definition. */
    val PRERELEASE_WORDS: Set<String> = setOf(
        "alpha", "beta", "milestone", "rc", "cr", "preview", "pre", "ea", "eap",
        "snapshot", "dev", "nightly", "incubating", "experimental", "canary", "test",
    )

    /** Short markers that denote a prerelease only when a number follows (1.0a1, 2.0-b3, 5.0.M2, 2.9.0.pr1). */
    private val SHORT_PRERELEASE_MARKERS = setOf("a", "b", "m", "pr")

    /** Qualifiers that mean "plain release" and therefore do not form a platform variant. */
    private val NEUTRAL_QUALIFIERS = setOf("final", "ga", "release", "sp", "sec")

    /** Human-readable policy statement included in tool output and documentation. */
    val POLICY_DESCRIPTION: String =
        "Ordering: Apache Maven ComparableVersion (Maven's own algorithm), not string order. " +
            "Prerelease: a version is a prerelease if any qualifier token (case-insensitive, split on " +
            "'.', '-', '_', '+' and letter/digit boundaries) is one of ${PRERELEASE_WORDS.sorted().joinToString(", ")}, " +
            "or a/b/m/pr directly followed by a number (e.g. 1.0a1, 2.0-b3, 5.0.M2, 2.9.0.pr1). " +
            "Final, GA, RELEASE, SP and SEC qualifiers are plain releases. " +
            "Other qualifiers such as -jre, -android, .jre11 or -jdbc4 are platform variants: separate, equally valid " +
            "builds of the same release that are reported per variant. " +
            "'Latest stable' is the highest non-prerelease version by Maven ordering; when several variants share that " +
            "release, the result is marked uncertain and the variants are listed."

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
                (token.text in SHORT_PRERELEASE_MARKERS && token.followedByDigit)
            )
    }

    private val LEADING_NUMERIC = Regex("^\\d+(?:[._-]\\d+)*")
    private val SINGLE_LETTER_STAMP = Regex("^[a-z]\\d+$")
    private val SHORT_PRERELEASE_COMPONENT = Regex("^(?:a|b|m|pr)\\d+$")
    private val HEX_HASH = Regex("^(?=.*[a-f])(?=.*\\d)[0-9a-f]{7,40}$")

    /**
     * The platform-variant label of [version], or null for a plain (unqualified) release.
     *
     * The label is built from the qualifier components after the leading numeric part, ignoring numbers,
     * prerelease markers, plain-release markers (Final, GA, RELEASE, SP, SEC), single-letter build stamps
     * (`v20240101`, `r18462`) and commit hashes. Examples: `33.8.0-jre` -> `jre`, `22.0-rc1-android` ->
     * `android`, `12.8.1.jre11` -> `jre11`, `42.2.5.jre7` -> `jre7`, `5.3.39.RELEASE` -> null, `1.0` -> null.
     */
    fun variantOf(version: String): String? {
        val numeric = LEADING_NUMERIC.find(version)
        val suffix = if (numeric != null) version.substring(numeric.range.last + 1) else version
        val kept = suffix.lowercase().split('.', '-', '_', '+').filter { c ->
            // Numbers and single-letter patch suffixes (protobuf 2.4.0a) are not platform variants.
            if (c.length <= 1 || c.all(Char::isDigit)) return@filter false
            val letters = c.takeWhile(Char::isLetter)
            !(letters in PRERELEASE_WORDS || letters in NEUTRAL_QUALIFIERS || SHORT_PRERELEASE_COMPONENT.matches(c) ||
                SINGLE_LETTER_STAMP.matches(c) || HEX_HASH.matches(c))
        }
        return kept.joinToString("-").ifEmpty { null }
    }

    /** True if [version] is a Maven SNAPSHOT version. */
    fun isSnapshot(version: String): Boolean = version.endsWith("-SNAPSHOT", ignoreCase = true) ||
        version.equals("SNAPSHOT", ignoreCase = true)

    /** Comparator implementing Maven version ordering (ascending). */
    val MAVEN_ORDER: Comparator<String> = Comparator { a, b -> ComparableVersion(a).compareTo(ComparableVersion(b)) }

    /** Returns [versions] sorted newest first by Maven ordering. Ties keep their original relative order. */
    fun sortNewestFirst(versions: List<String>): List<String> = versions.sortedWith(MAVEN_ORDER.reversed())

    /** The first numeric component (major version), or null if the version does not start with a number. */
    fun majorOf(version: String): Long? = LEADING_NUMERIC.find(version)?.value?.takeWhile(Char::isDigit)?.toLongOrNull()

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
        // Prerelease qualifiers (alpha < beta < rc ...) have a well-defined Maven order, so only stable builds of
        // the same numeric release with a different platform variant (e.g. -jre vs -android) are ambiguous.
        val competing = if (base.isEmpty() || isPrerelease(newest)) {
            emptyList()
        } else {
            val newestVariant = variantOf(newest)
            versions.filter {
                it != newest && !isPrerelease(it) && numericBase(it) == base && variantOf(it) != newestVariant
            }
        }
        return Pick(newest, sortNewestFirst(competing))
    }
}
