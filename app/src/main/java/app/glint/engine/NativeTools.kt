package app.glint.engine

import android.content.Context
import java.io.File
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/**
 * Python and ffmpeg as the yt-dlp library unpacks them, for running other tools with them:
 * gallery-dl, ffmpeg to join a video and its sound, and ffprobe to measure them. The paths and environment match
 * youtubedl-android's own (library 0.17), which unpacks everything before yt-dlp first runs.
 */
internal object NativeTools {
    private const val LibraryFolder = "youtubedl-android"

    class Result(val exitCode: Int, val output: String, val errors: String)

    private fun nativeDir(context: Context) = File(context.applicationInfo.nativeLibraryDir)

    private fun packages(context: Context) = File(File(context.noBackupFilesDir, LibraryFolder), "packages")

    fun python(context: Context) = File(nativeDir(context), "libpython.so")

    fun ffmpeg(context: Context) = File(nativeDir(context), "libffmpeg.so")

    private fun ffprobe(context: Context) = File(nativeDir(context), "libffprobe.so")

    /** yt-dlp itself, a Python zip application that gallery-dl can import for some videos. */
    fun ytDlp(context: Context) = File(File(context.noBackupFilesDir, LibraryFolder), "yt-dlp/yt-dlp")

    private fun certificates(context: Context) = File(packages(context), "python/usr/etc/tls/cert.pem")

    fun environment(context: Context): Map<String, String> {
        val packages = packages(context)
        val pythonHome = File(packages, "python/usr").absolutePath
        return mapOf(
            "LD_LIBRARY_PATH" to
                listOf("python", "ffmpeg", "aria2c").joinToString(":") { File(packages, "$it/usr/lib").absolutePath },
            "SSL_CERT_FILE" to certificates(context).absolutePath,
            // requests, which gallery-dl uses, reads its own variable for the same file.
            "REQUESTS_CA_BUNDLE" to certificates(context).absolutePath,
            "PATH" to System.getenv("PATH") + ":" + nativeDir(context).absolutePath,
            "PYTHONHOME" to pythonHome,
            "HOME" to pythonHome,
            "TMPDIR" to context.cacheDir.absolutePath,
            "PYTHONIOENCODING" to "utf-8",
        )
    }

    /**
     * Runs [command] to the end, handing each line it prints to [onLine]. Cancelling the
     * coroutine stops the process.
     */
    suspend fun run(
        context: Context,
        command: List<String>,
        extraEnvironment: Map<String, String> = emptyMap(),
        onLine: (String) -> Unit = {},
    ): Result =
        withContext(Dispatchers.IO) {
            val process =
                ProcessBuilder(command)
                    .apply { environment().putAll(environment(context) + extraEnvironment) }
                    .start()
            try {
                val output = StringBuffer()
                val errors = StringBuffer()
                val outputReader =
                    thread(name = "runtime-out") {
                        runCatching {
                            process.inputStream.bufferedReader().forEachLine {
                                output.append(it).append('\n')
                                onLine(it)
                            }
                        }
                    }
                val errorReader =
                    thread(name = "runtime-err") {
                        runCatching { process.errorStream.bufferedReader().forEachLine { errors.append(it).append('\n') } }
                    }
                // Waiting is interruptible, so cancelling lands here and the process is stopped below.
                val exitCode = runInterruptible { process.waitFor() }
                outputReader.join()
                errorReader.join()
                Result(exitCode, output.toString(), errors.toString())
            } finally {
                process.destroy()
            }
        }

    /**
     * Keeps only the sound of [input]: as it is, or converted to [codec] ("mp3" or "opus").
     */
    suspend fun extractAudio(context: Context, input: File, output: File, codec: String? = null) {
        val encoding =
            when (codec) {
                "mp3" -> listOf("-c:a", "libmp3lame", "-q:a", "2")
                "opus" -> listOf("-c:a", "libopus", "-b:a", "128k")
                else -> listOf("-c:a", "copy")
            }
        val result =
            run(
                context,
                listOf(ffmpeg(context).absolutePath, "-y", "-loglevel", "error", "-i", input.absolutePath, "-vn", "-map", "0:a:0") +
                    encoding + output.absolutePath,
            )
        check(result.exitCode == 0 && output.isFile && output.length() > 0) {
            "ffmpeg could not take the sound out: ${result.errors.trim().takeLast(400)}"
        }
    }

    /** The length of a video or sound file in seconds, or null when ffprobe cannot tell. */
    suspend fun duration(context: Context, file: File): Double? {
        val probe = ffprobe(context).takeIf { it.isFile } ?: return null
        val result =
            run(
                context,
                listOf(
                    probe.absolutePath, "-v", "error", "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1", file.absolutePath,
                ),
            )
        return result.output.lineSequence().firstNotNullOfOrNull { it.trim().toDoubleOrNull() }?.takeIf { it > 0 }
    }

    /** Joins a video-only file and an audio-only file into [output] without re-encoding. */
    suspend fun mux(context: Context, video: File, audio: File, output: File) {
        val result =
            run(
                context,
                listOf(
                    ffmpeg(context).absolutePath, "-y", "-loglevel", "error",
                    "-i", video.absolutePath, "-i", audio.absolutePath,
                    "-map", "0:v:0", "-map", "1:a:0", "-c", "copy",
                    output.absolutePath,
                ),
            )
        check(result.exitCode == 0 && output.isFile && output.length() > 0) {
            "ffmpeg could not join the video and its sound: ${result.errors.trim().takeLast(400)}"
        }
    }
}
