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

    /** Of [CompleteKeys], those that name the lower quality. */
    private val SmallKeys = setOf("browser_native_sd_url", "playable_url", "sd_src")

    private fun keyPattern(key: String) = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

    /** A complete file named in the page, where it was named, and how good its key says it is. */
    private class Found(val url: String, val position: Int, val rank: Int)

    /** Values of [key] inside a JSON string that is itself inside the page's data: \"key\":\"value\". */
    private fun escapedValues(html: String, key: String, rank: Int): List<Found> = buildList {
        val marker = "\\\"$key\\\":\\\""
        var from = 0
        while (true) {
            val start = html.indexOf(marker, from)
            if (start < 0) break
            val valueStart = start + marker.length
            val end = html.indexOf("\\\"", valueStart)
            if (end < 0) break
            add(Found(jsonString(jsonString(html.substring(valueStart, end))), start, rank))
            from = end
        }
    }

    private val VideoVersions = Regex("\"video_versions\"\\s*:\\s*\\[(.*?)\\]", RegexOption.DOT_MATCHES_ALL)
    private val UrlField = Regex("\"url\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
    private val OgVideo =
        Regex("<meta[^>]+property=\"og:video(?::secure_url|:url)?\"[^>]+content=\"([^\"]+)\"", RegexOption.IGNORE_CASE)

    /** How far, in characters of page data, a file's address may be from the wanted video's id. */
    private const val NearTarget = 25_000

    /**
     * A complete video file named in a page. [small] marks the lower of two qualities the page
     * offers; [distance] is how far from the wanted video's id the page named it, or null when
     * the page never names that video, so the file may be another one.
     */
    class PageFile(val url: String, val small: Boolean, val distance: Int?)

    /**
     * Complete video files named in [html], best first. Pages also hold other videos (suggested,
     * next, in the feed); with [target], the wanted video's id or code, only files named near it
     * count, closest first, unless the page never mentions it.
     */
    fun filesInPage(html: String, target: String? = null): List<PageFile> {
        val found = buildList {
            CompleteKeys.forEachIndexed { rank, key ->
                keyPattern(key).findAll(html).forEach { add(Found(jsonString(it.groupValues[1]), it.range.first, rank)) }
                addAll(escapedValues(html, key, rank))
            }
            // Instagram lists a post's video files, largest first.
            VideoVersions.findAll(html).forEach { versions ->
                UrlField.find(versions.groupValues[1])?.let {
                    add(Found(jsonString(it.groupValues[1]), versions.range.first, CompleteKeys.size))
                }
            }
            OgVideo.findAll(html).forEach { add(Found(htmlText(it.groupValues[1]), it.range.first, CompleteKeys.size + 1)) }
        }
            .filter { it.url.startsWith("https://") || it.url.startsWith("http://") }
        fun Found.small() = rank < CompleteKeys.size && CompleteKeys[rank] in SmallKeys
        val anchors = anchorsOf(html, target)
        if (anchors.isEmpty()) {
            return found.sortedBy { it.rank }.distinctBy { it.url }.map { PageFile(it.url, it.small(), null) }
        }
        return found
            .map { it to anchors.minOf { anchor -> kotlin.math.abs(anchor - it.position) } }
            .filter { (_, distance) -> distance <= NearTarget }
            .sortedWith(compareBy({ it.second / 2_000 }, { it.first.rank }))
            .distinctBy { it.first.url }
            .map { (file, distance) -> PageFile(file.url, file.small(), distance) }
    }

    /** The addresses of [filesInPage], best first. */
    fun fromPage(html: String, target: String? = null): List<String> = filesInPage(html, target).map { it.url }

    /** Where the page names the wanted video, by its id or code. */
    private fun anchorsOf(html: String, target: String?): List<Int> {
        val id = target?.takeIf { it.length >= 5 } ?: return emptyList()
        val anchors = mutableListOf<Int>()
        var from = 0
        while (true) {
            val at = html.indexOf(id, from)
            if (at < 0) break
            // A longer number or code that only contains the id is another video.
            val before = html.getOrNull(at - 1)
            val after = html.getOrNull(at + id.length)
            if ((before == null || !before.isLetterOrDigit()) && (after == null || !after.isLetterOrDigit())) anchors += at
            from = at + id.length
        }
        return anchors
    }

    private val FacebookId =
        listOf(
            Regex("""[?&](?:v|video_id)=(\d{6,})"""),
            Regex("""/(?:reel|reels|videos|watch/live)/(?:[^/?#]+/)?(\d{6,})"""),
            Regex("""/(?:[^/?#]+/)?videos/(?:[^/?#]+/)?(\d{6,})"""),
        )
    private val InstagramCode = Regex("""instagram\.com/(?:[^/?#]+/)?(?:p|reel|reels|tv)/([A-Za-z0-9_-]{5,})""")

    /** The id or code of the video a Facebook or Instagram address points to, when it says. */
    fun targetOf(url: String): String? {
        InstagramCode.find(url)?.let { return it.groupValues[1] }
        if (!url.contains("facebook.com") && !url.contains("fb.watch")) return null
        return FacebookId.firstNotNullOfOrNull { it.find(url)?.groupValues?.get(1) }
    }

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

    /** One file the player fetched: what it holds, its quality, and the video it belongs to. */
    private class Stream(val url: String, val kind: Kind, val bitrate: Long, val video: String?)

    /** What a media file is, from the encoding note Facebook and Instagram put in its address. */
    fun kindOf(url: String): Kind = describe(url).kind

    private fun describe(url: String): Stream {
        val efg = url.substringAfter('?', "").split('&').firstOrNull { it.startsWith("efg=") }?.removePrefix("efg=")
        val note = efg?.let { encoded -> runCatching { String(base64(URLDecoder.decode(encoded.replace("+", "%2B"), "UTF-8"))) }.getOrNull() }
        val json = note?.let { runCatching { JSONObject(it) }.getOrNull() }
        val tag = json?.optString("vencode_tag").orEmpty().lowercase()
        val bitrate = json?.optLong("bitrate") ?: 0L
        // Which video this stream is part of: a video's picture and sound streams share it.
        val video =
            listOf("video_id", "xpv_asset_id")
                .firstNotNullOfOrNull { key -> json?.opt(key)?.toString()?.takeIf { it.isNotBlank() && it != "null" && it != "0" } }
        val kind =
            when {
                tag.contains("audio") -> Kind.Audio
                tag.contains("progressive") -> Kind.Complete
                tag.contains("dash") -> Kind.VideoOnly
                url.contains(".m4a") -> Kind.Audio
                else -> Kind.Unknown
            }
        return Stream(url, kind, bitrate, video)
    }

    /** The video a fetched file belongs to, from the note in its address. */
    fun videoOf(url: String): String? = describe(url).video

    /**
     * What to download, and how sure Glint is that it is the wanted video: [sure] when the page
     * named the file next to the video's id, or the player's files carry that id. [offersSmall]
     * is true when the page offers the video in two qualities.
     */
    class Choice(val plan: MediaPlan, val sure: Boolean, val offersSmall: Boolean)

    /**
     * The best way to get the video, from the complete files named in the page and the files
     * the player fetched ([fetched] holds one entry per request, so the video that played the
     * longest appears most). Files the player fetched are only combined within one video: the
     * wanted one when they say so, else the one the player fetched most.
     */
    fun choose(
        page: List<PageFile>,
        fetched: List<String>,
        target: String? = null,
        quality: BrowserQuality = BrowserQuality.Best,
    ): Choice {
        fun pick(files: List<PageFile>): String {
            // HD and SD of one video are named together, before anything farther away.
            val nearest = files.first().distance ?: 0
            val group = files.filter { (it.distance ?: 0) - nearest <= 3_000 }
            val wanted = group.firstOrNull { it.small == (quality == BrowserQuality.Small) } ?: group.first()
            return wanted.url
        }
        fun offersSmall(files: List<PageFile>) = files.any { it.small } && files.any { !it.small }

        val anchored = page.filter { it.distance != null }
        if (anchored.isNotEmpty()) return Choice(MediaPlan.Single(pick(anchored)), sure = true, offersSmall(anchored))

        val all = fetched.map(::describe)
        val counts = all.mapNotNull { it.video }.groupingBy { it }.eachCount()
        val wanted = target?.takeIf { it in counts } ?: counts.maxByOrNull { it.value }?.key
        // Files that do not say which video they are part of go with any video.
        val streams = all.filter { wanted == null || it.video == wanted || it.video == null }.distinctBy { it.url }
        val sure = wanted != null && wanted == target
        streams.firstOrNull { it.kind == Kind.Complete }?.let { return Choice(MediaPlan.Single(it.url), sure, false) }
        val videos = streams.filter { it.kind == Kind.VideoOnly }
        val video = if (quality == BrowserQuality.Small) videos.minByOrNull { it.bitrate } else videos.maxByOrNull { it.bitrate }
        val audio = streams.filter { it.kind == Kind.Audio }.maxByOrNull { it.bitrate }
        if (video != null && audio != null) return Choice(MediaPlan.Separate(video.url, audio.url), sure, false)
        // Files named in a page that never names the wanted video may be another video's.
        if (page.isNotEmpty()) return Choice(MediaPlan.Single(pick(page)), sure = false, offersSmall(page))
        streams.firstOrNull { it.kind == Kind.Unknown }?.let { return Choice(MediaPlan.Single(it.url), sure, false) }
        // A picture without its sound, until the player fetches the sound too.
        video?.let { return Choice(MediaPlan.Single(it.url), sure = false, offersSmall = false) }
        return Choice(MediaPlan.None, sure = false, offersSmall = false)
    }

    /** True when two lengths, in seconds, are one video's picture and sound. */
    fun sameLength(first: Double, second: Double): Boolean =
        kotlin.math.abs(first - second) <= maxOf(3.0, 0.05 * maxOf(first, second))

    private val NotificationCount = Regex("""^\(\d+\+?\)\s*""")
    private val Counts =
        Regex("""^[\d.,\s]+[KMBkmb]?\s+(views?|reactions?|comments?|plays?|likes?|shares?)\b.*""", RegexOption.IGNORE_CASE)
    private val InstagramCaption = Regex("""^(.*?) on Instagram: ["“](.*)["”]$""", RegexOption.DOT_MATCHES_ALL)
    private val GenericTitles =
        setOf(
            "facebook", "instagram", "reels", "reel", "watch", "video", "videos", "facebook watch", "login",
            "log in", "log in or sign up to view", "log into facebook", "instagram reel", "instagram video",
        )

    /** The words of a page title that name the video, without the site, counts and the like. */
    fun cleanTitle(raw: String?): String? {
        val text = raw?.replace(Regex("\\s+"), " ")?.trim()?.replace(NotificationCount, "") ?: return null
        InstagramCaption.find(text)?.let { match ->
            return match.groupValues[2].trim().ifEmpty { match.groupValues[1].trim() }.ifEmpty { null }
        }
        val parts =
            text.split(" | ", " • ", " - Facebook", " | Facebook")
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.lowercase() !in GenericTitles && !Counts.matches(it) }
        val caption = parts.firstOrNull { !it.startsWith("By ") }
        return caption ?: parts.firstOrNull()?.removePrefix("By ")?.trim()?.ifEmpty { null }
    }

    private val Caption = Regex("\"message\":\\{[^{}]{0,1500}?\"text\":\"((?:[^\"\\\\]|\\\\.){1,3000})\"")
    private val Thumbnail =
        Regex(
            "\"(?:first_frame_thumbnail|thumbnail_url|thumbnailUrl)\":\"((?:[^\"\\\\]|\\\\.)+)\"|" +
                "\"(?:preferred_thumbnail|thumbnailImage)\":\\{(?:\"image\":\\{)?\"uri\":\"((?:[^\"\\\\]|\\\\.)+)\""
        )

    /** The caption the page gives the wanted video, from its data. */
    fun captionInPage(html: String, target: String?): String? =
        nearest(html, target, Caption)?.let { cleanTitle(jsonString(it).lines().firstOrNull { line -> line.isNotBlank() }) }

    /** A picture of the wanted video from the page's data. */
    fun thumbnailInPage(html: String, target: String?): String? =
        nearest(html, target, Thumbnail)?.let(::jsonString)?.takeIf { it.startsWith("https://") }

    /**
     * The first group of [pattern] found nearest the wanted video's id. Null when the page does
     * not name the video: pages hold other videos' details too.
     */
    private fun nearest(html: String, target: String?, pattern: Regex): String? {
        val anchors = anchorsOf(html, target)
        if (anchors.isEmpty()) return null
        return pattern.findAll(html)
            .map { match -> match.range.first to match.groupValues.drop(1).first { it.isNotEmpty() } }
            .map { (position, value) -> anchors.minOf { kotlin.math.abs(it - position) } to value }
            .filter { it.first <= NearTarget }
            .minByOrNull { it.first }
            ?.second
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
