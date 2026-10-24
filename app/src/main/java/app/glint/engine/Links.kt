package app.glint.engine

/** Finds links in text pasted or shared from other apps, which often wrap them in a sentence. */
object Links {
    private val WebLink = Regex("""(?i)(?:https?://|\bwww\.)[^\s<>"'`​‎‏]+""")
    private val PlaylistParameter = Regex("""[?&]list=[^&#]+""")
    private const val TrailingPunctuation = ".,;:!?'\"…»”’،"

    fun all(text: String): List<String> =
        WebLink.findAll(text)
            .map { tidy(it.value) }
            .filter { it.substringAfter("://").length > 3 }
            .distinct()
            .toList()

    fun first(text: String): String? = all(text).firstOrNull()

    /** True when a link names a playlist as well as a video, like YouTube's watch?v=…&list=…. */
    fun namesPlaylist(url: String): Boolean = PlaylistParameter.containsMatchIn(url)

    fun isWebLink(text: String): Boolean =
        text.startsWith("https://", ignoreCase = true) || text.startsWith("http://", ignoreCase = true)

    private fun tidy(raw: String): String {
        var link = raw
        while (link.isNotEmpty()) {
            val last = link.last()
            link =
                when {
                    last in TrailingPunctuation -> link.dropLast(1)
                    last == ')' && link.count { it == '(' } < link.count { it == ')' } -> link.dropLast(1)
                    last == ']' && link.count { it == '[' } < link.count { it == ']' } -> link.dropLast(1)
                    else -> break
                }
        }
        return if (link.startsWith("www.", ignoreCase = true)) "https://$link" else link
    }
}

/** Turns yt-dlp's error output into one readable line, or null when there is nothing to show. */
object ErrorText {
    /**
     * An exception and what caused it, such as "ExceptionInInitializerError ← RuntimeException:
     * Cannot instantiate …", since the outermost error alone rarely says what went wrong.
     */
    fun describe(error: Throwable): String =
        generateSequence(error) { it.cause.takeIf { cause -> cause !== it } }
            .take(4)
            .joinToString(" ← ") { e ->
                val message = e.message?.lines()?.firstOrNull { it.isNotBlank() }?.trim()
                if (message.isNullOrEmpty()) e.javaClass.simpleName else "${e.javaClass.simpleName}: $message"
            }

    private val GalleryDlPrefix = Regex("""^\[[\w.-]+]\[error]\s*""")

    fun summarize(message: String?): String? {
        val lines = message?.lines().orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
        val line = lines.lastOrNull { it.contains("ERROR", ignoreCase = true) } ?: lines.lastOrNull()
        // yt-dlp starts its errors with "ERROR:", gallery-dl with "[site][error]".
        return line?.removePrefix("ERROR:")?.replace(GalleryDlPrefix, "")?.trim()?.takeIf { it.isNotEmpty() }
    }
}
