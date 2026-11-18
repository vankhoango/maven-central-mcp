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

/**
 * Feature search: plain-word queries also match the search index's description keywords (`tags`), so
 * 'speech recognition' finds libraries such as Vosk whose names do not contain those words.
 * Fixtures mirror the live index (November 2026): name matches 1, keyword-only matches 17.
 */
class FeatureSearchTest {

    private fun doc(id: String, versions: Int = 10) = id.split(":").let { (g, a) ->
        """{"g":"$g","a":"$a","latestVersion":"1.0","p":"jar","timestamp":1700000000000,"versionCount":$versions}"""
    }

    private fun solr(total: Int, ids: List<String>) =
        """{"response":{"numFound":$total,"start":0,"docs":[${ids.joinToString(",") { doc(it) }}]}}"""

    private fun fetcher(keyword: (URI) -> FetchResponse, name: (URI) -> FetchResponse) =
        FakeFetcher(keywordRoute = keyword) { uri -> if (uri.host == "search.maven.org") name(uri) else status(404) }

    private suspend fun FakeFetcher.search(query: String, limit: Int = 10, offset: Int = 0, groupId: String? = null) =
        MavenCentralService(MavenCentralClient(this))
            .search(SearchRequest(query, groupId = groupId, limit = limit, offset = offset, includeDescriptions = false))

    // ---- query construction --------------------------------------------------------------------------------------

    @Test
    fun `keyword query requires every word in description keywords and excludes name matches`() {
        assertEquals(
            "(tags:speech OR tags:speechs) AND (tags:recognition OR tags:recognitions) AND NOT (speech AND recognition)",
            SearchRanking.keywordQuery("speech recognition"),
        )
        assertEquals("(tags:voice OR tags:voices) AND (tags:recognition OR tags:recognitions) AND NOT (voice AND recognition)", SearchRanking.keywordQuery("Voice  Recognition"))
    }

    @Test
    fun `hyphenated words are phrases in the exclusion and stopwords are not keywords`() {
        // Mirrors how the index's name query treats 'speech-to-text' (a phrase), so the two sets stay disjoint.
        assertEquals("(tags:speech OR tags:speechs) AND (tags:text OR tags:texts) AND NOT (\"speech to text\")", SearchRanking.keywordQuery("speech-to-text"))
        assertEquals("(tags:speech OR tags:speechs) AND (tags:text OR tags:texts) AND NOT (speech AND to AND text)", SearchRanking.keywordQuery("speech to text"))
        assertEquals(
            "(tags:audio OR tags:audios) AND (tags:transcription OR tags:transcriptions) AND NOT (audio AND transcription)",
            SearchRanking.keywordQuery("audio transcription"),
        )
    }

    @Test
    fun `no keyword query for stopwords only, punctuation only or search expressions`() {
        assertNull(SearchRanking.keywordQuery("to the"))
        assertNull(SearchRanking.keywordQuery("& , ;"))
        assertNull(SearchRanking.keywordQuery("g:com.example*"))
        assertNull(SearchRanking.keywordQuery("speech AND recognition"))
    }

    // ---- search behavior -----------------------------------------------------------------------------------------

    @Test
    fun `speech recognition finds libraries through description keywords`() = runTest {
        val f = fetcher(
            keyword = { ok(solr(17, listOf("com.alphacephei:vosk", "org.mozilla.deepspeech:libdeepspeech", "com.yandex.android:speechkit"))) },
            name = { ok(solr(1, listOf("com.bihe0832.android:lib-speech-recognition"))) },
        )
        val page = f.search("speech recognition")
        assertEquals(18, page.totalCount) // 1 name match + 17 keyword-only matches, disjoint by construction
        assertEquals(4, page.results.size)
        assertTrue(page.hasMore) // 18 matches, only 4 documents in this fixture's pools
        val byArtifact = page.results.associateBy { it.artifactId }
        assertTrue(byArtifact.getValue("vosk").rankingSignals.contains(SearchRanking.DESCRIPTION_KEYWORD_SIGNAL))
        assertTrue(byArtifact.getValue("lib-speech-recognition").rankingSignals.contains("artifactId contains the query phrase"))
        assertFalse(byArtifact.getValue("lib-speech-recognition").rankingSignals.contains(SearchRanking.DESCRIPTION_KEYWORD_SIGNAL))
        assertTrue(page.notes.any { it.startsWith("17 of 18 matches were found through description keywords") })
        assertEquals("(tags:speech OR tags:speechs) AND (tags:recognition OR tags:recognitions) AND NOT (speech AND recognition)", f.keywordRequests.first().param("q"))
        assertEquals(4, page.results.map { "${it.groupId}:${it.artifactId}" }.toSet().size) // no duplicates
    }

    @Test
    fun `name matches still outrank equally established description-keyword matches`() = runTest {
        val f = fetcher(
            keyword = { ok(solr(1, listOf("org.example:speech-engine"))) },
            name = { ok(solr(1, listOf("org.example:speech-recognition"))) },
        )
        assertEquals("speech-recognition", f.search("speech recognition").results.first().artifactId)
    }

    @Test
    fun `filters apply to the keyword query too`() = runTest {
        val f = fetcher(keyword = { ok(solr(0, emptyList())) }, name = { ok(solr(0, emptyList())) })
        f.search("speech recognition", groupId = "com.alphacephei")
        assertEquals(
            "((tags:speech OR tags:speechs) AND (tags:recognition OR tags:recognitions) AND NOT (speech AND recognition)) AND g:\"com.alphacephei\"",
            f.keywordRequests.single().param("q"),
        )
    }

    @Test
    fun `few results suggest synonyms`() = runTest {
        val f = fetcher(keyword = { ok(solr(1, listOf("org.example:transcribe"))) }, name = { ok(solr(0, emptyList())) })
        val page = f.search("audio transcription")
        assertEquals(1, page.totalCount)
        assertTrue(page.notes.any { "try synonyms" in it })
    }

    @Test
    fun `name-only queries, group-shaped queries, expressions and coordinates`() = runTest {
        // Plain name query: keyword search runs but finds nothing extra; results unchanged.
        val plain = fetcher(keyword = { ok(solr(0, emptyList())) }, name = { ok(solr(2, listOf("com.squareup.okhttp3:okhttp", "a.b:okhttp"))) })
        val page = plain.search("okhttp")
        assertEquals(2, page.totalCount)
        assertTrue(page.notes.none { "found through description keywords" in it })

        for (query in listOf("com.fasterxml.jackson.core", "g:com.squareup*", "com.google.guava:guava")) {
            // The group check ("g:... OR g:...*") finds the group for the groupId query; nothing else matches.
            val f = fetcher(keyword = { error("keyword search must not run for '$query'") }, name = { uri ->
                if (" OR g:" in uri.param("q").orEmpty()) ok(solr(3, emptyList())) else ok(solr(0, emptyList()))
            })
            f.search(query)
            assertTrue(f.keywordRequests.isEmpty(), query)
        }
    }

    @Test
    fun `keyword search failure degrades to name results with a note`() = runTest {
        val f = fetcher(keyword = { status(500) }, name = { ok(solr(1, listOf("com.bihe0832.android:lib-speech-recognition"))) })
        val page = f.search("speech recognition")
        assertEquals(1, page.totalCount) // name matches only; keyword matches are not counted
        assertEquals(1, page.results.size)
        assertEquals("unavailable", page.descriptionKeywordMatching)
        assertEquals(1L, page.nameMatchCount)
        assertNull(page.descriptionKeywordMatchCount)
        assertTrue(page.notes.any { it.startsWith("Description-keyword matching failed and was skipped") && "name matches only" in it })
    }

    @Test
    fun `deep pages continue with remaining name matches, then remaining keyword matches`() = runTest {
        val namePool = (0 until 100).map { "name.g$it:lib" }
        val keywordPool = (0 until 100).map { "kw.g$it:tool" }
        val f = fetcher(
            keyword = { uri ->
                val start = uri.param("start")!!.toInt()
                val rows = uri.param("rows")!!.toInt()
                ok(solr(120, if (start == 0) keywordPool else (start until start + rows).map { "kw.deep$it:tool" }))
            },
            name = { uri ->
                val start = uri.param("start")!!.toInt()
                val rows = uri.param("rows")!!.toInt()
                ok(solr(150, if (start == 0) namePool else (start until start + rows).map { "name.deep$it:lib" }))
            },
        )
        // total = 150 + 120; the re-ranked pool holds 200 (100 + 100).
        val boundary = f.search("lib tool", limit = 10, offset = 195)
        assertEquals(270, boundary.totalCount)
        assertEquals((196..205).toList(), boundary.results.map { it.rank })
        assertEquals(5, boundary.results.count { it.groupId.startsWith("name.deep") }) // name positions 100-104
        assertEquals("100", f.requests.last().param("start"))

        val crossing = f.search("lib tool", limit = 10, offset = 245)
        val groups = crossing.results.map { it.groupId }
        assertEquals((145..149).map { "name.deep$it" }, groups.take(5)) // last 5 name matches
        assertEquals((100..104).map { "kw.deep$it" }, groups.drop(5)) // then keyword matches 100-104
        assertTrue(crossing.results.drop(5).all { SearchRanking.DESCRIPTION_KEYWORD_SIGNAL in it.rankingSignals })
        assertTrue(crossing.results.last().rankingSignals.first().startsWith("position 255 in search-index order"))

        val last = f.search("lib tool", limit = 10, offset = 265)
        assertEquals(5, last.results.size)
        assertFalse(last.hasMore)
        assertEquals((115..119).map { "kw.deep$it" }, last.results.map { it.groupId })
    }
}
