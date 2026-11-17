package net.khoango.mavencentralmcp.core.error

import kotlin.time.Duration

/**
 * Typed, user-safe failures produced by the core layer.
 *
 * Messages are written to be shown to MCP clients: they never contain stack traces,
 * raw upstream payloads, or local file paths.
 */
sealed class MavenCentralException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** A caller-supplied argument is missing or invalid. */
    class InvalidArgument(message: String) : MavenCentralException(message)

    /** The requested Maven coordinate does not exist on Maven Central. */
    class ArtifactNotFound(val groupId: String, val artifactId: String) :
        MavenCentralException("Artifact '$groupId:$artifactId' was not found on Maven Central.")

    /** Maven Central responded with HTTP 429. [retryAfter] is the server-provided hint, if any. */
    class RateLimited(val retryAfter: Duration?) : MavenCentralException(
        "Maven Central rate limit reached." +
            (retryAfter?.let { " Retry after about ${it.inWholeSeconds} seconds." } ?: " Retry later."),
    )

    /** Maven Central returned a server error or could not be reached. */
    class UpstreamUnavailable(message: String, cause: Throwable? = null) : MavenCentralException(message, cause)

    /** The request to Maven Central did not complete within the configured timeout. */
    class UpstreamTimeout(cause: Throwable? = null) :
        MavenCentralException("Maven Central did not respond in time. Retry later.", cause)

    /** Maven Central returned data that could not be parsed or exceeded the size limit. */
    class MalformedUpstreamResponse(message: String, cause: Throwable? = null) :
        MavenCentralException(message, cause)
}
