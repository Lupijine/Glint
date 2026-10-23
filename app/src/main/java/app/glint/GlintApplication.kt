package app.glint

import android.app.Application
import app.glint.engine.CommandStore
import app.glint.engine.CookieStore
import app.glint.engine.DownloadManager
import app.glint.engine.Engine
import app.glint.engine.FinishedNotifications
import app.glint.engine.Following
import app.glint.engine.SettingsStore
import app.glint.ui.theme.BundledArt
import app.glint.ui.theme.PhotoBackground

class GlintApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        // Settings come first: the engine and downloads read them as they start.
        SettingsStore.initialize(this)
        // The picture background, when chosen, decodes while the rest of the app starts.
        BundledArt.initialize(this)
        PhotoBackground.preload(this)
        CommandStore.initialize(this)
        CookieStore.initialize(this)
        FinishedNotifications.createChannel(this)
        Engine.initialize(this)
        DownloadManager.attach(this)
        Following.initialize(this)
    }
}
