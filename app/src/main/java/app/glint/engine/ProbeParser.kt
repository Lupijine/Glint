package app.glint.engine

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

/** One stream a site offers. Codecs are null when the stream has no such track. */
data class MediaFormat(
    val id: String,
    val ext: String,
    val width: Int?,
    val height: Int?,
    val fps: Int?,
    val videoCodec: String?,
    val audioCodec: String?,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    val bitrateKbps: Double?,
    val sizeBytes: Long?,
    val sizeIsEstimate: Boolean,
    val note: String,
    val dynamicRange: String?,
    val language: String?,
)

/** What a link turned out to be, read before downloading so the user can choose. */
sealed interface Probe {
    val url: String
    val title: String
    val source: String

    data class Video(
        override val url: String,
        override val title: String,
        override val source: String,
        val thumbnail: String?,
        val uploader: String?,
        val durationSeconds: Int,
        /** Ordered worst to best, as yt-dlp ranks them. */
        val formats: List<MediaFormat>,
        /** The link also names a playlist, which can be loaded instead. */
        val inPlaylist: Boolean,
        /** The video's chapters, in order; empty when it has none. */
        val chapters: List<Chapter> = emptyList(),
    ) : Probe

    data class Playlist(
        override val url: String,
        override val title: String,
        override val source: String,
        val uploader: String?,
        val entries: List<PlaylistEntry>,
        /** Search results rather than a real playlist: nothing is selected at first. */
        val isSearch: Boolean = false,
    ) : Probe
}

data class PlaylistEntry(
    val url: String,
    val title: String,
    val durationSeconds: Int,
    val thumbnail: String?,
    /** A nested list, such as a channel's Videos tab, which opens instead of downloading. */
    val isPlaylist: Boolean,
    val uploader: String? = null,
)

/** Parses yt-dlp's -J output. Missing and null fields are treated the same on every platform. */
internal object ProbeParser {
    private const val PreviewThumbnailWidth = 320

    fun parse(json: String, requestedUrl: String): Probe {
        val start = json.indexOf('{')
        val end = json.lastIndexOf('}')
        check(start >= 0 && end > start) { "The site returned no details for this link" }
        val root = JSONObject(json.substring(start, end + 1))
        val isPlaylist =
            root.text("_type") == "playlist" || (root.has("entries") && !root.has("formats"))
        return if (isPlaylist) playlist(root, requestedUrl) else video(root, requestedUrl)
    }

    private fun video(root: JSONObject, requestedUrl: String): Probe.Video {
        val listed = root.optJSONArray("formats")?.objects()?.mapNotNull(::parseFormat)?.toList().orEmpty()
        // Sites with a single stream describe it on the video itself.
        val formats = listed.ifEmpty { listOfNotNull(parseFormat(root)) }
        return Probe.Video(
            url = requestedUrl,
            title = root.text("title") ?: root.text("fulltitle") ?: requestedUrl,
            source = source(root),
            thumbnail = root.text("thumbnail")?.takeIf(Links::isWebLink) ?: thumbnail(root),
            uploader = root.text("uploader") ?: root.text("channel"),
            durationSeconds = root.number("duration")?.roundToInt() ?: 0,
            formats = formats,
            inPlaylist = Links.namesPlaylist(requestedUrl),
            chapters = chapters(root),
        )
    }

    /**
     * Chapters in whole seconds: starts rounded down and ends rounded up, so no part is lost. A
     * chapter without an end runs to the next one, or to the end of the video.
     */
    private fun chapters(root: JSONObject): List<Chapter> {
        val array = root.optJSONArray("chapters") ?: return emptyList()
        val duration = root.number("duration")?.let { ceil(it).toInt() }
        val raw = array.objects().mapNotNull { json -> json.number("start_time")?.let { it to json } }.toList()
        return raw.mapIndexedNotNull { index, (start, json) ->
            val next = raw.getOrNull(index + 1)?.first
            val end = json.number("end_time") ?: next ?: duration?.toDouble() ?: return@mapIndexedNotNull null
            val from = floor(start).toInt().coerceAtLeast(0)
            val to = ceil(end).toInt().let { if (duration != null) it.coerceAtMost(duration) else it }
            if (to <= from) return@mapIndexedNotNull null
            Chapter(start = from, end = to, title = json.text("title") ?: "${index + 1}")
        }
    }

    private fun playlist(root: JSONObject, requestedUrl: String): Probe.Playlist =
        Probe.Playlist(
            url = requestedUrl,
            title = root.text("title") ?: root.text("id") ?: requestedUrl,
            source = source(root),
            uploader = root.text("uploader") ?: root.text("channel"),
            entries = root.optJSONArray("entries")?.objects()?.mapNotNull(::parseEntry)?.toList().orEmpty(),
            isSearch = Search.isQuery(requestedUrl),
        )

    private fun parseEntry(json: JSONObject): PlaylistEntry? {
        val url =
            json.text("url")?.takeIf(Links::isWebLink)
                ?: json.text("webpage_url")?.takeIf(Links::isWebLink)
                ?: return null
        val key = json.text("ie_key").orEmpty()
        val nested = json.text("_type") == "playlist" || key.endsWith("Tab") || key.endsWith("Playlist")
        return PlaylistEntry(
            url = url,
            title = json.text("title") ?: json.text("id") ?: url,
            durationSeconds = json.number("duration")?.roundToInt() ?: 0,
            thumbnail = thumbnail(json) ?: json.text("thumbnail")?.takeIf(Links::isWebLink),
            isPlaylist = nested,
            uploader = json.text("uploader") ?: json.text("channel"),
        )
    }

    private fun parseFormat(json: JSONObject): MediaFormat? {
        val id = json.text("format_id") ?: return null
        if (json.text("protocol") == "mhtml") return null // Storyboard images, not media.
        val videoCodec = json.text("vcodec")
        val audioCodec = json.text("acodec")
        val width = json.number("width")?.toInt()
        val height = json.number("height")?.toInt()
        val hasVideo =
            when {
                videoCodec == "none" -> false
                videoCodec != null -> true
                else -> json.text("video_ext")?.let { it != "none" } ?: (height != null || audioCodec == null)
            }
        val hasAudio =
            when {
                audioCodec == "none" -> false
                audioCodec != null -> true
                else -> json.text("audio_ext")?.let { it != "none" } ?: (videoCodec == null)
            }
        if (!hasVideo && !hasAudio) return null
        val exactSize = json.number("filesize")?.toLong()?.takeIf { it > 0 }
        val approximateSize = json.number("filesize_approx")?.toLong()?.takeIf { it > 0 }
        return MediaFormat(
            id = id,
            ext = json.text("ext").orEmpty(),
            width = width?.takeIf { it > 0 },
            height = height?.takeIf { it > 0 },
            fps = json.number("fps")?.roundToInt()?.takeIf { it > 0 },
            videoCodec = videoCodec?.takeIf { hasVideo && it != "none" },
            audioCodec = audioCodec?.takeIf { hasAudio && it != "none" },
            hasVideo = hasVideo,
            hasAudio = hasAudio,
            bitrateKbps = json.number("tbr")?.takeIf { it > 0 },
            sizeBytes = exactSize ?: approximateSize,
            sizeIsEstimate = exactSize == null,
            note = json.text("format_note").orEmpty(),
            dynamicRange = json.text("dynamic_range"),
            language = json.text("language"),
        )
    }

    private fun source(json: JSONObject): String =
        json.text("extractor_key") ?: json.text("ie_key") ?: json.text("extractor") ?: ""

    /** The smallest thumbnail that still looks sharp in a list, or the largest when sizes are unknown. */
    private fun thumbnail(json: JSONObject): String? {
        val candidates =
            json.optJSONArray("thumbnails")
                ?.objects()
                ?.mapNotNull { thumb ->
                    thumb.text("url")?.takeIf(Links::isWebLink)?.let { it to (thumb.number("width")?.toInt() ?: 0) }
                }
                ?.toList()
                .orEmpty()
        return candidates.filter { it.second >= PreviewThumbnailWidth }.minByOrNull { it.second }?.first
            ?: candidates.lastOrNull()?.first
    }

    private fun JSONArray.objects(): Sequence<JSONObject> =
        (0 until length()).asSequence().mapNotNull { optJSONObject(it) }

    private fun JSONObject.text(name: String): String? =
        if (isNull(name)) null else opt(name)?.toString()?.takeIf { it.isNotBlank() }

    private fun JSONObject.number(name: String): Double? =
        when (val value = if (isNull(name)) null else opt(name)) {
            is Number -> value.toDouble().takeIf { !it.isNaN() && !it.isInfinite() }
            is String -> value.toDoubleOrNull()
            else -> null
        }
}

/** Searching a site with yt-dlp instead of opening a link. */
object Search {
    private const val Prefix = "ytsearch"
    const val Results = 20

    fun query(text: String): String = "$Prefix$Results:${text.trim()}"

    fun isQuery(url: String): Boolean = url.startsWith(Prefix)

    /** Text worth searching for: not a link, and more than a stray character. */
    fun canSearch(text: String): Boolean = text.isNotBlank() && text.trim().length >= 2 && Links.first(text) == null
}
