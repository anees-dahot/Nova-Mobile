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
import com.anees.novamobile.ui.player.AudioProbe
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

    /** [quality]: 0 480p, 1 720p, 2 1080p; null = the Settings default. */
    data class Item(val card: MediaCard, val season: Int, val episode: Int, val refreshOf: String? = null, val skipProvider: String? = null,
                    val quality: Int? = null,
                    /** Source picked in the download sheet (tried first); null = the richest in languages. */
                    val provider: String? = null)

    /** One answering source for the download sheet: real audio languages and best picture height (-1 unknown). */
    data class Probe(val provider: String, val langs: List<String>, val height: Int)

    /**
     * Asks every source for [item] at once (plain-HTTP resolvers right away, hidden pages a little
     * apart) and reports each that answers. Found links are cached, so the download then starts instantly.
     */
    fun probe(ctx: Context, item: Item): kotlinx.coroutines.flow.Flow<Probe> = kotlinx.coroutines.flow.channelFlow {
        val t = StreamExtractor.resolveTarget(item.card, item.season, item.episode, repo) ?: return@channelFlow
        val host = Prefetcher.host
        var pages = 0
        for (p in StreamExtractor.ordered()) {
            val cached = StreamExtractor.cached(p, t)
            if (cached == null && p.resolver == null && (host == null || pages >= MAX_PROBE_PAGES)) continue
            val wait = if (cached != null || p.resolver != null) 0L else pages++ * PROBE_STAGGER_MS
            launch {
                delay(wait)
                val r = cached ?: withTimeoutOrNull(p.timeoutMs) {
                    runCatching { p.resolver?.invoke(t) ?: WebStreamSniffer(ctx, host!!).sniff(StreamExtractor.embedUrl(p, t), p.timeoutMs) }.getOrNull()
                } ?: return@launch
                StreamExtractor.remember(p, t, r)
                val langs = AudioProbe.langs(r)
                val h = runCatching { com.anees.novamobile.ui.player.QualityGate.maxHeightAsync(r) }.getOrDefault(-1)
                send(Probe(p.name, langs, h))
            }
        }
    }

    private const val MAX_PROBE_PAGES = 6
    private const val PROBE_STAGGER_MS = 1_500L

    /** True while items are being resolved (keeps [ResolveService] alive). */
    val busy get() = worker?.isActive == true

    /** Rough size of one item at a quality, from typical stream bitrates (GB per hour: 0.45 / 0.9 / 1.8). */
    fun estimateBytes(card: MediaCard, quality: Int): Long {
        val minutes = card.runtime?.takeIf { it > 0 } ?: when (card.type) { com.anees.novamobile.data.model.MediaType.MOVIE -> 120; com.anees.novamobile.data.model.MediaType.ANIME -> 24; else -> 45 }
        val gbPerHour = when (quality) { 0 -> 0.45; 2 -> 1.8; else -> 0.9 }
        return (minutes / 60.0 * gbPerHour * 1024 * 1024 * 1024).toLong()
    }


    /** Single or bulk. Already downloaded / queued episodes are skipped. */
    /** Returns how many were added (0 = everything was already downloaded or queued). */
    fun enqueue(ctx: Context, items: List<Item>): Int {
        val app = ctx.applicationContext
        val fresh = items.filter { i ->
            val d = NovaDownloads.get(app, DownloadMeta.id(i.card, i.season, i.episode))
            i.refreshOf != null || d == null || d.state == Download.STATE_FAILED
        }
        if (fresh.isEmpty()) return 0
        LocalStore.savePendingDownloads(LocalStore.pendingDownloads() + fresh)
        resume(app)
        return fresh.size
    }

    /**
     * Called at app start, after enqueue, and by [ResolveService]: resolve whatever is still pending.
     * With the app open every source is tried (hidden WebView in the app window). With the app closed
     * only plain-HTTP sources can run; items they can't find wait for the app to open again.
     */
    fun resume(ctx: Context) {
        if (worker?.isActive == true) return
        val app = ctx.applicationContext
        worker = scope.launch {
            while (isActive) {
                val next = LocalStore.pendingDownloads().firstOrNull() ?: break
                val headless = Prefetcher.host == null
                val ok = runCatching { start(app, next, headless) }.onFailure { Log.w(TAG, "start failed: ${it.message}") }.getOrDefault(false)
                if (!ok && headless) break                           // needs a WebView source: continue when the app opens
                LocalStore.savePendingDownloads(LocalStore.pendingDownloads().drop(1))
                // No source had it: set aside, shown in Downloads as "Couldn't find a source — tap to retry"
                if (!ok) DownloadNotifier.failed(app, DownloadMeta.id(next.card, next.season, next.episode),
                    if (next.card.type == com.anees.novamobile.data.model.MediaType.MOVIE) next.card.title else "${next.card.title} · S${next.season} E${next.episode}",
                    "No source had it right now. Retry later.")
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

    private suspend fun start(ctx: Context, item: Item, headless: Boolean): Boolean {
        val id = DownloadMeta.id(item.card, item.season, item.episode)
        val target = StreamExtractor.resolveTarget(item.card, item.season, item.episode, repo) ?: return false
        val sources = StreamExtractor.ordered().filter { it.name != item.skipProvider }
        // Your pick from the sheet first; else ask every multi-audio resolver at once and save the stream with the most real languages
        val picked = item.provider?.let { n -> sources.firstOrNull { it.name == n } }
        val richest = if (picked != null) null else richest(ctx, sources, target)
        val first = picked ?: richest?.first
        for (p in listOfNotNull(first) + sources.filter { it != first }) {
            val host = Prefetcher.host
            if (p.resolver == null && (headless || host == null) && StreamExtractor.cached(p, target) == null) continue
            val sniffed = richest?.takeIf { it.first == p }?.second ?: StreamExtractor.cached(p, target)
                ?: runCatching {
                    if (p.resolver != null) withTimeoutOrNull(p.timeoutMs) { p.resolver.invoke(target) }
                    else WebStreamSniffer(ctx, host!!).sniff(StreamExtractor.embedUrl(p, target), p.timeoutMs)
                }.onFailure { if (it is CancellationException) throw it; Log.w(TAG, "${p.name} failed: ${it.message}") }.getOrNull()
                ?: continue
            StreamExtractor.remember(p, target, sniffed)
            rememberHeaders(sniffed)
            // Every subtitle the source offers, cleaned of ads
            val saved = withContext(Dispatchers.IO) {
                val dir = File(NovaDownloads.manager(ctx).let { NovaDownloads.dir }, "subs").apply { mkdirs() }
                sniffed.subtitles.filterNot { SubtitleCleaner.isThumbnailTrack(it) }.take(10).mapIndexedNotNull { i, url ->
                    val out = File(dir, "${id.hashCode()}_$i.vtt")
                    if (SubtitleCleaner.saveTo(ctx, url, RetrofitClient.base, sniffed.headers, out)) out.absolutePath to (SubtitleCleaner.langOf(url) ?: "") else null
                }
            }
            // Source brought no subtitles: OpenSubtitles (English + yours)
                .ifEmpty {
                    val dir = File(NovaDownloads.dir, "subs").apply { mkdirs() }
                    val langs = listOfNotNull(LocalStore.subtitleFor("${item.card.type}:${item.card.id}")?.takeIf { it != "off" }, "en").distinct()
                    com.anees.novamobile.ui.player.OpenSubs.await(target, repo, langs, 8_000).take(3).mapIndexedNotNull { i, sub ->
                        val out = File(dir, "${id.hashCode()}_os$i.vtt")
                        withContext(Dispatchers.IO) { SubtitleCleaner.saveTo(ctx, sub.url, RetrofitClient.base, emptyMap(), out) }
                            .takeIf { it }?.let { out.absolutePath to sub.lang }
                    }
                }
            val subs = saved.map { it.first }
            val meta = DownloadMeta(item.card, item.season, item.episode, p.name, subs.firstOrNull(), subtitles = subs,
                subtitleLangs = saved.map { it.second })
            val request = runCatching { buildRequest(ctx, id, sniffed, meta, item.quality) }.getOrNull() ?: continue
            DownloadService.sendAddDownload(ctx, NovaDownloadService::class.java, request, false)
            Log.i(TAG, "queued $id via ${p.name}")
            return true
        }
        return false
    }

    private suspend fun richest(ctx: Context, sources: List<StreamExtractor.Provider>, t: StreamExtractor.Target):
            Pair<StreamExtractor.Provider, WebStreamSniffer.Sniffed>? = coroutineScope {
        // Multi-audio pages (VidLux) need the app's hidden WebView; with the app closed only resolvers run
        val host = Prefetcher.host
        sources.filter { it.multiAudio && (it.resolver != null || StreamExtractor.cached(it, t) != null || host != null) }.map { p ->
            async {
                val r = StreamExtractor.cached(p, t)
                    ?: withTimeoutOrNull(p.timeoutMs) { runCatching {
                        p.resolver?.invoke(t) ?: WebStreamSniffer(ctx, host!!).sniff(StreamExtractor.embedUrl(p, t), p.timeoutMs)
                    }.getOrNull() }
                r?.let { Triple(p, it, AudioProbe.langs(it)) }
            }
        }.awaitAll().filterNotNull()
            .also { l -> Log.i(TAG, "download languages: ${l.joinToString { "${it.first.name}=${it.third}" }}") }
            .maxByOrNull { it.third.size }?.let { it.first to it.second }
    }

    /** Audio language picked for this title in the player, else the Settings default. */
    fun audioFor(card: MediaCard): String? = LocalStore.audioFor("${card.type}:${card.id}")

    private fun rememberHeaders(s: WebStreamSniffer.Sniffed) {
        LocalStore.setDownloadUserAgent(s.userAgent)
        val uri = Uri.parse(s.url)
        uri.host?.let { LocalStore.setHostHeaders(it, s.headers) }
        uri.getQueryParameter("url")?.let { Uri.parse(it).host }?.let { LocalStore.setHostHeaders(it, s.headers) }
    }

    /** Video at the chosen quality; audio in the preferred language plus English, so multi-audio survives offline. */
    private suspend fun buildRequest(ctx: Context, id: String, s: WebStreamSniffer.Sniffed, meta0: DownloadMeta, quality: Int?): DownloadRequest {
        var meta = meta0
        val settings = LocalStore.settings()
        val http = OkHttpDataSource.Factory(RetrofitClient.base).setUserAgent(s.userAgent).setDefaultRequestProperties(s.headers)
        val factory: DataSource.Factory = ResolvingDataSource.Factory(
            DataSource.Factory { PngStripDataSource(DefaultDataSource.Factory(ctx, http).createDataSource()) }
        ) { spec -> spec.withUri(StreamExtractor.unwrapProxy(spec.uri)) }
        val item = MediaItem.Builder().setUri(s.url).setCustomCacheKey(null)
            .apply { if (s.url.lowercase().contains("m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8) }.build()
        val (w, h) = when (quality ?: settings.dlQuality) { 0 -> 854 to 480; 2 -> 1920 to 1080; else -> 1280 to 720 }
        val params = DownloadHelper.getDefaultTrackSelectorParameters(ctx).buildUpon()
            .setMaxVideoSize(w, h).setForceHighestSupportedBitrate(true).build()
        val helper = DownloadHelper.forMediaItem(ctx, item, DefaultRenderersFactory(ctx), factory)
        try {
            withContext(Dispatchers.Main) { prepare(helper) }
            // Audio: every language the stream has
            fun lang(f: androidx.media3.common.Format) = LangTags.norm(f.language) ?: LangTags.code(f.label)
            var height = 0
            val audioKept = mutableSetOf<String>()
            for (period in 0 until helper.periodCount) {
                helper.clearTrackSelections(period)
                helper.addTrackSelection(period, params)   // video at the chosen quality
                val info = helper.getMappedTrackInfo(period)
                for (r in 0 until info.rendererCount) {
                    val type = info.getRendererType(r)
                    val groups = info.getTrackGroups(r)
                    if (type == androidx.media3.common.C.TRACK_TYPE_VIDEO) {
                        for (g in 0 until groups.length) for (t in 0 until groups[g].length)
                            groups[g].getFormat(t).height.takeIf { it in 1..h }?.let { if (it > height) height = it }
                    }
                    if (type != androidx.media3.common.C.TRACK_TYPE_AUDIO && type != androidx.media3.common.C.TRACK_TYPE_TEXT) continue
                    val all = mutableListOf<Pair<DefaultTrackSelector.SelectionOverride, String?>>()
                    for (g in 0 until groups.length) for (t in 0 until groups[g].length) all += DefaultTrackSelector.SelectionOverride(g, t) to lang(groups[g].getFormat(t))
                    val keep = all   // every audio language and subtitle the stream has
                    if (type == androidx.media3.common.C.TRACK_TYPE_AUDIO) keep.mapNotNullTo(audioKept) { it.second }
                    if (keep.isNotEmpty()) helper.addTrackSelectionForSingleRenderer(period, r, params, keep.map { it.first })
                }
            }
            meta = meta.copy(quality = height.takeIf { it > 0 }, audioLangs = audioKept.toList().takeIf { it.isNotEmpty() })
            return helper.getDownloadRequest(id, meta.toBytes())
        } finally {
            helper.release()
        }
    }

    private suspend fun prepare(helper: DownloadHelper) = suspendCancellableCoroutine<Unit> { cont ->
        helper.prepare(object : DownloadHelper.Callback {
            override fun onPrepared(h: DownloadHelper, tracksInfoAvailable: Boolean) { if (cont.isActive) cont.resume(Unit) }
            override fun onPrepareError(h: DownloadHelper, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
        })
    }
}
