package com.anees.novamobile.ui.downloads

import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadService
import com.anees.novamobile.data.local.LocalStore
import com.anees.novamobile.data.model.LangTags
import com.anees.novamobile.data.model.MediaCard
import com.anees.novamobile.data.model.MediaType
import com.anees.novamobile.download.DlStats
import com.anees.novamobile.download.DownloadMeta
import com.anees.novamobile.download.NovaDownloadService
import com.anees.novamobile.download.NovaDownloads

/** Shared wording + ring states for every screen that shows a download. */
@UnstableApi
object DownloadUi {

    fun paused(d: Download) = d.state == Download.STATE_STOPPED || d.stopReason != Download.STOP_REASON_NONE

    fun ring(d: Download?): ProgressRing.State = when {
        d == null -> ProgressRing.State.IDLE
        d.state == Download.STATE_COMPLETED -> ProgressRing.State.DONE
        d.state == Download.STATE_FAILED -> ProgressRing.State.FAILED
        paused(d) -> ProgressRing.State.PAUSED
        d.state == Download.STATE_DOWNLOADING && d.percentDownloaded > 0 -> ProgressRing.State.DOWNLOADING
        else -> ProgressRing.State.QUEUED
    }

    /** "E3" / "S2 · E3" / "" for movies. */
    fun episode(m: DownloadMeta?): String = when {
        m == null || m.card.type == MediaType.MOVIE || m.season <= 0 -> ""
        m.card.type == MediaType.ANIME -> "Episode ${m.episode}"
        else -> "S${m.season} · E${m.episode}"
    }

    fun name(m: DownloadMeta) = episode(m).let { if (it.isEmpty()) m.card.title else "${m.card.title} · $it" }

    /** One line, never wraps: what's happening right now. */
    fun status(ctx: Context, d: Download): String = when {
        d.state == Download.STATE_COMPLETED -> Formatter.formatShortFileSize(ctx, d.bytesDownloaded)
        d.state == Download.STATE_FAILED -> "Failed · tap to retry"
        paused(d) -> "Paused · ${DlStats.progress(ctx, d)}"
        NovaDownloads.storageFull -> "Storage full"
        d.state == Download.STATE_DOWNLOADING -> DlStats.line(ctx, d)
        d.state == Download.STATE_RESTARTING -> "Restarting…"
        else -> "Waiting…"
    }

    /** "720p · Hindi, English · 2 subtitles" */
    fun details(m: DownloadMeta): String = listOfNotNull(
        m.quality?.let { "${it}p" },
        m.audioLangs?.mapNotNull { LangTags.name(it) }?.takeIf { it.isNotEmpty() }?.joinToString(", "),
        m.subtitles?.size?.takeIf { it > 0 }?.let { "$it sub${if (it > 1) "s" else ""}" },
    ).joinToString("  ·  ")

    fun watched(m: DownloadMeta): Float =
        LocalStore.episodeProgress("${m.card.type}:${m.card.id}")["${m.season}:${m.episode}"] ?: 0f

    /** Ring tap: pause / resume / retry. */
    fun toggle(ctx: Context, d: Download) {
        when {
            d.state == Download.STATE_FAILED -> NovaDownloads.retry(ctx, d)
            paused(d) -> DownloadService.sendSetStopReason(ctx, NovaDownloadService::class.java, d.request.id, Download.STOP_REASON_NONE, false)
            d.state != Download.STATE_COMPLETED -> DownloadService.sendSetStopReason(ctx, NovaDownloadService::class.java, d.request.id, 1, false)
        }
    }

    fun remove(ctx: Context, d: Download) {
        NovaDownloads.deleteSubtitles(d)
        DownloadService.sendRemoveDownload(ctx, NovaDownloadService::class.java, d.request.id, false)
    }

    fun size(ctx: Context, bytes: Long): String = Formatter.formatShortFileSize(ctx, bytes)

    fun showKey(card: MediaCard) = "${card.type}:${card.id}"
}
