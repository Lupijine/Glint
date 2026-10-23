package app.glint.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** Real yt-dlp error lines, and the plain message each one should show. */
class ErrorKindTest {
    private fun assertKind(expected: ErrorKind, message: String) =
        assertEquals(message, expected, ErrorKind.of(message))

    @Test
    fun `a dropped connection is a network error`() {
        assertKind(ErrorKind.Network, "[download] Got error: [Errno 104] Connection reset by peer. Giving up after 10 retries")
        assertKind(ErrorKind.Network, "Unable to download webpage: <urlopen error [Errno 7] No address associated with hostname>")
        assertKind(ErrorKind.Network, "[Errno -3] Temporary failure in name resolution")
        assertKind(ErrorKind.Network, "Read timed out. (read timeout=20.0)")
        assertKind(ErrorKind.Network, "Unable to download video data: HTTP Error 503: Service Unavailable")
    }

    @Test
    fun `sites that want an account or a person`() {
        assertKind(
            ErrorKind.BotCheck,
            "[youtube] abc: Sign in to confirm you’re not a bot. Use --cookies-from-browser or --cookies for the authentication.",
        )
        assertKind(ErrorKind.SignIn, "[youtube] abc: Sign in to confirm your age. This video may be inappropriate for some users.")
        assertKind(ErrorKind.SignIn, "[youtube] abc: Private video. Sign in if you've been granted access to this video")
        assertKind(ErrorKind.SignIn, "[youtube] abc: Join this channel to get access to members-only content like this video")
        assertKind(
            ErrorKind.SignIn,
            "[instagram] abc: Requested content is not available, rate-limit reached or login required. Use --cookies",
        )
    }

    @Test
    fun `videos that are gone, blocked or not yet live`() {
        assertKind(ErrorKind.Unavailable, "[youtube] abc: Video unavailable")
        assertKind(
            ErrorKind.Unavailable,
            "[youtube] abc: Video unavailable. This video is no longer available because the YouTube account " +
                "associated with this video has been terminated.",
        )
        assertKind(ErrorKind.Unavailable, "[generic] Unable to download webpage: HTTP Error 404: Not Found")
        assertKind(ErrorKind.Geo, "[youtube] abc: Video unavailable. The uploader has not made this video available in your country")
        assertKind(ErrorKind.NotStarted, "[youtube] abc: This live event will begin in 3 hours.")
        assertKind(ErrorKind.NotStarted, "[youtube] abc: Premieres in 2 days")
        assertKind(ErrorKind.Drm, "[youtube] abc: This video is DRM protected")
        assertKind(ErrorKind.RateLimited, "[youtube] abc: Video unavailable. This content isn’t available, try again later.")
        assertKind(ErrorKind.RateLimited, "Unable to download webpage: HTTP Error 429: Too Many Requests")
    }

    @Test
    fun `links, formats, the phone and an old yt-dlp`() {
        assertKind(ErrorKind.Unsupported, "[generic] Unsupported URL: https://example.com/login")
        assertKind(ErrorKind.Format, "Requested format is not available. Use --list-formats for a list of available formats")
        assertKind(ErrorKind.Storage, "unable to write data: [Errno 28] No space left on device")
        assertKind(ErrorKind.Folder, "[Errno 13] Permission denied: '/storage/emulated/0/Download/Glint/a.mp4'")
        assertKind(ErrorKind.Outdated, "[youtube] abc: Unable to extract uploader id; please report this issue")
        assertKind(ErrorKind.Outdated, "unable to download video data: HTTP Error 403: Forbidden")
    }

    @Test
    fun `anything else is unknown, and the full output helps when the summary does not`() {
        assertKind(ErrorKind.Unknown, "Postprocessing: something odd happened")
        assertEquals(ErrorKind.Unknown, ErrorKind.of(null))
        assertEquals(ErrorKind.Unknown, ErrorKind.of("  "))
        val detail =
            """
            [youtube] abc: Downloading webpage
            [download] Destination: a.webm
            [download] Got error: [Errno 104] Connection reset by peer
            """
                .trimIndent()
        assertEquals(ErrorKind.Network, ErrorKind.of("Giving up", detail))
        assertEquals(ErrorKind.Unavailable, ErrorKind.of("[youtube] abc: Video unavailable", detail))
    }
}
