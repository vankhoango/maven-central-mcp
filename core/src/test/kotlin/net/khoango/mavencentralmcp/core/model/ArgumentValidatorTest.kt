package net.khoango.mavencentralmcp.core.model

import net.khoango.mavencentralmcp.core.error.MavenCentralException.InvalidArgument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ArgumentValidatorTest {

    @Test
    fun `accepts and trims valid coordinates`() {
        assertEquals(
            ArtifactCoordinates("com.google.guava", "guava"),
            ArgumentValidator.coordinates(" com.google.guava ", "guava"),
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "  ", "com/google", "../etc", "a..b", "com.google.", "g a", "x\"y", "https://evil", ".hidden"])
    fun `rejects invalid identifiers`(value: String) {
        assertThrows<InvalidArgument> { ArgumentValidator.id("groupId", value) }
    }

    @Test
    fun `rejects missing and oversized identifiers`() {
        assertThrows<InvalidArgument> { ArgumentValidator.id("groupId", null) }
        assertThrows<InvalidArgument> { ArgumentValidator.id("groupId", "a".repeat(201)) }
    }

    @Test
    fun `optional filters treat blank as absent`() {
        assertNull(ArgumentValidator.optionalId("groupId", " "))
        assertNull(ArgumentValidator.optionalPackaging(null))
        assertEquals("jar", ArgumentValidator.optionalPackaging("jar"))
        assertThrows<InvalidArgument> { ArgumentValidator.optionalPackaging("jar\"OR") }
    }

    @Test
    fun `validates query`() {
        assertEquals("guava", ArgumentValidator.query("  guava "))
        assertThrows<InvalidArgument> { ArgumentValidator.query("") }
        assertThrows<InvalidArgument> { ArgumentValidator.query("a".repeat(201)) }
        assertThrows<InvalidArgument> { ArgumentValidator.query("a\u0000b") }
    }

    @Test
    fun `enforces limit and offset bounds`() {
        assertEquals(10, ArgumentValidator.limit(null, 10, 50))
        assertEquals(50, ArgumentValidator.limit(50, 10, 50))
        assertThrows<InvalidArgument> { ArgumentValidator.limit(0, 10, 50) }
        assertThrows<InvalidArgument> { ArgumentValidator.limit(51, 10, 50) }
        assertEquals(0, ArgumentValidator.offset(null, 100))
        assertThrows<InvalidArgument> { ArgumentValidator.offset(-1, 100) }
        assertThrows<InvalidArgument> { ArgumentValidator.offset(101, 100) }
    }
}
