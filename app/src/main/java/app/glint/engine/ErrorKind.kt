package app.glint.engine

import androidx.annotation.StringRes
import app.glint.R

/**
 * What went wrong, in words a person can act on. yt-dlp's own output stays available under
 * Details, for copying into a bug report.
 */
enum class ErrorKind(@StringRes val message: Int) {
    Network(R.string.error_network),
    RateLimited(R.string.error_rate_limited),
    BotCheck(R.string.error_bot_check),
    SignIn(R.string.error_sign_in),
    Unavailable(R.string.error_unavailable),
    Geo(R.string.error_geo),
    NotStarted(R.string.error_not_started),
    Drm(R.string.error_drm),
    Unsupported(R.string.error_unsupported),
    Format(R.string.error_format),
    Storage(R.string.error_storage),
    Folder(R.string.folder_failed),
    Outdated(R.string.error_outdated),
    Unknown(R.string.error_unknown);

    companion object {
        private val Ignore = RegexOption.IGNORE_CASE

        // Checked in order: the first match wins, so specific causes come before general ones.
        private val Rules: List<Pair<ErrorKind, Regex>> =
            listOf(
                BotCheck to Regex("not a bot", Ignore),
                Unsupported to Regex("Unsupported URL|is not a valid URL|no suitable (info)?extractor", Ignore),
                NotStarted to
                    Regex("live event will begin|premieres in|this live event|is upcoming|scheduled to (start|begin)", Ignore),
                Drm to Regex("\\bDRM\\b"),
                Geo to Regex("in your country|geo.?restrict|not available in your (region|location)", Ignore),
                Storage to Regex("No space left|Errno 28\\b|ENOSPC|disk (is )?full", Ignore),
                Folder to Regex("Permission denied|Errno 13\\b|Read-only file system|EACCES|EROFS|to the chosen folder", Ignore),
                Format to Regex("Requested format is not available", Ignore),
                SignIn to
                    Regex(
                        "sign in|log ?in|private video|members.only|confirm your age|age.restricted|" +
                            "requires authentication|account (is )?required|--cookies",
                        Ignore,
                    ),
                // After sign-in: "rate-limit reached or login required" is solved by signing in.
                RateLimited to Regex("HTTP Error 429|too many requests|rate.?limit|try again later", Ignore),
                Unavailable to
                    Regex(
                        "video unavailable|no longer available|has been removed|been terminated|does not exist|" +
                            "HTTP Error 404|404: Not Found|been deleted|is not available",
                        Ignore,
                    ),
                Outdated to
                    Regex(
                        "unable to extract|extraction failed|failed to extract|HTTP Error 403|" +
                            "Precondition check failed|nsig extraction|signature (extraction|function)",
                        Ignore,
                    ),
                Network to
                    Regex(
                        "Errno (101|104|110|111|113|7|-2|-3)\\b|connection (reset|refused|aborted|timed out|closed)|" +
                            "timed? ?out|name resolution|resolve host|network is unreachable|no address associated|" +
                            "remote end closed|IncompleteRead|unable to connect|\\bSSL\\b|EOF occurred|" +
                            "HTTP Error 5\\d\\d|urlopen error|Unable to download (webpage|video data)",
                        Ignore,
                    ),
            )

        /** The kind of error behind yt-dlp's [message], or [Unknown]. */
        fun of(message: String?): ErrorKind {
            if (message.isNullOrBlank()) return Unknown
            return Rules.firstOrNull { (_, pattern) -> pattern.containsMatchIn(message) }?.first ?: Unknown
        }

        /**
         * The kind of error behind a failed download: its summary line first, then the last lines
         * of the full output when the summary alone does not say.
         */
        fun of(summary: String?, detail: String?): ErrorKind =
            of(summary).takeIf { it != Unknown }
                ?: of(detail?.lines()?.filter { it.isNotBlank() }?.takeLast(4)?.joinToString("\n"))
    }
}

/** Why this download failed, for the card, the notification and the error dialog. */
val DownloadItem.errorKind: ErrorKind
    get() = ErrorKind.of(error, errorDetail)
