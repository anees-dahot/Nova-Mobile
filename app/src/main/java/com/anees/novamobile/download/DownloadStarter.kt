package com.anees.novamobile.download

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.anees.novamobile.data.api.RetrofitClient
import com.anees.novamobile.data.local.LocalStore
import com.anees.novamobile.data.model.LangTags
import com.anees.novamobile.data.model.MediaCard
import com.anees.novamobile.data.repository.MediaRepository
import com.anees.novamobile.ui.player.PngStripDataSource
import com.anees.novamobile.ui.player.Prefetcher
import com.anees.novamobile.ui.player.StreamExtractor
import com.anees.novamobile.ui.player.SubtitleCleaner
import com.anees.novamobile.ui.player.WebStreamSniffer
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Turns "download this episode / season" into Media3 downloads. Finding a stream needs the hidden
 * WebView, so items are resolved one at a time while the app is open; the list of items still to
 * resolve is saved, so a bulk download continues after the app is reopened.
 */
@UnstableApi
object DownloadStarter {
    private const val TAG = "NovaDL"
    private val scope = MainScope()
    private val repo = MediaRepository()
    private var worker: Job? = null

    data class Item(val card: MediaCard, val season: Int, val episode: Int, val refreshOf: String? = null, val skipProvider: String? = null)

    /** Single or bulk. Already downloaded / queued episodes are skipped. */
    fun enqueue(ctx: Context, items: List<Item>) {
        val app = ctx.applicationContext
        val fresh = items.filter { i ->
            val d = NovaDownloads.get(app, DownloadMeta.id(i.card, i.season, i.episode))
            i.refreshOf != null || d == null || d.state == Download.STATE_FAILED
        }
        if (fresh.isEmpty()) { Toast.makeText(app, "Already downloaded", Toast.LENGTH_SHORT).show(); return }
        LocalStore.savePendingDownloads(LocalStore.pendingDownloads() + fresh)
        Toast.makeText(app, if (fresh.size == 1) "Download added" else "${fresh.size} episodes added to downloads", Toast.LENGTH_SHORT).show()
        resume(app)
    }

    /** Called at app start and after enqueue: resolve whatever is still pending. */
    fun resume(ctx: Context) {
        if (worker?.isActive == true) return
        val app = ctx.applicationContext
        worker = scope.launch {
            while (isActive) {
                val next = LocalStore.pendingDownloads().firstOrNull() ?: break
                if (Prefetcher.host == null) break                    // app window gone: continue next launch
                val ok = runCatching { start(app, next) }.onFailure { Log.w(TAG, "start failed: ${it.message}") }.getOrDefault(false)
                LocalStore.savePendingDownloads(LocalStore.pendingDownloads().drop(1))
                // No source had it: set aside, shown in Downloads as "Couldn't find a source — tap to retry"
                if (!ok) LocalStore.saveUnresolved((LocalStore.unresolved().filterNot { it.card.id == next.card.id && it.episode == next.episode && it.season == next.season } + next).takeLast(50))
                else LocalStore.saveUnresolved(LocalStore.unresolved().filterNot { it.card.id == next.card.id && it.episode == next.episode && it.season == next.season })
            }
        }
    }

    /** Expired link while downloading: same id, fresh link; data already saved is reused (stable cache keys). */
    fun refresh(ctx: Context, d: Download, nextSource: Boolean) {
        val meta = DownloadMeta.of(d) ?: return
        enqueue(ctx, listOf(Item(meta.card, meta.season, meta.episode, refreshOf = d.request.id, skipProvider = meta.provider.takeIf { nextSource })))
    }

    private suspend fun start(ctx: Context, item: Item): Boolean {
        val host = Prefetcher.host ?: return false          // needs the app's window for the hidden WebView
        val id = DownloadMeta.id(item.card, item.season, item.episode)
        val target = StreamExtractor.resolveTarget(item.card, item.season, item.episode, repo) ?: return false
        for (p in StreamExtractor.ordered().filter { it.name != item.skipProvider }) {
            val sniffed = StreamExtractor.cached(p, target) ?: WebStreamSniffer(ctx, host).sniff(StreamExtractor.embedUrl(p, target), p.timeoutMs) ?: continue
            StreamExtractor.remember(p, target, sniffed)
            rememberHeaders(sniffed)
            val sub = sniffed.subtitles.firstOrNull { !SubtitleCleaner.isThumbnailTrack(it) }?.let { url ->
                withContext(Dispatchers.IO) {
                    val out = File(File(NovaDownloads.manager(ctx).let { NovaDownloads.dir }, "subs").apply { mkdirs() }, "${id.hashCode()}.vtt")
                    if (SubtitleCleaner.saveTo(ctx, url, RetrofitClient.base, sniffed.headers, out)) out.absolutePath else null
                }
            }
            val meta = DownloadMeta(item.card, item.season, item.episode, p.name, sub)
            val request = runCatching { buildRequest(ctx, id, sniffed, meta) }.getOrNull() ?: continue
            DownloadService.sendAddDownload(ctx, NovaDownloadService::class.java, request, false)
            Log.i(TAG, "queued $id via ${p.name}")
            return true
        }
        return false
    }

    private fun rememberHeaders(s: WebStreamSniffer.Sniffed) {
        LocalStore.setDownloadUserAgent(s.userAgent)
        val uri = Uri.parse(s.url)
        uri.host?.let { LocalStore.setHostHeaders(it, s.headers) }
        uri.getQueryParameter("url")?.let { Uri.parse(it).host }?.let { LocalStore.setHostHeaders(it, s.headers) }
    }

    /** Video at the chosen quality; audio in the preferred language plus English, so multi-audio survives offline. */
    private suspend fun buildRequest(ctx: Context, id: String, s: WebStreamSniffer.Sniffed, meta: DownloadMeta): DownloadRequest {
        val settings = LocalStore.settings()
        val http = OkHttpDataSource.Factory(RetrofitClient.base).setUserAgent(s.userAgent).setDefaultRequestProperties(s.headers)
        val factory: DataSource.Factory = ResolvingDataSource.Factory(
            DataSource.Factory { PngStripDataSource(DefaultDataSource.Factory(ctx, http).createDataSource()) }
        ) { spec -> spec.withUri(StreamExtractor.unwrapProxy(spec.uri)) }
        val item = MediaItem.Builder().setUri(s.url).setCustomCacheKey(null)
            .apply { if (s.url.lowercase().contains("m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8) }.build()
        val (w, h) = when (settings.dlQuality) { 0 -> 854 to 480; 2 -> 1920 to 1080; else -> 1280 to 720 }
        val params = DownloadHelper.getDefaultTrackSelectorParameters(ctx).buildUpon()
            .setMaxVideoSize(w, h).setForceHighestSupportedBitrate(true).build()
        val helper = DownloadHelper.forMediaItem(ctx, item, DefaultRenderersFactory(ctx), factory)
        try {
            withContext(Dispatchers.Main) { prepare(helper) }
            val want = (listOf(LocalStore.audioFor("${meta.card.type}:${meta.card.id}") ?: settings.audioLang, "en")).filter { it != "orig" }.distinct()
            for (period in 0 until helper.periodCount) {
                helper.clearTrackSelections(period)
                helper.addTrackSelection(period, params)
                // Extra audio tracks by language code or by name ("Hindi") when the source gives no code
                val info = helper.getMappedTrackInfo(period)
                for (r in 0 until info.rendererCount) {
                    if (info.getRendererType(r) != androidx.media3.common.C.TRACK_TYPE_AUDIO) continue
                    val groups = info.getTrackGroups(r)
                    val overrides = mutableListOf<DefaultTrackSelector.SelectionOverride>()
                    for (g in 0 until groups.length) for (t in 0 until groups[g].length) {
                        val f = groups[g].getFormat(t)
                        val lang = f.language?.take(2)?.takeIf { it != "un" } ?: LangTags.code(f.label)
                        if (lang != null && lang in want) overrides += DefaultTrackSelector.SelectionOverride(g, t)
                    }
                    if (overrides.isNotEmpty()) helper.addTrackSelectionForSingleRenderer(period, r, params, overrides)
                }
            }
            return helper.getDownloadRequest(id, meta.toBytes())
        } finally {
            helper.release()
        }
    }

    private suspend fun prepare(helper: DownloadHelper) = suspendCancellableCoroutine<Unit> { cont ->
        helper.prepare(object : DownloadHelper.Callback {
            override fun onPrepared(h: DownloadHelper) { if (cont.isActive) cont.resume(Unit) }
            override fun onPrepareError(h: DownloadHelper, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
        })
    }
}
