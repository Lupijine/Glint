package app.glint

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import app.glint.engine.DownloadManager
import app.glint.engine.FinishedNotifications
import app.glint.ui.CrashReport
import app.glint.ui.GlintApp
import app.glint.ui.openDownload
import app.glint.ui.theme.GlintTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Every theme is dark, so the status and navigation bars always use light icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        // After a configuration change the tapped download was already opened.
        if (savedInstanceState == null) openDownloadFrom(intent)
        setContent {
            GlintTheme {
                GlintApp()
                CrashReport()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openDownloadFrom(intent)
    }

    override fun onStart() {
        super.onStart()
        // Results are on screen now, so the finished-downloads summary is no longer needed.
        FinishedNotifications.appVisible = true
        FinishedNotifications.clear(this)
    }

    override fun onStop() {
        FinishedNotifications.appVisible = false
        super.onStop()
    }

    /** Opens the file of a download whose notification was tapped. */
    private fun openDownloadFrom(intent: Intent?) {
        val id = intent?.getStringExtra(ExtraOpenDownload) ?: return
        intent.removeExtra(ExtraOpenDownload)
        lifecycleScope.launch {
            // After a restart, saved downloads take a moment to load.
            val item =
                withTimeoutOrNull(5_000) {
                    DownloadManager.items.first { list -> list.any { it.id == id } }.first { it.id == id }
                }
            if (item != null && item.hasFile) openDownload(this@MainActivity, item)
        }
    }

    companion object {
        const val ExtraOpenDownload = "app.glint.OPEN_DOWNLOAD"
    }
}
