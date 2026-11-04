package com.anees.novamobile.download

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.anees.novamobile.MainActivity

/**
 * "Saving to phone" notification with a progress bar (indeterminate while converting). Uses the
 * [ExportService] notification id, so while the service runs this IS its foreground notification.
 */
class SaveProgress(private val ctx: Context, private val title: String) {
    private var lastPct = -2

    @SuppressLint("MissingPermission")
    fun update(fraction: Float) {
        val pct = if (fraction < 0) -1 else (fraction * 100).toInt().coerceIn(0, 100)
        if (pct == lastPct) return
        lastPct = pct
        val more = PublicExport.pending
        val text = (if (pct < 0) "$title · converting…" else "$title · $pct%") + if (more > 0) "  ·  $more more queued" else ""
        runCatching { NotificationManagerCompat.from(ctx).notify(ID, build(ctx, text, pct)) }
    }

    /** Clears the bar once nothing else is queued (the service removes it too, if it was running). */
    fun done() {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!PublicExport.busy) runCatching { NotificationManagerCompat.from(ctx).cancel(ID) }
        }, 1_500)
    }

    companion object {
        const val ID = 7300
        private const val RESULT_ID = 7301

        fun build(ctx: Context, text: String, pct: Int) = NotificationCompat.Builder(ctx, NovaDownloads.CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Saving to phone")
            .setContentText(text)
            .setProgress(100, pct.coerceAtLeast(0), pct < 0)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setContentIntent(open(ctx))
            .build()

        private fun open(ctx: Context) = PendingIntent.getActivity(ctx, 4, Intent(ctx, MainActivity::class.java).putExtra("open", "downloads"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        /** Lasting "Saved …" / "Couldn't save …" note, so a save finished in the background is noticed. */
        @SuppressLint("MissingPermission")
        fun result(ctx: Context, ok: Boolean, msg: String) {
            val n = NotificationCompat.Builder(ctx, NovaDownloads.CHANNEL)
                .setSmallIcon(if (ok) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
                .setContentTitle(if (ok) "Saved to phone" else "Save failed")
                .setContentText(msg)
                .setAutoCancel(true).setSilent(true)
                .setContentIntent(open(ctx))
                .build()
            runCatching { NotificationManagerCompat.from(ctx).notify(RESULT_ID, n) }
        }
    }
}
