package app.glint.engine

import org.json.JSONArray
import org.json.JSONObject

/** A followed channel or playlist whose new videos download on their own. */
data class Subscription(
    val id: String,
    val url: String,
    val title: String,
    val source: String,
    val type: MediaType,
    val quality: Quality,
    val audioFormat: AudioFormat,
    val enabled: Boolean = true,
    val createdAt: Long = 0,
    val lastCheckedAt: Long? = null,
    val lastError: String? = null,
    /** New videos found since following, all of which were queued to download. */
    val downloadedCount: Int = 0,
)

/** What one check found: videos to download now, and every video to remember as seen. */
data class CheckPlan(val download: List<PlaylistEntry>, val markSeen: List<String>)

/** Rules for following, kept free of Android so they can be tested. */
object Subscriptions {
    /**
     * More new videos than this at once usually means the list was reordered or its history was
     * lost, not that dozens of videos appeared; only the newest are downloaded then.
     */
    const val MaxNewPerCheck = 15

    /** Channels list their newest videos first, so only the start of the list needs reading. */
    const val ChannelEntries = 60

    private val ChannelPath = Regex("""(?i)youtube\.com/(@[^/?#]+|channel/|c/|user/)""")

    /** How much of a list to read when checking it: the newest part of a channel, all of a playlist. */
    fun entriesToRead(url: String): Int? = if (ChannelPath.containsMatchIn(url)) ChannelEntries else null

    /** The videos in [entries] not yet seen, newest first as listed, capped to [MaxNewPerCheck]. */
    fun plan(entries: List<PlaylistEntry>, seen: Set<String>, newestFirst: Boolean): CheckPlan {
        val unseen = entries.filter { !it.isPlaylist && it.url !in seen }.distinctBy { it.url }
        val newest = if (newestFirst) unseen else unseen.asReversed()
        return CheckPlan(download = newest.take(MaxNewPerCheck), markSeen = unseen.map { it.url })
    }

    /** When following, everything already in the list counts as seen; only later videos download. */
    fun initialSeen(entries: List<PlaylistEntry>): List<String> =
        entries.filter { !it.isPlaylist }.map { it.url }.distinct()

    fun encode(subscriptions: List<Subscription>): String =
        JSONArray(
                subscriptions.map { s ->
                    JSONObject()
                        .put("id", s.id)
                        .put("url", s.url)
                        .put("title", s.title)
                        .put("source", s.source)
                        .put("type", s.type.name)
                        .put("quality", s.quality.name)
                        .put("audioFormat", s.audioFormat.name)
                        .put("enabled", s.enabled)
                        .put("createdAt", s.createdAt)
                        .put("lastCheckedAt", s.lastCheckedAt ?: JSONObject.NULL)
                        .put("lastError", s.lastError ?: JSONObject.NULL)
                        .put("downloadedCount", s.downloadedCount)
                })
            .toString()

    /** Unreadable entries are skipped rather than losing every subscription. */
    fun decode(json: String): List<Subscription> {
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val o = array.optJSONObject(index) ?: return@mapNotNull null
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val url = o.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Subscription(
                id = id,
                url = url,
                title = o.optString("title").ifBlank { url },
                source = o.optString("source"),
                type = MediaType.entries.firstOrNull { it.name == o.optString("type") } ?: MediaType.Video,
                quality = Quality.entries.firstOrNull { it.name == o.optString("quality") } ?: Quality.Best,
                audioFormat =
                    AudioFormat.entries.firstOrNull { it.name == o.optString("audioFormat") } ?: AudioFormat.Original,
                enabled = o.optBoolean("enabled", true),
                createdAt = o.optLong("createdAt"),
                lastCheckedAt = if (o.isNull("lastCheckedAt")) null else o.optLong("lastCheckedAt"),
                lastError = if (o.isNull("lastError")) null else o.optString("lastError"),
                downloadedCount = o.optInt("downloadedCount"),
            )
        }
    }
}
