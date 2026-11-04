package com.anees.novamobile.download

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** "Saving to phone" notification with a progress bar (indeterminate while converting). */
class SaveProgress(private val ctx: Context, private val title: String) {
    private val id = 7300 + (title.hashCode() and 0xff)
    private var lastPct = -2

    @SuppressLint("MissingPermission")
    fun update(fraction: Float) {
        val pct = if (fraction < 0) -1 else (fraction * 100).toInt().coerceIn(0, 100)
        if (pct == lastPct) return
        lastPct = pct
        val n = NotificationCompat.Builder(ctx, NovaDownloads.CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Saving to phone")
            .setContentText(if (pct < 0) "$title · converting…" else "$title · $pct%")
            .setProgress(100, pct.coerceAtLeast(0), pct < 0)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(id, n) }
    }

    fun done() { runCatching { NotificationManagerCompat.from(ctx).cancel(id) } }
}
