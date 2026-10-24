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

    private fun stream(name: String, tag: String, bitrate: Int, video: String? = null): String {
        val id = video?.let { ""","xpv_asset_id":$it""" }.orEmpty()
        val note = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"vencode_tag":"$tag","bitrate":$bitrate$id}""".toByteArray())
        return "https://video.fbcdn.net/$name.mp4?efg=$note&oh=1"
    }

    private fun page(vararg urls: String) = urls.map { MediaLinks.PageFile(it, small = false, distance = 0) }

    @Test
    fun `streams are told apart by the note in their address`() {
        assertEquals(MediaLinks.Kind.Audio, MediaLinks.kindOf(stream("a", "dash_ln_heaac_vbr3_audio", 128)))
        assertEquals(MediaLinks.Kind.VideoOnly, MediaLinks.kindOf(stream("v", "dash_baseline_1_v1", 900)))
        assertEquals(MediaLinks.Kind.Complete, MediaLinks.kindOf(stream("p", "xpv_progressive.INSTAGRAM", 900)))
        assertEquals(MediaLinks.Kind.Unknown, MediaLinks.kindOf("https://video.fbcdn.net/x.mp4?oh=1"))
        assertEquals("777888999", MediaLinks.videoOf(stream("v", "dash_baseline_1_v1", 900, video = "777888999")))
        assertNull(MediaLinks.videoOf(stream("v", "dash_baseline_1_v1", 900)))
    }

    @Test
    fun `the best plan prefers a complete file, else the sharpest video with its sound`() {
        val low = stream("low", "dash_baseline_3_v1", 300)
        val high = stream("high", "dash_baseline_1_v1", 2000)
        val sound = stream("sound", "dash_ln_heaac_vbr3_audio", 128)
        val whole = stream("whole", "progressive", 1000)

        assertEquals(MediaPlan.Single("https://page/hd.mp4"), MediaLinks.choose(page("https://page/hd.mp4"), listOf(whole)).plan)
        assertEquals(MediaPlan.Single(whole), MediaLinks.choose(emptyList(), listOf(low, whole, sound)).plan)
        assertEquals(MediaPlan.Separate(high, sound), MediaLinks.choose(emptyList(), listOf(low, sound, high)).plan)
        assertEquals(MediaPlan.Single(high), MediaLinks.choose(emptyList(), listOf(high)).plan)
        assertEquals(MediaPlan.None, MediaLinks.choose(emptyList(), emptyList()).plan)
        // A smaller quality takes the lightest video stream.
        assertEquals(
            MediaPlan.Separate(low, sound),
            MediaLinks.choose(emptyList(), listOf(low, sound, high), quality = BrowserQuality.Small).plan,
        )
    }

    @Test
    fun `a video's picture is never joined with another video's sound`() {
        val wantedPicture = stream("wanted-v", "dash_baseline_1_v1", 900, video = "111111111")
        val wantedSound = stream("wanted-a", "dash_ln_heaac_vbr3_audio", 128, video = "111111111")
        val otherPicture = stream("other-v", "dash_baseline_1_v1", 2500, video = "222222222")
        val otherSound = stream("other-a", "dash_ln_heaac_vbr3_audio", 160, video = "222222222")
        val fetched = listOf(otherPicture, wantedSound, wantedPicture, wantedSound, otherSound)

        val named = MediaLinks.choose(emptyList(), fetched, target = "111111111")
        assertEquals(MediaPlan.Separate(wantedPicture, wantedSound), named.plan)
        assertTrue(named.sure)
        // Without the id, the video the player fetched the most is taken, and not as sure.
        val guessed = MediaLinks.choose(emptyList(), fetched + wantedPicture)
        assertEquals(MediaPlan.Separate(wantedPicture, wantedSound), guessed.plan)
        assertFalse(guessed.sure)
        // A picture alone of the wanted video is not joined with the other one's sound.
        assertEquals(
            MediaPlan.Single(wantedPicture),
            MediaLinks.choose(emptyList(), listOf(wantedPicture, otherSound), target = "111111111").plan,
        )
    }

    @Test
    fun `files named next to the wanted video come first, and others far away are left out`() {
        val filler = " ".repeat(30_000)
        val html =
            """{"id":"222222222","browser_native_hd_url":"https:\/\/v.fbcdn.net\/other.mp4"}""" + filler +
                """{"id":"111111111","browser_native_sd_url":"https:\/\/v.fbcdn.net\/sd.mp4",""" +
                """"browser_native_hd_url":"https:\/\/v.fbcdn.net\/hd.mp4"}"""
        val files = MediaLinks.filesInPage(html, target = "111111111")
        assertEquals(listOf("https://v.fbcdn.net/hd.mp4", "https://v.fbcdn.net/sd.mp4"), files.map { it.url })
        assertEquals(listOf(false, true), files.map { it.small })

        val best = MediaLinks.choose(files, emptyList(), "111111111")
        assertEquals(MediaPlan.Single("https://v.fbcdn.net/hd.mp4"), best.plan)
        assertTrue(best.sure)
        assertTrue(best.offersSmall)
        assertEquals(
            MediaPlan.Single("https://v.fbcdn.net/sd.mp4"),
            MediaLinks.choose(files, emptyList(), "111111111", BrowserQuality.Small).plan,
        )
        // A page that never names the video: its files may be another video's.
        val unnamed = MediaLinks.filesInPage(html, target = "333333333")
        assertTrue(unnamed.all { it.distance == null })
        assertFalse(MediaLinks.choose(unnamed, emptyList(), "333333333").sure)
        // A longer id that only contains the wanted one is another video.
        assertTrue(MediaLinks.filesInPage(html, target = "11111111").all { it.distance == null })
    }

    @Test
    fun `the wanted video is named by its address`() {
        assertEquals("1234567890", MediaLinks.targetOf("https://www.facebook.com/reel/1234567890/?s=1"))
        assertEquals("1234567890", MediaLinks.targetOf("https://www.facebook.com/watch/?v=1234567890"))
        assertEquals("1234567890", MediaLinks.targetOf("https://m.facebook.com/somepage/videos/1234567890/"))
        assertEquals("1234567890", MediaLinks.targetOf("https://www.facebook.com/somepage/videos/a-title/1234567890"))
        assertEquals("C1a2B3c4D5e", MediaLinks.targetOf("https://www.instagram.com/reel/C1a2B3c4D5e/?igsh=x"))
        assertEquals("C1a2B3c4D5e", MediaLinks.targetOf("https://www.instagram.com/someone/p/C1a2B3c4D5e/"))
        // A share link does not name it; the page it leads to does.
        assertNull(MediaLinks.targetOf("https://www.facebook.com/share/r/1AbCdEf/"))
        assertNull(MediaLinks.targetOf("https://example.com/watch?v=1234567890"))
    }

    @Test
    fun `page titles lose the site, the counts and the account`() {
        assertEquals("Sunset over the bay", MediaLinks.cleanTitle("12K views · 300 reactions | Sunset over the bay | By Some Page | Facebook"))
        assertEquals("Some Page", MediaLinks.cleanTitle("1.2M views · 30K reactions | By Some Page"))
        assertEquals("Cooking at home", MediaLinks.cleanTitle("(3) Cooking at home | Facebook"))
        assertEquals("my caption", MediaLinks.cleanTitle("Someone on Instagram: \"my caption\""))
        assertNull(MediaLinks.cleanTitle("Facebook"))
        assertNull(MediaLinks.cleanTitle("(12) Reels | Facebook"))
        assertNull(MediaLinks.cleanTitle("Log in or sign up to view"))
        assertNull(MediaLinks.cleanTitle(null))
    }

    @Test
    fun `the caption and picture come from the wanted video's data`() {
        val html =
            """{"id":"222222222","message":{"ranges":[],"text":"Not this one"}}""" + " ".repeat(30_000) +
                """{"id":"111111111","message":{"ranges":[],"text":"This one\nsecond line"},""" +
                """"preferred_thumbnail":{"image":{"uri":"https:\/\/scontent.fbcdn.net\/t.jpg"}}}"""
        assertEquals("This one", MediaLinks.captionInPage(html, "111111111"))
        assertEquals("https://scontent.fbcdn.net/t.jpg", MediaLinks.thumbnailInPage(html, "111111111"))
        assertNull(MediaLinks.captionInPage(html, null))
        assertNull(MediaLinks.captionInPage(html, "333333333"))
    }

    @Test
    fun `a picture and a sound of one video last as long`() {
        assertTrue(MediaLinks.sameLength(30.0, 31.5))
        assertTrue(MediaLinks.sameLength(600.0, 620.0))
        assertFalse(MediaLinks.sameLength(15.0, 45.0))
        assertFalse(MediaLinks.sameLength(600.0, 660.0))
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
    fun `gallery-dl's listing counts pictures and videos and finds a title`() {
        val output =
            """[
              [2, {"category": "instagram", "description": "A day at the sea\nmore", "username": "someone"}],
              [3, "https://scontent.cdninstagram.com/a.jpg?x=1", {"category": "instagram", "extension": "jpg"}],
              [3, "https://scontent.cdninstagram.com/b.mp4", {"category": "instagram", "extension": "mp4"}],
              [3, "https://scontent.cdninstagram.com/c.webp", {"category": "instagram", "extension": "webp"}]
            ]"""
        val listing = GalleryDlOutput.listing(output, "", 0, limit = 100)
        assertEquals(3, listing.files)
        assertEquals(2, listing.pictures)
        assertEquals(1, listing.videos)
        assertFalse(listing.more)
        assertEquals("A day at the sea", listing.title)
        assertEquals("https://scontent.cdninstagram.com/a.jpg?x=1", listing.thumbnail)
        assertEquals("Instagram", listing.site)

        // One file past the limit says there are more; the account names a post without words.
        val many =
            "[" + (1..4).joinToString(",") { """[3, "https://i.imgur.com/$it.png", {"category": "imgur", "user": {"name": "pat"}}]""" } + "]"
        val more = GalleryDlOutput.listing(many, "", 0, limit = 3)
        assertEquals(3, more.files)
        assertTrue(more.more)
        assertEquals("pat", more.title)
    }

    @Test
    fun `gallery-dl's reason is kept when it finds nothing`() {
        val failed =
            runCatching {
                GalleryDlOutput.listing("""[[-1, {"error": "AuthRequired", "message": "Login required"}]]""", "", 0, 100)
            }.exceptionOrNull()
        assertEquals("AuthRequired: Login required", failed?.message)
        assertEquals(ErrorKind.SignIn, ErrorKind.of(failed?.message))

        val unsupported =
            runCatching {
                GalleryDlOutput.listing("", "[gallery-dl][error] Unsupported URL 'https://x.com/'\n", 64, 100)
            }.exceptionOrNull()
        assertEquals("[gallery-dl][error] Unsupported URL 'https://x.com/'", unsupported?.message)
        assertEquals("Unsupported URL 'https://x.com/'", ErrorText.summarize(unsupported?.message))
        assertEquals(ErrorKind.Unsupported, ErrorKind.of(unsupported?.message))
        assertEquals("gallery-dl found nothing to download at this link", GalleryDlOutput.failure("", 0))
    }

    @Test
    fun `gallery-dl lists without downloading`() {
        val args = GalleryDlOutput.listArguments("/c.txt", GlintSettings(), limit = 50)
        assertTrue("--config-ignore" in args)
        assertTrue("--resolve-json" in args)
        assertEquals("1-51", args[args.indexOf("--range") + 1])
        assertEquals("/c.txt", args[args.indexOf("--cookies") + 1])
        assertFalse("-D" in args)
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
    fun `a way chosen in the sheet is not followed by others`() {
        val chosen = item("https://www.instagram.com/p/x/").copy(tool = Tool.GalleryDl)
        assertFalse(Fallbacks.mayHelp(chosen, "Unsupported URL"))
        assertTrue(Fallbacks.mayHelp("https://www.facebook.com/reel/1", "Cannot parse data"))
        assertFalse(Fallbacks.mayHelp("https://www.youtube.com/watch?v=1", "Video unavailable"))
    }

    @Test
    fun `gallery-dl is offered for every site but YouTube`() {
        assertTrue(Fallbacks.galleryMayRead("https://www.instagram.com/p/x/"))
        assertTrue(Fallbacks.galleryMayRead("https://imgur.com/a/x"))
        assertFalse(Fallbacks.galleryMayRead("https://m.youtube.com/watch?v=1"))
        assertFalse(Fallbacks.galleryMayRead("words"))
    }

    @Test
    fun `an untitled video is named after its site and id`() {
        assertEquals("Facebook 1234567890", Fallbacks.untitled("https://www.facebook.com/reel/1234567890/"))
        assertEquals("Facebook", Fallbacks.untitled("https://www.facebook.com/share/r/abc/"))
        assertEquals("video", Fallbacks.untitled("nothing"))
    }

    @Test
    fun `the chosen way is kept with the download`() {
        val stored = item("https://www.facebook.com/reel/1").copy(tool = Tool.Browser, formatSelector = "Small").toEntity()
        assertEquals("Browser", stored.tool)
        assertEquals(Tool.Browser, stored.toItemOrNull()?.tool)
        // Rows from before, and yt-dlp's own, have no tool.
        val plain = item("https://www.youtube.com/watch?v=1").toEntity()
        assertNull(plain.tool)
        assertEquals(Tool.YtDlp, plain.toItemOrNull()?.tool)
        assertNull(plain.copy(tool = "SomethingNewer").toItemOrNull())
    }

    @Test
    fun `sources are named after the site`() {
        assertEquals("Facebook", Fallbacks.siteName("https://m.facebook.com/x"))
        assertEquals("Instagram", Fallbacks.siteName("https://www.instagram.com/p/x/"))
        assertEquals("Fb", Fallbacks.siteName("https://fb.watch/x"))
        assertEquals("", Fallbacks.siteName("nothing"))
    }
}
