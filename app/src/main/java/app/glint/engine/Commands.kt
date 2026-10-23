package app.glint.engine

import org.json.JSONArray
import org.json.JSONObject

/** A saved set of yt-dlp arguments, run on a link instead of Glint's own choices. */
data class CommandTemplate(val id: String, val name: String, val args: String)

internal object CommandTemplates {
    /** Useful starting points; they can be edited or deleted like any other template. */
    val Starters =
        listOf(
            CommandTemplate(
                id = "starter-mp4",
                name = "Best MP4",
                args = "-f \"bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/bv*+ba/b\" --merge-output-format mp4 --embed-metadata",
            ),
            CommandTemplate(
                id = "starter-mp3",
                name = "MP3 320k with cover",
                args = "-x --audio-format mp3 --audio-quality 320K --embed-thumbnail --convert-thumbnails jpg --embed-metadata",
            ),
            CommandTemplate(
                id = "starter-playlist-audio",
                name = "Whole playlist as M4A",
                args = "--yes-playlist -x --audio-format m4a -o \"%(playlist_title|Playlist)s/%(playlist_index)03d %(title).150B.%(ext)s\"",
            ),
            CommandTemplate(
                id = "starter-subs",
                name = "720p with all subtitles",
                args = "-f \"bv*[height<=720]+ba/b[height<=720]/b\" --embed-subs --sub-langs all --write-auto-subs",
            ),
        )

    fun encode(templates: List<CommandTemplate>): String =
        JSONArray(
                templates.map {
                    JSONObject().put("id", it.id).put("name", it.name).put("args", it.args)
                })
            .toString()

    /** Unreadable entries are skipped rather than losing every template. */
    fun decode(json: String): List<CommandTemplate> {
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            CommandTemplate(id = id, name = item.optString("name"), args = item.optString("args"))
        }
    }
}

/** Reads and writes cookies in the Netscape format yt-dlp expects. */
object NetscapeCookies {
    const val Header = "# Netscape HTTP Cookie File"

    /** True when the text looks like a cookies.txt export rather than some other file. */
    fun looksValid(text: String): Boolean = lines(text).isNotEmpty()

    /** The cookie lines of a file: seven tab-separated fields, comments and blanks skipped. */
    fun lines(text: String): List<String> =
        text.lineSequence()
            .map { it.trimEnd('\r') }
            .filter { line ->
                val content = line.removePrefix("#HttpOnly_")
                (line.startsWith("#HttpOnly_") || !line.startsWith("#")) && content.split('\t').size == 7
            }
            .toList()

    /** The host a cookie line belongs to, without a leading dot or HttpOnly marker. */
    fun domainOf(line: String): String = line.removePrefix("#HttpOnly_").substringBefore('\t').trimStart('.').lowercase()

    /**
     * Cookies as a browser reports them ("a=1; b=2") for [host], valid for its subdomains too.
     * They are given a year to live, since a browser does not say when they expire.
     */
    fun fromBrowser(host: String, cookieHeader: String, nowSeconds: Long): List<String> {
        val domain = "." + host.lowercase().removePrefix("www.").trimStart('.')
        val expires = nowSeconds + 365L * 24 * 60 * 60
        return cookieHeader.split(';').mapNotNull { pair ->
            val name = pair.substringBefore('=').trim()
            if (name.isEmpty() || !pair.contains('=')) return@mapNotNull null
            val value = pair.substringAfter('=').trim()
            listOf(domain, "TRUE", "/", "TRUE", expires.toString(), name, value).joinToString("\t")
        }
    }

    /** Replaces the cookies of the hosts in [fresh] and keeps every other site's cookies. */
    fun merge(existing: String, fresh: List<String>): String {
        val replaced = fresh.mapTo(HashSet(), ::domainOf)
        val kept = lines(existing).filter { line ->
            val domain = domainOf(line)
            replaced.none { domain == it || domain.endsWith(".$it") }
        }
        return (listOf(Header, "") + kept + fresh).joinToString("\n", postfix = "\n")
    }

    /** The sites a cookies file has cookies for, for showing the user. */
    fun sites(text: String): List<String> =
        lines(text).map(::domainOf).map { it.removePrefix("www.") }.distinct().sorted()
}
