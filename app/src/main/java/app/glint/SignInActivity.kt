package app.glint

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.graphics.Bitmap
import android.util.Log
import android.view.ContextThemeWrapper
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.glint.engine.CookieStore
import app.glint.engine.NetscapeCookies
import app.glint.ui.ChoiceRow
import app.glint.ui.GlassIconButton
import app.glint.ui.GlassPill
import app.glint.ui.SecondaryText
import app.glint.ui.icons.GlintIcons
import app.glint.ui.theme.GlintTheme
import kotlinx.coroutines.launch

/**
 * A small browser for signing in to sites. Its cookies are saved for yt-dlp, which then reaches
 * private, age-restricted and members-only videos the account can see.
 */
class SignInActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { GlintTheme { SignInScreen(onDone = ::finish) } }
    }
}

/** How the sign-in browser presents itself to sites. */
internal object SignInBrowser {
    /** Logcat tag: `adb logcat -s GlintSignIn` shows what the sign-in pages did. */
    const val Tag = "GlintSignIn"

    private val ChromeVersion = Regex("""Chrome/(\d+)""")

    /**
     * Chrome's own user agent on Android, with the browser's Chrome version: WebView adds "wv",
     * "Version/4.0" and the build number, which sites read as "a browser inside an app".
     */
    fun chromeUserAgent(webViewAgent: String): String {
        val major = ChromeVersion.find(webViewAgent)?.groupValues?.get(1) ?: "130"
        return "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/$major.0.0.0 Mobile Safari/537.36"
    }

    /** Pages the browser shows itself; anything else (fb://, intent://, market://) is another app. */
    fun isWebPage(scheme: String?): Boolean = scheme?.lowercase() in WebSchemes

    private val WebSchemes = setOf("https", "http", "about", "data", "blob")
}

private enum class Site(val label: String, val url: String) {
    YouTube("YouTube", "https://m.youtube.com/"),
    Instagram("Instagram", "https://www.instagram.com/accounts/login/"),
    TikTok("TikTok", "https://www.tiktok.com/login"),
    X("X", "https://x.com/i/flow/login"),
    Facebook("Facebook", "https://m.facebook.com/login/"),
    Reddit("Reddit", "https://www.reddit.com/login/"),
    Vimeo("Vimeo", "https://vimeo.com/log_in"),
    Twitch("Twitch", "https://www.twitch.tv/login"),
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SignInScreen(onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var site by rememberSaveable { mutableStateOf(Site.YouTube) }
    var progress by remember { mutableIntStateOf(0) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    // Why the page did not load, shown instead of a blank page.
    var loadError by remember { mutableStateOf<String?>(null) }
    // A new browser replaces one whose page crashed, the next time a site is chosen.
    var browser by remember { mutableIntStateOf(0) }
    var rendererGone by remember { mutableStateOf(false) }

    BackHandler(enabled = canGoBack) { webView?.goBack() }

    Column(
        modifier =
            Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(
                icon = GlintIcons.Close,
                contentDescription = stringResource(R.string.close),
                onClick = onDone,
                size = 42,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.sign_in_title),
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.sign_in_hint),
                    color = SecondaryText,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            val context = LocalContext.current
            GlassPill(
                text = stringResource(R.string.save_cookies),
                icon = GlintIcons.Check,
                tint = MaterialTheme.colorScheme.primary,
                onClick = {
                    val url = webView?.url ?: site.url
                    val host = Uri.parse(url).host.orEmpty()
                    val header = CookieManager.getInstance().getCookie(url).orEmpty()
                    scope.launch {
                        CookieManager.getInstance().flush()
                        val saved = if (host.isEmpty()) 0 else CookieStore.saveFromBrowser(host, header)
                        if (saved > 0) {
                            val message = context.getString(R.string.cookies_saved, NetscapeCookies.siteOf(host))
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            onDone()
                        } else {
                            Toast.makeText(context, R.string.cookies_nothing, Toast.LENGTH_LONG).show()
                        }
                    }
                },
            )
        }
        ChoiceRow(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            options = Site.entries,
            selected = site,
            label = { it.label },
            onSelect = {
                site = it
                if (rendererGone) {
                    rendererGone = false
                    loadError = null
                    browser++
                } else {
                    webView?.loadUrl(it.url)
                }
            },
        )
        loadError?.let {
            Text(
                text = stringResource(R.string.probe_failed) + " " + it,
                color = MaterialTheme.colorScheme.error,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        if (progress in 1..99) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent,
            )
        }
        key(browser) {
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { context ->
                    // Sign-in pages are made light first. Glint's dark theme would ask them for dark
                    // mode, which some draw as light text on the browser's white background.
                    WebView(ContextThemeWrapper(context, android.R.style.Theme_Material_Light_NoActionBar)).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // Some sign-in pages show nothing to browsers built into apps, so this one
                        // introduces itself exactly as Chrome on Android does.
                        settings.userAgentString = SignInBrowser.chromeUserAgent(settings.userAgentString)
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient =
                            object : WebViewClient() {
                                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                                    canGoBack = view.canGoBack()
                                }

                                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                    loadError = null
                                    Log.i(SignInBrowser.Tag, "Loading $url")
                                }

                                override fun onPageFinished(view: WebView, url: String?) {
                                    Log.i(SignInBrowser.Tag, "Loaded $url (${view.title})")
                                }

                                override fun onReceivedError(
                                    view: WebView,
                                    request: WebResourceRequest,
                                    error: WebResourceError,
                                ) {
                                    if (!request.isForMainFrame) return
                                    Log.w(SignInBrowser.Tag, "${request.url}: ${error.errorCode} ${error.description}")
                                    loadError = "(${error.description})"
                                }

                                override fun onReceivedHttpError(
                                    view: WebView,
                                    request: WebResourceRequest,
                                    response: WebResourceResponse,
                                ) {
                                    if (request.isForMainFrame) {
                                        Log.w(SignInBrowser.Tag, "${request.url}: HTTP ${response.statusCode}")
                                    }
                                }

                                // A crashed page must not take Glint down with it; choosing a site loads it again.
                                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                    Log.w(SignInBrowser.Tag, "The page's renderer stopped (crashed: ${detail.didCrash()})")
                                    loadError = "(renderer stopped)"
                                    rendererGone = true
                                    return true
                                }

                                // Sites try to hand over to their own app (fb://, intent://); staying
                                // on the web page is what signing in here needs.
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                    request.isForMainFrame && !SignInBrowser.isWebPage(request.url.scheme)
                            }
                        webChromeClient =
                            object : android.webkit.WebChromeClient() {
                                override fun onProgressChanged(view: WebView, newProgress: Int) {
                                    progress = newProgress
                                }

                                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                                    Log.i(SignInBrowser.Tag, "Page: ${message.message()} (${message.sourceId()}:${message.lineNumber()})")
                                    return true
                                }
                            }
                        loadUrl(site.url)
                        webView = this
                    }
                },
                onRelease = { it.destroy() },
            )
        }
    }
}
