package com.anees.novamobile.download

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
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
    private val helper by lazy { DownloadNotificationHelper(this, NovaDownloads.CHANNEL) }

    override fun getDownloadManager(): DownloadManager = NovaDownloads.manager(this)

    override fun getScheduler(): Scheduler = PlatformScheduler(this, 1001)

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).putExtra("open", "downloads"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val active = downloads.count { it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_QUEUED }
        val msg = when {
            notMetRequirements and Requirements.NETWORK_UNMETERED != 0 -> "Waiting for Wi-Fi"
            notMetRequirements and Requirements.NETWORK != 0 -> "Waiting for a connection"
            NovaDownloads.storageFull -> "Storage full — free up space"
            active == 1 -> "Downloading 1 title"
            else -> "Downloading $active titles"
        }
        return helper.buildProgressNotification(this, R.drawable.ic_download, open, msg, downloads, notMetRequirements)
    }
}
