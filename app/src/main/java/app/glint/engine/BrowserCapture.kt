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

    /**
     * What the page showed: how to get its video, its title, picture and length in seconds (0
     * when unknown), and whether it offers a smaller quality.
     */
    class Capture(
        val plan: MediaPlan,
        val title: String?,
        val thumbnail: String?,
        val durationSeconds: Int,
        val offersSmall: Boolean,
        val pageUrl: String,
        val userAgent: String,
    )

    /**
     * Reads what a page shows: its HTML, its title and picture, and the video on screen, which
     * it plays without sound so the player fetches its files. Every other video is paused, so
     * the files fetched are the wanted video's and not a suggested one's.
     */
    private const val PageScript =
        """(function() {
            function meta(p) {
                var m = document.querySelector('meta[property="' + p + '"]') || document.querySelector('meta[name="' + p + '"]');
                return m ? (m.content || '') : '';
            }
            var list = [].slice.call(document.querySelectorAll('video'));
            var w = window.innerWidth || 1280, h = window.innerHeight || 2000;
            var main = null, shown = 0;
            list.forEach(function(v) {
                var r = v.getBoundingClientRect();
                var area = Math.max(0, Math.min(r.right, w) - Math.max(r.left, 0)) * Math.max(0, Math.min(r.bottom, h) - Math.max(r.top, 0));
                if (area > shown) { shown = area; main = v; }
            });
            if (!main && list.length) main = list[0];
            list.forEach(function(v) {
                if (v === main) { v.muted = true; var p = v.play(); if (p && p.catch) p.catch(function() {}); }
                else { try { v.pause(); } catch (e) {} }
            });
            return { html: document.documentElement.outerHTML,
                     video: main ? (main.currentSrc || main.src || '') : '',
                     poster: main ? (main.poster || '') : '',
                     duration: main && isFinite(main.duration) ? main.duration : 0,
                     title: meta('og:title'), description: meta('og:description') || meta('description'),
                     documentTitle: document.title, image: meta('og:image') };
        })()"""

    /**
     * The video on [url], or null when none showed up within [timeoutMillis]. The page is read
     * every moment: a file Glint is sure is the wanted video's is used at once (a video and sound
     * pair after one more look, in case the page names a complete file); otherwise the page gets
     * some time, and what it kept offering is used.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun find(
        context: Context,
        url: String,
        quality: BrowserQuality = BrowserQuality.Best,
        timeoutMillis: Long = 35_000,
    ): Capture? =
        withContext(Dispatchers.Main) {
            // One entry per request: the video that played the longest is fetched the most.
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
                var guess: Capture? = null
                var steady = 0
                var rounds = 0
                while (SystemClock.elapsedRealtime() < deadline) {
                    delay(1500)
                    if (!loaded) continue
                    rounds++
                    val page = evaluate(web, PageScript)
                    val pageUrl = web.url ?: url
                    // Share links lead to the video's own address, which names it.
                    val target = MediaLinks.targetOf(pageUrl) ?: MediaLinks.targetOf(url)
                    val files = buildList {
                        // The video on screen playing a plain file is the surest of all.
                        page.video.takeIf { it.startsWith("http") }?.let { add(MediaLinks.PageFile(it, small = false, distance = 0)) }
                        addAll(MediaLinks.filesInPage(page.html, target))
                    }
                    val choice = MediaLinks.choose(files, fetched.toList(), target, quality)
                    val capture =
                        Capture(
                            plan = choice.plan,
                            title = titleOf(page, target),
                            thumbnail =
                                listOf(page.image, page.poster).firstOrNull { it?.startsWith("https://") == true }
                                    ?: MediaLinks.thumbnailInPage(page.html, target),
                            durationSeconds = page.duration.toInt(),
                            offersSmall = choice.offersSmall,
                            pageUrl = pageUrl,
                            userAgent = agent,
                        )
                    val plan = choice.plan
                    when {
                        plan is MediaPlan.None -> Unit
                        choice.sure && plan is MediaPlan.Single -> {
                            Log.i(Tag, "Found the video after $rounds reads (wanted: ${target != null})")
                            return@withContext capture
                        }
                        choice.sure -> {
                            if (pair?.plan == plan) {
                                Log.i(Tag, "Found the video and its sound apart")
                                return@withContext capture
                            }
                            pair = capture
                        }
                        else -> {
                            // Not sure it is the wanted video: let the page settle, then take
                            // what it kept offering.
                            steady = if (guess?.plan == plan) steady + 1 else 0
                            guess = capture
                            if (rounds >= 6 && steady >= 2) {
                                Log.i(Tag, "Took the video the page kept playing after $rounds reads")
                                return@withContext capture
                            }
                        }
                    }
                }
                Log.w(Tag, "No sure video (page loaded: $loaded, files fetched: ${fetched.size})")
                pair ?: guess
            } finally {
                web.stopLoading()
                web.destroy()
            }
        }

    /** The video's title: the page's own, else its caption in the page's data, else its description. */
    private fun titleOf(page: Page, target: String?): String? {
        val title =
            MediaLinks.cleanTitle(page.title)
                ?: MediaLinks.captionInPage(page.html, target)
                ?: MediaLinks.cleanTitle(page.description)
                ?: MediaLinks.cleanTitle(page.documentTitle)
                ?: return null
        val line = title.lines().first().trim()
        return if (line.length > 150) line.take(150).trimEnd() + "…" else line
    }

    private class Page(
        val html: String,
        val video: String,
        val poster: String,
        val duration: Double,
        val title: String?,
        val description: String?,
        val documentTitle: String?,
        val image: String?,
    )

    private suspend fun evaluate(web: WebView, script: String): Page {
        val raw = suspendCancellableCoroutine { continuation ->
            web.evaluateJavascript(script) { continuation.resume(it) }
        }
        val json = runCatching { JSONTokener(raw).nextValue() as? JSONObject }.getOrNull()
        fun text(name: String) = json?.optString(name).orEmpty().ifBlank { null }
        return Page(
            html = json?.optString("html").orEmpty(),
            video = json?.optString("video").orEmpty(),
            poster = json?.optString("poster").orEmpty(),
            duration = json?.optDouble("duration")?.takeIf { it.isFinite() && it > 0 } ?: 0.0,
            title = text("title"),
            description = text("description"),
            documentTitle = text("documentTitle"),
            image = text("image"),
        )
    }

    /** Chrome on a computer, with the version of the phone's own browser engine. */
    fun desktopUserAgent(webViewAgent: String): String {
        val major = Regex("""Chrome/(\d+)""").find(webViewAgent)?.groupValues?.get(1) ?: "130"
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36"
    }
}
