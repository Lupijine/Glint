package app.glint

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.glint.engine.DownloadManager
import app.glint.engine.Links
import app.glint.ui.NewDownloadSheet
import app.glint.ui.theme.ShareGlassDialog
import app.glint.ui.theme.GlintTheme
import kotlinx.coroutines.delay

/**
 * Opens the new download sheet over the app a link was shared from, without leaving it.
 * A bounded native dialog frosts the other app on devices that support system window blur.
 */
class ShareActivity : ComponentActivity() {
    private val shared = mutableStateOf("")
    // Each share starts a fresh sheet, also when one is already open.
    private val session = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // The sheet animates its own content; the host window stays transparent.
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
        shared.value = textFrom(intent)
        setContent {
            GlintTheme {
                key(session.intValue) {
                    ShareSheet(initialText = shared.value, onFinished = ::finish)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        shared.value = textFrom(intent)
        session.intValue++
    }

    override fun finish() {
        super.finish()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            @Suppress("DEPRECATION") overridePendingTransition(0, 0)
        }
    }

    /** The shared link, or the shared words to search for when there is no link. */
    private fun textFrom(intent: Intent?): String {
        if (intent?.action != Intent.ACTION_SEND) return ""
        val text =
            listOfNotNull(
                    intent.getCharSequenceExtra(Intent.EXTRA_TEXT),
                    intent.getStringExtra(Intent.EXTRA_SUBJECT),
                )
                .joinToString(" ")
        return Links.first(text) ?: text.trim()
    }
}

@Composable
private fun ShareSheet(initialText: String, onFinished: () -> Unit) {
    val context = LocalContext.current
    var visible by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    val reveal by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(220),
        label = "share sheet reveal",
    )
    fun close() {
        if (closing) return
        visible = false
        closing = true
    }
    val notificationPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { close() }

    LaunchedEffect(Unit) { visible = true }
    LaunchedEffect(closing) {
        if (closing) {
            delay(240)
            onFinished()
        }
    }

    ShareGlassDialog(onDismissRequest = ::close) {
        // Keep the window's measured size stable throughout the animation: an
        // AnimatedVisibility layout would collapse its native blur rectangle.
        Box(Modifier.graphicsLayer {
            alpha = reveal
            translationY = (1f - reveal) * 18.dp.toPx()
        }) {
            NewDownloadSheet(
                initialUrl = initialText,
                onClose = ::close,
                windowManagedInsets = true,
                onDownload = { requests ->
                    DownloadManager.enqueue(requests)
                    Toast.makeText(context, R.string.download_added, Toast.LENGTH_SHORT).show()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                            PackageManager.PERMISSION_GRANTED) {
                        // Progress shows in a notification, so ask first; the sheet closes after.
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        close()
                    }
                },
            )
        }
    }
}
