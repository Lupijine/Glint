package app.glint.engine

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.glint.MainActivity
import app.glint.R

/**
 * Tells the user when downloads finish or fail while Glint is out of sight. Everything that ends
 * before the user looks is gathered into one notification, which alerts once and is cleared when
 * Glint is opened.
 */
object FinishedNotifications {
    private const val ChannelId = "finished"
    private const val NotificationId = 2
    private const val RecentLines = 5

    /** Set while Glint is on screen, where the Downloads page already shows every result. */
    @Volatile var appVisible: Boolean = false

    private val batch = FinishedBatch()

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel =
            NotificationChannel(
                ChannelId,
                context.getString(R.string.notification_channel_finished),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun completed(context: Context, item: DownloadItem) = record(context, item)

    fun failed(context: Context, item: DownloadItem) = record(context, item)

    /** Called when Glint comes into view: the user can now see every result. */
    fun clear(context: Context) {
        batch.clear()
        NotificationManagerCompat.from(context).cancel(NotificationId)
    }

    /** The user swiped the notification away; later results start a new summary. */
    internal fun dismissed() = batch.clear()

    private fun record(context: Context, item: DownloadItem) {
        if (appVisible || !SettingsStore.current.notifyFinished) return
        val snapshot = batch.add(item)
        if (snapshot.total == 0) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) {
            return
        }
        try {
            NotificationManagerCompat.from(context).notify(NotificationId, build(context, snapshot))
        } catch (_: SecurityException) {
            // Notification permission can be revoked at any moment.
        }
    }

    private fun build(context: Context, snapshot: FinishedBatch.Snapshot): android.app.Notification {
        val builder =
            NotificationCompat.Builder(context, ChannelId)
                .setSmallIcon(R.drawable.ic_stat_download)
                .setAutoCancel(true)
                // A playlist finishing one video at a time alerts once, not for every video.
                .setOnlyAlertOnce(true)
                .setDeleteIntent(
                    PendingIntent.getBroadcast(
                        context,
                        0,
                        Intent(context, FinishedDismissedReceiver::class.java),
                        PendingIntent.FLAG_IMMUTABLE,
                    ))
        val single = snapshot.single
        return when {
            single != null && single.status == DownloadItem.Status.Completed ->
                builder
                    .setContentTitle(context.getString(R.string.notification_completed))
                    .setContentText(single.title.ifBlank { single.url })
                    .setContentIntent(openApp(context, openItemId = single.id.takeIf { single.hasFile }))
                    .build()
            single != null ->
                builder
                    .setContentTitle(context.getString(R.string.notification_failed, single.title.ifBlank { single.url }))
                    .setContentText(single.error ?: context.getString(R.string.status_failed))
                    .setStyle(NotificationCompat.BigTextStyle().bigText(single.error))
                    .setContentIntent(openApp(context, openItemId = null))
                    .build()
            else -> {
                val lines = snapshot.recent(RecentLines)
                val style = NotificationCompat.InboxStyle()
                lines.forEach { (item, ok) -> style.addLine((if (ok) "✓ " else "✕ ") + item.title.ifBlank { item.url }) }
                if (snapshot.total > lines.size) style.setSummaryText("+${snapshot.total - lines.size}")
                builder
                    .setContentTitle(
                        context.resources.getQuantityString(
                            R.plurals.notification_finished, snapshot.total, snapshot.total))
                    .setContentText(
                        if (snapshot.failed.isEmpty()) lines.firstOrNull()?.first?.title.orEmpty()
                        else
                            context.getString(
                                R.string.notification_finished_detail,
                                snapshot.completed.size,
                                snapshot.failed.size,
                            ))
                    .setStyle(style)
                    .setNumber(snapshot.total)
                    .setContentIntent(openApp(context, openItemId = null))
                    .build()
            }
        }
    }

    /** Opens Glint, which then opens [openItemId]'s file when one is given. */
    private fun openApp(context: Context, openItemId: String?): PendingIntent {
        val intent =
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.ExtraOpenDownload, openItemId)
        return PendingIntent.getActivity(
            context,
            openItemId?.hashCode() ?: 0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

/** Learns when the finished-downloads notification is swiped away. */
class FinishedDismissedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = FinishedNotifications.dismissed()
}
