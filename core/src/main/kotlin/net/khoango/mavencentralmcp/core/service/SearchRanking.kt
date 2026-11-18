package net.khoango.mavencentralmcp.core.service

import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.ln
import kotlin.math.min

/**
 * Local relevance ranking for artifact search results.
 *
 * The Maven Central search index orders by its own text score and then by recency, so forks and
 * repackagings frequently outrank the artifact a developer means (for example a vendor repackage of
 * `okhttp` above `com.squareup.okhttp3:okhttp`). This ranking reorders a bounded candidate pool using
 * only observable facts:
 *
 * 1. group match, for queries shaped like a groupId (`com.fasterxml.jackson.core`): the groupId equals the
 *    query, lies inside the query's namespace, or is a parent namespace of it;
 * 2. name match: the artifactId equals the query (terms joined with `-`), starts with it, or contains all terms.
 *    For groupId-shaped queries this signal is weakened so that an unrelated artifact whose artifactId happens
 *    to equal the query (`com.liferay:com.fasterxml.jackson.core`) cannot outrank the group's own artifacts;
 * 3. a non-generic query term in the groupId (projects usually publish under a group named after them);
 * 4. the number of indexed versions (a long release history), capped so it cannot override a name match;
 * 5. recency relative to the newest candidate;
 * 6. the upstream position as a tie-breaker.
 *
 * None of these signals establishes who publishes an artifact; results never claim to be "official".
 * A groupId-shaped query is never turned into a filter: other artifacts still match and are still returned.
 */
internal object SearchRanking {

    /** Number of upstream candidates that are re-ranked locally. */
    const val POOL_SIZE = 100

    const val DESCRIPTION =
        "Relevance ranking of the index matches: exact groupId match (for groupId-shaped queries), exact artifactId " +
            "match, artifactId prefix/term match, query term in groupId, number of published versions, recency, then " +
            "index order. Heuristic only; it does not verify the publisher. Confirm the groupId against the project's " +
            "own documentation."

    const val UPSTREAM_DESCRIPTION = "Maven Central search index order (text score, then recency); not re-ranked."

    const val MIXED_DESCRIPTION =
        "Positions 1-$POOL_SIZE are re-ranked by relevance (see rankingSignals); later positions keep the search " +
            "index order (text score, then recency)."

    private const val EXACT_GROUP = 150.0
    private const val SUBGROUP = 60.0
    private const val PARENT_GROUP = 20.0
    private const val EXACT_NAME = 100.0
    private const val EXACT_NAME_FOR_GROUP_QUERY = 40.0
    private const val PREFIX_NAME = 30.0
    private const val ALL_TERMS = 20.0
    private const val GROUP_TERM = 15.0
    private const val MAX_HISTORY = 40.0
    private const val RECENT = 5.0
    private const val MAX_POSITION = 5.0
    private const val RECENT_WINDOW_MILLIS = 2L * 365 * 24 * 60 * 60 * 1000

    /** Reverse-domain prefixes that appear in most groupIds and therefore say nothing about relevance. */
    private val GENERIC_GROUP_TOKENS = setOf("com", "org", "net", "io", "dev", "edu", "gov", "co", "app", "me", "info")

    data class Candidate(
        val groupId: String,
        val artifactId: String,
        val versionCount: Int?,
        val timestamp: Long?,
        val upstreamPosition: Int,
    )

    data class Ranked(val candidate: Candidate, val score: Double, val signals: List<String>)

    private val SOLR_SYNTAX = Regex("""[:*?"()\[\]{}^~!\\/]|\b(AND|OR|NOT)\b""")
    private val GROUP_SHAPED = Regex("^[A-Za-z0-9_\\-]+(\\.[A-Za-z0-9_\\-]+)+$")

    /**
     * Query terms for ranking, or null if [query] uses search-expression syntax (field queries, wildcards,
     * boolean operators). Expert queries keep the upstream order.
     */
    fun terms(query: String): List<String>? {
        if (SOLR_SYNTAX.containsMatchIn(query)) return null
        return query.lowercase().split(Regex("[\\s._\\-]+")).filter { it.isNotEmpty() }.ifEmpty { null }
    }

    /**
     * The query as a groupId candidate (lower-cased) if it is a single dotted token such as
     * `com.fasterxml.jackson.core`, otherwise null. Used only for ranking, never as a filter.
     */
    fun groupQuery(query: String): String? = query.trim().takeIf { GROUP_SHAPED.matches(it) }?.lowercase()

    fun rank(terms: List<String>, candidates: List<Candidate>, groupQuery: String? = null): List<Ranked> {
        // A dotted query is only treated as a groupId if the pool contains that group or one of its subgroups;
        // otherwise (e.g. 'jackson.databind') it is ordinary free text and ranks exactly as before.
        @Suppress("NAME_SHADOWING")
        val groupQuery = groupQuery?.takeIf { gq ->
            candidates.any { c -> c.groupId.lowercase().let { it == gq || it.startsWith("$gq.") } }
        }
        val joined = terms.joinToString("-")
        val newest = candidates.mapNotNull { it.timestamp }.maxOrNull()
        val poolSize = candidates.size.coerceAtLeast(1)
        return candidates.map { c ->
            val signals = mutableListOf<String>()
            var score = 0.0
            val group = c.groupId.lowercase()
            if (groupQuery != null) {
                when {
                    group == groupQuery -> {
                        score += EXACT_GROUP
                        signals += "groupId exactly matches the query"
                    }
                    group.startsWith("$groupQuery.") -> {
                        score += SUBGROUP
                        signals += "groupId is inside the query's namespace ($groupQuery.*)"
                    }
                    groupQuery.startsWith("$group.") -> {
                        score += PARENT_GROUP
                        signals += "groupId is a parent namespace of the query"
                    }
                }
            }
            val artifact = c.artifactId.lowercase().replace('.', '-').replace('_', '-')
            when {
                artifact == joined && groupQuery != null -> {
                    score += EXACT_NAME_FOR_GROUP_QUERY
                    signals += "artifactId equals the query, which is shaped like a groupId (weak signal)"
                }
                artifact == joined -> {
                    score += EXACT_NAME
                    signals += "artifactId exactly matches the query"
                }
                artifact.startsWith(joined) -> {
                    score += PREFIX_NAME
                    signals += "artifactId starts with the query"
                }
                terms.all { it in artifact } -> {
                    score += ALL_TERMS
                    signals += "artifactId contains all query terms"
                }
            }
            // For groupId-shaped queries the group signals above already cover the groupId.
            if (groupQuery == null) {
                terms.firstOrNull { it.length >= 3 && it !in GENERIC_GROUP_TOKENS && it in group }?.let {
                    score += GROUP_TERM
                    signals += "groupId contains '$it'"
                }
            }
            score += historyAndRecency(c, newest, signals)
            score += MAX_POSITION * (poolSize - c.upstreamPosition).toDouble() / poolSize
            Ranked(c, score, signals)
        }.sortedWith(compareByDescending<Ranked> { it.score }.thenBy { it.candidate.upstreamPosition })
    }

    /**
     * Factual signals for results that keep the search-index order (search expressions, explicit filters
     * without free text, or positions beyond the re-ranked pool). [absolutePosition] is 1-based.
     */
    fun upstreamSignals(c: Candidate, absolutePosition: Int, newest: Long?, groupFilter: String?): List<String> {
        val signals = mutableListOf("position $absolutePosition in search-index order (not re-ranked)")
        if (groupFilter != null && c.groupId.equals(groupFilter, ignoreCase = true)) {
            signals += "groupId matches the groupId filter"
        }
        historyAndRecency(c, newest, signals)
        return signals
    }

    private fun historyAndRecency(c: Candidate, newest: Long?, signals: MutableList<String>): Double {
        var score = 0.0
        c.versionCount?.takeIf { it > 0 }?.let { n ->
            score += min(MAX_HISTORY, 8.0 * ln(1.0 + n) / ln(2.0))
            signals += "$n versions indexed"
        }
        if (c.timestamp != null && newest != null && newest - c.timestamp <= RECENT_WINDOW_MILLIS) {
            score += RECENT
            signals += "recently updated (indexed ${Instant.ofEpochMilli(c.timestamp).atZone(ZoneOffset.UTC).toLocalDate()})"
        }
        return score
    }
}
