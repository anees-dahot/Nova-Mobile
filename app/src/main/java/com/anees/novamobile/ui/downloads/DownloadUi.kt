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
        !online(ctx) && !paused(d) -> "Waiting for network · resumes by itself"
        !paused(d) && d.state == Download.STATE_QUEUED && notMet(ctx, androidx.media3.exoplayer.scheduler.Requirements.DEVICE_CHARGING) -> "Waiting for the charger"
        !paused(d) && d.state == Download.STATE_QUEUED && notMet(ctx, androidx.media3.exoplayer.scheduler.Requirements.NETWORK_UNMETERED) -> "Waiting for Wi-Fi"
        !paused(d) && d.state == Download.STATE_QUEUED && notMet(ctx, androidx.media3.exoplayer.scheduler.Requirements.DEVICE_STORAGE_NOT_LOW) -> "Phone storage low · free up space"
        d.state == Download.STATE_FAILED -> "Failed · tap to retry"
        paused(d) -> "Paused · ${DlStats.progress(ctx, d)}"
        NovaDownloads.storageFull -> "Storage full"
        d.state == Download.STATE_DOWNLOADING -> DlStats.line(ctx, d)
        d.state == Download.STATE_RESTARTING -> "Reconnecting…"
        d.state == Download.STATE_QUEUED && d.bytesDownloaded > 0 -> "Queued · ${DlStats.progress(ctx, d)}"
        else -> "In line · starts when a slot frees up"
    }

    private fun notMet(ctx: Context, flag: Int) = runCatching { NovaDownloads.manager(ctx).notMetRequirements and flag != 0 }.getOrDefault(false)

    private fun online(ctx: Context): Boolean {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java) ?: return true
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    /** Enough saved to start watching while the rest downloads (the player fetches what's missing). */
    fun watchable(d: Download) = d.state != Download.STATE_FAILED && d.bytesDownloaded > 8L * 1024 * 1024

    /** Re-download in another quality/source: the old copy goes, the download sheet opens for a fresh pick. */
    fun changeQuality(act: androidx.appcompat.app.AppCompatActivity, d: Download, m: DownloadMeta, onChange: () -> Unit) {
        android.app.AlertDialog.Builder(act)
            .setTitle("Change quality?")
            .setMessage("The current copy (${m.quality?.let { "${it}p, " } ?: ""}${size(act, d.bytesDownloaded)}) is deleted, then you pick the new source and quality.")
            .setPositiveButton("Continue") { _, _ ->
                remove(act, d)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    DownloadSheet.show(act, listOf(com.anees.novamobile.download.DownloadStarter.Item(m.card, m.season, m.episode)), onChange)
                }, 500)
            }
            .setNegativeButton("Cancel", null).show()
    }

    /** "MP4  ·  720P  ·  MOVIEBOX  ·  HINDI": what this download really is. */
    fun tags(d: Download, m: DownloadMeta?): String = listOfNotNull(
        if (com.anees.novamobile.download.DownloadStarter.isProgressive(d.request.uri.toString())) "MP4" else "HLS",
        m?.quality?.let { "${it}p" },
        m?.provider?.substringBefore('#'),
        m?.let { com.anees.novamobile.data.sources.anime.FillerInfo.cached(it.card)?.at(it.season, it.episode)?.kind }?.let { com.anees.novamobile.ui.common.FillerBadge.text(it) },
        m?.audioLangs?.takeIf { it.isNotEmpty() }?.let { l -> l.take(3).mapNotNull { com.anees.novamobile.data.model.LangTags.name(it) }.joinToString(", ").ifEmpty { null } },
    ).joinToString("  ·  ").uppercase()

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

    /** [alsoPhone]: delete the Movies/Nova copy too (otherwise it stays in the phone's gallery). */
    fun remove(ctx: Context, d: Download, alsoPhone: Boolean = false) {
        if (alsoPhone) com.anees.novamobile.download.PublicExport.removeCopy(ctx, d)
        NovaDownloads.deleteSubtitles(d)
        DownloadService.sendRemoveDownload(ctx, NovaDownloadService::class.java, d.request.id, false)
    }

    /**
     * Delete with one question when a copy also sits in the phone's Movies/Nova folder:
     * Nova only (frees Nova's space, video stays in the gallery) or both. [onCancel] when dismissed.
     */
    fun confirmDelete(ctx: Context, targets: List<Download>, onCancel: () -> Unit = {}, onDone: (alsoPhone: Boolean) -> Unit) {
        val onPhone = targets.count { com.anees.novamobile.download.PublicExport.exported(ctx, it.request.id) }
        if (onPhone == 0) { onDone(false); return }
        var chose = false
        val what = if (targets.size == 1) "This video is" else "$onPhone of these videos are"
        com.anees.novamobile.ui.common.ActionSheet.show(ctx, "Also delete from your phone?", listOf(
            com.anees.novamobile.ui.common.ActionSheet.Item("Delete from Nova and phone") { chose = true; onDone(true) },
            com.anees.novamobile.ui.common.ActionSheet.Item("Delete from Nova only") { chose = true; onDone(false) },
        ), subtitle = "$what also saved in Movies/Nova on your phone.", onDismiss = { if (!chose) onCancel() })
    }

    /** confirmDelete, then remove. */
    fun delete(ctx: Context, targets: List<Download>, onCancel: () -> Unit = {}, onDone: () -> Unit = {}) =
        confirmDelete(ctx, targets, onCancel) { phone -> targets.forEach { remove(ctx, it, phone) }; onDone() }

    fun size(ctx: Context, bytes: Long): String = Formatter.formatShortFileSize(ctx, bytes)

    fun showKey(card: MediaCard) = "${card.type}:${card.id}"
}
