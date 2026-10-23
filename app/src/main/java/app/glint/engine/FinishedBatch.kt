package app.glint.engine

/**
 * Downloads that ended while Glint was out of sight, summed up in one notification instead of
 * one per download, so a playlist does not flood the notification shade.
 */
internal class FinishedBatch {
    data class Snapshot(val completed: List<DownloadItem>, val failed: List<DownloadItem>) {
        val total: Int
            get() = completed.size + failed.size

        /** The only download in the batch, which a tap on the notification opens. */
        val single: DownloadItem?
            get() = (completed + failed).singleOrNull()

        /** The most recent titles, newest first, marked as finished or failed. */
        fun recent(limit: Int): List<Pair<DownloadItem, Boolean>> =
            (completed.map { it to true } + failed.map { it to false })
                .sortedByDescending { (item, _) -> item.completedAt ?: item.createdAt }
                .take(limit)
    }

    private val completed = LinkedHashMap<String, DownloadItem>()
    private val failed = LinkedHashMap<String, DownloadItem>()

    /** Records how a download ended; a retried download counts once, with its latest result. */
    @Synchronized
    fun add(item: DownloadItem): Snapshot {
        completed.remove(item.id)
        failed.remove(item.id)
        when (item.status) {
            DownloadItem.Status.Completed -> completed[item.id] = item
            DownloadItem.Status.Failed -> failed[item.id] = item
            else -> Unit
        }
        return snapshot()
    }

    @Synchronized fun clear() {
        completed.clear()
        failed.clear()
    }

    @Synchronized fun snapshot(): Snapshot = Snapshot(completed.values.toList(), failed.values.toList())
}
