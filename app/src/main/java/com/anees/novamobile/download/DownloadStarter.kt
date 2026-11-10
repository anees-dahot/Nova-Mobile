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
                    val provider: String? = null,
                    /** Exact picture height picked in the sheet (a quality the source really has); null = from [quality]. */
                    val height: Int? = null,
                    /** Episode name for lists (optional). */
                    val title: String? = null,
                    /** Episode length in minutes (better size estimates than the 45-minute guess). */
                    val minutes: Int? = null)

    /** One real quality a source offers: height, size when known, direct file or HLS. */
    data class Quality(val height: Int, val bytes: Long?, val mp4: Boolean, val meta: com.anees.novamobile.ui.player.StreamMeta? = null)

    /** Settings index (0/1/2) → picture height. */
    fun heightOf(quality: Int) = when (quality) { 0 -> 480; 2 -> 1080; else -> 720 }

    /** The source's file closest to [h] without going over (smallest if all are bigger); the stream itself without options. */
    fun pickOption(s: WebStreamSniffer.Sniffed, h: Int): WebStreamSniffer.Sniffed {
        val opts = s.options?.takeIf { it.isNotEmpty() } ?: return s
        val o = opts.filter { it.height <= h }.maxByOrNull { it.height } ?: opts.minBy { it.height }
        return s.copy(url = o.url, height = o.height)
    }

    /** One answering source for the download sheet: real audio languages and best picture height (-1 unknown). */
    data class Probe(val provider: String, val langs: List<String>, val height: Int, val audioTracks: Int = -1,
                     val qualities: List<Quality> = emptyList(),
                     /** Running time in seconds (-1 unknown): tells an extended cut or a wrong title apart. */
                     val durationSec: Int = -1,
                     /** The stream itself (this dub / server), to play it at once from the Sources page. */
                     val stream: WebStreamSniffer.Sniffed? = null,
                     /** What the source states about it (release, cut, CAM/WEB-DL, codec, server). */
                     val meta: com.anees.novamobile.ui.player.StreamMeta? = null)

    /**
     * Asks every source for [item] at once (plain-HTTP resolvers right away, hidden pages a little
     * apart) and reports each that answers. Found links are cached, so the download then starts instantly.
     */
    fun probe(ctx: Context, item: Item, quickFirst: Boolean = false): kotlinx.coroutines.flow.Flow<Probe> = kotlinx.coroutines.flow.channelFlow {
        val t = StreamExtractor.resolveTarget(item.card, item.season, item.episode, repo) ?: return@channelFlow
        val probeAt = System.currentTimeMillis()
        val host = Prefetcher.host
        var pages = 0
        for (p in StreamExtractor.ordered()) {
            val cached = StreamExtractor.cached(p, t)
            if (cached == null && p.resolver == null && (host == null || pages >= MAX_PROBE_PAGES)) continue
            val wait = if (cached != null || p.resolver != null) 0L else pages++ * PROBE_STAGGER_MS
            launch {
                delay(wait)
                val r = cached ?: withTimeoutOrNull(p.timeoutMs) {
                    runCatching { p.resolver?.invoke(t) ?: StreamExtractor.sniffUrl(p, t)?.let { WebStreamSniffer(ctx, host!!).sniff(it, p.timeoutMs) } }.getOrNull()
                } ?: run { Log.i(TAG, "probe miss ${p.name} ${System.currentTimeMillis() - probeAt}ms"); return@launch }
                StreamExtractor.remember(p, t, r)
                // Per-language streams (CastleTV, MovieBox) each get their own row: "CastleTV#hi"
                r.withVariants().forEachIndexed { i, v ->
                    launch {
                        val name = StreamExtractor.variantKey(v)?.takeIf { i > 0 }?.let { "${p.name}#$it" } ?: p.name
                        // [quickFirst]: the playlist-level answer shows at once, the deep one replaces it (same name)
                        if (quickFirst) AudioProbe.info(v).let { q ->
                            send(Probe(name, q.langs, q.height, q.audioTracks, qualities(v, q), q.durationSec.takeIf { it > 0 } ?: v.meta?.durationSec ?: -1, v, v.meta))
                        }
                        // Deep read: first segment / MP4 index, so every row shows its real audio and quality
                        val info = AudioProbe.info(v, deep = true)
                        Log.i(TAG, "probe $name ${System.currentTimeMillis() - probeAt}ms h=${info.heights.ifEmpty { listOf(info.height) }} " +
                            "langs=${info.langs} tracks=${info.audioTracks} dur=${info.durationSec} mp4=${isProgressive(v.url)} opts=${v.options?.size ?: 0} subs=${v.subtitles.size}")
                        send(Probe(StreamExtractor.variantKey(v)?.takeIf { i > 0 }?.let { "${p.name}#$it" } ?: p.name, info.langs, info.height, info.audioTracks,
                            qualities(v, info), info.durationSec.takeIf { it > 0 } ?: v.meta?.durationSec ?: -1, v, v.meta))
                    }
                }
            }
        }
    }

    /** Real qualities of one stream: its MP4 files (sizes asked with HEAD when the source didn't say) or HLS variants. */
    private suspend fun qualities(v: WebStreamSniffer.Sniffed, info: AudioProbe.Info): List<Quality> = coroutineScope {
        val opts = v.options?.takeIf { it.isNotEmpty() }
        if (opts != null) return@coroutineScope opts.map { o ->
            async { Quality(o.height, o.bytes ?: sizeOf(o.url, v), isProgressive(o.url) || !o.url.contains("m3u8"), o.meta) }
        }.awaitAll()
        val mp4 = isProgressive(v.url)
        val hs = info.heights.ifEmpty { listOfNotNull(info.height.takeIf { it > 0 } ?: v.height) }
        if (mp4) hs.take(1).map { Quality(it, sizeOf(v.url, v) ?: v.meta?.bytes, true, v.meta) } else hs.map { Quality(it, null, false) }
    }

    /** Content-Length of a direct file (Range 0-0 → Content-Range total), null when the server won't say. */
    suspend fun sizeOf(url: String, s: WebStreamSniffer.Sniffed): Long? = withTimeoutOrNull(5_000) {
        withContext(Dispatchers.IO) {
            runCatching {
                val req = okhttp3.Request.Builder().url(url).header("Range", "bytes=0-0").header("User-Agent", s.userAgent)
                    .apply { s.headers.forEach { (k, v) -> header(k, v) } }.build()
                RetrofitClient.base.newCall(req).execute().use { r ->
                    r.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                        ?: r.header("Content-Length")?.toLongOrNull()?.takeIf { r.code == 200 && it > 1 }
                }
            }.getOrNull()
        }
    }

    private const val MAX_PROBE_PAGES = 8
    private const val PROBE_STAGGER_MS = 1_500L

    /** True while items are being resolved (keeps [ResolveService] alive). */
    val busy get() = worker?.isActive == true

    /** Rough size of one item at a quality, from typical stream bitrates (GB per hour: 0.45 / 0.9 / 1.8). */
    /** Same estimate from a known runtime (episode lengths from TMDB beat the 45-minute guess). */
    fun estimateMinutes(minutes: Int, quality: Int): Long {
        val gbPerHour = when (quality) { 0 -> 0.3; 2 -> 1.3; else -> 0.6 }   // measured: 720p HLS ≈ 0.35-0.6 GB/h
        return (minutes / 60.0 * gbPerHour * 1024 * 1024 * 1024).toLong()
    }

    fun estimateBytes(card: MediaCard, quality: Int): Long {
        val minutes = card.runtime?.takeIf { it > 0 } ?: when (card.type) { com.anees.novamobile.data.model.MediaType.MOVIE -> 120; com.anees.novamobile.data.model.MediaType.ANIME -> 24; else -> 45 }
        val gbPerHour = when (quality) { 0 -> 0.3; 2 -> 1.3; else -> 0.6 }   // measured: 720p HLS ≈ 0.35-0.6 GB/h
        return (minutes / 60.0 * gbPerHour * 1024 * 1024 * 1024).toLong()
    }


    /** Single or bulk. Already downloaded / queued episodes are skipped. */
    /** Returns how many were added (0 = everything was already downloaded or queued). */
    fun enqueue(ctx: Context, items: List<Item>): Int {
        val app = ctx.applicationContext
        val pendingNow = LocalStore.pendingDownloads().map { pk(it) }.toSet()
        val fresh = items.filter { i ->
            val d = NovaDownloads.get(app, DownloadMeta.id(i.card, i.season, i.episode))
            // Double taps / same episode twice in a row: one entry only
            pk(i) !in pendingNow && (i.refreshOf != null || d == null || d.state == Download.STATE_FAILED)
        }.distinctBy { pk(it) }
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
        retryUnresolvedIfDue(app)
        // A few items resolve side by side (a season no longer waits episode by episode). All on the main
        // thread, so the pending list and [inFlight] need no locks.
        worker = scope.launch {
            coroutineScope {
                repeat(PARALLEL_RESOLVES) { lane ->
                    launch {
                        delay(lane * 1_500L)   // stagger: hidden pages are heavy
                        while (isActive) {
                            val next = LocalStore.pendingDownloads().firstOrNull { pk(it) !in inFlight } ?: break
                            inFlight += pk(next)
                            val headless = Prefetcher.host == null
                            val ok = try { runCatching { start(app, next, headless) }.onFailure { if (it is CancellationException) throw it; Log.w(TAG, "start failed: ${it.message}") }.getOrDefault(false) }
                                finally { inFlight -= pk(next) }
                            if (!ok && headless) break                           // needs a WebView source: continue when the app opens
                            val same = { x: Item -> pk(x) == pk(next) }
                            LocalStore.savePendingDownloads(LocalStore.pendingDownloads().filterNot(same))
                            // No source had it: set aside, shown in Downloads as "No source had it — tap to retry"
                            if (!ok) DownloadNotifier.failed(app, DownloadMeta.id(next.card, next.season, next.episode),
                                if (next.card.type == com.anees.novamobile.data.model.MediaType.MOVIE) next.card.title else "${next.card.title} · S${next.season} E${next.episode}",
                                "No source had it right now. Retry later.")
                            LocalStore.saveUnresolved((LocalStore.unresolved().filterNot(same) + listOfNotNull(next.takeIf { !ok })).takeLast(50))
                        }
                    }
                }
            }
        }
    }

    /**
     * "No source had it" is often temporary (a new episode isn't up yet, a source is down): every 3 hours
     * those items quietly go back in line (at most 10 at a time).
     */
    private fun retryUnresolvedIfDue(ctx: Context, force: Boolean = false) {
        val prefs = ctx.getSharedPreferences("dl_auto", Context.MODE_PRIVATE)
        val last = prefs.getLong("unresolved_retry", 0)
        val list = LocalStore.unresolved()
        if (list.isEmpty() || (!force && System.currentTimeMillis() - last < 3 * 3600_000L)) return
        prefs.edit().putLong("unresolved_retry", System.currentTimeMillis()).apply()
        val again = list.take(10)
        Log.i(TAG, "retrying ${again.size} item(s) no source had earlier")
        LocalStore.saveUnresolved(list.drop(again.size))
        val pendingNow = LocalStore.pendingDownloads()
        LocalStore.savePendingDownloads(pendingNow + again.filter { a -> pendingNow.none { pk(it) == pk(a) } })
    }

    /** Retry everything that failed: failed downloads restart, "no source" items search again. */
    fun retryAllFailed(ctx: Context) {
        val app = ctx.applicationContext
        NovaDownloads.all(app).filter { it.state == Download.STATE_FAILED }.forEach { NovaDownloads.retry(app, it) }
        retryUnresolvedIfDue(app, force = true)
        resume(app)
    }

    private const val PARALLEL_RESOLVES = 3   // bulk: a season resolves three at a time (hidden pages stay staggered)
    /** Items being resolved right now (main thread only). */
    private val inFlight = mutableSetOf<String>()
    private fun pk(i: Item) = "${i.card.type}:${i.card.id}:${i.season}:${i.episode}"
    /** Whether this item is being looked up right now (vs waiting its turn). */
    fun resolving(i: Item) = pk(i) in inFlight

    /** Expired link while downloading: same id, fresh link; data already saved is reused (stable cache keys). */
    fun refresh(ctx: Context, d: Download, nextSource: Boolean) {
        val meta = DownloadMeta.of(d) ?: return
        // Same source and quality as before (so saved pieces are reused); another source only after repeated failures
        enqueue(ctx, listOf(Item(meta.card, meta.season, meta.episode, refreshOf = d.request.id,
            skipProvider = meta.provider.takeIf { nextSource },
            provider = meta.provider.takeIf { !nextSource },
            height = meta.quality)))
    }

    /** Hard caps so "Finding a source…" always ends: whole item, one source's DownloadHelper prepare. */
    private const val ITEM_CAP_MS = 90_000L
    private const val PREPARE_CAP_MS = 20_000L
    /** After the first usable answer, wait this long for a better one (MP4 / more dubs), then go. */
    private const val RACE_GRACE_MS = 8_000L
    private const val RACE_CAP_MS = 35_000L

    private suspend fun start(ctx: Context, item: Item, headless: Boolean): Boolean =
        withTimeoutOrNull(ITEM_CAP_MS) { startInner(ctx, item, headless) }
            ?: false.also { Log.w(TAG, "gave up on ${item.card.title} S${item.season}E${item.episode} after ${ITEM_CAP_MS / 1000}s") }

    private suspend fun startInner(ctx: Context, item: Item, headless: Boolean): Boolean {
        val id = DownloadMeta.id(item.card, item.season, item.episode)
        val target = StreamExtractor.resolveTarget(item.card, item.season, item.episode, repo) ?: return false
        val sources = StreamExtractor.ordered().filter { it.name != item.skipProvider }
        // Your pick from the sheet first (with a timeout); else every source races and the best answer wins
        // No pick in the sheet: the source (and dub) that served this show's last episode, so a season stays one source and one quality
        val showKey = "${item.card.type}:${item.card.id}"
        val saved = LocalStore.sourcePick(showKey)
        val (pickName, pickLang) = item.provider?.let { StreamExtractor.splitPick(it) } ?: lastGood[showKey]
            ?: saved?.first?.let { StreamExtractor.splitPick(it) } ?: (null to null)
        val picked = pickName?.let { n -> sources.firstOrNull { it.name == n } }
        val wantH = item.height ?: saved?.second?.takeIf { it > 0 && item.provider == null } ?: heightOf(item.quality ?: LocalStore.settings().dlQuality)
        val movie = item.card.type == com.anees.novamobile.data.model.MediaType.MOVIE || target.isMovie
        val expectedMin = if (movie) item.card.runtime else item.minutes
        val candidates = mutableListOf<Pair<StreamExtractor.Provider, WebStreamSniffer.Sniffed>>()
        // Your source gets a second try before anyone else is asked (one slow answer shouldn't switch the season's quality)
        if (picked != null) (ask(ctx, picked, target, headless) ?: if (item.provider != null) ask(ctx, picked, target, headless) else null)?.let { candidates += picked to it }
        if (candidates.isEmpty()) candidates += race(ctx, sources, target, headless, wantH, expectedMin, movie)
        var raced = picked == null
        val queue = ArrayDeque(candidates)
        while (true) {
            if (queue.isEmpty()) {
                // The source you picked didn't work out: let every other source race instead of giving up
                if (raced) break
                raced = true
                queue += race(ctx, sources.filter { it != picked }, target, headless, wantH, expectedMin, movie)
                continue
            }
            val (p, sniffed) = queue.removeFirst()
            // A language variant picked in the sheet ("CastleTV#hi"), else the stream itself
            // ...then the file at the quality you picked (MovieBox/NetMirror list one MP4 per quality)
            val chosen = pickOption((if (p == picked) StreamExtractor.variant(sniffed, pickLang) else null) ?: sniffed, wantH)
            rememberHeaders(chosen)
            // Every subtitle the source offers, cleaned of ads
            val saved = (withTimeoutOrNull(20_000) { withContext(Dispatchers.IO) {
                val dir = File(NovaDownloads.manager(ctx).let { NovaDownloads.dir }, "subs").apply { mkdirs() }
                // Language as the source states it, else guessed from the file name
                val stated = chosen.meta?.subLangs.orEmpty()
                chosen.subtitles.mapIndexed { i, u -> u to stated.getOrNull(i)?.takeIf { it.isNotEmpty() } }
                    .filterNot { SubtitleCleaner.isThumbnailTrack(it.first) }.take(10).mapIndexedNotNull { i, (url, lang) ->
                    val out = File(dir, "${id.hashCode()}_$i.vtt")
                    if (SubtitleCleaner.saveTo(ctx, url, RetrofitClient.base, chosen.headers, out)) out.absolutePath to (lang ?: SubtitleCleaner.langOf(url) ?: "") else null
                }
            } } ?: emptyList<Pair<String, String>>().also { Log.w(TAG, "subtitles took too long; downloading without the rest") })
            // Source brought no subtitles: OpenSubtitles (English + yours)
                .ifEmpty {
                    val dir = File(NovaDownloads.dir, "subs").apply { mkdirs() }
                    val langs = listOfNotNull(LocalStore.subtitleFor("${item.card.type}:${item.card.id}")?.takeIf { it != "off" }, "en").distinct()
                    runCatching { com.anees.novamobile.ui.player.OpenSubs.await(target, repo, langs, 8_000) }.getOrDefault(emptyList()).take(3).mapIndexedNotNull { i, sub ->
                        val out = File(dir, "${id.hashCode()}_os$i.vtt")
                        withContext(Dispatchers.IO) { SubtitleCleaner.saveTo(ctx, sub.url, RetrofitClient.base, emptyMap(), out) }
                            .takeIf { it }?.let { out.absolutePath to sub.lang }
                    }
                }
            val subs = saved.map { it.first }
            val meta = DownloadMeta(item.card, item.season, item.episode, p.name, subs.firstOrNull(), subtitles = subs,
                subtitleLangs = saved.map { it.second })
            // DownloadHelper.prepare can hang on a dead CDN: cap it and move to the next candidate
            val request = runCatching { withTimeout(PREPARE_CAP_MS) { buildRequest(ctx, id, chosen, meta, wantH) } }
                .onFailure { if (it is CancellationException && it !is TimeoutCancellationException) throw it; Log.w(TAG, "${p.name} prepare failed: $it", it) }
                .getOrNull() ?: continue
            // Cancelled while we were searching: don't bring it back
            if (LocalStore.pendingDownloads().none { pk(it) == pk(item) }) { Log.i(TAG, "$id cancelled during search"); return true }
            NovaDownloads.registerKey(request)
            DownloadService.sendAddDownload(ctx, NovaDownloadService::class.java, request, false)
            lastGood[showKey] = p.name to StreamExtractor.variantKey(chosen)
            Log.i(TAG, "queued $id via ${p.name} (${if (isProgressive(chosen.url)) "MP4" else "HLS"}, want ${wantH}p)")
            return true
        }
        return false
    }

    /** One source, with its timeout; null when it can't run now (WebView source with the app closed). */
    private suspend fun ask(ctx: Context, p: StreamExtractor.Provider, t: StreamExtractor.Target, headless: Boolean): WebStreamSniffer.Sniffed? {
        StreamExtractor.cached(p, t)?.let { return it }
        val host = Prefetcher.host
        if (p.resolver == null && (headless || host == null)) return null
        return withTimeoutOrNull(p.timeoutMs) {
            runCatching { p.resolver?.invoke(t) ?: StreamExtractor.sniffUrl(p, t)?.let { WebStreamSniffer(ctx, host!!).sniff(it, p.timeoutMs) } }
                .onFailure { if (it is CancellationException) throw it; Log.w(TAG, "${p.name} failed: ${it.message}") }.getOrNull()
        }?.also { StreamExtractor.remember(p, t, it) }   // whole answer (all dubs) cached before a variant is picked
    }

    private val FILE_HOSTS = listOf("r2.dev", "r2.cloudflarestorage.com", "video-downloads.googleusercontent.com", "googlevideo.com",
        "pixeldrain.dev/api/file", "pixeldrain.com/api/file", "hubcloud")

    /** Direct file (MP4/MKV/WebM) rather than an HLS playlist. */
    fun isProgressive(url: String): Boolean {
        val path = (StreamExtractor.unwrapProxy(Uri.parse(url)).path ?: url).lowercase()
        val u = url.lowercase()
        return !u.contains("m3u8") && (path.endsWith(".mp4") || path.endsWith(".mkv") || path.endsWith(".webm") || path.contains(".mp4/") ||
            // File hosts that serve whole files without an extension (Vegamovies V-Cloud / G-Drive)
            FILE_HOSTS.any { u.contains(it) })
    }

    /** The picked file's own release details, else the stream's. */
    private fun s0meta(v: WebStreamSniffer.Sniffed, chosen: WebStreamSniffer.Sniffed) =
        v.options?.firstOrNull { it.url == chosen.url }?.meta?.orElse(v.meta) ?: v.meta

    /** Show → (source, dub) that served its last episode: later episodes go there first. */
    private val lastGood = mutableMapOf<String, Pair<String, String?>>()

    /** Height this stream would download at for [wantH]: the MP4 option picked, else the tallest HLS variant within it (-1 unknown). */
    fun effectiveHeight(s: WebStreamSniffer.Sniffed, info: AudioProbe.Info?, wantH: Int): Int {
        if (!s.options.isNullOrEmpty()) return pickOption(s, wantH).height ?: -1
        val hs = (info?.heights.orEmpty().ifEmpty { listOfNotNull(info?.height, s.height) }).filter { it > 0 }
        if (hs.isEmpty()) return -1
        return hs.filter { it <= wantH }.maxOrNull() ?: hs.min()
    }

    /**
     * Higher is better. Your audio language first (can't watch without it), then the quality you picked
     * (exact beats lower beats unknown beats higher), then direct file over HLS, then more dubs.
     */
    private fun score(s: WebStreamSniffer.Sniffed, langs: List<String>, wanted: String?, h: Int, wantH: Int): Int {
        val fit = when { h <= 0 -> 1200; h <= wantH -> 2000 - (wantH - h); else -> 1000 - (h - wantH) / 2 }
        return fit + (if (isProgressive(s.url)) 300 else 0) +
            (if (wanted != null && (langs.contains(wanted) || s.audioLang == wanted)) 3000 else 0) + langs.size * 10
    }

    /**
     * Every source at once (plain-HTTP resolvers right away, hidden pages staggered, like [probe]).
     * Stops [RACE_GRACE_MS] after the first answer, at once on a direct file in the wanted language,
     * or at [RACE_CAP_MS]; returns answers best first so a dead link falls through to the next.
     */
    private suspend fun race(ctx: Context, sources: List<StreamExtractor.Provider>, t: StreamExtractor.Target, headless: Boolean, wantH: Int,
                             expectedMin: Int? = null, movie: Boolean = false):
            List<Pair<StreamExtractor.Provider, WebStreamSniffer.Sniffed>> = coroutineScope {
        val wanted = LangTags.norm(LocalStore.settings().audioLang)
        val results = kotlinx.coroutines.channels.Channel<Triple<StreamExtractor.Provider, WebStreamSniffer.Sniffed, Int>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        var pages = 0
        // Direct file, exactly your quality, in your language: nothing can beat it
        val perfect = kotlinx.coroutines.CompletableDeferred<Unit>()
        val jobs = sources.mapNotNull { p ->
            val cached = StreamExtractor.cached(p, t)
            if (cached == null && p.resolver == null && (headless || Prefetcher.host == null || pages >= MAX_PROBE_PAGES)) return@mapNotNull null
            val wait = if (cached != null || p.resolver != null) 0L else pages++ * PROBE_STAGGER_MS
            launch {
                delay(wait)
                val r = ask(ctx, p, t, headless) ?: return@launch
                // Per-dub streams (MovieBox / CastleTV "Hindi dub") compete one by one: the one in your language can win
                val best = r.withVariants().map { v ->
                    async {
                        val info = withTimeoutOrNull(6_000) { runCatching { AudioProbe.info(v) }.getOrNull() }
                        val h = effectiveHeight(v, info, wantH)
                        val chosen = pickOption(v, wantH)
                        // CAM, wrong cut for your setting, or so short it's probably another title: pushed down
                        val pen = com.anees.novamobile.ui.player.CutRules.penalty(info?.durationSec?.takeIf { it > 0 } ?: v.meta?.durationSec ?: -1,
                            (s0meta(v, chosen)), expectedMin, movie, t.title)
                        Triple(v, score(chosen, info?.langs.orEmpty(), wanted, h, wantH) + pen, h)
                    }
                }.awaitAll().maxByOrNull { it.second } ?: return@launch
                if (best.third == wantH && isProgressive(pickOption(best.first, wantH).url) && best.second >= 3000) perfect.complete(Unit)
                results.send(Triple(p, best.first, best.second))
            }
        }
        if (jobs.isEmpty()) return@coroutineScope emptyList()
        launch { jobs.joinAll(); results.close() }
        val got = mutableListOf<Triple<StreamExtractor.Provider, WebStreamSniffer.Sniffed, Int>>()
        val startAt = System.currentTimeMillis()
        var deadline = startAt + RACE_CAP_MS
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) break
            val r = withTimeoutOrNull(left) { results.receiveCatching() } ?: break
            val v = r.getOrNull() ?: break
            got += v
            if (got.size == 1) deadline = minOf(deadline, System.currentTimeMillis() + RACE_GRACE_MS)
            if (perfect.isCompleted) break
        }
        coroutineContext.cancelChildren()
        Log.i(TAG, "race: ${got.joinToString { "${it.first.name}=${it.third}" }} in ${System.currentTimeMillis() - startAt}ms")
        got.sortedByDescending { it.third }.map { it.first to it.second }
    }

    /** Audio language picked for this title in the player, else the Settings default. */
    fun audioFor(card: MediaCard): String? = LocalStore.audioFor("${card.type}:${card.id}")

    private fun rememberHeaders(s: WebStreamSniffer.Sniffed) {
        LocalStore.setDownloadUserAgent(s.userAgent)
        val uri = Uri.parse(s.url)
        uri.host?.let { LocalStore.setHostHeaders(it, s.headers) }
        uri.getQueryParameter("url")?.let { Uri.parse(it).host }?.let { LocalStore.setHostHeaders(it, s.headers) }
    }

    /** One video track at the chosen quality (no bitrate bloat); one audio track per language; all subtitles. */
    private suspend fun buildRequest(ctx: Context, id: String, s: WebStreamSniffer.Sniffed, meta0: DownloadMeta, h: Int): DownloadRequest {
        var meta = meta0
        // Direct file (MP4/MKV): nothing to pick inside it, and DownloadHelper can't select tracks on one
        // (it threw, so every MP4 used to fall back to HLS). Download the file as is.
        if (isProgressive(s.url)) {
            meta = meta.copy(quality = s.height?.takeIf { it > 0 }, audioLangs = listOfNotNull(s.audioLang).takeIf { it.isNotEmpty() })
            val u = Uri.parse(s.url)
            // Fresh link for a download already under way: same size = same file, so keep its cache key and every
            // saved piece, even when the new URL looks nothing like the old one (Google / HubCloud links change wholly)
            val old = NovaDownloads.get(ctx, id)
            val key = old?.request?.customCacheKey?.takeIf { old.bytesDownloaded > 0 && old.contentLength > 0 &&
                withContext(Dispatchers.IO) { NovaDownloads.totalLength(u) } == old.contentLength }
                ?: NovaDownloads.fileKey(u)
            if (old != null && key == old.request.customCacheKey) Log.i(TAG, "$id: fresh link, same file — ${old.bytesDownloaded shr 20} MB kept")
            return DownloadRequest.Builder(id, u).setData(meta.toBytes()).setCustomCacheKey(key).build()
        }
        val settings = LocalStore.settings()
        val http = OkHttpDataSource.Factory(RetrofitClient.base).setUserAgent(s.userAgent).setDefaultRequestProperties(s.headers)
        val factory: DataSource.Factory = ResolvingDataSource.Factory(
            DataSource.Factory { PngStripDataSource(DefaultDataSource.Factory(ctx, http).createDataSource()) }
        ) { spec -> spec.withUri(StreamExtractor.unwrapProxy(spec.uri)) }
        val item = MediaItem.Builder().setUri(s.url).setCustomCacheKey(null)
            .apply { if (s.url.lowercase().contains("m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8) }.build()
        val params = DownloadHelper.getDefaultTrackSelectorParameters(ctx).buildUpon().setMaxVideoSize(Int.MAX_VALUE, h).build()
        val helper = DownloadHelper.forMediaItem(ctx, item, DefaultRenderersFactory(ctx), factory)
        try {
            withContext(Dispatchers.Main) { prepare(helper) }
            fun lang(f: androidx.media3.common.Format) = LangTags.norm(f.language) ?: LangTags.code(f.label)
            fun rate(f: androidx.media3.common.Format) = f.bitrate.takeIf { it > 0 } ?: f.peakBitrate.takeIf { it > 0 } ?: Int.MAX_VALUE
            var height = 0
            val audioKept = mutableSetOf<String>()
            for (period in 0 until helper.periodCount) {
                helper.clearTrackSelections(period)
                val info = helper.getMappedTrackInfo(period)
                var videoPicked = false
                for (r in 0 until info.rendererCount) {
                    val type = info.getRendererType(r)
                    val groups = info.getTrackGroups(r)
                    val all = mutableListOf<Pair<DefaultTrackSelector.SelectionOverride, androidx.media3.common.Format>>()
                    for (g in 0 until groups.length) for (t in 0 until groups[g].length) all += DefaultTrackSelector.SelectionOverride(g, t) to groups[g].getFormat(t)
                    when (type) {
                        // Video: ONE track, tallest within the chosen quality, smallest bitrate at that height
                        androidx.media3.common.C.TRACK_TYPE_VIDEO -> {
                            // Nothing at or under your pick (source has only 1080p): its smallest, never silently the biggest
                            val fit = all.filter { it.second.height in 1..h }.ifEmpty { all.filter { it.second.height > 0 }.let { l -> l.filter { it.second.height == l.minOfOrNull { x -> x.second.height } } } }
                            val top = fit.maxOfOrNull { it.second.height } ?: continue
                            val pick = fit.filter { it.second.height == top }.minBy { rate(it.second) }
                            height = maxOf(height, top)
                            helper.addTrackSelectionForSingleRenderer(period, r, params, listOf(pick.first))
                            Log.i(TAG, "video: ${pick.second.height}p ${rate(pick.second)} of ${all.size} tracks")
                            videoPicked = true
                        }
                        // Audio: every language, but only ONE track per language (AAC, smallest sane bitrate)
                        androidx.media3.common.C.TRACK_TYPE_AUDIO -> {
                            val keep = all.groupBy { lang(it.second) ?: "#${it.first.groupIndex}" }.values.map { same ->
                                same.minWith(compareBy<Pair<DefaultTrackSelector.SelectionOverride, androidx.media3.common.Format>>(
                                    { it.second.sampleMimeType != MimeTypes.AUDIO_AAC },
                                    { rate(it.second) < 96_000 },
                                    { rate(it.second) }))
                            }
                            keep.mapNotNullTo(audioKept) { lang(it.second) }
                            Log.i(TAG, "audio: ${keep.joinToString { "${lang(it.second)} ${it.second.sampleMimeType} ${rate(it.second)}" }} of ${all.size} tracks")
                            if (keep.isNotEmpty()) helper.addTrackSelectionForSingleRenderer(period, r, params, keep.map { it.first })
                        }
                        // Subtitles: all (tiny)
                        androidx.media3.common.C.TRACK_TYPE_TEXT ->
                            if (all.isNotEmpty()) helper.addTrackSelectionForSingleRenderer(period, r, params, all.map { it.first })
                    }
                }
                // No height info: let the selector pick one fixed video track
                if (!videoPicked) helper.addTrackSelection(period, params.buildUpon().setForceHighestSupportedBitrate(true).build())
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
