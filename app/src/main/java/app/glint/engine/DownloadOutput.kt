package app.glint.engine

import java.io.File

/** Names and resolves a single attempt's output without inspecting unrelated downloads. */
internal object DownloadOutput {
    private const val ForbiddenInNames = "/\\:*?\"<>|"
    private const val MaxFolderNameLength = 80
    private const val MaxFileTitleLength = 150

    /**
     * yt-dlp's file name for a download. The download's id keeps it apart from every other file
     * while it downloads, and stays the same when it resumes; [finalName] removes it afterwards.
     */
    fun template(itemId: String, name: String? = null): String {
        // A clip is named after its own title, such as a chapter, rather than the whole video's.
        val literal = name?.let(::fileTitle)?.replace("%", "%%")
        return if (literal != null) "$literal [$itemId].%(ext)s" else "%(title).150B [$itemId].%(ext)s"
    }

    /**
     * A title made safe to use as a file name: no folder separators or other reserved characters,
     * at most 150 characters, never cut inside a character. Null when nothing usable is left.
     */
    fun fileTitle(title: String): String? {
        val cleaned =
            title
                .map { c -> if (c in ForbiddenInNames || c.isISOControl()) ' ' else c }
                .joinToString("")
                .replace(Regex("""\s+"""), " ")
                .trim()
                .trim('.')
                .trim()
        val shortened =
            cleaned.take(MaxFileTitleLength).let {
                if (it.isNotEmpty() && it.last().isHighSurrogate()) it.dropLast(1) else it
            }
        return shortened.trim().trim('.').trim().takeIf { it.isNotEmpty() }
    }

    /**
     * A clean name for a finished file: its title without the download id, with " (2)", " (3)"
     * and so on when another file already has that name.
     */
    fun finalName(fileName: String, itemId: String, exists: (String) -> Boolean): String {
        val clean = fileName.replace(" [$itemId]", "").ifBlank { fileName }
        if (clean == fileName || !exists(clean)) return clean
        val dot = clean.lastIndexOf('.').takeIf { it > 0 } ?: clean.length
        val base = clean.substring(0, dot)
        val extension = clean.substring(dot)
        return generateSequence(2) { it + 1 }.map { "$base ($it)$extension" }.first { !exists(it) }
    }

    /**
     * Gives a finished file its clean name in the same folder; keeps the old name if renaming
     * fails. Choosing the name and renaming happen under one lock, so two downloads that finish
     * at once with the same title cannot pick the same free name, and one never replaces the other.
     */
    fun moveToFinalName(file: File, itemId: String): File =
        synchronized(renameLock) {
            val parent = file.parentFile ?: return file
            val name = finalName(file.name, itemId) { File(parent, it).exists() }
            if (name == file.name) return file
            val target = File(parent, name)
            if (target.exists()) return file
            if (file.renameTo(target)) target else file
        }

    private val renameLock = Any()

    private val PictureExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "heic", "bmp")

    /** True for a picture file, which can be its own thumbnail. */
    fun isPicture(fileName: String): Boolean = fileName.substringAfterLast('.', "").lowercase() in PictureExtensions

    /**
     * The folders inside the download location for one download: by type or site when chosen,
     * then the playlist's own folder. [byTypeFolders] is off where the location already splits
     * videos from audio, such as Movies and Music.
     */
    fun subfolders(
        type: MediaType,
        source: String,
        playlistFolder: String?,
        layout: FolderLayout,
        byTypeFolders: Boolean = true,
    ): List<String> = buildList {
        when (layout) {
            FolderLayout.OneFolder -> Unit
            FolderLayout.ByType -> if (byTypeFolders) add(if (type == MediaType.Audio) "Audio" else "Video")
            FolderLayout.BySite -> add(folderName(source) ?: "Other")
        }
        playlistFolder?.let(::add)
    }

    fun completedFile(marker: File, outputDirectory: File): File {
        check(marker.isFile) { "The downloader did not report a completed file" }
        val paths = marker.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        check(paths.size == 1) { "The downloader did not report exactly one completed file" }

        val file = File(paths.single()).canonicalFile
        check(file.parentFile == outputDirectory.canonicalFile && file.isFile) {
            "The downloader's completed file is missing or outside the download folder"
        }
        return file
    }

    /**
     * Every file a command reported saving that still exists, in the order saved. Commands may
     * choose their own folders, so files are not limited to the download folder.
     */
    fun reportedFiles(marker: File): List<File> {
        if (!marker.isFile) return emptyList()
        return marker.readLines(Charsets.UTF_8)
            .filter { it.isNotBlank() }
            .map { File(it.trim()).canonicalFile }
            .filter { it.isFile }
            .distinct()
    }

    private val FileCountSuffix = Regex(""" ×\d+$""")

    /** A label such as "MP3 ×12" for a command that saved twelve files; retries do not stack. */
    fun withFileCount(label: String, count: Int): String {
        val base = label.replace(FileCountSuffix, "")
        return if (count > 1) "$base ×$count".trim() else base
    }

    /**
     * A folder name for a playlist's downloads. It cannot leave the download folder: separators
     * and other reserved characters are replaced, and names made only of dots are rejected.
     */
    fun folderName(title: String): String? {
        val cleaned =
            title
                .map { c -> if (c in ForbiddenInNames || c.isISOControl()) ' ' else c }
                .joinToString("")
                .replace(Regex("""\s+"""), " ")
                .trim()
                .trim('.')
                .trim()
        val shortened =
            cleaned.take(MaxFolderNameLength).let {
                if (it.isNotEmpty() && it.last().isHighSurrogate()) it.dropLast(1) else it
            }
        return shortened.trim().trim('.').trim().takeIf { it.isNotEmpty() }
    }
}
