package app.glint.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadArgsTest {
    private val video =
        DownloadItem(id = "1", url = "https://youtu.be/a", type = MediaType.Video, formatSelector = "137+ba/137")
    private val audio = video.copy(type = MediaType.Audio, formatSelector = "ba/b", audioFormat = AudioFormat.Mp3)

    private fun args(
        item: DownloadItem = video,
        settings: GlintSettings = GlintSettings(),
        cookies: String? = null,
        language: String = "ar",
    ) =
        DownloadArgs.download(
            item = item,
            settings = settings,
            homeDirectory = "/home",
            temporaryDirectory = "/tmp",
            markerPath = "/marker",
            outputTemplate = "%(title)s.%(ext)s",
            cookiesPath = cookies,
            deviceLanguage = language,
        )

    /** The value after an option, or null when the option is absent. */
    private fun List<String>.valueOf(option: String): String? = indexOf(option).takeIf { it >= 0 }?.let { getOrNull(it + 1) }

    @Test
    fun `a video download keeps its paths, format and marker`() {
        val a = args()

        assertEquals("137+ba/137", a.valueOf("-f"))
        assertEquals("%(title)s.%(ext)s", a.valueOf("-o"))
        assertTrue(a.containsAll(listOf("-P", "home:/home", "temp:/tmp", "--no-playlist")))
        val marker = a.indexOf("--print-to-file")
        assertEquals(listOf("after_move:filepath", "/marker"), a.subList(marker + 1, marker + 3))
    }

    @Test
    fun `default settings embed metadata and speed up fragments`() {
        val a = args()

        assertTrue("--embed-metadata" in a)
        assertEquals("4", a.valueOf("-N"))
        assertFalse("-S" in a)
        assertFalse("--embed-subs" in a)
        assertFalse("--cookies" in a)
    }

    @Test
    fun `compatible mode prefers H264 and AAC`() {
        val a = args(settings = GlintSettings(videoPreference = VideoPreference.Compatible))

        assertEquals("vcodec:h264,res,acodec:m4a", a.valueOf("-S"))
    }

    @Test
    fun `audio is extracted, converted and given a JPEG cover`() {
        val a = args(item = audio)

        assertTrue("-x" in a)
        assertEquals("mp3", a.valueOf("--audio-format"))
        assertEquals("0", a.valueOf("--audio-quality"))
        assertTrue("--embed-thumbnail" in a)
        assertEquals("jpg", a.valueOf("--convert-thumbnails"))
    }

    @Test
    fun `original audio is not converted`() {
        val a = args(item = audio.copy(audioFormat = AudioFormat.Original), settings = GlintSettings(embedThumbnail = false))

        assertTrue("-x" in a)
        assertFalse("--audio-format" in a)
        assertFalse("--embed-thumbnail" in a)
    }

    @Test
    fun `subtitles use the phone's language and English unless chosen`() {
        val on = GlintSettings(subtitles = true)

        assertEquals("ar.*,en.*", args(settings = on).valueOf("--sub-langs"))
        assertEquals("en.*", args(settings = on, language = "en").valueOf("--sub-langs"))
        assertEquals("fr,de", args(settings = on.copy(subtitleLanguages = " fr , de ,")).valueOf("--sub-langs"))
        assertFalse("--write-auto-subs" in args(settings = on))
        assertTrue("--write-auto-subs" in args(settings = on.copy(autoSubtitles = true)))
        // Subtitles are never embedded into audio files.
        assertFalse("--embed-subs" in args(item = audio, settings = on))
    }

    @Test
    fun `sponsorblock can mark or remove segments`() {
        assertEquals("all", args(settings = GlintSettings(sponsorBlock = SponsorBlockMode.Mark)).valueOf("--sponsorblock-mark"))
        assertEquals(
            "sponsor,selfpromo,interaction",
            args(settings = GlintSettings(sponsorBlock = SponsorBlockMode.Remove)).valueOf("--sponsorblock-remove"),
        )
    }

    @Test
    fun `network settings apply to downloads`() {
        val a = args(settings = GlintSettings(proxy = " socks5://127.0.0.1:9050 ", speedLimit = "2M"), cookies = "/c.txt")

        assertEquals("/c.txt", a.valueOf("--cookies"))
        assertEquals("socks5://127.0.0.1:9050", a.valueOf("--proxy"))
        assertEquals("2M", a.valueOf("-r"))
    }

    @Test
    fun `an invalid speed limit is ignored`() {
        assertFalse("-r" in args(settings = GlintSettings(speedLimit = "fast")))
        assertTrue(GlintSettings.isValidSpeedLimit("1.5M"))
        assertTrue(GlintSettings.isValidSpeedLimit("500k"))
        assertTrue(GlintSettings.isValidSpeedLimit(""))
        assertFalse(GlintSettings.isValidSpeedLimit("2 MB"))
    }

    @Test
    fun `a command replaces Glint's choices but keeps paths, marker and network`() {
        val item = video.copy(customArgs = "-f \"bv*[height<=720]+ba\" --embed-subs")
        val a = args(item = item, settings = GlintSettings(sponsorBlock = SponsorBlockMode.Remove), cookies = "/c.txt")

        assertEquals("bv*[height<=720]+ba", a.valueOf("-f"))
        assertEquals("%(title)s.%(ext)s", a.valueOf("-o"))
        assertEquals("/c.txt", a.valueOf("--cookies"))
        assertTrue("--print-to-file" in a)
        assertFalse("--no-playlist" in a)
        assertFalse("--sponsorblock-remove" in a)
        assertFalse("--embed-metadata" in a)
    }

    @Test
    fun `a command's own output template wins`() {
        val a = args(item = video.copy(customArgs = "-o \"%(id)s.%(ext)s\""))

        assertEquals(listOf("%(id)s.%(ext)s"), a.indices.filter { a[it] == "-o" }.map { a[it + 1] })
        assertFalse("-o" in args(item = video.copy(customArgs = "--output=x")))
    }

    @Test
    fun `reading a link uses the same network and preference settings`() {
        val settings = GlintSettings(videoPreference = VideoPreference.Compatible, proxy = "http://p")
        val single = DownloadArgs.probe(false, settings, "/c.txt")
        val whole = DownloadArgs.probe(true, settings, null)

        assertTrue(single.containsAll(listOf("-J", "--flat-playlist", "--no-playlist", "--cookies", "/c.txt", "--proxy")))
        assertFalse("--no-playlist" in whole)
        assertFalse("--cookies" in whole)
        assertEquals("vcodec:h264,res,acodec:m4a", whole.valueOf("-S"))
    }
}
