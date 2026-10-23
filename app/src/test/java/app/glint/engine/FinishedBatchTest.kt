package app.glint.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FinishedBatchTest {
    private fun item(id: String, status: DownloadItem.Status, at: Long) =
        DownloadItem(
            id = id,
            url = "https://example.com/$id",
            type = MediaType.Video,
            formatSelector = "b",
            status = status,
            title = "Video $id",
            completedAt = at,
        )

    @Test
    fun `one download is shown on its own`() {
        val batch = FinishedBatch()

        val snapshot = batch.add(item("a", DownloadItem.Status.Completed, 1))

        assertEquals("a", snapshot.single?.id)
        assertEquals(1, snapshot.total)
    }

    @Test
    fun `a retried download counts once, with its latest result`() {
        val batch = FinishedBatch()
        batch.add(item("a", DownloadItem.Status.Failed, 1))

        val snapshot = batch.add(item("a", DownloadItem.Status.Completed, 2))

        assertEquals(1, snapshot.total)
        assertEquals(listOf("a"), snapshot.completed.map { it.id })
        assertEquals(emptyList<DownloadItem>(), snapshot.failed)
    }

    @Test
    fun `several results are summed up, newest first`() {
        val batch = FinishedBatch()
        batch.add(item("a", DownloadItem.Status.Completed, 1))
        batch.add(item("b", DownloadItem.Status.Failed, 3))

        val snapshot = batch.add(item("c", DownloadItem.Status.Completed, 2))

        assertNull(snapshot.single)
        assertEquals(3, snapshot.total)
        assertEquals(listOf("b" to false, "c" to true), snapshot.recent(2).map { (it, ok) -> it.id to ok })
    }

    @Test
    fun `clearing starts a new summary`() {
        val batch = FinishedBatch()
        batch.add(item("a", DownloadItem.Status.Completed, 1))

        batch.clear()

        assertEquals(0, batch.snapshot().total)
        assertEquals(1, batch.add(item("b", DownloadItem.Status.Completed, 2)).total)
    }
}
