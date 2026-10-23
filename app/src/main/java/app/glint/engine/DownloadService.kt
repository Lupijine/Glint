package app.glint.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import app.glint.MainActivity
import app.glint.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps Glint running while downloads are active, with a notification showing their progress.
 *
 * The service stops itself once nothing is active. Posting the progress notification and removing
 * it both happen here, on the main thread and in that order; an update posted from elsewhere after
 * the service stopped would come back as an ongoing notification that cannot be dismissed.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var updates: Job? = null
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // Android requires this promptly after every start, even if the downloads already ended.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(notificationState(DownloadManager.items.value)),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else 0,
        )
        if (updates?.isActive == true) return START_NOT_STICKY
        updates = scope.launch {
            DownloadManager.items
                .map(::notificationState)
                .distinctUntilChanged()
                .conflate()
                .collect { state ->
                    if (state.activeCount == 0) {
                        stopNow()
                        return@collect
                    }
                    post(state)
                    // Android drops notifications posted faster than a few times a second.
                    delay(UpdateIntervalMs)
                }
        }
        return START_NOT_STICKY
    }

    /** Android 15 limits how long a data sync service may run; downloads carry on without it. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopNow()
    }

    override fun onDestroy() {
        scope.cancel()
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    private fun stopNow() {
        updates?.cancel()
        updates = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        // A start that arrived meanwhile has a newer id, so this does not stop a fresh batch.
        stopSelf(lastStartId)
    }

    private fun post(state: NotificationState) {
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(state))
        } catch (_: SecurityException) {
            // Notification permission can be denied or revoked while a download runs.
            // The foreground service still runs, with its required system notification.
        }
    }

    private data class NotificationState(val activeCount: Int, val title: String?, val progress: Int)

    private fun notificationState(items: List<DownloadItem>): NotificationState {
        var activeCount = 0
        var title: String? = null
        var knownCount = 0
        var totalProgress = 0.0
        for (item in items) {
            if (!item.isActive) continue
            activeCount++
            title = if (activeCount == 1) item.title.takeIf { it.isNotBlank() } else null
            item.progress?.let {
                knownCount++
                totalProgress += it
            }
        }
        val progress = if (knownCount == 0) -1 else (totalProgress / knownCount * 100).toInt()
        return NotificationState(activeCount, title, progress)
    }

    private fun buildNotification(state: NotificationState): android.app.Notification {
        val progress = state.progress
        val openApp =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(
                state.title
                    ?: resources.getQuantityString(
                        R.plurals.notification_active,
                        state.activeCount.coerceAtLeast(1),
                        state.activeCount.coerceAtLeast(1),
                    ))
            .setContentText(if (progress >= 0) "$progress%" else getString(R.string.status_preparing))
            .setProgress(100, progress.coerceAtLeast(0), progress < 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "downloads"
        const val NOTIFICATION_ID = 1
        const val UpdateIntervalMs = 750L
    }
}
