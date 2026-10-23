package app.glint.engine

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryTest {
    private fun item(
        id: String,
        title: String,
        type: MediaType = MediaType.Video,
        status: DownloadItem.Status = DownloadItem.Status.Completed,
        source: String = "Youtube",
        size: Long = 0,
        completedAt: Long? = null,
        createdAt: Long = 0,
    ) =
        DownloadItem(
            id = id,
            url = "https://example.com/$id",
            type = type,
            formatSelector = "b",
            status = status,
            title = title,
            source = source,
            sizeBytes = size,
            createdAt = createdAt,
            completedAt = completedAt,
        )

    private val items =
        listOf(
            item("1", "Morning Mix", type = MediaType.Audio, size = 5, completedAt = 300),
            item("2", "Cats compilation", size = 50, completedAt = 100),
            item("3", "Still downloading", status = DownloadItem.Status.Downloading, createdAt = 400),
            item("4", "Évora travel vlog", source = "Vimeo", size = 20, completedAt = null, createdAt = 200),
            item("5", "Failed one", status = DownloadItem.Status.Failed, createdAt = 500),
        )

    private fun ids(query: String = "", kind: LibraryKind = LibraryKind.All, sort: LibrarySort = LibrarySort.Newest) =
        Library.files(items, query, kind, sort, Locale.ENGLISH).map { it.id }

    @Test
    fun `only finished downloads are in the library, newest first`() {
        assertEquals(listOf("1", "4", "2"), ids())
    }

    @Test
    fun `sorting by oldest, title and size`() {
        assertEquals(listOf("2", "4", "1"), ids(sort = LibrarySort.Oldest))
        assertEquals(listOf("2", "4", "1"), ids(sort = LibrarySort.Title))
        assertEquals(listOf("2", "4", "1"), ids(sort = LibrarySort.Size))
    }

    @Test
    fun `filtering by kind`() {
        assertEquals(listOf("1"), ids(kind = LibraryKind.Audio))
        assertEquals(listOf("4", "2"), ids(kind = LibraryKind.Video))
    }

    @Test
    fun `search matches every word in the title or site, ignoring case`() {
        assertEquals(listOf("2"), ids("CATS"))
        assertEquals(listOf("4"), ids("vimeo"))
        assertEquals(listOf("4"), ids("  travel   évora "))
        assertEquals(emptyList<String>(), ids("cats vimeo"))
    }
}
