package app.glint.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloadOutputTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `completed file belongs to its marker even when another download finished later`() {
        val directory = temporaryFolder.newFolder("downloads")
        val ownFile = File(directory, "first video.mp4").apply { writeText("first") }
        val otherFile = File(directory, "second video.mp4").apply { writeText("second") }
        assertTrue(ownFile.setLastModified(1_000_000L))
        assertTrue(otherFile.setLastModified(2_000_000L))
        val marker = markerFor(ownFile)

        assertEquals(ownFile.canonicalFile, DownloadOutput.completedFile(marker, directory).canonicalFile)
    }

    @Test
    fun `separate items use separate output names for the same video`() {
        val first = DownloadOutput.template("11111111-1111-4111-8111-111111111111")
        val second = DownloadOutput.template("22222222-2222-4222-8222-222222222222")

        assertNotEquals(first, second)
        assertTrue(first.contains("11111111-1111-4111-8111-111111111111"))
        assertTrue(second.contains("22222222-2222-4222-8222-222222222222"))
        assertTrue(first.contains("%(ext)s"))
    }

    @Test
    fun `an existing output can be selected when yt-dlp skips downloading it again`() {
        val directory = temporaryFolder.newFolder("downloads")
        val existingFile = File(directory, "existing audio.m4a").apply { writeText("audio") }
        assertTrue(existingFile.setLastModified(1_000_000L))

        assertEquals(
            existingFile.canonicalFile,
            DownloadOutput.completedFile(markerFor(existingFile), directory).canonicalFile,
        )
    }

    @Test
    fun `missing result marker fails even when other downloaded files exist`() {
        val directory = temporaryFolder.newFolder("downloads")
        File(directory, "unrelated.mp4").writeText("unrelated")
        val missingMarker = File(temporaryFolder.root, "missing-result.txt")

        assertThrows(IllegalStateException::class.java) {
            DownloadOutput.completedFile(missingMarker, directory)
        }
    }

    @Test
    fun `marker pointing to a missing file fails`() {
        val directory = temporaryFolder.newFolder("downloads")
        val missingOutput = File(directory, "missing.mp4")

        assertThrows(IllegalStateException::class.java) {
            DownloadOutput.completedFile(markerFor(missingOutput), directory)
        }
    }

    @Test
    fun `marker cannot claim a file outside its output directory`() {
        val directory = temporaryFolder.newFolder("downloads")
        val foreignFile = temporaryFolder.newFile("foreign.mp4")

        assertThrows(IllegalStateException::class.java) {
            DownloadOutput.completedFile(markerFor(foreignFile), directory)
        }
    }

    @Test
    fun `directory containment rejects a sibling sharing the directory name prefix`() {
        val directory = temporaryFolder.newFolder("downloads")
        val siblingDirectory = temporaryFolder.newFolder("downloads-other")
        val siblingFile = File(siblingDirectory, "foreign.mp4").apply { writeText("foreign") }

        assertThrows(IllegalStateException::class.java) {
            DownloadOutput.completedFile(markerFor(siblingFile), directory)
        }
    }

    @Test
    fun `multiple output entries fail instead of selecting an arbitrary file`() {
        val directory = temporaryFolder.newFolder("downloads")
        val first = File(directory, "first.mp4").apply { writeText("first") }
        val second = File(directory, "second.mp4").apply { writeText("second") }
        val marker = temporaryFolder.newFile("result.txt")
        marker.writeText("${first.absolutePath}\n${second.absolutePath}\n")

        assertThrows(IllegalStateException::class.java) {
            DownloadOutput.completedFile(marker, directory)
        }
    }

    @Test
    fun `blank result does not identify a completed download`() {
        val directory = temporaryFolder.newFolder("downloads")
        val marker = temporaryFolder.newFile("empty-result.txt")
        marker.writeText("\n")

        assertThrows(IllegalStateException::class.java) {
            DownloadOutput.completedFile(marker, directory)
        }
    }

    @Test
    fun `a directory cannot be reported as the finished file`() {
        val directory = temporaryFolder.newFolder("downloads")
        val nestedDirectory = File(directory, "nested.mp4").apply { mkdirs() }

        assertThrows(IllegalStateException::class.java) {
            DownloadOutput.completedFile(markerFor(nestedDirectory), directory)
        }
    }

    @Test
    fun `playlist folder names cannot leave the download folder`() {
        assertEquals("Road trip part 1", DownloadOutput.folderName("Road trip / part 1"))
        assertEquals("a b c", DownloadOutput.folderName("a\\b:c"))
        assertEquals("hidden", DownloadOutput.folderName("..hidden.."))
        assertEquals(null, DownloadOutput.folderName(".."))
        assertEquals(null, DownloadOutput.folderName(" / "))
        assertEquals("قائمتي", DownloadOutput.folderName("قائمتي"))
    }

    @Test
    fun `long playlist folder names are shortened without splitting characters`() {
        val name = DownloadOutput.folderName("x".repeat(79) + "😀" + "tail")!!

        assertEquals("x".repeat(79), name)
        assertTrue(DownloadOutput.folderName("y".repeat(200))!!.length <= 80)
    }

    @Test
    fun `a command's files are all reported, wherever they were saved`() {
        val directory = temporaryFolder.newFolder("downloads")
        val first = File(directory, "one.m4a").apply { writeText("1") }
        val elsewhere = File(temporaryFolder.newFolder("elsewhere"), "two.m4a").apply { writeText("2") }
        val marker = temporaryFolder.newFile("result.txt")
        marker.writeText("${first.absolutePath}\n\n${File(directory, "gone.m4a").absolutePath}\n${elsewhere.absolutePath}\n${first.absolutePath}\n")

        assertEquals(listOf(first.canonicalFile, elsewhere.canonicalFile), DownloadOutput.reportedFiles(marker))
        assertEquals(emptyList<File>(), DownloadOutput.reportedFiles(File(temporaryFolder.root, "missing.txt")))
    }

    @Test
    fun `file counts are shown once, even after retries`() {
        assertEquals("MP3 ×3", DownloadOutput.withFileCount("MP3", 3))
        assertEquals("MP3 ×5", DownloadOutput.withFileCount("MP3 ×3", 5))
        assertEquals("MP3", DownloadOutput.withFileCount("MP3 ×3", 1))
        assertEquals("×2", DownloadOutput.withFileCount("", 2))
    }

    private fun markerFor(file: File): File =
        temporaryFolder.newFile("result.txt").apply { writeText("${file.absolutePath}\n") }
}
