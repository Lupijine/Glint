package app.glint.engine

/** A named part of a video, in whole seconds. */
data class Chapter(val start: Int, val end: Int, val title: String) {
    val length: Int
        get() = end - start
}

/** Choosing chapters to download, each as its own clip or several in a row as one. */
object Chapters {
    private const val VideoTitleLength = 80
    private const val ChapterTitleLength = 60

    /** True when the chosen chapters follow one another, so they can be joined into one clip. */
    fun areConsecutive(selected: Collection<Int>): Boolean {
        val sorted = selected.sorted()
        return sorted.size >= 2 && sorted.zipWithNext().all { (a, b) -> b == a + 1 }
    }

    /** "Video - 03 Chapter", with each part shortened so the chapter's name survives. */
    fun title(videoTitle: String, index: Int, chapter: Chapter): String =
        "${videoTitle.take(VideoTitleLength).trim()} - ${"%02d".format(index + 1)} ${chapter.title.take(ChapterTitleLength).trim()}"

    /** "Video - First – Last" for chapters joined into one clip. */
    fun joinedTitle(videoTitle: String, first: Chapter, last: Chapter): String =
        "${videoTitle.take(VideoTitleLength).trim()} - ${first.title.take(ChapterTitleLength / 2).trim()} – " +
            last.title.take(ChapterTitleLength / 2).trim()
}
