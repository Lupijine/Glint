package app.glint.engine

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Tumblr's share links in the forms gallery-dl reads. The Tumblr app shares short links
 * (at.tumblr.com, tmblr.co) and the site now names blogs with an @ (tumblr.com/@blog/123);
 * gallery-dl only reads tumblr.com/blog/123 and blog.tumblr.com/post/123.
 */
internal object TumblrLinks {
    private val ShortHosts = setOf("at.tumblr.com", "tmblr.co", "www.tmblr.co")
    private val SharedPost = Regex("""^(?:https?://)?at\.tumblr\.com/([\w-]+)/(\d+)(?:[/?#].*)?$""", RegexOption.IGNORE_CASE)
    private val Handle = Regex("""^(?:https?://)?(?:www\.)?tumblr\.com/@([\w-]+)(.*)$""", RegexOption.IGNORE_CASE)
    private val Canonical =
        listOf(
            Regex("""<link[^>]+rel=["']canonical["'][^>]+href=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<meta[^>]+property=["']og:url["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
        )

    private fun hostOf(url: String) = Fallbacks.hostOf(url.trim())

    /** The address gallery-dl reads, when it can be told without asking Tumblr. */
    fun clean(url: String): String {
        val text = url.trim()
        SharedPost.find(text)?.let { return "https://www.tumblr.com/${it.groupValues[1]}/${it.groupValues[2]}" }
        Handle.find(text)?.let { return "https://www.tumblr.com/${it.groupValues[1]}${it.groupValues[2]}" }
        return text
    }

    fun isShortLink(url: String) = hostOf(url) in ShortHosts

    /** [clean], and for a short link the post it leads to; any other link comes back as it is. */
    suspend fun resolve(url: String): String {
        val cleaned = clean(url)
        if (!isShortLink(cleaned)) return cleaned
        return withContext(Dispatchers.IO) { runCatching { follow(cleaned) }.getOrNull() } ?: cleaned
    }

    private fun follow(start: String): String? {
        var current = if (start.contains("://")) start else "https://$start"
        repeat(6) {
            val connection = URL(current).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) Glint")
                val code = connection.responseCode
                val location = connection.getHeaderField("Location")
                when {
                    code in 300..399 && location != null -> {
                        current = clean(URL(URL(current), location).toString())
                        if (!isShortLink(current)) return current
                    }
                    code in 200..299 -> {
                        // Some short links answer with a page that names the post.
                        val page = connection.inputStream.bufferedReader().use { reader ->
                            val buffer = CharArray(256 * 1024)
                            val read = reader.read(buffer)
                            if (read > 0) String(buffer, 0, read) else ""
                        }
                        return Canonical.firstNotNullOfOrNull { it.find(page)?.groupValues?.get(1) }
                            ?.replace("&amp;", "&")
                            ?.let(::clean)
                            ?.takeIf { !isShortLink(it) }
                    }
                    else -> return null
                }
            } finally {
                connection.disconnect()
            }
        }
        return null
    }
}
