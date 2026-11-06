package com.anees.novamobile.download

import android.content.Context
import android.text.format.Formatter
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download

/** Size, smoothed speed and time left for a download, shared by the Downloads list and the notification. */
@OptIn(UnstableApi::class)
object DlStats {
    private class S(var bytes: Long, var time: Long, var speed: Double)
    private val seen = HashMap<String, S>()

    /** Total size: real length when known, else estimated from bytes / percent (HLS has no length). */
    fun total(d: Download): Long {
        if (d.contentLength > 0) return d.contentLength
        val p = d.percentDownloaded
        return if (p in 1f..100f) (d.bytesDownloaded * 100 / p).toLong() else -1
    }

    /** Bytes per second, smoothed so the number doesn't jump around. */
    fun speed(d: Download): Long = synchronized(seen) {
        val now = System.currentTimeMillis()
        val s = seen[d.request.id] ?: return S(d.bytesDownloaded, now, 0.0).also { seen[d.request.id] = it }.speed.toLong()
        val dt = now - s.time
        if (dt >= 700) {
            val inst = (d.bytesDownloaded - s.bytes).coerceAtLeast(0) * 1000.0 / dt
            s.speed = if (s.speed == 0.0) inst else s.speed * 0.7 + inst * 0.3
            s.bytes = d.bytesDownloaded; s.time = now
        }
        s.speed.toLong()
    }

    fun etaSeconds(d: Download): Long {
        val t = total(d); val sp = speed(d)
        return if (t > 0 && sp > 0) ((t - d.bytesDownloaded).coerceAtLeast(0) / sp) else -1
    }

    fun eta(sec: Long): String = when {
        sec < 0 -> ""
        sec < 60 -> "Less than a minute left"
        sec < 3600 -> "${(sec + 30) / 60} min left"
        else -> "${sec / 3600} hr ${(sec % 3600) / 60} min left"
    }

    fun size(ctx: Context, b: Long): String = Formatter.formatShortFileSize(ctx, b)

    /** "120 MB of 260 MB" (or just "120 MB" when the total is unknown). */
    fun progress(ctx: Context, d: Download): String {
        val t = total(d)
        return if (t > 0) "${size(ctx, d.bytesDownloaded)} of ${if (d.contentLength > 0) "" else "~"}${size(ctx, t)}" else size(ctx, d.bytesDownloaded)
    }

    /** One line for an active download: "45% · 120 MB of ~260 MB · 2.1 MB/s · 3 min left". */
    fun line(ctx: Context, d: Download): String {
        val sp = speed(d)
        return listOfNotNull(
            "${d.percentDownloaded.toInt().coerceIn(0, 100)}%",
            progress(ctx, d).replace(" of ", " / "),
            if (sp > 0) "${size(ctx, sp)}/s" else null,
            etaSeconds(d).takeIf { it >= 0 }?.let { e -> if (e < 60) "<1m" else if (e < 3600) "${(e + 30) / 60}m" else "${e / 3600}h ${(e % 3600) / 60}m" }
        ).joinToString("  ·  ")
    }
}
