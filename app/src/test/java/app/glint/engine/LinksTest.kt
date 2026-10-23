package app.glint.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinksTest {
    @Test
    fun `a link shared inside a sentence is found`() {
        val text = "Watch this! https://youtu.be/dQw4w9WgXcQ?si=abc shared from YouTube"

        assertEquals("https://youtu.be/dQw4w9WgXcQ?si=abc", Links.first(text))
    }

    @Test
    fun `trailing punctuation is not part of the link`() {
        assertEquals("https://example.com/v/1", Links.first("Look: https://example.com/v/1."))
        assertEquals("https://example.com/v/1", Links.first("(https://example.com/v/1)"))
        assertEquals("https://example.com/v/1", Links.first("“https://example.com/v/1”"))
        assertEquals("https://example.com/v/1", Links.first("رابط: https://example.com/v/1،"))
    }

    @Test
    fun `balanced parentheses inside a link are kept`() {
        val link = "https://en.wikipedia.org/wiki/Mercury_(planet)"

        assertEquals(link, Links.first("See $link"))
    }

    @Test
    fun `www links get a scheme`() {
        assertEquals("https://www.example.com/watch", Links.first("www.example.com/watch"))
    }

    @Test
    fun `several links are found in order without repeats`() {
        val text = "https://a.example/1\nhttps://b.example/2 https://a.example/1"

        assertEquals(listOf("https://a.example/1", "https://b.example/2"), Links.all(text))
    }

    @Test
    fun `text without a link gives nothing`() {
        assertNull(Links.first("no links here, just http and https words"))
        assertNull(Links.first(""))
    }

    @Test
    fun `playlist parameters are recognized`() {
        assertTrue(Links.namesPlaylist("https://www.youtube.com/watch?v=abc&list=PL123"))
        assertTrue(Links.namesPlaylist("https://www.youtube.com/playlist?list=PL123"))
        assertFalse(Links.namesPlaylist("https://www.youtube.com/watch?v=abc&playlist=no"))
    }

    @Test
    fun `error output is reduced to its last error line`() {
        val output =
            """
            [youtube] abc: Downloading webpage
            WARNING: something minor
            ERROR: [youtube] abc: Video unavailable
            """
                .trimIndent()

        assertEquals("[youtube] abc: Video unavailable", ErrorText.summarize(output))
        assertEquals("Only line", ErrorText.summarize("\n Only line \n"))
        assertNull(ErrorText.summarize("  \n "))
        assertNull(ErrorText.summarize(null))
    }

    @Test
    fun `an exception is described with its causes`() {
        val error = ExceptionInInitializerError(RuntimeException("Cannot instantiate a.B\nmore detail"))

        assertEquals("ExceptionInInitializerError ← RuntimeException: Cannot instantiate a.B", ErrorText.describe(error))
        assertEquals("IllegalStateException: boom", ErrorText.describe(IllegalStateException("boom")))
    }
}
