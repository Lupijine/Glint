package app.glint.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * Where downloads are saved. Download and Movies/Music are written directly. A folder picked with
 * the system picker, such as one on a memory card, is reached through its document provider, so
 * files are downloaded to Glint's own staging folder first and then copied there.
 */
object Storage {
    /**
     * Where yt-dlp saves one download ([home]), and, for a picked folder, where the finished file
     * is copied afterwards ([tree] and the [treeFolders] inside it).
     */
    data class Target(val home: File, val tree: Uri? = null, val treeFolders: List<String> = emptyList())

    fun target(context: Context, item: DownloadItem, settings: GlintSettings): Target {
        val tree = customTree(context, settings)
        return when {
            tree != null ->
                Target(
                    home = File(context.getExternalFilesDir("staging") ?: File(context.filesDir, "staging"), item.id),
                    tree = tree,
                    treeFolders =
                        DownloadOutput.subfolders(item.type, item.source, item.folder, settings.folderLayout),
                )
            // Commands may save any kind of file, which Movies and Music do not accept.
            settings.storageLocation == StorageLocation.MediaFolders && item.customArgs == null ->
                Target(
                    DownloadOutput.subfolders(
                            item.type, item.source, item.folder, settings.folderLayout, byTypeFolders = false)
                        .fold(mediaBase(context, item.type)) { dir, name -> File(dir, name) })
            else ->
                Target(
                    DownloadOutput.subfolders(item.type, item.source, item.folder, settings.folderLayout)
                        .fold(downloadsBase(context)) { dir, name -> File(dir, name) })
        }
    }

    /** Download/Glint; on Android 10 and older, Glint's own folder, which needs no permission. */
    fun downloadsBase(context: Context): File = publicOrPrivate(context, Environment.DIRECTORY_DOWNLOADS)

    /** Movies/Glint for videos, Music/Glint for audio. */
    fun mediaBase(context: Context, type: MediaType): File =
        publicOrPrivate(context, if (type == MediaType.Audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES)

    private fun publicOrPrivate(context: Context, kind: String): File =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            File(Environment.getExternalStoragePublicDirectory(kind), "Glint")
        } else {
            File(context.getExternalFilesDir(kind) ?: File(context.filesDir, kind), "Glint")
        }

    /** The picked folder, when it is chosen and Glint still has permission to write there. */
    fun customTree(context: Context, settings: GlintSettings): Uri? {
        if (settings.storageLocation != StorageLocation.CustomFolder || settings.customFolderUri.isBlank()) return null
        val uri = Uri.parse(settings.customFolderUri)
        return uri.takeIf { tree ->
            context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }
        }
    }

    /** Keeps access to a picked folder across restarts. */
    fun keepAccess(context: Context, uri: Uri): Boolean =
        runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                true
            }
            .getOrDefault(false)

    fun folderName(context: Context, uri: Uri): String =
        DocumentFile.fromTreeUri(context, uri)?.name ?: uri.lastPathSegment.orEmpty().substringAfterLast(':')

    fun mimeType(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

    /**
     * Copies a finished file into the picked folder, inside [folders]. Returns its document, or
     * null when the folder is gone or full. The provider adds " (1)" when the name is taken.
     */
    fun copyToTree(context: Context, tree: Uri, folders: List<String>, source: File, displayName: String): Uri? {
        var dir = DocumentFile.fromTreeUri(context, tree) ?: return null
        for (name in folders) {
            dir = dir.findFile(name)?.takeIf { it.isDirectory } ?: dir.createDirectory(name) ?: return null
        }
        val target = dir.createFile(mimeType(displayName), displayName) ?: return null
        val copied =
            runCatching {
                    context.contentResolver.openOutputStream(target.uri)?.use { output ->
                        source.inputStream().use { it.copyTo(output, 1 shl 16) }
                    } != null
                }
                .getOrDefault(false)
        if (!copied) {
            target.delete()
            return null
        }
        return target.uri
    }

    /** True when a finished download's file is still there, wherever it was saved. */
    fun exists(context: Context, item: DownloadItem): Boolean {
        item.filePath?.let { return File(it).isFile }
        val uri = item.fileUri?.let(Uri::parse) ?: return false
        return runCatching { DocumentFile.fromSingleUri(context, uri)?.exists() == true }.getOrDefault(false)
    }

    /** Deletes a file saved in a picked folder. */
    fun deleteDocument(context: Context, uri: Uri): Boolean =
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false)
}
