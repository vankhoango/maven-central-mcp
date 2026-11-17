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
 * 1. name match: the artifactId equals the query (terms joined with `-`), starts with it, or contains all terms;
 * 2. the groupId contains a query term (projects usually publish under a group named after them);
 * 3. the number of indexed versions (a long release history), capped so it cannot override a name match;
 * 4. recency relative to the newest candidate;
 * 5. the upstream position as a tie-breaker.
 *
 * None of these signals establishes who publishes an artifact; results never claim to be "official".
 */
internal object SearchRanking {

    /** Number of upstream candidates that are re-ranked locally. */
    const val POOL_SIZE = 100

    const val DESCRIPTION =
        "Relevance ranking of the top $POOL_SIZE index matches: exact artifactId match, artifactId prefix/term " +
            "match, query term in groupId, number of published versions, recency, then index order. Heuristic only; " +
            "it does not verify the publisher. Confirm the groupId against the project's own documentation."

    const val UPSTREAM_DESCRIPTION = "Maven Central search index order (text score, then recency); not re-ranked."

    private const val EXACT_NAME = 100.0
    private const val PREFIX_NAME = 30.0
    private const val ALL_TERMS = 20.0
    private const val GROUP_TERM = 15.0
    private const val MAX_HISTORY = 40.0
    private const val RECENT = 5.0
    private const val MAX_POSITION = 5.0
    private const val RECENT_WINDOW_MILLIS = 2L * 365 * 24 * 60 * 60 * 1000

    data class Candidate(
        val groupId: String,
        val artifactId: String,
        val versionCount: Int?,
        val timestamp: Long?,
        val upstreamPosition: Int,
    )

    data class Ranked(val candidate: Candidate, val score: Double, val signals: List<String>)

    private val SOLR_SYNTAX = Regex("""[:*?"()\[\]{}^~!\\/]|\b(AND|OR|NOT)\b""")

    /**
     * Query terms for ranking, or null if [query] uses search-expression syntax (field queries, wildcards,
     * boolean operators). Expert queries keep the upstream order.
     */
    fun terms(query: String): List<String>? {
        if (SOLR_SYNTAX.containsMatchIn(query)) return null
        return query.lowercase().split(Regex("[\\s._\\-]+")).filter { it.isNotEmpty() }.ifEmpty { null }
    }

    fun rank(terms: List<String>, candidates: List<Candidate>): List<Ranked> {
        val joined = terms.joinToString("-")
        val newest = candidates.mapNotNull { it.timestamp }.maxOrNull()
        val poolSize = candidates.size.coerceAtLeast(1)
        return candidates.map { c ->
            val signals = mutableListOf<String>()
            var score = 0.0
            val artifact = c.artifactId.lowercase().replace('.', '-').replace('_', '-')
            when {
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
            val group = c.groupId.lowercase()
            terms.firstOrNull { it.length >= 3 && it in group }?.let {
                score += GROUP_TERM
                signals += "groupId contains '$it'"
            }
            c.versionCount?.takeIf { it > 0 }?.let { n ->
                score += min(MAX_HISTORY, 8.0 * ln(1.0 + n) / ln(2.0))
                signals += "$n versions indexed"
            }
            if (c.timestamp != null && newest != null && newest - c.timestamp <= RECENT_WINDOW_MILLIS) {
                score += RECENT
                signals += "recently updated (indexed ${Instant.ofEpochMilli(c.timestamp).atZone(ZoneOffset.UTC).toLocalDate()})"
            }
            score += MAX_POSITION * (poolSize - c.upstreamPosition).toDouble() / poolSize
            Ranked(c, score, signals)
        }.sortedWith(compareByDescending<Ranked> { it.score }.thenBy { it.candidate.upstreamPosition })
    }
}
