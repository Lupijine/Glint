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
        val command = buildList {
            add(NativeTools.python(appContext).absolutePath)
            add(File(home, "launcher.py").absolutePath)
            addAll(GalleryDlOutput.arguments(directory, cookies, settings, NativeTools.ffmpeg(appContext)))
            add(url)
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
        if (result.exitCode != 0) Log.w(Tag, "gallery-dl ended with ${result.exitCode}")
        return Outcome(synchronized(files) { files.toList() }, result.exitCode, result.errors)
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
        if (cookies != null) addAll(listOf("--cookies", cookies))
        settings.proxy.trim().takeIf { it.isNotEmpty() }?.let { addAll(listOf("--proxy", it)) }
        settings.speedLimit.trim().takeIf { it.isNotEmpty() && GlintSettings.isValidSpeedLimit(it) }?.let {
            addAll(listOf("--limit-rate", it))
        }
    }

    /** The path of a file gallery-dl saved or already had, from one line it printed. */
    fun savedFile(line: String): String? {
        val path = line.removePrefix("# ").trim()
        return path.takeIf { it.startsWith("/") }
    }
}

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
