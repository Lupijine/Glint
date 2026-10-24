package app.glint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignInBrowserTest {
    @Test
    fun `the sign-in browser introduces itself as Chrome on Android, with WebView's Chrome version`() {
        val webView =
            "Mozilla/5.0 (Linux; Android 16; SM-S908B Build/BP2A.250605.031; wv) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Version/4.0 Chrome/141.0.7390.122 Mobile Safari/537.36"
        val agent = SignInBrowser.chromeUserAgent(webView)

        assertEquals(
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/141.0.0.0 Mobile Safari/537.36",
            agent,
        )
        listOf("; wv", "Version/4.0", "Build/").forEach { assertFalse(it, agent.contains(it)) }
    }

    @Test
    fun `hand-overs to other apps are ignored, web pages load`() {
        assertTrue(SignInBrowser.isWebPage("https"))
        assertTrue(SignInBrowser.isWebPage("HTTP"))
        assertTrue(SignInBrowser.isWebPage("about"))
        assertFalse(SignInBrowser.isWebPage("fb"))
        assertFalse(SignInBrowser.isWebPage("intent"))
        assertFalse(SignInBrowser.isWebPage(null))
    }
}
