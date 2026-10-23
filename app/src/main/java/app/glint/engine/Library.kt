package app.glint.engine

import java.text.Collator
import java.util.Locale

enum class LibraryKind {
    All,
    Video,
    Audio,
}

enum class LibrarySort {
    Newest,
    Oldest,
    Title,
    Size,
}

/** Finished downloads, searched by title and site, filtered by kind and sorted. */
object Library {
    private val Whitespace = Regex("""\s+""")

    fun files(
        items: List<DownloadItem>,
        query: String,
        kind: LibraryKind,
        sort: LibrarySort,
        locale: Locale = Locale.getDefault(),
    ): List<DownloadItem> {
        val words = query.trim().lowercase(locale).split(Whitespace).filter { it.isNotEmpty() }
        val matching =
            items.filter { item ->
                item.status == DownloadItem.Status.Completed &&
                    when (kind) {
                        LibraryKind.All -> true
                        LibraryKind.Video -> item.type == MediaType.Video
                        LibraryKind.Audio -> item.type == MediaType.Audio
                    } &&
                    words.all { word ->
                        item.title.lowercase(locale).contains(word) ||
                            item.source.lowercase(locale).contains(word)
                    }
            }
        return when (sort) {
            LibrarySort.Newest -> matching.sortedByDescending { it.finishedAt }
            LibrarySort.Oldest -> matching.sortedBy { it.finishedAt }
            LibrarySort.Title -> {
                val collator = Collator.getInstance(locale)
                matching.sortedWith(compareBy<DownloadItem, String>(collator) { it.title })
            }
            LibrarySort.Size -> matching.sortedByDescending { it.sizeBytes }
        }
    }

    private val DownloadItem.finishedAt: Long
        get() = completedAt ?: createdAt
}
