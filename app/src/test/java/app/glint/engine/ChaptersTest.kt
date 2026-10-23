package app.glint.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChaptersTest {
    private fun video(chapters: String, duration: String = "300.4") =
        ProbeParser.parse(
            """{"id":"v","title":"Talk","duration":$duration,"formats":[{"format_id":"18","height":360,"vcodec":"avc1","acodec":"mp4a"}],"chapters":$chapters}""",
            "https://youtu.be/v",
        ) as Probe.Video

    @Test
    fun `chapters are read in whole seconds without losing any part`() {
        val chapters =
            video(
                """[{"start_time":0.0,"end_time":65.4,"title":"Intro"},{"start_time":65.4,"end_time":180.0,"title":"Main"},{"start_time":180.0,"end_time":300.4,"title":"Q&A"}]"""
            ).chapters

        assertEquals(listOf(Chapter(0, 66, "Intro"), Chapter(65, 180, "Main"), Chapter(180, 301, "Q&A")), chapters)
    }

    @Test
    fun `a chapter without an end runs to the next one or the end of the video`() {
        val chapters = video("""[{"start_time":0,"title":"A"},{"start_time":100,"title":null}]""", duration = "250").chapters

        assertEquals(listOf(Chapter(0, 100, "A"), Chapter(100, 250, "2")), chapters)
    }

    @Test
    fun `videos without chapters have none`() {
        assertTrue(video("null").chapters.isEmpty())
        assertTrue(video("[]").chapters.isEmpty())
    }

    @Test
    fun `only chapters in a row can be joined`() {
        assertTrue(Chapters.areConsecutive(setOf(2, 3, 4)))
        assertTrue(Chapters.areConsecutive(listOf(5, 4)))
        assertFalse(Chapters.areConsecutive(setOf(1, 3)))
        assertFalse(Chapters.areConsecutive(setOf(1)))
    }

    @Test
    fun `chapter titles keep the chapter's name when the video's is long`() {
        val title = Chapters.title("V".repeat(200), 2, Chapter(0, 10, "Getting started"))

        assertTrue(title.endsWith(" - 03 Getting started"))
        assertEquals("Talk - Intro – Q&A", Chapters.joinedTitle("Talk", Chapter(0, 1, "Intro"), Chapter(2, 3, "Q&A")))
    }

    @Test
    fun `clips are named after their own title, safely`() {
        val id = "abc"

        assertEquals("Talk - 01 Intro [abc].%(ext)s", DownloadOutput.template(id, "Talk - 01 Intro"))
        assertEquals("A B 100%% [abc].%(ext)s", DownloadOutput.template(id, "A/B: 100%"))
        assertEquals("%(title).150B [abc].%(ext)s", DownloadOutput.template(id, " / "))
        assertNull(DownloadOutput.fileTitle(".."))
        assertEquals(150, DownloadOutput.fileTitle("x".repeat(400))!!.length)
    }

    @Test
    fun `a clip's file loses only the download id`() {
        assertEquals("Talk - 01 Intro.mp4", DownloadOutput.finalName("Talk - 01 Intro [abc].mp4", "abc") { false })
    }
}
