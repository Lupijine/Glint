package app.glint.engine

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.Collections
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Opens a link in a browser no one sees, with the sign-in saved in Glint's browser, and finds
 * the video as the page plays it. Facebook and Instagram refuse tools such as yt-dlp more and
 * more, but a page in a real browser engine gets the same answer as a person's browser.
 */
internal object BrowserCapture {
    private const val Tag = "GlintCapture"

    class Capture(
        val plan: MediaPlan,
        val title: String?,
        val thumbnail: String?,
        val pageUrl: String,
        val userAgent: String,
    )

    /** Reads what a page shows: its HTML, its players' files, its title and picture. */
    private const val PageScript =
        """(function() {
            function meta(p) { var m = document.querySelector('meta[property="' + p + '"]'); return m ? m.content : ''; }
            var videos = [].slice.call(document.querySelectorAll('video')).map(function(v) { return v.currentSrc || v.src || ''; });
            return { html: document.documentElement.outerHTML, videos: videos,
                     title: meta('og:title') || document.title, image: meta('og:image') };
        })()"""

    /** Starts the players, without sound, so they fetch their files. */
    private const val PlayScript =
        """document.querySelectorAll('video').forEach(function(v) {
            v.muted = true; var p = v.play(); if (p && p.catch) p.catch(function() {});
        });"""

    /**
     * The video on [url], or null when none showed up within [timeoutMillis]. The page is read
     * every moment until a complete file is found; a video and sound pair is used if that is all
     * the page gives.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun find(context: Context, url: String, timeoutMillis: Long = 35_000): Capture? =
        withContext(Dispatchers.Main) {
            val fetched = Collections.synchronizedList(mutableListOf<String>())
            var loaded = false
            val web = WebView(context.applicationContext)
            try {
                // A desktop browser gets the full page, with its video data in it.
                val agent = desktopUserAgent(web.settings.userAgentString)
                web.settings.javaScriptEnabled = true
                web.settings.domStorageEnabled = true
                web.settings.mediaPlaybackRequiresUserGesture = false
                web.settings.userAgentString = agent
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
                web.webViewClient =
                    object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            MediaLinks.mediaUrl(request.url.toString())?.let { fetched += it }
                            return null
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            loaded = true
                        }

                        // Pages try to open their app (fb://, intent://); this browser stays on the web.
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                            request.isForMainFrame && request.url.scheme?.lowercase() !in setOf("https", "http")
                    }
                // A screen's size, so the page lays out its player as for a person.
                web.measure(
                    View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY),
                )
                web.layout(0, 0, 1280, 2000)
                web.onResume()
                web.resumeTimers()
                web.loadUrl(url)

                val deadline = SystemClock.elapsedRealtime() + timeoutMillis
                var pair: Capture? = null
                var rounds = 0
                while (SystemClock.elapsedRealtime() < deadline) {
                    delay(1500)
                    if (!loaded) continue
                    rounds++
                    val page = evaluate(web, PageScript)
                    web.evaluateJavascript(PlayScript, null)
                    val fromPage = MediaLinks.fromPage(page.html) + page.videos.filter { it.startsWith("http") }
                    val plan = MediaLinks.choose(fromPage, fetched.toList())
                    val capture = Capture(plan, page.title, page.image, web.url ?: url, agent)
                    when (plan) {
                        is MediaPlan.Single -> {
                            Log.i(Tag, "Found a complete video after $rounds reads (${fromPage.size} in the page)")
                            return@withContext capture
                        }
                        is MediaPlan.Separate -> {
                            // Give the page a moment more to name a complete file, then use the pair.
                            if (pair != null) {
                                Log.i(Tag, "Found a video and its sound apart")
                                return@withContext pair
                            }
                            pair = capture
                        }
                        MediaPlan.None -> Unit
                    }
                }
                Log.w(Tag, "No video found (page loaded: $loaded, files fetched: ${fetched.size})")
                pair
            } finally {
                web.stopLoading()
                web.destroy()
            }
        }

    private class Page(val html: String, val videos: List<String>, val title: String?, val image: String?)

    private suspend fun evaluate(web: WebView, script: String): Page {
        val raw = suspendCancellableCoroutine { continuation ->
            web.evaluateJavascript(script) { continuation.resume(it) }
        }
        val json = runCatching { JSONTokener(raw).nextValue() as? JSONObject }.getOrNull() ?: return Page("", emptyList(), null, null)
        val videos = json.optJSONArray("videos")
        return Page(
            html = json.optString("html"),
            videos = (0 until (videos?.length() ?: 0)).map { videos!!.optString(it) },
            title = json.optString("title").ifBlank { null },
            image = json.optString("image").ifBlank { null },
        )
    }

    /** Chrome on a computer, with the version of the phone's own browser engine. */
    fun desktopUserAgent(webViewAgent: String): String {
        val major = Regex("""Chrome/(\d+)""").find(webViewAgent)?.groupValues?.get(1) ?: "130"
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36"
    }
}
