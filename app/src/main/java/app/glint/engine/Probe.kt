package app.glint.engine

import android.os.SystemClock
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runInterruptible

/** Reads links with yt-dlp. Recent answers are reused, so reopening the sheet is instant. */
object Prober {
    private const val CacheMillis = 10 * 60 * 1000L
    private const val CacheSize = 12
    private val cache = LinkedHashMap<String, Pair<Long, Probe>>()

    /**
     * Reads [url]. [fresh] skips the cache, for checking followed channels; [playlistEnd] reads
     * only the start of a long list.
     */
    suspend fun probe(url: String, wholePlaylist: Boolean, fresh: Boolean = false, playlistEnd: Int? = null): Probe {
        // Settings such as cookies and the video preference change the answer, so they are in the key.
        val args = DownloadArgs.probe(wholePlaylist, SettingsStore.current, CookieStore.path(), playlistEnd)
        val key = args.joinToString(" ") + " " + CookieStore.sites.value.joinToString(",") + " " + url
        if (!fresh) {
            synchronized(cache) {
                val cached = cache[key]
                if (cached != null && SystemClock.elapsedRealtime() - cached.first < CacheMillis) {
                    return cached.second
                }
            }
        }
        val engine = Engine.state.first { it is Engine.State.Ready || it is Engine.State.Failed }
        if (engine is Engine.State.Failed) throw IllegalStateException(engine.message)

        val probe =
            CookieStore.withRunCookies { cookies ->
                val runArgs = DownloadArgs.probe(wholePlaylist, SettingsStore.current, cookies, playlistEnd)
                runInterruptible(Dispatchers.IO) {
                    // Playlist entries are listed, not resolved one by one, so long lists stay fast.
                    val request = YoutubeDLRequest(url).apply { addCommands(runArgs) }
                    ProbeParser.parse(YoutubeDL.execute(request).out, url)
                }
            }
        synchronized(cache) {
            cache.remove(key)
            cache[key] = SystemClock.elapsedRealtime() to probe
            while (cache.size > CacheSize) cache.remove(cache.keys.first())
        }
        return probe
    }
}
