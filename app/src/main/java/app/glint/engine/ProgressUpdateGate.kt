package app.glint.engine

/** Limits only transient progress; status changes and completion never pass through this gate. */
internal class ProgressUpdateGate(private val intervalMs: Long = 250L) {
    private var lastPublishedAtMs: Long? = null

    @Synchronized
    fun shouldPublish(elapsedRealtimeMs: Long): Boolean {
        val previous = lastPublishedAtMs
        if (previous != null && elapsedRealtimeMs - previous < intervalMs) return false
        lastPublishedAtMs = elapsedRealtimeMs
        return true
    }
}
