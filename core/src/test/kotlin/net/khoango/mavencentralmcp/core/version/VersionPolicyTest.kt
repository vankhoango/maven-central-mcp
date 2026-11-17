package net.khoango.mavencentralmcp.core.version

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class VersionPolicyTest {

    @ParameterizedTest
    @ValueSource(
        strings = [
            "1.0-alpha", "1.0-alpha-1", "1.0.0-ALPHA2", "1.0-beta", "1.0.0-beta.2", "2.0.0-beta01",
            "1.0-RC1", "2.5.0-RC", "1.0.0-rc.1", "1.0-CR2", "5.0.0-M1", "1.0.M3", "1.0a1", "2.0-b3",
            "1.0-milestone-2", "1.0-preview", "1.0-pre", "21-ea", "1.0-SNAPSHOT", "1.0-dev3",
            "1.0.0-nightly", "1.0-incubating", "2.0-EAP",
        ],
    )
    fun `classifies prereleases`(version: String) {
        assertTrue(VersionPolicy.isPrerelease(version), version)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "1.0", "1.0.0", "33.8.0-jre", "33.8.0-android", "5.3.39.RELEASE", "6.4.4.Final", "1.0.GA",
            "2.0.0.v20240101", "1.2.3-1", "0.9.0", "3.6.1", "1.0-a", "8.0-m",
        ],
    )
    fun `classifies stable versions`(version: String) {
        assertFalse(VersionPolicy.isPrerelease(version), version)
    }

    @Test
    fun `orders with Maven semantics not lexicographically`() {
        val sorted = VersionPolicy.sortNewestFirst(
            listOf("1.9", "1.10", "1.0-alpha", "1.0", "1.0-rc1", "1.0-beta", "1.0-SNAPSHOT", "1.2"),
        )
        assertEquals(listOf("1.10", "1.9", "1.2", "1.0", "1.0-SNAPSHOT", "1.0-rc1", "1.0-beta", "1.0-alpha"), sorted)
    }

    @Test
    fun `preserves original version strings`() {
        val input = listOf("1.0.0.Final", "1.0.1.FINAL")
        assertEquals(setOf("1.0.0.Final", "1.0.1.FINAL"), VersionPolicy.sortNewestFirst(input).toSet())
    }

    @Test
    fun `reports platform variants of the same release as competing`() {
        val pick = VersionPolicy.pickNewest(listOf("33.7.2-jre", "33.8.0-android", "33.8.0-jre", "33.7.2-android"))
        assertEquals("33.8.0-jre", pick.version)
        assertEquals(listOf("33.8.0-android"), pick.competing)
    }

    @Test
    fun `does not flag ordered prereleases as competing`() {
        val pick = VersionPolicy.pickNewest(listOf("2.0-beta1", "2.0-beta2", "1.0"))
        assertEquals("2.0-beta2", pick.version)
        assertTrue(pick.competing.isEmpty())
    }

    @Test
    fun `does not flag distinct releases as competing`() {
        val pick = VersionPolicy.pickNewest(listOf("1.0", "1.0.1", "1.1"))
        assertEquals("1.1", pick.version)
        assertTrue(pick.competing.isEmpty())
    }

    @Test
    fun `empty input yields no pick`() {
        assertNull(VersionPolicy.pickNewest(emptyList()).version)
    }

    @Test
    fun `detects snapshots`() {
        assertTrue(VersionPolicy.isSnapshot("1.0-SNAPSHOT"))
        assertFalse(VersionPolicy.isSnapshot("1.0"))
    }
}
