package app.glint.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeParserTest {
    private val youtubeVideo =
        """
        {
          "id": "abc", "title": "A video", "extractor_key": "Youtube", "uploader": "Someone",
          "duration": 212.4, "thumbnail": "https://i.ytimg.com/vi/abc/maxresdefault.jpg",
          "formats": [
            {"format_id": "sb0", "ext": "mhtml", "protocol": "mhtml", "vcodec": "none", "acodec": "none"},
            {"format_id": "139", "ext": "m4a", "vcodec": "none", "acodec": "mp4a.40.5", "tbr": 48.8, "filesize": 1300000},
            {"format_id": "251", "ext": "webm", "vcodec": "none", "acodec": "opus", "tbr": 130.1, "filesize": 3400000, "language": "en"},
            {"format_id": "18", "ext": "mp4", "width": 640, "height": 360, "fps": 30, "vcodec": "avc1.42001E", "acodec": "mp4a.40.2", "filesize_approx": 9000000},
            {"format_id": "136", "ext": "mp4", "width": 1280, "height": 720, "fps": 30, "vcodec": "avc1.4d401f", "acodec": "none", "filesize": 20000000, "dynamic_range": "SDR"},
            {"format_id": "299", "ext": "mp4", "width": 1920, "height": 1080, "fps": 60, "vcodec": "avc1.64002a", "acodec": "none", "filesize": null, "tbr": 4000, "dynamic_range": "SDR"},
            {"format_id": "399", "ext": "mp4", "width": 1920, "height": 1080, "fps": 60, "vcodec": "av01.0.09M.08", "acodec": "none", "filesize": 30000000, "dynamic_range": "HDR10"}
          ]
        }
        """
            .trimIndent()

    @Test
    fun `a video lists its media formats and skips storyboards`() {
        val video = ProbeParser.parse(youtubeVideo, "https://youtu.be/abc") as Probe.Video

        assertEquals("A video", video.title)
        assertEquals("Youtube", video.source)
        assertEquals("Someone", video.uploader)
        assertEquals(212, video.durationSeconds)
        assertEquals(listOf("139", "251", "18", "136", "299", "399"), video.formats.map { it.id })
        assertFalse(video.inPlaylist)
    }

    @Test
    fun `stream kinds and sizes are read`() {
        val formats = (ProbeParser.parse(youtubeVideo, "https://youtu.be/abc") as Probe.Video).formats
        val audio = formats.first { it.id == "251" }
        val muxed = formats.first { it.id == "18" }
        val videoOnly = formats.first { it.id == "299" }

        assertTrue(audio.hasAudio && !audio.hasVideo)
        assertNull(audio.videoCodec)
        assertEquals("en", audio.language)
        assertTrue(muxed.hasAudio && muxed.hasVideo)
        assertEquals(9000000L, muxed.sizeBytes)
        assertTrue(muxed.sizeIsEstimate)
        assertTrue(videoOnly.hasVideo && !videoOnly.hasAudio)
        assertNull(videoOnly.sizeBytes)
        assertEquals(4000.0, videoOnly.bitrateKbps!!, 0.0)
    }

    @Test
    fun `a video link that names a playlist offers it`() {
        val video = ProbeParser.parse(youtubeVideo, "https://www.youtube.com/watch?v=abc&list=PL1") as Probe.Video

        assertTrue(video.inPlaylist)
    }

    @Test
    fun `a site with one stream describes it on the video itself`() {
        val json = """{"id": "x", "title": "Clip", "ext": "mp4", "url": "https://cdn.example/x.mp4", "format_id": "0", "extractor": "generic"}"""

        val video = ProbeParser.parse(json, "https://example.com/x") as Probe.Video

        assertEquals(1, video.formats.size)
        assertTrue(video.formats.single().hasVideo)
        assertTrue(video.formats.single().hasAudio)
        assertEquals("generic", video.source)
    }

    @Test
    fun `a flat playlist lists its entries`() {
        val json =
            """
            {
              "_type": "playlist", "title": "My list", "uploader": null, "extractor_key": "YoutubeTab",
              "entries": [
                {"_type": "url", "ie_key": "Youtube", "id": "a", "title": "First", "url": "https://www.youtube.com/watch?v=a", "duration": 61,
                 "thumbnails": [{"url": "https://i.ytimg.com/a/small.jpg", "width": 168}, {"url": "https://i.ytimg.com/a/mid.jpg", "width": 336}, {"url": "https://i.ytimg.com/a/big.jpg", "width": 1280}]},
                {"_type": "url", "ie_key": "Youtube", "id": "b", "title": null, "url": "https://www.youtube.com/watch?v=b", "duration": null},
                {"_type": "url", "ie_key": "Youtube", "id": "c", "title": "No link", "url": "c"}
              ]
            }
            """
                .trimIndent()

        val playlist = ProbeParser.parse(json, "https://www.youtube.com/playlist?list=PL1") as Probe.Playlist

        assertEquals("My list", playlist.title)
        assertNull(playlist.uploader)
        assertEquals(2, playlist.entries.size)
        val first = playlist.entries[0]
        assertEquals("First", first.title)
        assertEquals(61, first.durationSeconds)
        assertEquals("https://i.ytimg.com/a/mid.jpg", first.thumbnail)
        assertFalse(first.isPlaylist)
        assertEquals("b", playlist.entries[1].title)
        assertEquals(0, playlist.entries[1].durationSeconds)
    }

    @Test
    fun `a channel's tabs open as nested lists`() {
        val json =
            """
            {"_type": "playlist", "title": "Channel", "entries": [
              {"_type": "url", "ie_key": "YoutubeTab", "title": "Channel - Videos", "url": "https://www.youtube.com/@c/videos"},
              {"_type": "url", "ie_key": "YoutubeTab", "title": "Channel - Shorts", "url": "https://www.youtube.com/@c/shorts"}
            ]}
            """
                .trimIndent()

        val playlist = ProbeParser.parse(json, "https://www.youtube.com/@c") as Probe.Playlist

        assertTrue(playlist.entries.all { it.isPlaylist })
    }

    @Test
    fun `log lines around the JSON are ignored`() {
        val video = ProbeParser.parse("WARNING: noise\n$youtubeVideo\n", "https://youtu.be/abc")

        assertTrue(video is Probe.Video)
    }

    @Test
    fun `output without JSON is an error`() {
        assertThrows(IllegalStateException::class.java) { ProbeParser.parse("", "https://x.example") }
    }
}
