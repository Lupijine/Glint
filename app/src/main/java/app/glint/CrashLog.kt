package app.glint

import android.content.Context
import android.os.Build
import java.io.File

/**
 * Keeps the error behind the last crash, so Glint can show it on the next launch and it can
 * be copied into a bug report.
 */
object CrashLog {
    private const val FileName = "last-crash.txt"
    private const val MaxLength = 24_000

    fun install(context: Context) {
        val file = File(context.filesDir, FileName)
        val version = runCatching {
                @Suppress("DEPRECATION") context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }
            .getOrNull()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val report = buildString {
                    appendLine("Glint $version")
                    appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("Thread: ${thread.name}")
                    appendLine()
                    append(error.stackTraceToString())
                }
                file.writeText(report.take(MaxLength))
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The saved crash report, removed once read, or null when Glint did not crash. */
    fun take(context: Context): String? {
        val file = File(context.filesDir, FileName)
        if (!file.isFile) return null
        val text = runCatching { file.readText() }.getOrNull()
        file.delete()
        return text?.takeIf { it.isNotBlank() }
    }
}
