package app.glint.engine

/** A video quality built from the streams a video really offers. */
data class VideoChoice(
    /** For example "1080p60", using the short side so portrait videos read like landscape ones. */
    val label: String,
    val height: Int,
    /** The shorter side, which is what "1080p" means for portrait and landscape videos alike. */
    val shortSide: Int,
    val hdr: Boolean,
    val codec: String?,
    /** The video plus the audio yt-dlp will merge into it, when known. */
    val sizeBytes: Long?,
    val sizeIsEstimate: Boolean,
    val selector: String,
)

/** A size that may be estimated from the bitrate rather than reported by the site. */
data class SizeEstimate(val bytes: Long, val isEstimate: Boolean)

/**
 * Turns a video's formats into choices. yt-dlp lists formats worst to best, so the last match
 * is the one yt-dlp itself would pick; every selector falls back to a looser one if a stream
 * disappears before the download starts.
 */
object FormatChoices {
    fun preset(type: MediaType, quality: Quality): String =
        when (type) {
            MediaType.Audio -> "ba/b"
            MediaType.Video ->
                quality.maxHeight?.let { h -> "bv*[height<=$h]+ba/b[height<=$h]/b" } ?: "bv*+ba/b"
        }

    fun presetLabel(type: MediaType, quality: Quality, audioFormat: AudioFormat): String =
        when (type) {
            MediaType.Audio -> audioLabel(audioFormat)
            MediaType.Video -> quality.maxHeight?.let { "${it}p" }.orEmpty()
        }

    fun audioLabel(audioFormat: AudioFormat): String = audioFormat.codec?.uppercase().orEmpty()

    /** The streams that fit a download type: audio-only streams for audio, anything with video for video. */
    fun listFor(formats: List<MediaFormat>, type: MediaType): List<MediaFormat> =
        when (type) {
            MediaType.Audio -> formats.filter { it.hasAudio && !it.hasVideo }
            MediaType.Video -> formats.filter { it.hasVideo }
        }

    /** The best audio-only stream, which yt-dlp's "ba" selects. */
    fun bestAudio(formats: List<MediaFormat>): MediaFormat? = formats.lastOrNull { it.hasAudio && !it.hasVideo }

    /** One choice per resolution, highest first. */
    fun video(formats: List<MediaFormat>, durationSeconds: Int): List<VideoChoice> {
        val audio = bestAudio(formats)
        val bestPerHeight = HashMap<Int, MediaFormat>()
        for (format in formats) {
            val height = format.height ?: continue
            if (format.hasVideo) bestPerHeight[height] = format
        }
        return bestPerHeight.values
            .sortedByDescending { it.height }
            .map { choice(it, audio, durationSeconds) }
    }

    /** The best choice within a preset, such as the highest choice at or below 720p. */
    fun pick(choices: List<VideoChoice>, preset: Quality): VideoChoice? {
        val limit = preset.maxHeight ?: return choices.firstOrNull()
        return choices.firstOrNull { it.shortSide <= limit } ?: choices.lastOrNull()
    }

    /** A selector for one exact stream, adding the best audio to a silent video stream. */
    fun selectorFor(format: MediaFormat): String =
        if (format.hasVideo && !format.hasAudio) "${format.id}+ba/${format.id}" else format.id

    fun size(format: MediaFormat, durationSeconds: Int): SizeEstimate? {
        format.sizeBytes?.let { return SizeEstimate(it, format.sizeIsEstimate) }
        val kbps = format.bitrateKbps ?: return null
        if (durationSeconds <= 0) return null
        return SizeEstimate((kbps * 1000 / 8 * durationSeconds).toLong(), isEstimate = true)
    }

    /** A short codec name for display, such as "AV1" for "av01.0.08M.08". */
    fun codecName(codec: String?): String? {
        val c = codec?.lowercase() ?: return null
        return when {
            c.startsWith("av01") -> "AV1"
            c.startsWith("vp09") || c.startsWith("vp9") -> "VP9"
            c.startsWith("avc") || c.startsWith("h264") -> "H.264"
            c.startsWith("hev") || c.startsWith("hvc") || c.startsWith("h265") -> "HEVC"
            c.startsWith("vp8") -> "VP8"
            c.startsWith("opus") -> "Opus"
            c.startsWith("mp4a") || c == "aac" -> "AAC"
            c.startsWith("mp3") -> "MP3"
            c.startsWith("vorbis") -> "Vorbis"
            c == "flac" -> "FLAC"
            else -> codec.substringBefore('.').uppercase()
        }
    }

    fun resolutionLabel(format: MediaFormat): String? {
        val height = format.height ?: return null
        val shortSide = format.width?.let { minOf(it, height) } ?: height
        val fps = format.fps?.takeIf { it > 30 }?.toString().orEmpty()
        return "${shortSide}p$fps"
    }

    private fun choice(video: MediaFormat, audio: MediaFormat?, durationSeconds: Int): VideoChoice {
        val height = video.height ?: 0
        val looser = "bv*[height<=$height]+ba/b[height<=$height]/b"
        val selector =
            if (video.hasAudio) "${video.id}/$looser" else "${video.id}+ba/${video.id}/$looser"
        val videoSize = size(video, durationSeconds)
        val audioSize = if (video.hasAudio || audio == null) null else size(audio, durationSeconds)
        val total =
            when {
                videoSize == null -> null
                video.hasAudio || audio == null -> videoSize
                audioSize == null -> null
                else -> SizeEstimate(videoSize.bytes + audioSize.bytes, videoSize.isEstimate || audioSize.isEstimate)
            }
        return VideoChoice(
            label = resolutionLabel(video) ?: "${height}p",
            height = height,
            shortSide = video.width?.let { minOf(it, height) } ?: height,
            hdr = video.dynamicRange?.let { !it.equals("SDR", ignoreCase = true) } ?: false,
            codec = codecName(video.videoCodec),
            sizeBytes = total?.bytes,
            sizeIsEstimate = total?.isEstimate ?: true,
            selector = selector,
        )
    }
}
