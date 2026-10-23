package app.glint.engine

/** Times for clips, written the way video players show them: 1:05 or 1:02:03. */
object Clips {
    /** The yt-dlp --download-sections value for a clip, such as "*65-130". */
    fun section(start: Int, end: Int): String = "*$start-$end"

    fun format(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
        else "%d:%02d".format(s / 60, s % 60)
    }

    /** Reads "75", "1:15" or "0:01:15"; null for anything else, such as "1:75". */
    fun parse(text: String): Int? {
        val parts = text.trim().split(':')
        if (parts.isEmpty() || parts.size > 3 || parts.any { it.isEmpty() || !it.all(Char::isDigit) }) return null
        val numbers = parts.map { it.toIntOrNull() ?: return null }
        if (numbers.drop(1).any { it >= 60 }) return null
        return numbers.fold(0) { total, n -> total * 60 + n }
    }

    /** A clip within a video, or null when it is empty, reversed or the whole video. */
    fun clip(start: Int, end: Int, duration: Int): Pair<Int, Int>? {
        val from = start.coerceIn(0, duration)
        val to = end.coerceIn(0, duration)
        if (to <= from) return null
        if (from == 0 && to == duration) return null
        return from to to
    }

    /** A clip's share of a full download's size. */
    fun scaledSize(fullSize: Long, start: Int, end: Int, duration: Int): Long =
        if (duration <= 0) fullSize else fullSize * (end - start).coerceAtLeast(0) / duration
}
