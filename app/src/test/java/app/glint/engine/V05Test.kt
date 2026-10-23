package app.glint.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipsTest {
    @Test
    fun `times read and write like a video player`() {
        assertEquals(75, Clips.parse("1:15"))
        assertEquals(75, Clips.parse(" 75 "))
        assertEquals(3723, Clips.parse("1:02:03"))
        assertNull(Clips.parse("1:75"))
        assertNull(Clips.parse("1::2"))
        assertNull(Clips.parse("abc"))
        assertNull(Clips.parse(""))
        assertEquals("1:05", Clips.format(65))
        assertEquals("1:02:03", Clips.format(3723))
        assertEquals("0:00", Clips.format(-4))
    }

    @Test
    fun `a clip must be a real part of the video`() {
        assertEquals(10 to 20, Clips.clip(10, 20, 100))
        assertEquals(90 to 100, Clips.clip(90, 500, 100))
        assertNull(Clips.clip(20, 10, 100))
        assertNull(Clips.clip(0, 100, 100))
        assertEquals("*65-130", Clips.section(65, 130))
        assertEquals(250L, Clips.scaledSize(1000, 25, 50, 100))
    }

    @Test
    fun `a clip is passed to yt-dlp, with precise cuts on request`() {
        val item =
            DownloadItem(id = "1", url = "u", type = MediaType.Video, formatSelector = "b", sectionStart = 65, sectionEnd = 130)
        fun args(i: DownloadItem) =
            DownloadArgs.download(i, GlintSettings(), "/h", "/t", "/m", "o", null, "en")

        val plain = args(item)
        assertEquals("*65-130", plain[plain.indexOf("--download-sections") + 1])
        assertFalse("--force-keyframes-at-cuts" in plain)
        assertTrue("--force-keyframes-at-cuts" in args(item.copy(preciseCut = true)))
        assertFalse("--download-sections" in args(item.copy(sectionStart = null)))
    }
}

class FileNamesTest {
    private val id = "0f1e2d3c-aaaa-bbbb-cccc-123456789abc"

    @Test
    fun `finished files lose the download id`() {
        assertEquals("My video.mp4", DownloadOutput.finalName("My video [$id].mp4", id) { false })
    }

    @Test
    fun `a taken name gets a number`() {
        val taken = setOf("My video.mp4", "My video (2).mp4")

        assertEquals("My video (3).mp4", DownloadOutput.finalName("My video [$id].mp4", id) { it in taken })
        assertEquals("Song (2)", DownloadOutput.finalName("Song [$id]", id) { it == "Song" })
    }

    @Test
    fun `names without the id are left alone`() {
        assertEquals("custom.mp3", DownloadOutput.finalName("custom.mp3", id) { true })
    }

    @Test
    fun `the template keeps the id until the end`() {
        assertEquals("%(title).150B [$id].%(ext)s", DownloadOutput.template(id))
    }

    @Test
    fun `folders follow the layout and the playlist`() {
        fun folders(layout: FolderLayout, byType: Boolean = true) =
            DownloadOutput.subfolders(MediaType.Audio, "Youtube", "My list", layout, byType)

        assertEquals(listOf("My list"), folders(FolderLayout.OneFolder))
        assertEquals(listOf("Audio", "My list"), folders(FolderLayout.ByType))
        assertEquals(listOf("My list"), folders(FolderLayout.ByType, byType = false))
        assertEquals(listOf("Youtube", "My list"), folders(FolderLayout.BySite))
        assertEquals(listOf("Other"), DownloadOutput.subfolders(MediaType.Video, "", null, FolderLayout.BySite))
    }
}

class FormatListTest {
    private fun format(id: String, video: Boolean, audio: Boolean) =
        MediaFormat(id, "mp4", null, if (video) 720 else null, null, null, null, video, audio, null, null, true, "", null, null)

    @Test
    fun `audio shows only audio streams and video shows streams with video`() {
        val formats = listOf(format("a", false, true), format("v", true, false), format("m", true, true))

        assertEquals(listOf("a"), FormatChoices.listFor(formats, MediaType.Audio).map { it.id })
        assertEquals(listOf("v", "m"), FormatChoices.listFor(formats, MediaType.Video).map { it.id })
    }
}

class SearchTest {
    @Test
    fun `words search and links open`() {
        assertEquals("ytsearch20:sofia rotaru", Search.query("  sofia rotaru "))
        assertTrue(Search.canSearch("sofia rotaru"))
        assertFalse(Search.canSearch("https://youtu.be/x"))
        assertFalse(Search.canSearch("a"))
        assertFalse(Search.canSearch("   "))
    }

    @Test
    fun `search results are marked as such`() {
        val json = """{"_type":"playlist","title":"q","entries":[{"url":"https://www.youtube.com/watch?v=a","title":"A","channel":"Chan"}]}"""

        val result = ProbeParser.parse(json, Search.query("q")) as Probe.Playlist

        assertTrue(result.isSearch)
        assertEquals("Chan", result.entries.single().uploader)
        assertFalse((ProbeParser.parse(json, "https://www.youtube.com/playlist?list=x") as Probe.Playlist).isSearch)
    }
}

class SubscriptionsTest {
    private fun entry(n: Int, nested: Boolean = false) =
        PlaylistEntry(url = "https://v/$n", title = "Video $n", durationSeconds = 60, thumbnail = null, isPlaylist = nested)

    @Test
    fun `only unseen videos download, newest first`() {
        val entries = listOf(entry(5), entry(4), entry(3), entry(2, nested = true), entry(5))

        val plan = Subscriptions.plan(entries, seen = setOf("https://v/3"), newestFirst = true)

        assertEquals(listOf("https://v/5", "https://v/4"), plan.download.map { it.url })
        assertEquals(listOf("https://v/5", "https://v/4"), plan.markSeen)
    }

    @Test
    fun `playlists add new videos at the end`() {
        val plan = Subscriptions.plan(listOf(entry(1), entry(2), entry(3)), setOf("https://v/1"), newestFirst = false)

        assertEquals(listOf("https://v/3", "https://v/2"), plan.download.map { it.url })
    }

    @Test
    fun `a flood of new videos is capped but all are remembered`() {
        val entries = (1..40).map(::entry)

        val plan = Subscriptions.plan(entries, emptySet(), newestFirst = true)

        assertEquals(Subscriptions.MaxNewPerCheck, plan.download.size)
        assertEquals(40, plan.markSeen.size)
    }

    @Test
    fun `following marks what is already there as seen`() {
        assertEquals(listOf("https://v/1", "https://v/2"), Subscriptions.initialSeen(listOf(entry(1), entry(2), entry(9, nested = true))))
    }

    @Test
    fun `channels read their newest videos and playlists read everything`() {
        assertEquals(Subscriptions.ChannelEntries, Subscriptions.entriesToRead("https://www.youtube.com/@someone/videos"))
        assertEquals(Subscriptions.ChannelEntries, Subscriptions.entriesToRead("https://youtube.com/channel/UC123"))
        assertNull(Subscriptions.entriesToRead("https://www.youtube.com/playlist?list=PL1"))
    }

    @Test
    fun `subscriptions survive saving and loading`() {
        val list =
            listOf(
                Subscription("a", "https://y/@a", "A", "Youtube", MediaType.Audio, Quality.P720, AudioFormat.Mp3, createdAt = 5),
                Subscription("b", "https://y/p", "B", "Youtube", MediaType.Video, Quality.Best, AudioFormat.Original,
                    enabled = false, lastCheckedAt = 9, lastError = "boom", downloadedCount = 3),
            )

        assertEquals(list, Subscriptions.decode(Subscriptions.encode(list)))
        assertEquals(emptyList<Subscription>(), Subscriptions.decode("nope"))
    }
}

class DownloadItemStateTest {
    private val base = DownloadItem(id = "1", url = "u", type = MediaType.Video, formatSelector = "b")

    @Test
    fun `paused and interrupted downloads can resume but are not running`() {
        listOf(DownloadItem.Status.Paused, DownloadItem.Status.Interrupted, DownloadItem.Status.Failed).forEach {
            assertTrue(base.copy(status = it).canResume)
            assertFalse(base.copy(status = it).isActive)
        }
        assertTrue(base.copy(status = DownloadItem.Status.Saving).isActive)
    }

    @Test
    fun `a finished file can live at a path or behind a folder permission`() {
        val done = base.copy(status = DownloadItem.Status.Completed)

        assertFalse(done.hasFile)
        assertTrue(done.copy(filePath = "/x.mp4").hasFile)
        assertTrue(done.copy(fileUri = "content://tree/x.mp4").hasFile)
    }
}
