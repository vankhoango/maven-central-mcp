package net.khoango.mavencentralmcp.core.version

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/** Cases taken from real Maven Central version lists (Jackson, Hibernate, Spring, Jetty, mssql-jdbc, ...). */
class VersionVariantsTest {

    @ParameterizedTest
    @ValueSource(strings = ["2.9.0.pr1", "2.9.0.pr3", "0.0.1-test-deploy", "1.0.0-canary01", "6.1.2.jre7-preview",
        "9.5.0-SNAPSHOT.jre11-preview", "2.26-b01", "22.0-rc1-android", "4.0.0.Alpha1", "3.5.0-CR-1", "0.25.0-eap13"])
    fun `real prereleases`(version: String) = assertTrue(VersionPolicy.isPrerelease(version), version)

    @ParameterizedTest
    @ValueSource(strings = ["3.3.0.SP1", "2.5.6.SEC01", "3.3.0.GA", "3.6.0.Final", "5.3.39.RELEASE",
        "7.0.0.v20091005", "12.8.1.jre11", "42.2.5.jre7", "9.2-1003-jdbc4", "1.2.4-groovyless", "2.4.0a", "1.3.5-native-mt"])
    fun `real stable releases`(version: String) = assertFalse(VersionPolicy.isPrerelease(version), version)

    @ParameterizedTest
    @CsvSource(
        "33.8.0-jre, jre",
        "33.8.0-android, android",
        "22.0-rc1-android, android",
        "12.8.1.jre11, jre11",
        "6.1.2.jre7-preview, jre7",
        "9.2-1003-jdbc4, jdbc4",
        "1.3.5-2-native-mt-1.4-M1, native-mt",
        "2.13.0-M4-pre-20d3c21, ",
        "5.3.39.RELEASE, ",
        "6.4.4.Final, ",
        "3.3.0.SP1, ",
        "2.5.6.SEC01, ",
        "7.0.0.v20091005, ",
        "1.0, ",
        "1.2.3-1, ",
        "r09, ",
        "2.4.0a, ",
        "2.0.0-beta.2, ",
    )
    fun `variant labels`(version: String, expected: String?) = assertEquals(expected, VersionPolicy.variantOf(version))

    @Test
    fun `plain-release spellings are not competing variants`() {
        val pick = VersionPolicy.pickNewest(listOf("5.3.39", "5.3.39.RELEASE", "5.3.38"))
        assertTrue(pick.competing.isEmpty(), pick.toString())
    }

    @Test
    fun `different jre levels of one release compete`() {
        val pick = VersionPolicy.pickNewest(listOf("12.8.1.jre8", "12.8.1.jre11", "12.6.0.jre11"))
        assertEquals(1, pick.competing.size)
    }

    @Test
    fun `major version`() {
        assertEquals(33L, VersionPolicy.majorOf("33.8.0-jre"))
        assertEquals(0L, VersionPolicy.majorOf("0.9.0"))
        assertEquals(null, VersionPolicy.majorOf("r09"))
    }
}
