package app.glint.engine

enum class MediaType {
    Video,
    Audio,
}

/** A preset for links whose formats are not known yet; [maxHeight] null means the best. */
enum class Quality(val maxHeight: Int?) {
    Best(null),
    P1080(1080),
    P720(720),
    P480(480),
}

/** Audio conversion; [codec] null keeps the original audio stream. */
enum class AudioFormat(val codec: String?) {
    Original(null),
    Mp3("mp3"),
    M4a("m4a"),
    Opus("opus"),
}

/** The program that gets a download. */
enum class Tool {
    /** yt-dlp; when it cannot read a link, Glint's other ways are tried after it. */
    YtDlp,

    /** gallery-dl: every picture and video of a post, album or profile. */
    GalleryDl,

    /**
     * Glint's hidden browser, for Facebook and Instagram videos yt-dlp cannot read. The format
     * selector is then [BrowserQuality]'s name.
     */
    Browser,
}

/** The quality the hidden browser picks when a page offers more than one. */
enum class BrowserQuality {
    Best,
    Small,
}

/**
 * Everything needed to start one download. Details that are already known, such as a title read
 * while choosing a format, let the download start without asking the site again.
 */
data class DownloadRequest(
    val url: String,
    val type: MediaType,
    /** A yt-dlp format selector, passed to -f. */
    val formatSelector: String,
    /** A short, language-neutral label such as "1080p60" or "MP3"; empty for the best quality. */
    val formatLabel: String = "",
    val audioFormat: AudioFormat = AudioFormat.Original,
    val title: String = "",
    val thumbnail: String? = null,
    val source: String = "",
    val durationSeconds: Int = 0,
    val sizeBytes: Long = 0,
    /** A subfolder of the download folder, used for playlists. */
    val folder: String? = null,
    /** yt-dlp arguments from a saved command, used instead of Glint's format choices. */
    val customArgs: String? = null,
    /** A clip of the video, in seconds; both null for the whole video. */
    val sectionStart: Int? = null,
    val sectionEnd: Int? = null,
    /** Cuts exactly at the chosen times by re-encoding the edges, which takes longer. */
    val preciseCut: Boolean = false,
    /** The followed channel or playlist this download came from. */
    val subscriptionId: String? = null,
    val tool: Tool = Tool.YtDlp,
)

data class DownloadItem(
    val id: String,
    val url: String,
    val type: MediaType,
    val formatSelector: String,
    val formatLabel: String = "",
    val audioFormat: AudioFormat = AudioFormat.Original,
    val status: Status = Status.Queued,
    val title: String = "",
    val thumbnail: String? = null,
    val source: String = "",
    val durationSeconds: Int = 0,
    val sizeBytes: Long = 0,
    /** Download progress from 0 to 1, or null when unknown. */
    val progress: Float? = null,
    val speed: String = "",
    val etaSeconds: Long = -1,
    val filePath: String? = null,
    val fileUri: String? = null,
    /** One readable line about a failure. */
    val error: String? = null,
    /** The downloader's full error output, shown on request. */
    val errorDetail: String? = null,
    val folder: String? = null,
    val customArgs: String? = null,
    val sectionStart: Int? = null,
    val sectionEnd: Int? = null,
    val preciseCut: Boolean = false,
    val subscriptionId: String? = null,
    val createdAt: Long = 0,
    val completedAt: Long? = null,
    /** Shown on the Downloads page. Finished files stay in the library after leaving it. */
    val inQueue: Boolean = true,
    /** A finished file that is no longer on the device. Checked on the device, never stored. */
    val fileMissing: Boolean = false,
    val tool: Tool = Tool.YtDlp,
) {
    enum class Status {
        /** Waiting for a free download slot. */
        Queued,
        Preparing,
        Downloading,
        Completed,
        Failed,
        Canceled,
        /** Was running when the app was closed; it resumes where it stopped. */
        Interrupted,
        /** Stopped by the user; it resumes where it stopped. */
        Paused,
        /** Downloaded, and being copied into a folder the user chose. */
        Saving,
    }

    val isActive: Boolean
        get() =
            status == Status.Queued ||
                status == Status.Preparing ||
                status == Status.Downloading ||
                status == Status.Saving

    /** Stopped part way, with its downloaded pieces kept so it can continue. */
    val canResume: Boolean
        get() = status == Status.Paused || status == Status.Interrupted || status == Status.Failed

    /** A finished download whose file Glint can find, by path or through the folder picker. */
    val hasFile: Boolean
        get() = status == Status.Completed && (filePath != null || fileUri != null)

    val isClip: Boolean
        get() = sectionStart != null && sectionEnd != null
}
