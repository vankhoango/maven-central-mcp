package net.khoango.mavencentralmcp.maven.cache

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class TtlCacheTest {
    @Test
    fun `entries expire after ttl`() {
        val time = TestTimeSource()
        val cache = TtlCache<String, String>(10, 30.seconds, time)
        cache.put("k", "v")
        assertEquals("v", cache.get("k"))
        time += 31.seconds
        assertNull(cache.get("k"))
    }

    @Test
    fun `least recently used entry is evicted`() {
        val cache = TtlCache<String, String>(2, 1.minutes)
        cache.put("a", "1")
        cache.put("b", "2")
        cache.get("a")
        cache.put("c", "3")
        assertEquals("1", cache.get("a"))
        assertNull(cache.get("b"))
        assertEquals(2, cache.size())
    }
}
