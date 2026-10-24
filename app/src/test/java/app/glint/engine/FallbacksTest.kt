package app.glint.engine

import java.io.File
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaLinksTest {
    @Test
    fun `facebook pages name their complete video files, the sharper one first`() {
        val html =
            """<script>{"browser_native_sd_url":"https:\/\/video.xx.fbcdn.net\/v\/sd.mp4?a=1&b=2",""" +
                """"browser_native_hd_url":"https:\/\/video.xx.fbcdn.net\/v\/hd.mp4?a=1&b=2","x":null}</script>"""

        assertEquals(
            listOf("https://video.xx.fbcdn.net/v/hd.mp4?a=1&b=2", "https://video.xx.fbcdn.net/v/sd.mp4?a=1&b=2"),
            MediaLinks.fromPage(html),
        )
    }

    @Test
    fun `addresses inside a JSON string inside the page are found too`() {
        val html = """{"data":"{\"browser_native_hd_url\":\"https:\\\/\\\/video.fbcdn.net\\\/hd.mp4?x=1\\u0026y=2\"}"}"""

        assertEquals(listOf("https://video.fbcdn.net/hd.mp4?x=1&y=2"), MediaLinks.fromPage(html))
    }

    @Test
    fun `instagram lists its video files, and pages may name one for sharing`() {
        val html =
            """{"video_versions":[{"width":1080,"height":1920,"url":"https:\/\/scontent.cdninstagram.com\/v\/big.mp4"},""" +
                """{"width":480,"height":854,"url":"https:\/\/scontent.cdninstagram.com\/v\/small.mp4"}]}""" +
                """<meta property="og:video" content="https://example.com/v.mp4?a=1&amp;b=2" />"""

        assertEquals(
            listOf("https://scontent.cdninstagram.com/v/big.mp4", "https://example.com/v.mp4?a=1&b=2"),
            MediaLinks.fromPage(html),
        )
        assertTrue(MediaLinks.fromPage("<html>nothing here</html>").isEmpty())
    }

    @Test
    fun `pieces the player fetched become whole files`() {
        assertEquals(
            "https://video-xx.fbcdn.net/o1/v/t2/f2/m69/clip.mp4?efg=abc&oh=1",
            MediaLinks.mediaUrl("https://video-xx.fbcdn.net/o1/v/t2/f2/m69/clip.mp4?efg=abc&bytestart=0&byteend=9999&oh=1"),
        )
        assertEquals(
            "https://scontent.cdninstagram.com/v/clip.mp4?oh=1",
            MediaLinks.mediaUrl("https://scontent.cdninstagram.com/v/clip.mp4?bytestart=10&byteend=20&oh=1"),
        )
        assertNull(MediaLinks.mediaUrl("https://static.xx.fbcdn.net/rsrc.php/v4/script.js"))
        assertNull(MediaLinks.mediaUrl("https://example.com/clip.mp4"))
    }

    private fun stream(name: String, tag: String, bitrate: Int): String {
        val note = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"vencode_tag":"$tag","bitrate":$bitrate}""".toByteArray())
        return "https://video.fbcdn.net/$name.mp4?efg=$note&oh=1"
    }

    @Test
    fun `streams are told apart by the note in their address`() {
        assertEquals(MediaLinks.Kind.Audio, MediaLinks.kindOf(stream("a", "dash_ln_heaac_vbr3_audio", 128)))
        assertEquals(MediaLinks.Kind.VideoOnly, MediaLinks.kindOf(stream("v", "dash_baseline_1_v1", 900)))
        assertEquals(MediaLinks.Kind.Complete, MediaLinks.kindOf(stream("p", "xpv_progressive.INSTAGRAM", 900)))
        assertEquals(MediaLinks.Kind.Unknown, MediaLinks.kindOf("https://video.fbcdn.net/x.mp4?oh=1"))
    }

    @Test
    fun `the best plan prefers a complete file, else the sharpest video with its sound`() {
        val low = stream("low", "dash_baseline_3_v1", 300)
        val high = stream("high", "dash_baseline_1_v1", 2000)
        val sound = stream("sound", "dash_ln_heaac_vbr3_audio", 128)
        val whole = stream("whole", "progressive", 1000)

        assertEquals(MediaPlan.Single("https://page/hd.mp4"), MediaLinks.choose(listOf("https://page/hd.mp4"), listOf(whole)))
        assertEquals(MediaPlan.Single(whole), MediaLinks.choose(emptyList(), listOf(low, whole, sound)))
        assertEquals(MediaPlan.Separate(high, sound), MediaLinks.choose(emptyList(), listOf(low, sound, high)))
        assertEquals(MediaPlan.Single(high), MediaLinks.choose(emptyList(), listOf(high)))
        assertEquals(MediaPlan.None, MediaLinks.choose(emptyList(), emptyList()))
    }

    @Test
    fun `file names come from page titles`() {
        assertEquals("A video by me - Facebook", MediaLinks.fileName("A video by me / Facebook".replace("/", "-"), "video"))
        assertEquals("a b c", MediaLinks.fileName("a:b\nc", "video"))
        assertEquals("video", MediaLinks.fileName("  ", "video"))
        assertEquals("video", MediaLinks.fileName(null, "video"))
    }

    @Test
    fun `base64 in both alphabets, with or without padding`() {
        assertEquals("{\"a\":1}", String(MediaLinks.base64("eyJhIjoxfQ")))
        assertEquals("{\"a\":1}", String(MediaLinks.base64("eyJhIjoxfQ==")))
        assertEquals("??>", String(MediaLinks.base64("Pz8-")))
        assertEquals("??>", String(MediaLinks.base64("Pz8+")))
    }
}

class GalleryDlTest {
    @Test
    fun `gallery-dl prints the files it saved, and those it already had after a hash`() {
        assertEquals("/data/x/a.jpg", GalleryDlOutput.savedFile("/data/x/a.jpg"))
        assertEquals("/data/x/b.jpg", GalleryDlOutput.savedFile("# /data/x/b.jpg"))
        assertNull(GalleryDlOutput.savedFile("[instagram][error] HttpError"))
        assertNull(GalleryDlOutput.savedFile(""))
    }

    @Test
    fun `gallery-dl runs with only Glint's choices`() {
        val args =
            GalleryDlOutput.arguments(
                File("/d"),
                cookies = "/c.txt",
                settings = GlintSettings(proxy = " socks5://h:1 ", speedLimit = "2M"),
                ffmpeg = File("/lib/libffmpeg.so"),
            )
        assertTrue("--config-ignore" in args)
        assertEquals("/d", args[args.indexOf("-D") + 1])
        assertEquals("/c.txt", args[args.indexOf("--cookies") + 1])
        assertEquals("socks5://h:1", args[args.indexOf("--proxy") + 1])
        assertEquals("2M", args[args.indexOf("--limit-rate") + 1])
        assertTrue("output.mode=pipe" in args)
        assertTrue(args.any { it.startsWith("downloader.ytdl.raw-options=") && it.contains("libffmpeg.so") })
        assertFalse("--cookies" in GalleryDlOutput.arguments(File("/d"), null, GlintSettings(), File("/f")))
    }

    @Test
    fun `the newest wheel is read from PyPI`() {
        val json =
            """{"info":{"version":"1.33.0"},"urls":[
                {"filename":"gallery_dl-1.33.0.tar.gz","packagetype":"sdist","url":"https://f/x.tar.gz","digests":{"sha256":"aa"}},
                {"filename":"gallery_dl-1.33.0-py3-none-any.whl","packagetype":"bdist_wheel","url":"https://f/x.whl","digests":{"sha256":"bb"}}]}"""
        val release = GalleryDlRelease.parse(json)!!
        assertEquals("1.33.0", release.version)
        assertEquals("https://f/x.whl", release.wheelUrl)
        assertEquals("bb", release.sha256)
        assertNull(GalleryDlRelease.parse("not json"))
        assertNull(GalleryDlRelease.parse("""{"info":{"version":"1"},"urls":[]}"""))
    }

    @Test
    fun `versions compare by number`() {
        assertTrue(GalleryDlRelease.isNewer("1.32.18", "1.32.17"))
        assertTrue(GalleryDlRelease.isNewer("1.33.0", "1.32.99"))
        assertTrue(GalleryDlRelease.isNewer("1.32.17.1", "1.32.17"))
        assertFalse(GalleryDlRelease.isNewer("1.32.17", "1.32.17"))
        assertFalse(GalleryDlRelease.isNewer("1.9.0", "1.32.0"))
    }
}

class FallbacksTest {
    private fun item(url: String, args: String? = null, clip: Boolean = false) =
        DownloadItem(
            id = "x",
            url = url,
            type = MediaType.Video,
            formatSelector = "b",
            customArgs = args,
            sectionStart = if (clip) 10 else null,
            sectionEnd = if (clip) 20 else null,
        )

    @Test
    fun `each site gets the ways that can work there, in order`() {
        assertEquals(listOf(Fallbacks.Way.Browser, Fallbacks.Way.Gallery), Fallbacks.ways("https://www.facebook.com/share/v/1/"))
        assertEquals(listOf(Fallbacks.Way.Browser, Fallbacks.Way.Gallery), Fallbacks.ways("https://fb.watch/abc"))
        assertEquals(listOf(Fallbacks.Way.Gallery, Fallbacks.Way.Browser), Fallbacks.ways("https://www.instagram.com/p/x/"))
        assertEquals(listOf(Fallbacks.Way.Gallery), Fallbacks.ways("https://x.com/a/status/1"))
        assertTrue(Fallbacks.ways("https://www.youtube.com/watch?v=1").isEmpty())
        assertTrue(Fallbacks.ways("https://youtu.be/1").isEmpty())
        assertTrue(Fallbacks.ways("not a link").isEmpty())
    }

    @Test
    fun `another way is tried only when it may help`() {
        val facebook = item("https://www.facebook.com/share/v/1/")
        assertTrue(Fallbacks.mayHelp(facebook, "[facebook] 1: Cannot parse data; please report this issue"))
        assertTrue(Fallbacks.mayHelp(item("https://www.instagram.com/p/x/"), "[instagram] x: There is no video in this post"))
        assertTrue(Fallbacks.mayHelp(item("https://imgur.com/a/x"), "Unsupported URL: https://imgur.com/a/x"))
        // The connection, the phone or the video itself: no other way would do better.
        assertFalse(Fallbacks.mayHelp(facebook, "[Errno 104] Connection reset by peer"))
        assertFalse(Fallbacks.mayHelp(facebook, "[Errno 28] No space left on device"))
        assertFalse(Fallbacks.mayHelp(facebook, "This video is DRM protected"))
        // yt-dlp's own work: commands, clips and YouTube.
        assertFalse(Fallbacks.mayHelp(item("https://www.facebook.com/v/1", args = "-x"), "Cannot parse data"))
        assertFalse(Fallbacks.mayHelp(item("https://www.facebook.com/v/1", clip = true), "Cannot parse data"))
        assertFalse(Fallbacks.mayHelp(item("https://www.youtube.com/watch?v=1"), "Video unavailable"))
    }

    @Test
    fun `sources are named after the site`() {
        assertEquals("Facebook", Fallbacks.siteName("https://m.facebook.com/x"))
        assertEquals("Instagram", Fallbacks.siteName("https://www.instagram.com/p/x/"))
        assertEquals("Fb", Fallbacks.siteName("https://fb.watch/x"))
        assertEquals("", Fallbacks.siteName("nothing"))
    }
}
