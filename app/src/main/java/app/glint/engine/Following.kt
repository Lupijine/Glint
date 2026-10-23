package app.glint.engine

import android.content.Context
import android.content.SharedPreferences
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Followed channels and playlists. Each check reads the list again and queues the videos it has
 * not seen; everything listed when following counts as seen, so only later uploads download.
 */
object Following {
    private const val Key = "subscriptions"
    private const val WorkName = "follow-check"

    private lateinit var appContext: Context
    private lateinit var preferences: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val checkLock = Mutex()

    private val mutableSubscriptions = MutableStateFlow<List<Subscription>>(emptyList())
    val subscriptions: StateFlow<List<Subscription>> = mutableSubscriptions.asStateFlow()

    private val mutableChecking = MutableStateFlow<Set<String>>(emptySet())
    /** Subscriptions being checked right now. */
    val checking: StateFlow<Set<String>> = mutableChecking.asStateFlow()

    fun initialize(context: Context) {
        appContext = context.applicationContext
        preferences = appContext.getSharedPreferences("following", Context.MODE_PRIVATE)
        mutableSubscriptions.value = preferences.getString(Key, null)?.let(Subscriptions::decode).orEmpty()
        // Background checks run only while something is followed, at the chosen interval.
        scope.launch {
            combine(
                    subscriptions.map { list -> list.any { it.enabled } },
                    SettingsStore.settings.map { it.followIntervalHours to it.followWifiOnly },
                ) { anyEnabled, timing -> anyEnabled to timing }
                .distinctUntilChanged()
                .collect { (anyEnabled, timing) -> schedule(anyEnabled, timing.first, timing.second) }
        }
    }

    fun isFollowing(url: String): Boolean = subscriptions.value.any { it.url == url }

    /** Follows a list; videos already in it count as seen. Following it again updates its choices. */
    fun follow(playlist: Probe.Playlist, type: MediaType, quality: Quality, audioFormat: AudioFormat): Subscription {
        val existing = subscriptions.value.firstOrNull { it.url == playlist.url }
        val subscription =
            existing?.copy(type = type, quality = quality, audioFormat = audioFormat, enabled = true)
                ?: Subscription(
                    id = UUID.randomUUID().toString(),
                    url = playlist.url,
                    title = playlist.title,
                    source = playlist.source,
                    type = type,
                    quality = quality,
                    audioFormat = audioFormat,
                    createdAt = System.currentTimeMillis(),
                    lastCheckedAt = System.currentTimeMillis(),
                )
        if (existing == null) {
            seenFile(subscription.id).apply {
                parentFile?.mkdirs()
                writeText(Subscriptions.initialSeen(playlist.entries).joinToString("\n", postfix = "\n"))
            }
        }
        save { list -> if (existing == null) list + subscription else list.map { if (it.id == subscription.id) subscription else it } }
        return subscription
    }

    fun unfollow(id: String) {
        save { list -> list.filterNot { it.id == id } }
        seenFile(id).delete()
    }

    fun setEnabled(id: String, enabled: Boolean) = save { list -> list.map { if (it.id == id) it.copy(enabled = enabled) else it } }

    /** Checks one subscription, or every enabled one, without waiting. */
    fun checkNow(id: String? = null) {
        scope.launch { if (id == null) checkAll() else subscriptions.value.firstOrNull { it.id == id }?.let { check(it) } }
    }

    /** Checks every enabled subscription; returns how many new videos were queued. */
    suspend fun checkAll(): Int = subscriptions.value.filter { it.enabled }.sumOf { check(it) }

    private suspend fun check(subscription: Subscription): Int =
        checkLock.withLock {
            mutableChecking.update { it + subscription.id }
            try {
                // Downloads from this channel that stopped when Glint closed continue first.
                DownloadManager.resumeInterrupted(subscription.id)
                val limit = Subscriptions.entriesToRead(subscription.url)
                val playlist =
                    Prober.probe(subscription.url, wholePlaylist = true, fresh = true, playlistEnd = limit) as? Probe.Playlist
                        ?: throw IllegalStateException("This link no longer opens as a channel or playlist")
                val seen = readSeen(subscription.id)
                val plan = Subscriptions.plan(playlist.entries, seen, newestFirst = limit != null)
                if (plan.download.isNotEmpty()) DownloadManager.enqueue(plan.download.map { request(subscription, it) })
                if (plan.markSeen.isNotEmpty()) {
                    seenFile(subscription.id).appendText(plan.markSeen.joinToString("\n", postfix = "\n"))
                }
                save { list ->
                    list.map {
                        if (it.id != subscription.id) it
                        else
                            it.copy(
                                lastCheckedAt = System.currentTimeMillis(),
                                lastError = null,
                                downloadedCount = it.downloadedCount + plan.download.size,
                            )
                    }
                }
                plan.download.size
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val message = ErrorText.summarize(error.message) ?: ErrorText.describe(error)
                save { list ->
                    list.map {
                        if (it.id == subscription.id) it.copy(lastCheckedAt = System.currentTimeMillis(), lastError = message)
                        else it
                    }
                }
                0
            } finally {
                mutableChecking.update { it - subscription.id }
            }
        }

    private fun request(subscription: Subscription, entry: PlaylistEntry) =
        DownloadRequest(
            url = entry.url,
            type = subscription.type,
            formatSelector = FormatChoices.preset(subscription.type, subscription.quality),
            formatLabel = FormatChoices.presetLabel(subscription.type, subscription.quality, subscription.audioFormat),
            audioFormat = subscription.audioFormat,
            title = entry.title,
            thumbnail = entry.thumbnail,
            source = subscription.source,
            durationSeconds = entry.durationSeconds,
            folder = DownloadOutput.folderName(subscription.title),
            subscriptionId = subscription.id,
        )

    private fun seenFile(id: String) = File(appContext.filesDir, "following/$id.txt")

    private fun readSeen(id: String): Set<String> =
        runCatching { seenFile(id).readLines().filter { it.isNotBlank() }.toSet() }.getOrDefault(emptySet())

    private fun save(transform: (List<Subscription>) -> List<Subscription>) {
        val next = mutableSubscriptions.updateAndGet(transform)
        if (::preferences.isInitialized) preferences.edit().putString(Key, Subscriptions.encode(next)).apply()
    }

    private fun schedule(anyEnabled: Boolean, hours: Int, wifiOnly: Boolean) {
        val workManager = runCatching { WorkManager.getInstance(appContext) }.getOrNull() ?: return
        if (!anyEnabled) {
            workManager.cancelUniqueWork(WorkName)
            return
        }
        val constraints =
            Constraints.Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
        val request =
            PeriodicWorkRequestBuilder<FollowWorker>(hours.toLong().coerceAtLeast(1), TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
        workManager.enqueueUniquePeriodicWork(WorkName, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}

/**
 * Checks followed channels in the background. Downloads it queues run in Glint's download
 * service when Android allows starting it from the background (with background downloads
 * allowed in settings); otherwise this worker keeps Glint running while they finish, within
 * Android's limit for background work, and anything unfinished continues on the next check.
 */
class FollowWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val engine =
            withTimeoutOrNull(5 * 60_000L) {
                Engine.state.first { it is Engine.State.Ready || it is Engine.State.Failed }
            }
        if (engine !is Engine.State.Ready) return Result.retry()
        val queued = Following.checkAll()
        if (queued > 0 || DownloadManager.items.value.any { it.isActive }) {
            withTimeoutOrNull(9 * 60_000L) {
                DownloadManager.items.first { list -> list.none { it.isActive && it.subscriptionId != null } }
            }
        }
        return Result.success()
    }
}
