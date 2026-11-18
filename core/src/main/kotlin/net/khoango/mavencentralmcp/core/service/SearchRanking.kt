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
 * Plain-word queries also match **description keywords**: the index's `tags` field holds keywords derived from
 * each artifact's POM description. [keywordQuery] builds a second query for artifacts whose description keywords
 * contain all query words but whose names do not, so feature searches such as `speech recognition` find
 * libraries like Vosk or DeepSpeech. Such results carry their own signal and a modest score below name matches.
 *
 * None of these signals establishes who publishes an artifact; results never claim to be "official".
 * A groupId-shaped query is never turned into a filter: other artifacts still match and are still returned.
 */
internal object SearchRanking {

    /** Number of upstream candidates that are re-ranked locally. */
    const val POOL_SIZE = 100

    const val DESCRIPTION =
        "Relevance ranking of the index matches: exact groupId match (for groupId-shaped queries), exact artifactId " +
            "match, artifactId prefix/phrase/term match, query term in groupId, description keywords, number of " +
            "published versions, recency, then index order. Heuristic only; it does not verify the publisher. Confirm the groupId against the project's " +
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
    private const val PHRASE_IN_NAME = 25.0
    private const val DESCRIPTION_KEYWORDS = 18.0
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

    const val DESCRIPTION_KEYWORD_SIGNAL =
        "description keywords contain all query terms (search-index tags from the POM description; " +
            "not matched by the index's name search)"

    /**
     * True if the query is one word joined by punctuation ('speech-to-text'): the index matches it as a phrase, so
     * word order matters and 'text-to-speech' is not a name match.
     */
    fun isPhraseQuery(query: String): Boolean = query.trim().split(Regex("\\s+")).let { words ->
        words.size == 1 && tokens(words.single()).size > 1
    }

    /** Grammatical words dropped from description-keyword matching (they are not indexed as keywords). */
    private val STOPWORDS = setOf(
        "a", "an", "and", "or", "the", "to", "of", "for", "in", "on", "with", "by", "from", "into", "as", "at", "is",
    )

    /** Separators between words inside a query or an artifactId: anything that is not a letter or digit. */
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

    /** Sentence punctuation at the end of a natural-language query ('json parser!', 'http client?'). */
    private val TRAILING_PUNCTUATION = Regex("[\\s!?.,;:]+$")

    /**
     * The query with trailing sentence punctuation removed, so that 'json parser!' or 'http client?' are searched as
     * words rather than as search expressions ('!' and '?' are operators in the search syntax).
     */
    fun normalize(query: String): String = query.trim().replace(TRAILING_PUNCTUATION, "").ifEmpty { query.trim() }

    private fun tokens(text: String) = text.lowercase().split(NON_WORD).filter { it.isNotEmpty() }

    /** Characters the index treats as word separators without changing meaning. */
    private val PLAIN_SEPARATORS = Regex("[\\p{L}\\p{N}\\s._\\-,;:!?'\"]")

    /**
     * Words whose meaning the search index cannot represent because it drops symbols, e.g. `c++` and `c#` are both
     * matched as `c`. Returned so the response can say so instead of silently searching for something else.
     */
    fun wordsWithDroppedSymbols(query: String): List<String> =
        query.trim().split(Regex("\\s+")).filter { word -> word.any { !PLAIN_SEPARATORS.matches(it.toString()) } && tokens(word).isNotEmpty() }

    /** Query terms that carry meaning for ranking (grammatical words removed). */
    private fun contentTerms(terms: List<String>) = terms.filter { it !in STOPWORDS }

    /**
     * Search-index expression for artifacts whose description keywords (`tags`) contain every non-stopword query
     * word while their names do not match the query, or null if the query has no meaningful word (only grammatical
     * words or punctuation) or uses search-expression syntax. Pass a [normalize]d query. The exclusion mirrors the name query exactly (a hyphenated or
     * dotted word is a phrase, other words are required terms), so the two result sets are disjoint and their
     * counts add up to the true total.
     */
    fun keywordQuery(query: String): String? {
        if (SOLR_SYNTAX.containsMatchIn(query)) return null
        val nameClauses = mutableListOf<String>()
        val keywords = mutableListOf<String>()
        for (word in query.trim().split(Regex("\\s+"))) {
            // A word that splits into several tokens ('speech-to-text', 'json,parser') is a phrase in the name query;
            // a punctuation-only word ('&') contributes nothing, exactly as in the index's analyzer.
            val wordTokens = tokens(word)
            if (wordTokens.isEmpty()) continue
            nameClauses += if (wordTokens.size > 1) "\"" + wordTokens.joinToString(" ") + "\"" else wordTokens.single()
            keywords += contentTerms(wordTokens)
        }
        if (keywords.isEmpty()) return null
        return keywords.distinct().joinToString(" AND ") { keywordClause(it) } +
            " AND NOT (" + nameClauses.joinToString(" AND ") + ")"
    }

    /**
     * Description-keyword clause for one query word, also accepting its plain plural ('migration' also matches
     * 'migrations', as in Flyway's "Database migrations made easy"). Only an `s` is appended, only to words of
     * letters with at least three characters that do not already end in `s`; no other inflection or synonym is
     * implied. The name exclusion is unchanged, so name and keyword results stay disjoint.
     */
    internal fun keywordClause(word: String): String =
        if (word.length >= 3 && word.all { it.isLetter() } && !word.endsWith("s")) "(tags:$word OR tags:${word}s)" else "tags:$word"

    private val SOLR_SYNTAX = Regex("""[:*?"()\[\]{}^~!\\/]|\b(AND|OR|NOT)\b""")
    private val GROUP_SHAPED = Regex("^[A-Za-z0-9_\\-]+(\\.[A-Za-z0-9_\\-]+)+$")

    /**
     * Query terms for ranking, or null if [query] uses search-expression syntax (field queries, wildcards,
     * boolean operators). Expert queries keep the upstream order.
     */
    fun terms(query: String): List<String>? {
        if (SOLR_SYNTAX.containsMatchIn(query)) return null
        return tokens(query).ifEmpty { null }
    }

    /**
     * The query as a groupId candidate (lower-cased) if it is a single dotted token such as
     * `com.fasterxml.jackson.core`, otherwise null. Used only for ranking, never as a filter.
     */
    fun groupQuery(query: String): String? = query.trim().takeIf { GROUP_SHAPED.matches(it) }?.lowercase()

    /**
     * Search-index expression that matches artifacts in group [group] or any of its subgroups. Whether a dotted
     * query is a groupId is decided by running this against the index (a real group such as `net.sf.json-lib`,
     * `edu.illinois` or `gov.nasa.pds` has matches; `speech.to.text` or `node.js` do not), not by a list of prefixes.
     * [group] must come from [groupQuery], which only admits letters, digits, `.`, `-` and `_`.
     */
    fun groupExistsQuery(group: String): String = "g:\"$group\" OR g:$group.*"

    fun rank(
        terms: List<String>,
        candidates: List<Candidate>,
        groupQuery: String? = null,
        descriptionKeywordMatches: Set<String> = emptySet(),
        phrase: Boolean = false,
    ): List<Ranked> {
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
            val artifactTokens = tokens(c.artifactId)
            val artifact = artifactTokens.joinToString("-")
            val content = contentTerms(terms)
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
                terms.size > 1 && "-$joined-" in "-$artifact-" -> {
                    score += PHRASE_IN_NAME
                    signals += "artifactId contains the query phrase"
                }
                // The query words written as one word, in order ('speechtotext', 'httpclient', 'httpclient5').
                terms.size > 1 && artifactTokens.any { it.trimEnd { ch -> ch.isDigit() } == terms.joinToString("") } -> {
                    score += PHRASE_IN_NAME
                    signals += "artifactId contains the query words joined into one word ('${terms.joinToString("")}')"
                }
                // Whole tokens only: 'texttospeech' does not contain the term 'speech', just as for the index.
                // Not for phrase queries, where word order matters ('text-to-speech' for 'speech-to-text').
                !phrase && content.isNotEmpty() && content.all { it in artifactTokens } -> {
                    score += ALL_TERMS
                    signals += "artifactId contains all query terms"
                }
            }
            // For groupId-shaped queries the group signals above already cover the groupId.
            if (groupQuery == null) {
                content.firstOrNull { it.length >= 3 && it !in GENERIC_GROUP_TOKENS && it in group }?.let {
                    score += GROUP_TERM
                    signals += "groupId contains '$it'"
                }
            }
            if ("${c.groupId}:${c.artifactId}" in descriptionKeywordMatches) {
                score += DESCRIPTION_KEYWORDS
                signals += DESCRIPTION_KEYWORD_SIGNAL
            }
            score += historyAndRecency(c, newest, signals)
            score += MAX_POSITION * (poolSize - c.upstreamPosition).toDouble() / poolSize
            Ranked(c, score, signals)
        }.sortedWith(RANK_ORDER).let { diversifyKeywordMatches(it, descriptionKeywordMatches) }
    }

    private val RANK_ORDER = compareByDescending<Ranked> { it.score }.thenBy { it.candidate.upstreamPosition }

    /** Keyword-only matches per groupId that keep their score; further ones are demoted. */
    private const val KEYWORD_MATCHES_PER_GROUP = 2
    private const val SAME_GROUP_PENALTY = 25.0

    /**
     * Multi-module projects often give every module the parent POM's description, so their modules all match the
     * same description keywords (16 factoryfx and 22 HK2 modules among the top 100 matches for 'dependency
     * injection'). Beyond [KEYWORD_MATCHES_PER_GROUP] keyword-only matches from one groupId, each further one is
     * demoted by [SAME_GROUP_PENALTY] per extra module, so one project does not fill the page. Nothing is removed,
     * name matches are never affected, and the order is deterministic.
     */
    private fun diversifyKeywordMatches(ranked: List<Ranked>, keywordMatches: Set<String>): List<Ranked> {
        if (keywordMatches.isEmpty()) return ranked
        val seenPerGroup = mutableMapOf<String, Int>()
        return ranked.map { r ->
            val c = r.candidate
            if ("${c.groupId}:${c.artifactId}" !in keywordMatches) return@map r
            val nth = seenPerGroup.merge(c.groupId, 1, Int::plus)!!
            if (nth <= KEYWORD_MATCHES_PER_GROUP) return@map r
            val extra = nth - KEYWORD_MATCHES_PER_GROUP
            r.copy(
                score = r.score - SAME_GROUP_PENALTY * extra,
                signals = r.signals + "description-keyword match #$nth from groupId ${c.groupId} " +
                    "(demoted: modules of one project often share a description)",
            )
        }.sortedWith(RANK_ORDER)
    }

    /**
     * Factual signals for results that keep the search-index order (search expressions, explicit filters
     * without free text, or positions beyond the re-ranked pool). [absolutePosition] is 1-based.
     */
    fun upstreamSignals(
        c: Candidate,
        absolutePosition: Int,
        newest: Long?,
        groupFilter: String?,
        coordinateQuery: Boolean = false,
        descriptionKeywordMatch: Boolean = false,
    ): List<String> {
        val signals = mutableListOf("position $absolutePosition in search-index order (not re-ranked)")
        if (descriptionKeywordMatch) signals += DESCRIPTION_KEYWORD_SIGNAL
        if (groupFilter != null && c.groupId.equals(groupFilter, ignoreCase = true)) {
            // A 'groupId:artifactId' query sets the group internally; only call it a filter if the caller passed one.
            signals += if (coordinateQuery) "exact match for the groupId:artifactId query" else "groupId matches the groupId filter"
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
