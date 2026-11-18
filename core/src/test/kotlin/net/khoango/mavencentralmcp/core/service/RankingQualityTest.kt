package net.khoango.mavencentralmcp.core.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Ranking changes from the relevance audit (November 2026), each backed by live index evidence. */
class RankingQualityTest {

    private fun c(id: String, versions: Int = 10, pos: Int = 0) = id.split(":").let { (g, a) ->
        SearchRanking.Candidate(g, a, versions, 1_700_000_000_000, pos)
    }

    // Evidence: Flyway's description keywords are [database, flyway, migrations, made, easy]; 'database migration'
    // did not match it at all. With the plural form it matches and enters the re-ranked pool.
    @Test
    fun `keyword clauses accept the plain plural of a word`() {
        assertEquals("(tags:migration OR tags:migrations)", SearchRanking.keywordClause("migration"))
        assertEquals("tags:migrations", SearchRanking.keywordClause("migrations")) // already ends in s
        assertEquals("tags:io", SearchRanking.keywordClause("io")) // too short
        assertEquals("tags:http2", SearchRanking.keywordClause("http2")) // not only letters
        assertEquals(
            "(tags:database OR tags:databases) AND (tags:migration OR tags:migrations) AND NOT (database AND migration)",
            SearchRanking.keywordQuery("database migration"),
        )
    }

    // Evidence: 'ai.rev.speechtotext:revai-java-sdk-speechtotext' (a speech-to-text SDK) and Google's
    // 'google-cloud-texttospeech' were both keyword-only matches for 'speech-to-text' with no name evidence either way.
    @Test
    fun `query words joined into one word in the name count as name evidence, in order only`() {
        val ranked = SearchRanking.rank(
            listOf("speech", "to", "text"),
            listOf(c("com.google.cloud:google-cloud-texttospeech", 209, 0), c("ai.rev.speechtotext:revai-java-sdk-speechtotext", 5, 1)),
            descriptionKeywordMatches = setOf("com.google.cloud:google-cloud-texttospeech", "ai.rev.speechtotext:revai-java-sdk-speechtotext"),
            phrase = true,
        )
        assertEquals("revai-java-sdk-speechtotext", ranked.first().candidate.artifactId)
        assertTrue(ranked.first().signals.contains("artifactId contains the query words joined into one word ('speechtotext')"))
        assertFalse(ranked.last().signals.any { it.startsWith("artifactId") }, ranked.last().signals.toString())
    }

    @Test
    fun `joined words tolerate a trailing version number`() {
        val ranked = SearchRanking.rank(listOf("http", "client"), listOf(c("org.apache.httpcomponents.client5:httpclient5")))
        assertTrue(ranked.single().signals.any { it.startsWith("artifactId contains the query words joined") })
        val notJoined = SearchRanking.rank(listOf("http", "client"), listOf(c("x.y:httpcomponents")))
        assertFalse(notJoined.single().signals.any { it.startsWith("artifactId") })
    }

    // Evidence: among the top 100 keyword-only matches for 'dependency injection', 16 were io.github.factoryfx modules
    // and 22 org.glassfish.hk2 modules sharing their parent's description; 7 factoryfx modules filled the top 10.
    @Test
    fun `one project cannot fill the page with keyword-only matches`() {
        val factoryfx = (0 until 7).map { c("io.github.factoryfx:module$it", 98, it) }
        val others = listOf(c("io.quarkus:quarkus-arc", 40, 7), c("org.kodein.di:kodein-di", 40, 8), c("com.google.inject:guice", 24, 9))
        val all = factoryfx + others
        val ranked = SearchRanking.rank(listOf("dependency", "injection"), all, descriptionKeywordMatches = all.map { "${it.groupId}:${it.artifactId}" }.toSet())
        val top5 = ranked.take(5).map { it.candidate.groupId }
        assertEquals(2, top5.count { it == "io.github.factoryfx" }, top5.toString())
        assertTrue(listOf("io.quarkus", "org.kodein.di", "com.google.inject").all { it in top5 }, top5.toString())
        // Nothing is removed and the demotion is explained.
        assertEquals(10, ranked.size)
        assertTrue(ranked.last().signals.any { it.startsWith("description-keyword match #") && "io.github.factoryfx" in it })
    }

    @Test
    fun `name matches are never demoted for sharing a group`() {
        val names = (0 until 4).map { c("org.opencastproject:opencast-speech-to-text-part$it", 30, it) }
        val ranked = SearchRanking.rank(listOf("speech", "to", "text"), names, phrase = true)
        assertTrue(ranked.none { r -> r.signals.any { it.startsWith("description-keyword match #") } })
    }

    @Test
    fun `ranking is deterministic`() {
        val pool = (0 until 30).map { c("g${it % 4}.x:a$it", 10 + it % 3, it) }
        val keyword = pool.filter { it.upstreamPosition % 2 == 0 }.map { "${it.groupId}:${it.artifactId}" }.toSet()
        val first = SearchRanking.rank(listOf("lib"), pool, descriptionKeywordMatches = keyword).map { it.candidate.artifactId }
        repeat(5) { assertEquals(first, SearchRanking.rank(listOf("lib"), pool.shuffled(), descriptionKeywordMatches = keyword).map { it.candidate.artifactId }) }
    }
}
