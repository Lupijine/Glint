package app.glint.ui.theme

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** Render tests must not unpack yt-dlp or start downloads and background workers. */
class GlassTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application = super.newApplication(cl, Application::class.java.name, context)
}
