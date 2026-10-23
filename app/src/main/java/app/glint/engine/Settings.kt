package app.glint.engine

/** How video streams are ranked when several share a resolution, or when quality is "best". */
enum class VideoPreference(val formatSort: String?) {
    /** yt-dlp's own ranking: the highest quality, whatever the codec. */
    BestQuality(null),
    /** H.264 video and AAC audio in MP4, which every phone and editor can play. */
    Compatible("vcodec:h264,res,acodec:m4a"),
}

/** How finished files are arranged inside the download folder. */
enum class FolderLayout {
    OneFolder,
    ByType,
    BySite,
}

enum class SponsorBlockMode {
    Off,
    /** Sponsor segments become chapters, so they can be skipped. */
    Mark,
    /** Sponsor, self-promotion and subscribe reminders are cut out. */
    Remove,
}

enum class UpdateChannel {
    Stable,
    Nightly,
}

/** Where finished files are saved. */
enum class StorageLocation {
    /** Download/Glint. */
    Downloads,
    /** Movies/Glint for videos and Music/Glint for audio, where galleries and players look. */
    MediaFolders,
    /** A folder picked with the system picker, on the phone or a memory card. */
    CustomFolder,
}

data class GlintSettings(
    val defaultType: MediaType = MediaType.Video,
    val defaultQuality: Quality = Quality.Best,
    val defaultAudioFormat: AudioFormat = AudioFormat.Original,
    val videoPreference: VideoPreference = VideoPreference.BestQuality,
    val concurrentDownloads: Int = 3,
    /** Downloads several pieces of a stream at once; most sites split videos into pieces. */
    val fasterDownloads: Boolean = true,
    val folderLayout: FolderLayout = FolderLayout.OneFolder,
    val subtitles: Boolean = false,
    /** yt-dlp language patterns such as "en.*,ar"; blank means the phone's language and English. */
    val subtitleLanguages: String = "",
    val autoSubtitles: Boolean = false,
    /** Cover art in audio files. */
    val embedThumbnail: Boolean = true,
    val embedMetadata: Boolean = true,
    val sponsorBlock: SponsorBlockMode = SponsorBlockMode.Off,
    val proxy: String = "",
    /** A yt-dlp rate such as "2M" or "500K"; blank means no limit. */
    val speedLimit: String = "",
    val notifyFinished: Boolean = true,
    val updateChannel: UpdateChannel = UpdateChannel.Nightly,
    val updateOnLaunch: Boolean = true,
    val storageLocation: StorageLocation = StorageLocation.Downloads,
    /** The picked folder's content URI, kept with a lasting permission. */
    val customFolderUri: String = "",
    /** The picked folder's name, for showing in settings. */
    val customFolderName: String = "",
    /** How often followed channels and playlists are checked for new videos. */
    val followIntervalHours: Int = 12,
    val followWifiOnly: Boolean = false,
    /** The chosen theme's id; unknown ids show the default theme. */
    val theme: String = "chrome",
) {
    companion object {
        val ConcurrentRange = 1..5
        val FollowIntervals = listOf(3, 6, 12, 24)
        const val FragmentThreads = 4
        private val SpeedLimitPattern = Regex("""^\d+(\.\d+)?[KkMmGg]?$""")

        fun isValidSpeedLimit(text: String): Boolean = text.isBlank() || SpeedLimitPattern.matches(text.trim())
    }
}

/** Builds yt-dlp arguments. Kept free of Android so every combination can be tested. */
object DownloadArgs {
    private const val SponsorCategories = "sponsor,selfpromo,interaction"

    /** Options shared by reading a link and downloading it. */
    fun network(settings: GlintSettings, cookiesPath: String?): List<String> = buildList {
        if (cookiesPath != null) addAll(listOf("--cookies", cookiesPath))
        settings.proxy.trim().takeIf { it.isNotEmpty() }?.let { addAll(listOf("--proxy", it)) }
    }

    fun probe(
        wholePlaylist: Boolean,
        settings: GlintSettings,
        cookiesPath: String?,
        playlistEnd: Int? = null,
    ): List<String> = buildList {
        addAll(listOf("-J", "--flat-playlist"))
        if (!wholePlaylist) add("--no-playlist")
        playlistEnd?.let { addAll(listOf("--playlist-end", it.toString())) }
        addAll(listOf("-R", "1", "--socket-timeout", "10"))
        settings.videoPreference.formatSort?.let { addAll(listOf("-S", it)) }
        addAll(network(settings, cookiesPath))
    }

    /** The subtitle languages to ask for, falling back to the phone's language and English. */
    fun subtitleLanguages(settings: GlintSettings, deviceLanguage: String): String {
        val chosen = settings.subtitleLanguages.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (chosen.isNotEmpty()) return chosen.joinToString(",")
        return listOf(deviceLanguage.lowercase(), "en").filter { it.isNotBlank() }.distinct().joinToString(",") { "$it.*" }
    }

    fun download(
        item: DownloadItem,
        settings: GlintSettings,
        homeDirectory: String,
        temporaryDirectory: String,
        markerPath: String,
        outputTemplate: String,
        cookiesPath: String?,
        deviceLanguage: String,
    ): List<String> = buildList {
        addAll(listOf("--no-mtime", "-R", "3", "--socket-timeout", "10"))
        addAll(listOf("-P", "home:$homeDirectory", "-P", "temp:$temporaryDirectory"))
        addAll(listOf("--print-to-file", "after_move:filepath", markerPath))
        addAll(network(settings, cookiesPath))
        settings.speedLimit.trim().takeIf { it.isNotEmpty() && GlintSettings.isValidSpeedLimit(it) }?.let {
            addAll(listOf("-r", it))
        }

        val custom = item.customArgs
        if (custom != null) {
            // A template's own options come last, so they override everything above.
            val args = CommandLine.split(custom)
            if (args.none { it == "-o" || it == "--output" || it.startsWith("--output=") }) {
                addAll(listOf("-o", outputTemplate))
            }
            addAll(args)
            return@buildList
        }

        addAll(listOf("--no-playlist", "-o", outputTemplate, "-f", item.formatSelector))
        settings.videoPreference.formatSort?.let { addAll(listOf("-S", it)) }
        if (item.sectionStart != null && item.sectionEnd != null) {
            addAll(listOf("--download-sections", Clips.section(item.sectionStart, item.sectionEnd)))
            if (item.preciseCut) add("--force-keyframes-at-cuts")
        }
        if (settings.fasterDownloads) addAll(listOf("-N", GlintSettings.FragmentThreads.toString()))
        if (settings.embedMetadata) add("--embed-metadata")
        when (settings.sponsorBlock) {
            SponsorBlockMode.Off -> Unit
            SponsorBlockMode.Mark -> addAll(listOf("--sponsorblock-mark", "all"))
            SponsorBlockMode.Remove -> addAll(listOf("--sponsorblock-remove", SponsorCategories))
        }
        when (item.type) {
            MediaType.Audio -> {
                add("-x")
                item.audioFormat.codec?.let { addAll(listOf("--audio-format", it, "--audio-quality", "0")) }
                // Covers are converted to JPEG, the one image type every audio container accepts.
                if (settings.embedThumbnail) addAll(listOf("--embed-thumbnail", "--convert-thumbnails", "jpg"))
            }
            MediaType.Video ->
                if (settings.subtitles) {
                    addAll(listOf("--embed-subs", "--sub-langs", subtitleLanguages(settings, deviceLanguage)))
                    if (settings.autoSubtitles) add("--write-auto-subs")
                }
        }
    }
}

/** Splits a command line into arguments, honouring quotes and backslashes like a shell. */
object CommandLine {
    fun split(text: String): List<String> {
        val args = mutableListOf<String>()
        val current = StringBuilder()
        var inArgument = false
        var quote: Char? = null
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quote == '\'' ->
                    if (c == '\'') quote = null else current.append(c)
                c == '\\' && i + 1 < text.length && (quote == null || text[i + 1] in "\"\\$`") -> {
                    // Inside double quotes a backslash only escapes characters special there.
                    if (!(quote == null && text[i + 1] == '\n')) current.append(text[i + 1])
                    inArgument = true
                    i++
                }
                // Phone keyboards often turn straight quotes into curly ones.
                quote == '"' ->
                    if (c == '"' || c == '“' || c == '”') quote = null else current.append(c)
                c == '"' || c == '“' || c == '”' -> {
                    quote = '"'
                    inArgument = true
                }
                c == '\'' -> {
                    quote = '\''
                    inArgument = true
                }
                c.isWhitespace() ->
                    if (inArgument) {
                        args.add(current.toString())
                        current.clear()
                        inArgument = false
                    }
                else -> {
                    current.append(c)
                    inArgument = true
                }
            }
            i++
        }
        if (inArgument) args.add(current.toString())
        return args
    }

    /** True when the arguments extract audio, so the download is shown as audio. */
    fun extractsAudio(text: String): Boolean = split(text).any { it == "-x" || it == "--extract-audio" }
}
