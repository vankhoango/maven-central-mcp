package net.khoango.mavencentralmcp.core.service

import kotlinx.coroutines.test.runTest
import net.khoango.mavencentralmcp.core.model.SearchRequest
import net.khoango.mavencentralmcp.maven.api.FakeFetcher
import net.khoango.mavencentralmcp.maven.api.FakeFetcher.Companion.ok
import net.khoango.mavencentralmcp.maven.api.FakeFetcher.Companion.param
import net.khoango.mavencentralmcp.maven.api.FakeFetcher.Companion.status
import net.khoango.mavencentralmcp.maven.api.FetchResponse
import net.khoango.mavencentralmcp.maven.api.MavenCentralClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI

/** Regression tests for bugs found by live end-to-end feature-search testing (November 2026). */
class FeatureSearchRegressionTest {

    private fun doc(id: String, versions: Int = 10) = id.split(":").let { (g, a) ->
        """{"g":"$g","a":"$a","latestVersion":"1.0","p":"jar","timestamp":1700000000000,"versionCount":$versions}"""
    }

    private fun solr(total: Int, ids: List<String>) =
        """{"response":{"numFound":$total,"start":0,"docs":[${ids.joinToString(",") { doc(it) }}]}}"""

    private fun fetcher(keyword: (URI) -> FetchResponse = { ok(solr(0, emptyList())) }, name: (URI) -> FetchResponse) =
        FakeFetcher(keywordRoute = keyword) { uri -> if (uri.host == "search.maven.org") name(uri) else status(404) }

    private suspend fun FakeFetcher.search(query: String) =
        MavenCentralService(MavenCentralClient(this)).search(SearchRequest(query, limit = 10, offset = 0, includeDescriptions = false))

    // Bug 1: substring matching gave keyword-only results a contradictory "artifactId contains all query terms"
    // signal (+20) — 'texttospeech' "contained" speech/to/text — so text-to-speech libraries ranked 3rd-5th for
    // 'speech-to-text' above genuine speech-to-text name matches.
    @Test
    fun `artifactId term signals use whole tokens and never contradict the keyword signal`() = runTest {
        val f = fetcher(
            keyword = { ok(solr(2, listOf("com.google.cloud:google-cloud-texttospeech", "aws.sdk.kotlin:databasemigrationservice"))) },
            name = { ok(solr(1, listOf("io.example:cronapi-watson-speech-to-text"))) },
        )
        val results = f.search("speech-to-text").results.associateBy { it.artifactId }
        val tts = results.getValue("google-cloud-texttospeech")
        assertTrue(tts.rankingSignals.contains(SearchRanking.DESCRIPTION_KEYWORD_SIGNAL))
        assertFalse(tts.rankingSignals.any { it.startsWith("artifactId") }, tts.rankingSignals.toString())
        assertEquals("cronapi-watson-speech-to-text", f.search("speech-to-text").results.first().artifactId)

        val ranked = SearchRanking.rank(
            listOf("database", "migration"),
            listOf(SearchRanking.Candidate("aws.sdk.kotlin", "databasemigrationservice", 100, 1L, 0)),
        )
        assertFalse(ranked.single().signals.contains("artifactId contains all query terms"))
    }

    // Live retest: for the phrase 'speech-to-text', IBM 'text-to-speech' (a keyword-only match, because the index
    // matches the phrase in order) still got "artifactId contains all query terms" and ranked 3rd.
    @Test
    fun `phrase queries respect word order like the index`() = runTest {
        assertTrue(SearchRanking.isPhraseQuery("speech-to-text"))
        assertFalse(SearchRanking.isPhraseQuery("speech to text"))
        assertFalse(SearchRanking.isPhraseQuery("okhttp"))
        val f = fetcher(
            keyword = { ok(solr(1, listOf("com.ibm.watson.developer_cloud:text-to-speech"))) },
            name = { ok(solr(1, listOf("io.example:cronapi-watson-speech-to-text"))) },
        )
        val results = f.search("speech-to-text").results
        val tts = results.single { it.artifactId == "text-to-speech" }
        assertFalse(tts.rankingSignals.any { it.startsWith("artifactId") }, tts.rankingSignals.toString())
        assertEquals("cronapi-watson-speech-to-text", results.first().artifactId)
        // With spaces, order does not matter for the index either: text-to-speech is a genuine all-terms match.
        val spaced = SearchRanking.rank(
            listOf("speech", "to", "text"),
            listOf(SearchRanking.Candidate("g", "text-to-speech", 1, 1L, 0)),
        )
        assertTrue(spaced.single().signals.contains("artifactId contains all query terms"))
    }

    @Test
    fun `keyword signal does not claim the terms are absent from the name`() {
        assertFalse("not in the name" in SearchRanking.DESCRIPTION_KEYWORD_SIGNAL)
        assertTrue("not matched by the index's name search" in SearchRanking.DESCRIPTION_KEYWORD_SIGNAL)
    }

    @Test
    fun `phrase signal requires token boundaries`() {
        val ranked = SearchRanking.rank(
            listOf("to", "text"),
            listOf(SearchRanking.Candidate("g.x", "photo-text", 1, 1L, 0), SearchRanking.Candidate("g.y", "speech-to-text", 1, 1L, 1)),
        ).associate { it.candidate.artifactId to it.signals }
        assertFalse(ranked.getValue("photo-text").contains("artifactId contains the query phrase"))
        assertTrue(ranked.getValue("speech-to-text").contains("artifactId contains the query phrase"))
    }

    // Bug 2: grammatical words produced ranking signals — "groupId contains 'for'" for org.openidentityplatform.
    @Test
    fun `grammatical words are never ranking signals`() {
        val ranked = SearchRanking.rank(
            listOf("library", "for", "json"),
            listOf(SearchRanking.Candidate("org.openidentityplatform.commons", "core", 10, 1L, 0)),
        )
        assertFalse(ranked.single().signals.any { "'for'" in it }, ranked.single().signals.toString())
        val forOnly = SearchRanking.rank(listOf("for"), listOf(SearchRanking.Candidate("a.b", "x", 1, 1L, 0)))
        assertFalse(forOnly.single().signals.contains("artifactId contains all query terms"))
    }

    // Bug 3: punctuation stuck to words ('json,') silently disabled feature search and removed all name signals.
    @Test
    fun `punctuation is a separator, not part of a word`() = runTest {
        assertEquals("(tags:json OR tags:jsons) AND (tags:parser OR tags:parsers) AND NOT (json AND parser)", SearchRanking.keywordQuery("json, parser"))
        assertEquals("(tags:json OR tags:jsons) AND (tags:parser OR tags:parsers) AND NOT (json AND parser)", SearchRanking.keywordQuery("json & parser"))
        // No space: the index treats 'json,parser' like 'json-parser', a phrase.
        assertEquals("(tags:json OR tags:jsons) AND (tags:parser OR tags:parsers) AND NOT (\"json parser\")", SearchRanking.keywordQuery("json,parser"))
        assertEquals(listOf("json", "parser"), SearchRanking.terms("json, parser"))

        val f = fetcher(name = { ok(solr(1, listOf("io.example:json-parser"))) })
        val page = f.search("json, parser")
        assertEquals("applied", page.descriptionKeywordMatching)
        assertTrue(page.results.single().rankingSignals.contains("artifactId exactly matches the query"))
    }

    @Test
    fun `trailing sentence punctuation is ignored and reported`() = runTest {
        assertEquals("json parser", SearchRanking.normalize("json parser!"))
        assertEquals("http client", SearchRanking.normalize("  http client? "))
        assertEquals("g:com.example*", SearchRanking.normalize("g:com.example*"))
        val f = fetcher(name = { ok(solr(1, listOf("io.example:json-parser"))) })
        val page = f.search("json parser!")
        assertEquals("json parser", f.requests.first().param("q"))
        assertEquals("applied", page.descriptionKeywordMatching)
        assertTrue(page.notes.any { "Trailing punctuation was ignored; searched for 'json parser'" in it })
    }

    @Test
    fun `symbols the index drops are reported`() = runTest {
        assertEquals(listOf("c++"), SearchRanking.wordsWithDroppedSymbols("c++ parser"))
        assertEquals(emptyList<String>(), SearchRanking.wordsWithDroppedSymbols("speech-to-text, json"))
        val page = fetcher(name = { ok(solr(0, emptyList())) }).search("c# parser")
        assertTrue(page.notes.any { "'c#'" in it && "match 'c'" in it })
    }

    // Bug 4: any dotted query skipped feature search ('speech.to.text' found 12 instead of 102). A first fix used a
    // list of reverse-domain prefixes; the final rule asks the index whether the group or a subgroup exists.
    // Counts below are the live index results for "g:\"<query>\" OR g:<query>.*" (November 2026).
    private val liveGroupCounts = mapOf(
        "net.sf.json-lib" to 2, "net.sourceforge" to 738, "edu.illinois" to 27, "gov.nasa.pds" to 40,
        "dev.vality" to 50, "info.cqframework" to 30, "io.ktor" to 2221, "com.google" to 3882, "org.apache" to 32623,
    )

    private fun groupAwareFetcher(failGroupCheck: Boolean = false) = fetcher { uri ->
        val q = uri.param("q").orEmpty()
        when {
            " OR g:" in q && failGroupCheck -> status(503)
            " OR g:" in q -> ok(solr(liveGroupCounts[q.substringAfter("g:\"").substringBefore("\"")] ?: 0, emptyList()))
            else -> ok(solr(0, emptyList()))
        }
    }

    @Test
    fun `dotted queries are classified by the index, not by prefix`() = runTest {
        for (group in liveGroupCounts.keys) {
            val f = groupAwareFetcher()
            val page = f.search(group)
            assertEquals("not_applicable", page.descriptionKeywordMatching, group)
            assertTrue(f.keywordRequests.isEmpty(), group)
            assertTrue(page.notes.any { "is a groupId in the search index" in it }, group)
        }
        for (words in listOf("speech.to.text", "node.js", "jackson.databind", "socket.io", "spring.boot", "json.parser")) {
            val f = groupAwareFetcher()
            assertEquals("applied", f.search(words).descriptionKeywordMatching, words)
            assertEquals(1, f.keywordRequests.size, words)
        }
    }

    @Test
    fun `group check query matches the group and its subgroups only`() {
        assertEquals("g:\"net.sf.json-lib\" OR g:net.sf.json-lib.*", SearchRanking.groupExistsQuery("net.sf.json-lib"))
        assertNull(SearchRanking.groupQuery("speech recognition"))
        assertNull(SearchRanking.groupQuery("com.google.guava:guava")) // a coordinate, handled separately
    }

    @Test
    fun `undecidable group check falls back to word search with a note`() = runTest {
        val f = groupAwareFetcher(failGroupCheck = true)
        val page = f.search("edu.illinois")
        assertEquals("applied", page.descriptionKeywordMatching)
        assertTrue(page.notes.any { it.startsWith("Could not check whether 'edu.illinois' is a groupId") })
    }

    @Test
    fun `coordinates and multi-word queries never run the group check`() = runTest {
        for (q in listOf("com.google.guava:guava", "speech recognition", "okhttp", "g:com.google*")) {
            val f = groupAwareFetcher()
            f.search(q)
            assertTrue(f.requests.none { " OR g:" in it.param("q").orEmpty() }, q)
        }
    }

    // Silent skip: queries without meaningful words gave no explanation.
    @Test
    fun `queries without meaningful words say so`() = runTest {
        val page = fetcher(name = { ok(solr(437, listOf("a.b:template-for-gradle"))) }).search("for")
        assertEquals("no_meaningful_words", page.descriptionKeywordMatching)
        assertNull(page.descriptionKeywordMatchCount)
        assertEquals(437L, page.nameMatchCount)
        assertTrue(page.notes.any { it.startsWith("Description-keyword matching was not applied") })
        assertTrue(page.notes.none { "try synonyms" in it })
    }

    @Test
    fun `match counts are reported per source`() = runTest {
        val page = fetcher(
            keyword = { ok(solr(17, listOf("com.alphacephei:vosk"))) },
            name = { ok(solr(1, listOf("a.b:lib-speech-recognition"))) },
        ).search("speech recognition")
        assertEquals(18, page.totalCount)
        assertEquals(1L, page.nameMatchCount)
        assertEquals(17L, page.descriptionKeywordMatchCount)
        assertEquals("applied", page.descriptionKeywordMatching)
    }
}
