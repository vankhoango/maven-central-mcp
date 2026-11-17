package net.khoango.mavencentralmcp.core.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Candidate pools mirror the real search-index order observed in November 2026. */
class SearchRankingTest {
    private fun pool(vararg items: Triple<String, Int, Long>) = items.mapIndexed { i, (coord, versions, ts) ->
        val (g, a) = coord.split(":")
        SearchRanking.Candidate(g, a, versions, ts, i)
    }

    private fun top(query: String, candidates: List<SearchRanking.Candidate>) =
        SearchRanking.rank(SearchRanking.terms(query)!!, candidates).first().candidate.let { "${it.groupId}:${it.artifactId}" }

    @Test
    fun `okhttp - original publisher outranks a vendor repackage listed first by the index`() {
        val okhttp = pool(
            Triple("org.wso2.orbit.com.squareup.okhttp:okhttp", 4, 1_779_948_014_000),
            Triple("com.squareup.okhttp3:okhttp", 96, 1_748_537_834_000),
            Triple("com.avito.android:okhttp", 52, 1_731_940_870_031),
            Triple("io.github.qsy7.java.dependencies:okhttp", 3, 1_705_184_598_126),
            Triple("io.github.sunny-chung:okhttp", 1, 1_698_473_339_000),
        )
        assertEquals("com.squareup.okhttp3:okhttp", top("okhttp", okhttp))
    }

    @Test
    fun `multi-word query matches hyphenated artifactId`() {
        val jackson = pool(
            Triple("io.github.errantfiddle:jackson-databind", 2, 1_700_000_000_000),
            Triple("com.liferay:com.fasterxml.jackson.databind", 30, 1_700_000_000_000),
            Triple("io.github.qsy7.java.modules.serialization.modules:jackson-databind", 5, 1_700_000_000_000),
            Triple("tools.jackson.core:jackson-databind", 12, 1_760_000_000_000),
            Triple("com.fasterxml.jackson.core:jackson-databind", 260, 1_750_000_000_000),
        )
        assertEquals("com.fasterxml.jackson.core:jackson-databind", top("jackson databind", jackson))
    }

    @Test
    fun `exact name beats prefix matches with long histories`() {
        val junit = pool(
            Triple("org.junit.jupiter:junit-jupiter-migration-support", 90, 1_750_000_000_000),
            Triple("io.fabric8:kubernetes-junit-jupiter-autodetected", 300, 1_750_000_000_000),
            Triple("org.mockito:junit-jupiter", 150, 1_750_000_000_000),
            Triple("org.junit.jupiter:junit-jupiter", 85, 1_750_000_000_000),
        )
        assertEquals("org.junit.jupiter:junit-jupiter", top("junit-jupiter", junit))
    }

    @Test
    fun `group name match separates same-named artifacts`() {
        val junit = pool(
            Triple("org.robolectric:junit", 40, 1_750_000_000_000),
            Triple("au.com.dius.pact.provider:junit", 40, 1_750_000_000_000),
            Triple("junit:junit", 32, 1_620_000_000_000),
        )
        val ranked = SearchRanking.rank(listOf("junit"), junit)
        assertEquals("junit:junit", ranked.first().candidate.let { "${it.groupId}:${it.artifactId}" })
        assertTrue(ranked.first().signals.contains("groupId contains 'junit'"))
    }

    @Test
    fun `signals are factual and never claim official status`() {
        val ranked = SearchRanking.rank(listOf("okhttp"), pool(Triple("com.squareup.okhttp3:okhttp", 96, 1L)))
        val signals = ranked.single().signals
        assertTrue("artifactId exactly matches the query" in signals)
        assertTrue("96 versions indexed" in signals)
        assertFalse(signals.any { "official" in it.lowercase() })
        assertTrue(SearchRanking.DESCRIPTION.contains("does not verify the publisher"))
    }

    @Test
    fun `search expressions are not re-ranked`() {
        assertNull(SearchRanking.terms("g:com.squareup*"))
        assertNull(SearchRanking.terms("guava AND android"))
        assertNull(SearchRanking.terms("\"exact phrase\""))
        assertEquals(listOf("jackson", "databind"), SearchRanking.terms("Jackson  databind"))
        assertEquals(listOf("spring", "boot", "starter", "web"), SearchRanking.terms("spring-boot-starter-web"))
    }

    @Test
    fun `ties keep index order`() {
        val same = pool(Triple("a.one:x", 1, 1L), Triple("b.two:x", 1, 1L))
        assertEquals("a.one:x", top("x", same))
    }
}
