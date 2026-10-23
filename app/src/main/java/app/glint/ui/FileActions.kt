package app.glint.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import app.glint.R
import app.glint.engine.DownloadItem
import app.glint.engine.MediaType
import java.io.File

internal fun formatDuration(seconds: Int): String =
    if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds % 3600 / 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)

internal fun formatSize(bytes: Long): String =
    when {
        bytes >= 1L shl 30 -> "%.2f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
        else -> "%d KB".format((bytes / 1024).coerceAtLeast(1))
    }

/** A size, marked as approximate when it was estimated. */
internal fun formatSize(bytes: Long, isEstimate: Boolean): String =
    if (isEstimate) "≈ ${formatSize(bytes)}" else formatSize(bytes)

internal fun formatEta(seconds: Long): String =
    if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds % 3600 / 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)

/**
 * A content URI other apps can read: the media store entry when there is one, or Glint's own
 * file provider, which also covers Android 10 and older where files stay in Glint's folder.
 */
private fun contentUri(context: Context, item: DownloadItem): Uri? {
    item.fileUri?.let { return Uri.parse(it) }
    val path = item.filePath ?: return null
    return runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.files", File(path))
        }
        .getOrNull()
}

private fun mimeType(context: Context, item: DownloadItem, uri: Uri): String {
    val extension = item.filePath?.let { File(it).extension }.orEmpty().lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        // Files in a picked folder have no path; their provider knows the type.
        ?: runCatching { context.contentResolver.getType(uri) }.getOrNull()?.takeIf { it != "application/octet-stream" }
        ?: if (item.type == MediaType.Audio) "audio/*" else "video/*"
}

/** Opens a finished download in the app the system picks for its type. */
fun openDownload(context: Context, item: DownloadItem) {
    val uri = contentUri(context, item)
    if (uri == null) {
        Toast.makeText(context, R.string.file_missing, Toast.LENGTH_SHORT).show()
        return
    }
    val intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeType(context, item, uri))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.no_app_to_open, Toast.LENGTH_SHORT).show()
    }
}

/** Sends the finished file to another app through the system share sheet. */
fun shareDownload(context: Context, item: DownloadItem) {
    val uri = contentUri(context, item)
    if (uri == null) {
        Toast.makeText(context, R.string.file_missing, Toast.LENGTH_SHORT).show()
        return
    }
    val send =
        Intent(Intent.ACTION_SEND)
            .setType(mimeType(context, item, uri))
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TITLE, item.title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(item.title, uri)
    try {
        context.startActivity(
            Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.no_app_to_open, Toast.LENGTH_SHORT).show()
    }
}

/** Opens the page the download came from. */
fun openSourcePage(context: Context, item: DownloadItem) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(item.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.no_app_to_open, Toast.LENGTH_SHORT).show()
    }
}
