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
    class Found(val files: List<File>, val title: String?, val thumbnail: String?, val durationSeconds: Int = 0)

    /** A way that was chosen and could not get the link, saying why. */
    class Failed(message: String) : Exception(message)

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

    /** Whether gallery-dl may read [url]: any site but YouTube, which is yt-dlp's. */
    fun galleryMayRead(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return !host.isOn(YouTubeHosts) && host.contains('.')
    }

    /** Whether another way may succeed where yt-dlp failed to read [url] with [message]. */
    fun mayHelp(url: String, message: String?): Boolean {
        if (ways(url).isEmpty()) return false
        return when (ErrorKind.of(message)) {
            ErrorKind.Network, ErrorKind.Storage, ErrorKind.Folder, ErrorKind.Drm, ErrorKind.NotStarted,
            ErrorKind.Geo, ErrorKind.RateLimited, ErrorKind.BotCheck -> false
            else -> true
        }
    }

    /** Whether another way may succeed where yt-dlp failed to download [item] with [message]. */
    fun mayHelp(item: DownloadItem, message: String?): Boolean {
        // Commands and clips are yt-dlp's own work, and a chosen way is not followed by others.
        if (item.tool != Tool.YtDlp || item.customArgs != null || item.sectionStart != null) return false
        return mayHelp(item.url, message)
    }

    /**
     * Downloads [item] with the way chosen for it ([DownloadItem.tool]). Throws [Failed] with
     * the reason when it gets nothing.
     */
    suspend fun runChosen(
        context: Context,
        item: DownloadItem,
        directory: File,
        workDirectory: File,
        cookies: String?,
        settings: GlintSettings,
        onProgress: (Float?) -> Unit,
    ): Found =
        when (item.tool) {
            Tool.GalleryDl -> viaGallery(item, directory, cookies, settings, onProgress, explain = true)
            Tool.Browser -> viaBrowser(context, item, directory, workDirectory, onProgress)
            Tool.YtDlp -> null
        } ?: throw Failed("Glint's browser found no video on this page")

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
        explain: Boolean = false,
    ): Found? {
        onProgress(null)
        val outcome = GalleryDl.download(item.url, directory, cookies, settings) { onProgress(null) }
        val inside = directory.canonicalFile
        val files = outcome.files.map { it.canonicalFile }.filter { it.isFile && it.parentFile == inside }
        if (files.isNotEmpty()) return Found(files, title = null, thumbnail = null)
        if (explain) throw Failed(GalleryDlOutput.failure(outcome.errors, outcome.exitCode))
        return null
    }

    /** A title for a video whose page gave none: the site and the video's id. */
    fun untitled(pageUrl: String, fallback: String = "video"): String {
        val site = siteName(pageUrl)
        val id = MediaLinks.targetOf(pageUrl)
        return listOfNotNull(site.ifEmpty { null }, id).joinToString(" ").ifEmpty { fallback }
    }

    private suspend fun viaBrowser(
        context: Context,
        item: DownloadItem,
        directory: File,
        workDirectory: File,
        onProgress: (Float?) -> Unit,
    ): Found? {
        onProgress(null)
        val quality = BrowserQuality.entries.firstOrNull { it.name == item.formatSelector } ?: BrowserQuality.Best
        val capture = BrowserCapture.find(context, item.url, quality) ?: return null
        val plan = capture.plan
        if (plan is MediaPlan.None) return null
        // The title the person saw in the new download sheet, else the page's.
        val title =
            item.title.takeIf { it.isNotBlank() && it != item.url } ?: capture.title ?: untitled(capture.pageUrl)
        val base = MediaLinks.fileName(title, fallback = "video")
        val headers = mapOf("User-Agent" to capture.userAgent, "Referer" to capture.pageUrl)
        workDirectory.mkdirs()
        // The download id in the name keeps it apart until the final name is chosen.
        fun named(extension: String) = File(directory, "$base [${item.id}].$extension")

        /** The sound of [input] in the chosen audio format, or as it is if converting fails. */
        suspend fun sound(input: File): File {
            val codec = item.audioFormat.codec?.takeIf { it != "m4a" }
            if (codec != null) {
                val converted = named(codec)
                val made = try {
                    NativeTools.extractAudio(context, input, converted, codec)
                    true
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(Tag, "Could not convert the sound to $codec: ${error.message}")
                    converted.delete()
                    false
                }
                if (made) return converted
            }
            return named("m4a").also { NativeTools.extractAudio(context, input, it) }
        }

        val saved: File =
            if (item.type == MediaType.Audio) {
                val source = File(workDirectory, "browser-source.mp4")
                fetch(if (plan is MediaPlan.Separate) plan.audio else (plan as MediaPlan.Single).url, source, headers, onProgress)
                sound(source).also { source.delete() }
            } else if (plan is MediaPlan.Separate) {
                val video = File(workDirectory, "browser-video.mp4")
                val audio = File(workDirectory, "browser-audio.m4a")
                fetch(plan.video, video, headers) { onProgress(it?.let { part -> part * 0.8f }) }
                fetch(plan.audio, audio, headers) { onProgress(it?.let { part -> 0.8f + part * 0.2f }) }
                // A picture and a sound of different lengths belong to different videos.
                val videoLength = NativeTools.duration(context, video)
                val audioLength = NativeTools.duration(context, audio)
                if (videoLength != null && audioLength != null && !MediaLinks.sameLength(videoLength, audioLength)) {
                    video.delete()
                    audio.delete()
                    throw Failed("The picture and the sound on the page belong to different videos")
                }
                named("mp4").also {
                    NativeTools.mux(context, video, audio, it)
                    video.delete()
                    audio.delete()
                }
            } else {
                named("mp4").also { fetch((plan as MediaPlan.Single).url, it, headers, onProgress) }
            }
        return Found(listOf(saved.canonicalFile), title, capture.thumbnail, capture.durationSeconds)
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
