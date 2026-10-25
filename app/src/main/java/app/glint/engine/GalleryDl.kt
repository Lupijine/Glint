package app.glint.engine

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * gallery-dl, a second downloader for what yt-dlp does not get: pictures, albums and posts on
 * Instagram, X, Pinterest, Reddit, Tumblr, Imgur and many more sites. It ships with Glint as
 * Python wheels (assets/gallery-dl) and runs on the Python that comes with yt-dlp.
 */
object GalleryDl {
    private const val Tag = "GlintGallery"
    private const val Assets = "gallery-dl"
    private const val PyPi = "https://pypi.org/pypi/gallery-dl/json"

    private lateinit var appContext: Context
    private val installLock = Mutex()

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    private fun home() = File(appContext.noBackupFilesDir, "gallery-dl")

    /** The installed gallery-dl version, or null before it is installed. */
    fun version(): String? =
        if (!::appContext.isInitialized) null
        else runCatching { File(home(), "version.txt").readText().trim() }.getOrNull()?.ifBlank { null }

    /** The files a run saved, and how gallery-dl ended (0 is success; see [GalleryDlOutput]). */
    class Outcome(val files: List<File>, val exitCode: Int, val errors: String)

    /**
     * Saves everything at [url] into [directory]. [onFile] is called with the number of files
     * saved so far. Cancelling the coroutine stops gallery-dl.
     */
    suspend fun download(
        url: String,
        directory: File,
        cookies: String?,
        settings: GlintSettings,
        onFile: (Int) -> Unit,
    ): Outcome {
        install()
        val home = home()
        val target = TumblrLinks.resolve(url)
        val command = buildList {
            add(NativeTools.python(appContext).absolutePath)
            add(File(home, "launcher.py").absolutePath)
            addAll(GalleryDlOutput.arguments(directory, cookies, settings, NativeTools.ffmpeg(appContext)))
            add(target)
        }
        val files = mutableListOf<File>()
        val result =
            NativeTools.run(
                appContext,
                command,
                mapOf("GLINT_YTDLP" to NativeTools.ytDlp(appContext).absolutePath),
            ) { line ->
                val file = GalleryDlOutput.savedFile(line)?.let(::File)?.takeIf { it.isFile } ?: return@run
                synchronized(files) {
                    if (file !in files) files += file
                    onFile(files.size)
                }
            }
        if (result.exitCode != 0) {
            // Its own words say why; no addresses or names beyond the link's site are in them.
            Log.w(Tag, "gallery-dl ended with ${result.exitCode}: ${result.errors.trim().takeLast(1500)}")
        }
        return Outcome(synchronized(files) { files.toList() }, result.exitCode, result.errors)
    }

    /**
     * What gallery-dl finds at [url], without downloading it: at most [limit] files are counted.
     * Throws [Fallbacks.Failed] with gallery-dl's reason when it finds nothing.
     */
    suspend fun list(url: String, cookies: String?, settings: GlintSettings, limit: Int = 100): GalleryListing {
        install()
        val command = buildList {
            add(NativeTools.python(appContext).absolutePath)
            add(File(home(), "launcher.py").absolutePath)
            addAll(GalleryDlOutput.listArguments(cookies, settings, limit))
            add(TumblrLinks.resolve(url))
        }
        val result =
            withTimeoutOrNull(45_000) {
                NativeTools.run(appContext, command, mapOf("GLINT_YTDLP" to NativeTools.ytDlp(appContext).absolutePath))
            } ?: throw Fallbacks.Failed("gallery-dl took too long to read this link")
        if (result.errors.isNotBlank()) Log.w(Tag, "gallery-dl listing said: ${result.errors.trim().takeLast(1500)}")
        return GalleryDlOutput.listing(result.output, result.errors, result.exitCode, limit)
    }

    /** Updates gallery-dl from PyPI. True when a newer version was installed. */
    suspend fun update(): Boolean =
        withContext(Dispatchers.IO) {
            install()
            val release = GalleryDlRelease.parse(fetchText(PyPi)) ?: return@withContext false
            val installed = version()
            if (installed != null && !GalleryDlRelease.isNewer(release.version, installed)) return@withContext false
            installLock.withLock {
                val home = home()
                val temporary = File(home, "gallery_dl.whl.tmp")
                fetchFile(release.wheelUrl, temporary)
                val digest = sha256(temporary)
                if (!digest.equals(release.sha256, ignoreCase = true)) {
                    temporary.delete()
                    error("The downloaded gallery-dl does not match PyPI's checksum")
                }
                val wheel = File(home, "gallery_dl.whl")
                if (!temporary.renameTo(wheel)) {
                    temporary.copyTo(wheel, overwrite = true)
                    temporary.delete()
                }
                File(home, "version.txt").writeText(release.version)
                Log.i(Tag, "Updated gallery-dl to ${release.version}")
            }
            true
        }

    /**
     * Copies the bundled gallery-dl into place: on first use, after Glint is updated with a newer
     * bundle, and whenever a file is missing. A newer gallery-dl from PyPI is kept.
     */
    private suspend fun install() =
        withContext(Dispatchers.IO) {
            installLock.withLock {
                val home = home()
                val assets = appContext.assets
                val bundled = assets.open("$Assets/version.txt").bufferedReader().use { it.readText().trim() }
                val deps = assets.list("$Assets/deps").orEmpty().toList()
                val complete =
                    File(home, "launcher.py").isFile && File(home, "gallery_dl.whl").isFile &&
                        deps.all { File(home, "deps/$it").isFile }
                val bundleFile = File(home, "bundle.txt")
                val installedBundle = runCatching { bundleFile.readText().trim() }.getOrNull()
                if (complete && installedBundle == bundled) return@withLock
                File(home, "deps").mkdirs()
                fun copy(name: String) {
                    val target = File(home, name)
                    val temporary = File(home, "$name.tmp")
                    assets.open("$Assets/$name").use { input -> temporary.outputStream().use { input.copyTo(it) } }
                    if (!temporary.renameTo(target)) {
                        temporary.copyTo(target, overwrite = true)
                        temporary.delete()
                    }
                }
                copy("launcher.py")
                deps.forEach { copy("deps/$it") }
                val installed = version()
                if (!File(home, "gallery_dl.whl").isFile || installed == null ||
                    GalleryDlRelease.isNewer(bundled, installed)) {
                    copy("gallery_dl.whl")
                    File(home, "version.txt").writeText(bundled)
                }
                bundleFile.writeText(bundled)
            }
        }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "Glint")
        }

    private fun fetchText(url: String): String {
        val connection = open(url)
        try {
            check(connection.responseCode == 200) { "PyPI answered HTTP ${connection.responseCode}" }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchFile(url: String, target: File) {
        val connection = open(url)
        try {
            check(connection.responseCode == 200) { "PyPI answered HTTP ${connection.responseCode}" }
            connection.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** How Glint runs gallery-dl and reads what it prints. */
internal object GalleryDlOutput {
    /** Exit status bits gallery-dl uses. */
    const val NeedsSignIn = 16
    const val Unsupported = 64

    fun arguments(directory: File, cookies: String?, settings: GlintSettings, ffmpeg: File): List<String> = buildList {
        // Only Glint's choices: no configuration file from anywhere else.
        add("--config-ignore")
        addAll(listOf("-D", directory.absolutePath))
        // One saved file per line ("# " before files that were already there).
        addAll(listOf("-o", "output.mode=pipe"))
        // Names that every storage accepts, memory cards included.
        addAll(listOf("-o", "path-restrict=windows"))
        addAll(listOf("-o", "downloader.ytdl.raw-options=" + JSONObject(mapOf("ffmpeg_location" to ffmpeg.absolutePath))))
        addAll(SiteOptions)
        if (cookies != null) addAll(listOf("--cookies", cookies))
        settings.proxy.trim().takeIf { it.isNotEmpty() }?.let { addAll(listOf("--proxy", it)) }
        settings.speedLimit.trim().takeIf { it.isNotEmpty() && GlintSettings.isValidSpeedLimit(it) }?.let {
            addAll(listOf("--limit-rate", it))
        }
    }

    /**
     * Choices for single sites. Tumblr: gallery-dl asks for each picture's full size, larger
     * than the 2048 pixels Tumblr shows; when Tumblr refuses, it waits two minutes, twice, for
     * every picture, so a post of a few pictures seemed never to finish. The shown size is used.
     */
    private val SiteOptions = listOf("-o", "extractor.tumblr.original=false")

    /** Lists what is at a link as JSON, without downloading, counting up to [limit] files. */
    fun listArguments(cookies: String?, settings: GlintSettings, limit: Int): List<String> = buildList {
        add("--config-ignore")
        // Albums and profiles lead to their posts; those are read too.
        add("--resolve-json")
        // One file past the limit says whether there are more.
        addAll(listOf("--range", "1-${limit + 1}"))
        addAll(listOf("--retries", "1"))
        addAll(listOf("--http-timeout", "20"))
        addAll(SiteOptions)
        if (cookies != null) addAll(listOf("--cookies", cookies))
        settings.proxy.trim().takeIf { it.isNotEmpty() }?.let { addAll(listOf("--proxy", it)) }
    }

    private val VideoExtensions = setOf("mp4", "webm", "mov", "m4v", "mkv", "avi", "flv", "m3u8", "ts", "3gp")
    private val TitleKeys = listOf("title", "description", "content", "caption", "summary", "text")
    private val UserKeys = listOf("fullname", "username", "uploader", "user", "author", "owner", "artist")
    private val NameKeys = listOf("name", "nick", "nickname", "username")
    private val PictureKeys = listOf("display_url", "thumbnail", "thumbnail_url", "preview")

    /**
     * What gallery-dl's JSON listing holds. Throws [Fallbacks.Failed] with its reason when it
     * holds no files.
     */
    fun listing(output: String, errors: String, exitCode: Int, limit: Int): GalleryListing {
        val start = output.indexOf('[')
        val messages = if (start < 0) JSONArray() else runCatching { JSONArray(output.substring(start)) }.getOrDefault(JSONArray())
        var files = 0
        var pictures = 0
        var videos = 0
        var problem: String? = null
        var title: String? = null
        var user: String? = null
        var thumbnail: String? = null
        var site: String? = null
        for (i in 0 until messages.length()) {
            val message = messages.optJSONArray(i) ?: continue
            val details = message.optJSONObject(message.length() - 1) ?: JSONObject()
            when (message.optInt(0)) {
                -1 -> problem = listOf(details.optString("error"), details.optString("message").trim())
                    .filter { it.isNotEmpty() }.joinToString(": ")
                2 -> Unit
                3, 6 -> {
                    files++
                    val address = message.optString(1)
                    val extension = details.optString("extension").lowercase().ifEmpty {
                        address.substringBefore('?').substringAfterLast('.', "").lowercase()
                    }
                    when {
                        message.optInt(0) == 6 -> Unit
                        address.startsWith("ytdl:") || extension in VideoExtensions -> videos++
                        DownloadOutput.isPicture("x.$extension") -> {
                            pictures++
                            if (thumbnail == null && address.startsWith("https://")) thumbnail = address
                        }
                    }
                }
                else -> continue
            }
            if (site == null) site = details.optString("category").takeIf { it.isNotEmpty() && it != "directlink" }
            if (title == null) title = TitleKeys.firstNotNullOfOrNull { key -> firstLine(details.opt(key)) }
            if (user == null) user = UserKeys.firstNotNullOfOrNull { key -> nameOf(details.opt(key)) }
            if (thumbnail == null) {
                thumbnail = PictureKeys.firstNotNullOfOrNull { key -> details.optString(key).takeIf { it.startsWith("https://") } }
            }
        }
        if (files == 0) throw Fallbacks.Failed(problem ?: failure(errors, exitCode))
        return GalleryListing(
            files = minOf(files, limit),
            pictures = pictures,
            videos = videos,
            more = files > limit,
            title = title ?: user,
            thumbnail = thumbnail,
            site = site?.replaceFirstChar { it.uppercase() },
        )
    }

    private fun firstLine(value: Any?): String? {
        val line = (value as? String)?.lines()?.firstOrNull { it.isNotBlank() }?.trim() ?: return null
        return if (line.length > 150) line.take(150).trimEnd() + "…" else line
    }

    private fun nameOf(value: Any?): String? =
        when (value) {
            is String -> value.trim().ifEmpty { null }
            is JSONObject -> NameKeys.firstNotNullOfOrNull { key -> value.optString(key).trim().ifEmpty { null } }
            else -> null
        }

    /** Why gallery-dl got nothing, from what it printed and how it ended. */
    fun failure(errors: String, exitCode: Int): String {
        val lines = errors.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val reported = lines.lastOrNull { it.contains("[error]") } ?: lines.lastOrNull()
        return reported
            ?: when {
                exitCode and Unsupported != 0 -> "Unsupported URL"
                exitCode and NeedsSignIn != 0 -> "gallery-dl: this link requires authentication"
                else -> "gallery-dl found nothing to download at this link"
            }
    }

    /** The path of a file gallery-dl saved or already had, from one line it printed. */
    fun savedFile(line: String): String? {
        val path = line.removePrefix("# ").trim()
        return path.takeIf { it.startsWith("/") }
    }
}

/** What gallery-dl found at a link; [more] when it holds more than the [files] counted. */
class GalleryListing(
    val files: Int,
    val pictures: Int,
    val videos: Int,
    val more: Boolean,
    val title: String?,
    val thumbnail: String?,
    val site: String?,
)

/** The newest gallery-dl on PyPI. */
internal class GalleryDlRelease(val version: String, val wheelUrl: String, val sha256: String) {
    companion object {
        /** Reads PyPI's JSON for gallery-dl; null when it holds no plain Python wheel. */
        fun parse(json: String): GalleryDlRelease? {
            val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
            val version = root.optJSONObject("info")?.optString("version").orEmpty()
            val urls = root.optJSONArray("urls") ?: return null
            for (i in 0 until urls.length()) {
                val file = urls.optJSONObject(i) ?: continue
                val name = file.optString("filename")
                if (file.optString("packagetype") != "bdist_wheel" || !name.endsWith("-py3-none-any.whl")) continue
                val url = file.optString("url")
                val sha = file.optJSONObject("digests")?.optString("sha256").orEmpty()
                if (version.isNotEmpty() && url.startsWith("https://") && sha.isNotEmpty()) {
                    return GalleryDlRelease(version, url, sha)
                }
            }
            return null
        }

        /** True when version [a] is newer than [b], comparing numbers part by part. */
        fun isNewer(a: String, b: String): Boolean {
            val left = a.split('.').map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            val right = b.split('.').map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(left.size, right.size)) {
                val x = left.getOrElse(i) { 0 }
                val y = right.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
