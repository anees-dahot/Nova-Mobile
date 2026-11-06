package com.anees.novamobile.download

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.media3.exoplayer.scheduler.Scheduler
import com.anees.novamobile.MainActivity
import com.anees.novamobile.R

/** Keeps downloads running with the app closed; one progress notification for the whole queue. */
@UnstableApi
class NovaDownloadService : DownloadService(
    1001, DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL, NovaDownloads.CHANNEL, R.string.downloads_channel, 0
) {
    override fun getDownloadManager(): DownloadManager = NovaDownloads.manager(this)

    override fun getScheduler(): Scheduler = PlatformScheduler(this, 1001)

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).putExtra("open", "downloads"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val running = downloads.filter { it.state == Download.STATE_DOWNLOADING }
        val waiting = downloads.count { it.state == Download.STATE_QUEUED }
        val blocked = when {
            notMetRequirements and Requirements.NETWORK_UNMETERED != 0 -> "Waiting for Wi-Fi"
            notMetRequirements and Requirements.NETWORK != 0 -> "Waiting for a connection"
            notMetRequirements and Requirements.DEVICE_CHARGING != 0 -> "Waiting for the charger"
            notMetRequirements and Requirements.DEVICE_STORAGE_NOT_LOW != 0 -> "Phone storage is low — free up space"
            NovaDownloads.storageFull -> "Storage full — free up space"
            else -> null
        }
        fun name(d: Download) = DownloadMeta.of(d)?.let { m ->
            if (m.card.type == com.anees.novamobile.data.model.MediaType.MOVIE) m.card.title else "${m.card.title} · S${m.season} E${m.episode}"
        } ?: "Video"

        val b = NotificationCompat.Builder(this, NovaDownloads.CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
            .setColor(com.anees.novamobile.ui.common.Ui.ACCENT)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        // Pause / Resume the whole queue; Cancel removes the one in front
        if (NovaDownloads.storageFull) b.addAction(R.drawable.ic_play, "Resume", service(1, buildResumeDownloadsIntent(this, NovaDownloadService::class.java, true)))
        else b.addAction(R.drawable.ic_pause, "Pause all", service(1, buildPauseDownloadsIntent(this, NovaDownloadService::class.java, false)))
        val first = running.firstOrNull()
        (first ?: downloads.firstOrNull())?.let { DownloadMeta.of(it) }?.let { DownloadNotifier.poster(this, it.card) }?.let { b.setLargeIcon(it) }
        if (first != null && running.size == 1)
            b.addAction(R.drawable.ic_delete, "Cancel", service(2, buildRemoveDownloadIntent(this, NovaDownloadService::class.java, first.request.id, false)))

        when {
            blocked != null -> {
                b.setContentTitle(blocked).setContentText(if (downloads.size == 1) name(downloads[0]) else "${downloads.size} downloads paused")
                    .setProgress(0, 0, false)
            }
            first == null -> b.setContentTitle("Starting download…").setContentText(if (waiting > 0) "$waiting in queue" else null).setProgress(0, 0, true)
            running.size == 1 -> {
                val pct = first.percentDownloaded
                b.setContentTitle(name(first))
                    .setContentText(DlStats.line(this, first))
                    .setSubText(if (waiting > 0) "$waiting more in queue" else null)
                    .setProgress(100, pct.toInt().coerceIn(0, 100), pct < 0)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(DlStats.line(this, first)))
            }
            else -> {
                val total = running.sumOf { DlStats.total(it).coerceAtLeast(0) }
                val done = running.sumOf { it.bytesDownloaded }
                val speed = running.sumOf { DlStats.speed(it) }
                val eta = if (total > 0 && speed > 0) DlStats.eta((total - done).coerceAtLeast(0) / speed) else ""
                val inbox = NotificationCompat.InboxStyle()
                running.take(5).forEach { inbox.addLine("${name(it)}  —  ${it.percentDownloaded.toInt().coerceIn(0, 100)}%") }
                if (waiting > 0) inbox.setSummaryText("$waiting more in queue")
                b.setContentTitle("Downloading ${running.size} videos")
                    .setContentText(listOf(if (speed > 0) "${DlStats.size(this, speed)}/s" else "", eta).filter { it.isNotEmpty() }.joinToString("  ·  "))
                    .setProgress(100, if (total > 0) (done * 100 / total).toInt() else 0, total <= 0)
                    .setStyle(inbox)
            }
        }
        return b.build()
    }

    private fun service(code: Int, i: Intent): PendingIntent =
        PendingIntent.getService(this, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}
