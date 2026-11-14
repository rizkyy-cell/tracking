package com.jrxrezkyy.webcloner

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Layanan latar depan: menjaga proses tetap hidup selama ada unduhan berjalan dan menampilkan
 * satu notifikasi kemajuan. Berhenti sendiri saat semua unduhan selesai.
 */
class DownloadService : Service() {

    private val listener: () -> Unit = { refresh() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DlNotify.ensureChannels(this)
        Downloads.addListener(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(
                this, DlNotify.ID_PROGRESS, DlNotify.progress(this),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            )
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        refresh()
        return START_NOT_STICKY
    }

    private fun refresh() {
        if (Downloads.activeCount() == 0) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            try {
                NotificationManagerCompat.from(this).notify(DlNotify.ID_PROGRESS, DlNotify.progress(this))
            } catch (e: Exception) {
                // tanpa izin notifikasi: unduhan tetap berjalan
            }
        }
    }

    override fun onDestroy() {
        Downloads.removeListener(listener)
        super.onDestroy()
    }

    companion object {
        fun ensure(c: Context) {
            try {
                ContextCompat.startForegroundService(c, Intent(c, DownloadService::class.java))
            } catch (e: Exception) {
                // Dilarang memulai layanan dari latar belakang: unduhan tetap jalan selama proses hidup.
            }
        }
    }
}

/** Notifikasi unduhan: kemajuan (satu) dan hasil (satu per file). */
object DlNotify {
    const val CH_PROGRESS = "dl_progress"
    const val CH_DONE = "dl_done"
    const val ID_PROGRESS = 7001

    fun ensureChannels(c: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = NotificationManagerCompat.from(c)
        nm.createNotificationChannel(
            NotificationChannelCompat.Builder(CH_PROGRESS, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(c.getString(R.string.dl_channel_progress)).build()
        )
        nm.createNotificationChannel(
            NotificationChannelCompat.Builder(CH_DONE, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(c.getString(R.string.dl_channel_done)).build()
        )
    }

    private fun listIntent(c: Context): PendingIntent = PendingIntent.getActivity(
        c, 1,
        Intent(c, DownloadsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun progress(c: Context): Notification {
        val active = Downloads.list(c).filter { it.active }
        val title = if (active.size == 1) {
            c.getString(R.string.dl_notif_one, active[0].name)
        } else {
            c.getString(R.string.dl_notif_many, active.size)
        }
        var sumDone = 0L
        var sumTotal = 0L
        var known = active.isNotEmpty()
        for (d in active) {
            sumDone += d.done
            if (d.total > 0) sumTotal += d.total else known = false
        }
        val b = NotificationCompat.Builder(c, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_install)
            .setContentTitle(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(listIntent(c))
        if (known && sumTotal > 0) {
            val pct = (sumDone * 100L / sumTotal).toInt().coerceIn(0, 100)
            b.setProgress(100, pct, false).setContentText("$pct%")
        } else {
            b.setProgress(0, 0, true)
        }
        return b.build()
    }

    /** Intent untuk membuka file hasil unduhan, atau null bila belum ada Uri. */
    fun viewIntent(item: DownloadItem): Intent? {
        val uri = item.uri ?: return null
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(uri), item.mime.ifBlank { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun finished(c: Context, item: DownloadItem) {
        try {
            ensureChannels(c)
            val nm = NotificationManagerCompat.from(c)
            val id = (item.id % 1_000_000L).toInt() + 10000
            when (item.status) {
                DlStatus.DONE -> {
                    val view = viewIntent(item)
                    val pi = if (view != null) {
                        PendingIntent.getActivity(
                            c, id, view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                        )
                    } else {
                        listIntent(c)
                    }
                    nm.notify(
                        id,
                        NotificationCompat.Builder(c, CH_DONE)
                            .setSmallIcon(R.drawable.ic_check)
                            .setContentTitle(item.name)
                            .setContentText(c.getString(R.string.dl_notif_done))
                            .setAutoCancel(true)
                            .setContentIntent(pi)
                            .build()
                    )
                }
                DlStatus.FAILED -> nm.notify(
                    id,
                    NotificationCompat.Builder(c, CH_DONE)
                        .setSmallIcon(R.drawable.ic_close)
                        .setContentTitle(c.getString(R.string.dl_notif_failed, item.name))
                        .setContentText(item.error ?: "")
                        .setStyle(NotificationCompat.BigTextStyle().bigText(item.error ?: ""))
                        .setAutoCancel(true)
                        .setContentIntent(listIntent(c))
                        .build()
                )
                else -> nm.cancel(id)
            }
        } catch (e: Exception) {
            // notifikasi hanya pelengkap
        }
    }
}
