package net.khoango.mavencentralmcp.maven.api

import kotlinx.coroutines.test.runTest
import net.khoango.mavencentralmcp.core.error.MavenCentralException
import net.khoango.mavencentralmcp.core.model.ArtifactCoordinates
import net.khoango.mavencentralmcp.maven.api.FakeFetcher.Companion.metadataXml
import net.khoango.mavencentralmcp.maven.api.FakeFetcher.Companion.ok
import net.khoango.mavencentralmcp.maven.api.FakeFetcher.Companion.param
import net.khoango.mavencentralmcp.maven.api.FakeFetcher.Companion.status
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.http.HttpTimeoutException
import kotlin.time.Duration.Companion.seconds

class MavenCentralClientTest {
    private val guava = ArtifactCoordinates("com.google.guava", "guava")

    @Test
    fun `builds metadata url from fixed repository host`() = runTest {
        val fetcher = FakeFetcher { ok(metadataXml(listOf("1.0"))) }
        MavenCentralClient(fetcher).fetchMetadata(guava)
        assertEquals(
            "https://repo1.maven.org/maven2/com/google/guava/guava/maven-metadata.xml",
            fetcher.requests.single().toString(),
        )
    }

    @Test
    fun `encodes search parameters and targets fixed search host`() = runTest {
        val fetcher = FakeFetcher { ok("""{"response":{"numFound":0,"start":0,"docs":[]}}""") }
        val q = MavenCentralClient.buildSearchQuery("json & co", "com.example", null, "jar")
        MavenCentralClient(fetcher).searchArtifacts(q, rows = 5, start = 10)
        val uri = fetcher.requests.single()
        assertEquals("search.maven.org", uri.host)
        assertEquals("(json & co) AND g:\"com.example\" AND p:\"jar\"", uri.param("q"))
        assertEquals("5", uri.param("rows"))
        assertEquals("10", uri.param("start"))
    }

    @Test
    fun `query without filters is passed through`() {
        assertEquals("g:com.google*", MavenCentralClient.buildSearchQuery("g:com.google*", null, null, null))
    }

    @Test
    fun `quote escapes quotes and backslashes`() {
        assertEquals("\"a\\\"b\\\\c\"", MavenCentralClient.quote("a\"b\\c"))
    }

    @Test
    fun `404 metadata maps to null`() = runTest {
        assertNull(MavenCentralClient(FakeFetcher { status(404) }).fetchMetadata(guava))
    }

    @Test
    fun `429 maps to RateLimited with retry hint`() = runTest {
        val e = assertThrows<MavenCentralException.RateLimited> {
            MavenCentralClient(FakeFetcher { status(429, "Retry-After" to "30") }).fetchMetadata(guava)
        }
        assertEquals(30.seconds, e.retryAfter)
    }

    @Test
    fun `5xx maps to UpstreamUnavailable`() = runTest {
        assertThrows<MavenCentralException.UpstreamUnavailable> {
            MavenCentralClient(FakeFetcher { status(503) }).fetchMetadata(guava)
        }
    }

    @Test
    fun `transient 503 is retried once and can succeed`() = runTest {
        var calls = 0
        val fetcher = FakeFetcher { if (++calls == 1) status(503) else ok(metadataXml(listOf("1.0"))) }
        assertEquals(listOf("1.0"), MavenCentralClient(fetcher).fetchMetadata(guava)!!.versions)
        assertEquals(2, fetcher.requests.size)
    }

    @Test
    fun `persistent 503 fails after two attempts and 500 or 429 or timeouts are not retried`() = runTest {
        val unavailable = FakeFetcher { status(503) }
        assertThrows<MavenCentralException.UpstreamUnavailable> { MavenCentralClient(unavailable).fetchMetadata(guava) }
        assertEquals(2, unavailable.requests.size)

        for (fetcher in listOf(FakeFetcher { status(500) }, FakeFetcher { status(429) }, FakeFetcher { throw HttpTimeoutException("slow") })) {
            assertThrows<MavenCentralException> { MavenCentralClient(fetcher).fetchMetadata(guava) }
            assertEquals(1, fetcher.requests.size)
        }
    }

    @Test
    fun `connection failure is retried once`() = runTest {
        var calls = 0
        val fetcher = FakeFetcher { if (++calls == 1) throw IOException("reset") else ok(metadataXml(listOf("2.0"))) }
        assertEquals(listOf("2.0"), MavenCentralClient(fetcher).fetchMetadata(guava)!!.versions)
    }

    @Test
    fun `search HTTP 400 becomes an InvalidArgument`() = runTest {
        assertThrows<MavenCentralException.InvalidArgument> {
            MavenCentralClient(FakeFetcher { status(400) }).searchArtifacts("g:(", 1, 0)
        }
        assertThrows<MavenCentralException.UpstreamUnavailable> {
            MavenCentralClient(FakeFetcher { status(400) }).fetchMetadata(guava)
        }
    }

    @Test
    fun `timeout maps to UpstreamTimeout`() = runTest {
        assertThrows<MavenCentralException.UpstreamTimeout> {
            MavenCentralClient(FakeFetcher { throw HttpTimeoutException("slow") }).fetchMetadata(guava)
        }
    }

    @Test
    fun `network failure maps to UpstreamUnavailable without leaking details`() = runTest {
        val e = assertThrows<MavenCentralException.UpstreamUnavailable> {
            MavenCentralClient(FakeFetcher { throw IOException("secret internal detail") }).fetchMetadata(guava)
        }
        assertTrue("secret" !in e.message!!)
    }

    @Test
    fun `oversized response maps to MalformedUpstreamResponse`() = runTest {
        assertThrows<MavenCentralException.MalformedUpstreamResponse> {
            MavenCentralClient(FakeFetcher { throw ResponseTooLargeException(10) }).fetchMetadata(guava)
        }
    }

    @Test
    fun `malformed json maps to MalformedUpstreamResponse`() = runTest {
        assertThrows<MavenCentralException.MalformedUpstreamResponse> {
            MavenCentralClient(FakeFetcher { ok("{not json") }).searchArtifacts("x", 1, 0)
        }
        assertThrows<MavenCentralException.MalformedUpstreamResponse> {
            MavenCentralClient(FakeFetcher { ok("""{"other":1}""") }).searchArtifacts("x", 1, 0)
        }
    }

    @Test
    fun `malformed metadata maps to MalformedUpstreamResponse`() = runTest {
        assertThrows<MavenCentralException.MalformedUpstreamResponse> {
            MavenCentralClient(FakeFetcher { ok("<metadata>") }).fetchMetadata(guava)
        }
    }

    @Test
    fun `successful responses are cached`() = runTest {
        val fetcher = FakeFetcher { ok(metadataXml(listOf("1.0"))) }
        val client = MavenCentralClient(fetcher)
        client.fetchMetadata(guava)
        client.fetchMetadata(guava)
        assertEquals(1, fetcher.requests.size)
    }

    @Test
    fun `unsafe version strings are never used in pom urls`() = runTest {
        val fetcher = FakeFetcher { ok("<project/>") }
        assertNull(MavenCentralClient(fetcher).fetchRawPom(guava, "../../x"))
        assertNull(MavenCentralClient(fetcher).fetchRawPom(guava, "1.0/evil"))
        assertTrue(fetcher.requests.isEmpty())
    }

    @Test
    fun `readBounded enforces the size limit`() {
        assertEquals(5, JdkHttpFetcher.readBounded(ByteArrayInputStream(ByteArray(5)), 5).size)
        assertThrows<ResponseTooLargeException> { JdkHttpFetcher.readBounded(ByteArrayInputStream(ByteArray(6)), 5) }
    }

    @Test
    fun `retry-after parsing`() {
        assertEquals(10.seconds, MavenCentralClient.parseRetryAfter("10"))
        assertEquals(3600.seconds, MavenCentralClient.parseRetryAfter("999999"))
        assertNull(MavenCentralClient.parseRetryAfter("Wed, 21 Oct 2015 07:28:00 GMT"))
        assertNull(MavenCentralClient.parseRetryAfter(null))
    }
}
