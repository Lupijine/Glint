package app.glint.engine

import java.net.URLDecoder
import org.json.JSONObject
import org.json.JSONTokener

/** What a page offers to download: one complete file, or a video and its sound apart. */
sealed interface MediaPlan {
    data class Single(val url: String) : MediaPlan

    data class Separate(val video: String, val audio: String) : MediaPlan

    data object None : MediaPlan
}

/**
 * Finds video files in Facebook and Instagram pages as a browser receives them: the addresses
 * written into the page's data, and those the player asks for while it plays.
 */
object MediaLinks {
    /** Keys whose value is a complete video file with its sound, best first. */
    private val CompleteKeys =
        listOf(
            "browser_native_hd_url", "playable_url_quality_hd", "hd_src",
            "browser_native_sd_url", "playable_url", "sd_src",
        )

    private fun keyPattern(key: String) = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

    /** Values of [key] inside a JSON string that is itself inside the page's data: \"key\":\"value\". */
    private fun escapedValues(html: String, key: String): List<String> = buildList {
        val marker = "\\\"$key\\\":\\\""
        var from = 0
        while (true) {
            val start = html.indexOf(marker, from)
            if (start < 0) break
            val valueStart = start + marker.length
            val end = html.indexOf("\\\"", valueStart)
            if (end < 0) break
            add(jsonString(jsonString(html.substring(valueStart, end))))
            from = end
        }
    }

    private val VideoVersions = Regex("\"video_versions\"\\s*:\\s*\\[(.*?)\\]", RegexOption.DOT_MATCHES_ALL)
    private val UrlField = Regex("\"url\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
    private val OgVideo =
        Regex("<meta[^>]+property=\"og:video(?::secure_url|:url)?\"[^>]+content=\"([^\"]+)\"", RegexOption.IGNORE_CASE)

    /** Complete video files named in [html], best first. */
    fun fromPage(html: String): List<String> = buildList {
        for (key in CompleteKeys) {
            keyPattern(key).findAll(html).forEach { add(jsonString(it.groupValues[1])) }
            addAll(escapedValues(html, key))
        }
        // Instagram lists a post's video files, largest first.
        VideoVersions.findAll(html).forEach { versions ->
            UrlField.find(versions.groupValues[1])?.let { add(jsonString(it.groupValues[1])) }
        }
        OgVideo.findAll(html).forEach { add(htmlText(it.groupValues[1])) }
    }
        .filter { it.startsWith("https://") || it.startsWith("http://") }
        .distinct()

    private val MediaHosts = listOf("fbcdn.net", "cdninstagram.com")
    private val RangeParameter = Regex("[?&](bytestart|byteend)=[^&]*")

    /**
     * A video or sound file the player asked for, without the byte range of the one piece it
     * wanted; null for anything else.
     */
    fun mediaUrl(requestUrl: String): String? {
        val host = requestUrl.substringAfter("://").substringBefore('/').substringBefore('?').lowercase()
        if (MediaHosts.none { host == it || host.endsWith(".$it") }) return null
        val path = requestUrl.substringAfter(host, "").substringBefore('?')
        if (!path.contains(".mp4") && !path.contains(".m4a")) return null
        var url = requestUrl
        while (true) {
            val match = RangeParameter.find(url) ?: break
            val separator = match.value.first()
            url = url.removeRange(match.range)
            // "?bytestart=1&x" loses its "?": the next parameter takes it.
            if (separator == '?' && url.length > match.range.first && url[match.range.first] == '&') {
                url = url.replaceRange(match.range.first, match.range.first + 1, "?")
            }
        }
        return url
    }

    enum class Kind { Complete, VideoOnly, Audio, Unknown }

    private class Stream(val url: String, val kind: Kind, val bitrate: Long)

    /** What a media file is, from the encoding note Facebook and Instagram put in its address. */
    fun kindOf(url: String): Kind = describe(url).kind

    private fun describe(url: String): Stream {
        val efg = url.substringAfter('?', "").split('&').firstOrNull { it.startsWith("efg=") }?.removePrefix("efg=")
        val note = efg?.let { encoded -> runCatching { String(base64(URLDecoder.decode(encoded.replace("+", "%2B"), "UTF-8"))) }.getOrNull() }
        val tag = note?.let { runCatching { JSONObject(it).optString("vencode_tag") }.getOrNull() }.orEmpty().lowercase()
        val bitrate = note?.let { runCatching { JSONObject(it).optLong("bitrate") }.getOrNull() } ?: 0L
        val kind =
            when {
                tag.contains("audio") -> Kind.Audio
                tag.contains("progressive") -> Kind.Complete
                tag.contains("dash") -> Kind.VideoOnly
                url.contains(".m4a") -> Kind.Audio
                else -> Kind.Unknown
            }
        return Stream(url, kind, bitrate)
    }

    /**
     * The best way to get the video: a complete file named in the page, else one the player
     * fetched, else the best video stream and the sound stream to join.
     */
    fun choose(fromPage: List<String>, fetched: List<String>): MediaPlan {
        fromPage.firstOrNull()?.let { return MediaPlan.Single(it) }
        val streams = fetched.distinct().map(::describe)
        streams.firstOrNull { it.kind == Kind.Complete }?.let { return MediaPlan.Single(it.url) }
        val video = streams.filter { it.kind == Kind.VideoOnly }.maxByOrNull { it.bitrate }
        val audio = streams.filter { it.kind == Kind.Audio }.maxByOrNull { it.bitrate }
        if (video != null && audio != null) return MediaPlan.Separate(video.url, audio.url)
        streams.firstOrNull { it.kind == Kind.Unknown }?.let { return MediaPlan.Single(it.url) }
        video?.let { return MediaPlan.Single(it.url) }
        return MediaPlan.None
    }

    /** A file name from a page title, for any storage. */
    fun fileName(title: String?, fallback: String): String {
        val clean =
            title.orEmpty()
                .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(120)
                .trim()
        return clean.ifEmpty { fallback }
    }

    /** Standard or URL-safe base64, with or without padding. */
    internal fun base64(text: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val output = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (char in text) {
            val value =
                when (char) {
                    '-' -> 62
                    '_' -> 63
                    '=', '\n', '\r', ' ' -> continue
                    else -> alphabet.indexOf(char).also { require(it >= 0) { "Not base64" } }
                }
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                output.write((buffer shr bits) and 0xFF)
            }
        }
        return output.toByteArray()
    }

    private fun jsonString(raw: String): String =
        runCatching { JSONTokener("\"$raw\"").nextValue() as String }.getOrDefault(raw.replace("\\/", "/"))

    private fun htmlText(text: String): String =
        text.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
}
