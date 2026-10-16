package com.kroryan.astrovia.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.kroryan.astrovia.MainActivity
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Keeps HiPS and catalogue downloads alive when the app is in the background.
 *
 * Without it Android may kill the process shortly after the user leaves the app, and a long
 * download silently dies. A foreground service of type `dataSync` shows a notification with the
 * progress and a Stop button, which is what Android requires for user-initiated transfers.
 *
 * It is only a keep-alive: the work itself still runs in the downloaders. If the service cannot be
 * started (for example the system refuses it) the download simply continues as before.
 */
class DownloadService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            DownloadForeground.stopAll()
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            DownloadForeground.ensureChannel(this)
            val n = DownloadForeground.buildNotification(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
        } catch (e: Exception) {
            android.util.Log.w("DownloadService", "could not go to the foreground", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** Android 15 limits a data-sync service to ~6 h: stop the service (the transfer is not cancelled). */
    @Suppress("NewApi")
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    companion object {
        const val NOTIFICATION_ID = 4107
        const val CHANNEL_ID = "downloads"
        const val ACTION_STOP = "com.kroryan.astrovia.action.STOP_DOWNLOADS"
    }
}

/** What the notification says for the running downloads. Pure, so it can be unit-tested. */
object DownloadNotice {
    /** (title, text) for the notification given each running task's one-line status. */
    fun format(tasks: Collection<String>): Pair<String, String> = when (tasks.size) {
        0 -> "Astrovia" to "Finishing..."
        1 -> "Astrovia download" to tasks.first()
        else -> "Astrovia: ${tasks.size} downloads" to tasks.joinToString("  |  ")
    }

    fun hipsLine(title: String, tiles: Int, bytes: Long): String =
        "$title: $tiles tiles, ${human(bytes)}"

    fun catalogLine(name: String, bytes: Long): String = "$name: ${human(bytes)}"

    fun human(b: Long): String = when {
        b >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f GB", b / 1073741824.0)
        b >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0)
        b >= 1L shl 10 -> String.format(java.util.Locale.US, "%.0f KB", b / 1024.0)
        else -> "$b B"
    }
}

/** Tracks the running downloads and starts / stops [DownloadService] around them. */
object DownloadForeground {
    private val tasks = LinkedHashMap<String, String>()
    private val stops = CopyOnWriteArrayList<() -> Unit>()
    @Volatile private var appContext: Context? = null
    private var lastNotify = 0L

    /** A downloader registers how to cancel everything it is running (used by the notification's Stop). */
    fun registerStop(handler: () -> Unit) {
        stops.add(handler)
    }

    fun stopAll() {
        for (s in stops) {
            try { s() } catch (e: Exception) { /* keep stopping the others */ }
        }
    }

    fun begin(ctx: Context, id: String, line: String) {
        val app = ctx.applicationContext
        appContext = app
        val first: Boolean
        synchronized(tasks) {
            first = tasks.isEmpty()
            tasks[id] = line
        }
        if (first) {
            try {
                ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java))
            } catch (e: Exception) {
                // e.g. the system does not allow it right now: the download still runs, just not protected.
                android.util.Log.w("DownloadForeground", "foreground service not started", e)
            }
        } else {
            notifyNow()
        }
    }

    /** Updates the status line of a running task; the notification is refreshed at most once a second. */
    fun progress(id: String, line: String) {
        synchronized(tasks) {
            if (!tasks.containsKey(id)) return
            tasks[id] = line
        }
        val now = System.currentTimeMillis()
        if (now - lastNotify >= 1000) notifyNow()
    }

    fun end(id: String) {
        val empty: Boolean
        synchronized(tasks) {
            tasks.remove(id)
            empty = tasks.isEmpty()
        }
        val app = appContext ?: return
        if (empty) {
            try { app.stopService(Intent(app, DownloadService::class.java)) } catch (e: Exception) { /* already gone */ }
        } else {
            notifyNow()
        }
    }

    internal fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(DownloadService.CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(DownloadService.CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress of HiPS and catalogue downloads"
                    setShowBadge(false)
                },
            )
        }
    }

    internal fun buildNotification(ctx: Context): Notification {
        val lines = synchronized(tasks) { tasks.values.toList() }
        val (title, text) = DownloadNotice.format(lines)
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            ctx, 1,
            Intent(ctx, DownloadService::class.java).setAction(DownloadService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(ctx, DownloadService.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(0, 0, true)
            .build()
    }

    private fun notifyNow() {
        val app = appContext ?: return
        lastNotify = System.currentTimeMillis()
        try {
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(DownloadService.NOTIFICATION_ID, buildNotification(app))
        } catch (e: SecurityException) {
            // notifications not allowed: the service keeps running, only the text is not shown
        }
    }
}
