package app.glint.engine

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Other ways to get a link when yt-dlp cannot: gallery-dl for pictures, albums and sites yt-dlp
 * does not read, and for Facebook and Instagram the hidden browser ([BrowserCapture]).
 */
internal object Fallbacks {
    private const val Tag = "GlintFallback"

    /** What another way saved, with what it learned about the post. */
    class Found(val files: List<File>, val title: String?, val thumbnail: String?)

    enum class Way { Browser, Gallery }

    private val YouTubeHosts = listOf("youtube.com", "youtu.be", "youtube-nocookie.com")
    private val FacebookHosts = listOf("facebook.com", "fb.watch", "fb.com")
    private val InstagramHosts = listOf("instagram.com")

    fun hostOf(url: String): String? =
        url.substringAfter("://", "").substringBefore('/').substringBefore('?').substringAfter('@').substringBefore(':')
            .lowercase().ifBlank { null }

    private fun String.isOn(sites: List<String>) = sites.any { this == it || endsWith(".$it") }

    /** "Facebook" for www.facebook.com, as yt-dlp names its sources. */
    fun siteName(url: String): String {
        val host = hostOf(url) ?: return ""
        val site = NetscapeCookies.siteOf(host).substringBefore('.')
        return site.replaceFirstChar { it.uppercase() }
    }

    /** The ways worth trying for [url], in order. */
    fun ways(url: String): List<Way> {
        val host = hostOf(url) ?: return emptyList()
        return when {
            host.isOn(YouTubeHosts) -> emptyList()
            // Facebook mostly blocks tools by how they connect, which the browser avoids.
            host.isOn(FacebookHosts) -> listOf(Way.Browser, Way.Gallery)
            // gallery-dl gets whole posts (every picture and video); the browser is the backup.
            host.isOn(InstagramHosts) -> listOf(Way.Gallery, Way.Browser)
            else -> listOf(Way.Gallery)
        }
    }

    /** Whether another way may succeed where yt-dlp failed with [message]. */
    fun mayHelp(item: DownloadItem, message: String?): Boolean {
        // Commands and clips are yt-dlp's own work.
        if (item.customArgs != null || item.sectionStart != null) return false
        if (ways(item.url).isEmpty()) return false
        return when (ErrorKind.of(message)) {
            ErrorKind.Network, ErrorKind.Storage, ErrorKind.Folder, ErrorKind.Drm, ErrorKind.NotStarted,
            ErrorKind.Geo, ErrorKind.RateLimited, ErrorKind.BotCheck -> false
            else -> true
        }
    }

    /**
     * Tries the other ways in turn, saving into [directory]. Null when none found anything;
     * [onProgress] gets 0 to 1 while a single file downloads, or null when the amount is unknown.
     */
    suspend fun run(
        context: Context,
        item: DownloadItem,
        directory: File,
        workDirectory: File,
        cookies: String?,
        settings: GlintSettings,
        onProgress: (Float?) -> Unit,
    ): Found? {
        for (way in ways(item.url)) {
            val found =
                try {
                    when (way) {
                        Way.Gallery -> viaGallery(item, directory, cookies, settings, onProgress)
                        Way.Browser -> viaBrowser(context, item, directory, workDirectory, onProgress)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(Tag, "$way did not work: ${error.message}")
                    null
                }
            if (found != null && found.files.isNotEmpty()) {
                Log.i(Tag, "$way saved ${found.files.size} file(s)")
                return found
            }
        }
        return null
    }

    private suspend fun viaGallery(
        item: DownloadItem,
        directory: File,
        cookies: String?,
        settings: GlintSettings,
        onProgress: (Float?) -> Unit,
    ): Found? {
        onProgress(null)
        val outcome = GalleryDl.download(item.url, directory, cookies, settings) { onProgress(null) }
        val inside = directory.canonicalFile
        val files = outcome.files.map { it.canonicalFile }.filter { it.isFile && it.parentFile == inside }
        if (files.isEmpty()) return null
        return Found(files, title = null, thumbnail = null)
    }

    private suspend fun viaBrowser(
        context: Context,
        item: DownloadItem,
        directory: File,
        workDirectory: File,
        onProgress: (Float?) -> Unit,
    ): Found? {
        onProgress(null)
        val capture = BrowserCapture.find(context, item.url) ?: return null
        val base = MediaLinks.fileName(capture.title ?: item.title.takeIf { it != item.url }, fallback = "video")
        val audioOnly = item.type == MediaType.Audio
        // The download id in the name keeps it apart until the final name is chosen.
        val target = File(directory, "$base [${item.id}]." + if (audioOnly) "m4a" else "mp4")
        val headers = mapOf("User-Agent" to capture.userAgent, "Referer" to capture.pageUrl)
        workDirectory.mkdirs()
        val plan = capture.plan
        when {
            plan is MediaPlan.None -> return null
            audioOnly && plan is MediaPlan.Separate -> fetch(plan.audio, target, headers, onProgress)
            audioOnly && plan is MediaPlan.Single -> {
                val whole = File(workDirectory, "browser-whole.mp4")
                fetch(plan.url, whole, headers, onProgress)
                NativeTools.extractAudio(context, whole, target)
                whole.delete()
            }
            plan is MediaPlan.Single -> fetch(plan.url, target, headers, onProgress)
            plan is MediaPlan.Separate -> {
                val video = File(workDirectory, "browser-video.mp4")
                val audio = File(workDirectory, "browser-audio.m4a")
                fetch(plan.video, video, headers) { onProgress(it?.let { part -> part * 0.8f }) }
                fetch(plan.audio, audio, headers) { onProgress(it?.let { part -> 0.8f + part * 0.2f }) }
                NativeTools.mux(context, video, audio, target)
                video.delete()
                audio.delete()
            }
        }
        return Found(listOf(target.canonicalFile), capture.title, capture.thumbnail)
    }

    /** Downloads one file the page pointed to, with the browser's sign-in. */
    private suspend fun fetch(url: String, target: File, headers: Map<String, String>, onProgress: (Float?) -> Unit) =
        withContext(Dispatchers.IO) {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 20_000
                connection.readTimeout = 30_000
                connection.instanceFollowRedirects = true
                headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                CookieManager.getInstance().getCookie(url)?.let { connection.setRequestProperty("Cookie", it) }
                val code = connection.responseCode
                check(code in 200..299) { "The site answered HTTP $code for the video file" }
                val total = connection.contentLengthLong
                var done = 0L
                val temporary = File(target.parentFile, target.name + ".part")
                connection.inputStream.use { input ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(1 shl 16)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            done += read
                            onProgress(if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null)
                        }
                    }
                }
                check(done > 0) { "The video file was empty" }
                if (!temporary.renameTo(target)) {
                    temporary.copyTo(target, overwrite = true)
                    temporary.delete()
                }
            } finally {
                connection.disconnect()
            }
        }
}
