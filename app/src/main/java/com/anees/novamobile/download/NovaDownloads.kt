package com.anees.novamobile.download

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.scheduler.Requirements
import com.anees.novamobile.R
import com.anees.novamobile.data.api.RetrofitClient
import com.anees.novamobile.data.local.LocalStore
import com.anees.novamobile.data.model.MediaCard
import com.anees.novamobile.ui.player.PngStripDataSource
import com.anees.novamobile.ui.player.StreamExtractor
import com.google.gson.Gson
import java.io.File
import okio.buffer
import okhttp3.ResponseBody.Companion.asResponseBody
import java.io.IOException
import java.util.concurrent.Executors

/** What we store with each download (DownloadRequest.data). */
data class DownloadMeta(
    val card: MediaCard,
    val season: Int,
    val episode: Int,
    val provider: String,
    val subtitle: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val subtitles: List<String>? = null,    // every subtitle file saved with the download
    val subtitleLangs: List<String>? = null, // language code per file in [subtitles] ("" = unknown)
    val quality: Int? = null,               // video height actually downloaded
    val audioLangs: List<String>? = null    // audio languages kept

) {
    fun toBytes(): ByteArray = Gson().toJson(this).toByteArray()
    companion object {
        private val GSON = Gson()
        /** Parsed metas by id + data hash: download lists re-read every item each second. */
        private val parsed = android.util.LruCache<String, DownloadMeta>(256)
        fun of(d: Download): DownloadMeta? {
            val k = d.request.id + "#" + d.request.data.contentHashCode()
            parsed.get(k)?.let { return it }
            return runCatching { GSON.fromJson(String(d.request.data), DownloadMeta::class.java) }.getOrNull()?.also { parsed.put(k, it) }
        }
        fun id(card: MediaCard, season: Int, episode: Int) = "${card.type}:${card.id}:$season:$episode"
    }
}

/**
 * Offline downloads on Media3's DownloadManager: HLS, resume after interruption, parallel
 * downloads, network/Wi-Fi requirements. On top of it, Nova's own rules (adapted from Vega):
 *  - network trouble: retry forever, waiting longer each time (30s → 1m → 5m → 15m)
 *  - expired link (403/404/410): find a fresh link, then continue with the data already saved
 *  - broken / unsupported stream: give up after 3 tries ("Failed — tap to retry")
 *  - storage full: pause everything
 */
@UnstableApi
object NovaDownloads {
    private const val TAG = "NovaDL"
    const val CHANNEL = "nova_downloads"
    private const val DONE_CHANNEL = "nova_downloads_done"

    private var manager: DownloadManager? = null
    lateinit var cache: SimpleCache; private set
    lateinit var dir: File; private set
    private lateinit var upstream: DataSource.Factory
    private lateinit var client: okhttp3.OkHttpClient

    /** Full size of a direct file via a 1-byte Range request (with the headers downloads send); null if unknown. */
    fun totalLength(uri: Uri): Long? = runCatching {
        val spec = resolve(DataSpec(StreamExtractor.unwrapProxy(uri)))
        val req = okhttp3.Request.Builder().url(spec.uri.toString()).header("Range", "bytes=0-0")
            .header("User-Agent", LocalStore.downloadUserAgent())
            .apply { spec.httpRequestHeaders.forEach { (k, v) -> header(k, v) } }.build()
        client.newCall(req).execute().use { r ->
            r.header("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull()
                ?: r.header("Content-Length")?.toLongOrNull()?.takeIf { r.code == 200 && it > 1 }
        }
    }.onFailure { Log.w(TAG, "length probe: ${it.message}") }.getOrNull()
    /**
     * HLS/DASH segments downloaded at once, shared by all running downloads. Stream CDNs answer
     * each small segment slowly (~0.5s to first byte), so throughput grows with parallel requests:
     * Nov 2026 test on VidLux, 4 → 0.67 MB/s, 12 → 1.4 MB/s, 24 → 1.8 MB/s.
     */
    private const val SEGMENT_THREADS = 24
    private val main = Handler(Looper.getMainLooper())
    @Volatile var storageFull = false; private set

    /** Tokens that change on every fresh link; dropping them from cache keys lets a refreshed link reuse saved data. */
    private val VOLATILE = setOf("token", "exp", "expires", "expiry", "e", "st", "sig", "signature", "hash", "_t", "t", "ts", "verify", "auth", "md5")
    /** Wider set for direct files' explicit keys (existing downloads keep the old keys above, so nothing saved is orphaned). */
    private val VOLATILE_FILE = VOLATILE + setOf("expire", "sign", "key-pair-id", "policy", "hdnts", "hmac", "acl", "validfrom", "validto", "time", "timestamp", "ip")

    /** Cache key for a direct file: same file behind a fresh signed link (MovieBox sign=, CloudFront, S3, GCS) → same key, saved data reused. */
    fun fileKey(uri: Uri): String {
        uri.getQueryParameter("url")?.let { inner -> return "proxied:" + fileKey(Uri.parse(inner)) }
        val b = uri.buildUpon().clearQuery()
        uri.queryParameterNames.filter { k -> k.lowercase().let { it !in VOLATILE_FILE && !it.startsWith("x-amz-") && !it.startsWith("x-goog-") } }.sorted()
            .forEach { k -> b.appendQueryParameter(k, uri.getQueryParameter(k)) }
        return "file:" + b.build().toString()
    }

    /** url → explicit key of a direct-file download, so plain reads of that url (export, transfer) find its data. */
    private val fileKeys = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun registerKey(r: androidx.media3.exoplayer.offline.DownloadRequest) { r.customCacheKey?.let { fileKeys[r.uri.toString()] = it } }

    val KEYS = CacheKeyFactory { spec -> spec.key ?: fileKeys[spec.uri.toString()] ?: stableKey(spec.uri) }

    fun stableKey(uri: Uri): String {
        uri.getQueryParameter("url")?.let { inner -> return "proxied:" + stableKey(Uri.parse(inner)) }
        val b = uri.buildUpon().clearQuery()
        uri.queryParameterNames.filter { it.lowercase() !in VOLATILE }.sorted()
            .forEach { k -> b.appendQueryParameter(k, uri.getQueryParameter(k)) }
        return b.build().toString()
    }

    @Synchronized
    fun manager(ctx: Context): DownloadManager {
        manager?.let { return it }
        val app = ctx.applicationContext
        LocalStore.init(app)
        dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "downloads").apply { mkdirs() }
        val db = StandaloneDatabaseProvider(app)
        cache = SimpleCache(File(dir, "media"), NoOpCacheEvictor(), db)

        // Own pool: segments of every running download are fetched side by side (default keeps only 5 warm sockets)
        client = RetrofitClient.base.newBuilder()
            .connectionPool(okhttp3.ConnectionPool(SEGMENT_THREADS, 5, java.util.concurrent.TimeUnit.MINUTES))
            // HTTP/1.1 on purpose: OkHttp folds every HTTP/2 request to one host into ONE TCP connection, so
            // "6 connections" were really 6 streams sharing one TCP window. Download managers (aria2, ADM, IDM)
            // open separate TCP connections; that's what makes parallel pieces faster on mobile/lossy links.
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 32 })
            // Slow links (~100 KB/s): a segment may take a while to finish, but a dead socket must not hang forever
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addNetworkInterceptor(DebugThrottle)   // Settings speed limit (and debug.nova.throttle in debug builds)
            .addNetworkInterceptor(NetMeter)
            .build()
        val http = OkHttpDataSource.Factory(client).setUserAgent(LocalStore.downloadUserAgent())
        // Phone-to-phone transfer: while one runs, pieces come from the other phone first
        upstream = com.anees.novamobile.transfer.BeamLink.route(ResolvingDataSource.Factory(
            DataSource.Factory { PngStripDataSource(DefaultDataSource.Factory(app, http).createDataSource()) }
        ) { spec -> resolve(spec) }, KEYS)
        val cacheFactory = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream).setCacheKeyFactory(KEYS)

        val s = LocalStore.settings()
        val dm = DownloadManager(app, DefaultDownloadIndex(db), NovaDownloaderFactory(cacheFactory, segmentExecutor))
        dm.maxParallelDownloads = s.dlParallel.coerceIn(1, 3)
        dm.minRetryCount = 10   // per segment/chunk retries before Media3 gives up (slow, flaky links)
        dm.requirements = requirements(s)
        dm.addListener(Policy(app))
        manager = dm
        // Failed by network trouble while the app was closed: try again now
        main.postDelayed({ all(app).filter { it.state == Download.STATE_FAILED && LocalStore.dlAttempts(it.request.id) < 3 }.forEach { dm.addDownload(it.request) } }, 3000)
        // Subtitle files of downloads that no longer exist
        main.postDelayed({ cleanOrphans(app) }, 10_000)
        // Finished downloads not yet copied to the phone's Movies/Nova folder
        main.postDelayed({ PublicExport.catchUp(app) }, 15_000)
        // Media3's manager starts paused until its service runs: after an app restart queued downloads sat
        // at "Waiting…" forever. Wake the service when there's unfinished work; at least resume in-process.
        main.postDelayed({
            val pending = all(app).any { it.state == Download.STATE_QUEUED || it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_RESTARTING }
            if (pending && !storageFull) {
                runCatching { androidx.media3.exoplayer.offline.DownloadService.start(app, NovaDownloadService::class.java) }
                    .onFailure { Log.w(TAG, "service start: ${it.message}") }
                dm.resumeDownloads()
            }
        }, 1_500)
        runCatching { dm.downloadIndex.getDownloads().use { c -> while (c.moveToNext()) c.download.let { d ->
            registerKey(d.request)
            // Older parallel MP4 downloads never stored their total length (see ParallelProgressiveDownloader): add it
            if (d.state == Download.STATE_COMPLETED && d.contentLength > 0 && d.request.mimeType.isNullOrEmpty() && d.request.streamKeys.isEmpty()) runCatching {
                val key = d.request.customCacheKey ?: stableKey(d.request.uri)
                if (androidx.media3.datasource.cache.ContentMetadata.getContentLength(cache.getContentMetadata(key)) <= 0 &&
                    cache.getCachedBytes(key, 0, d.contentLength) >= d.contentLength)
                    cache.applyContentMetadataMutations(key, androidx.media3.datasource.cache.ContentMetadataMutations().also {
                        androidx.media3.datasource.cache.ContentMetadataMutations.setContentLength(it, d.contentLength) })
            }
        } } }
        watchStalls(dm)
        watchNetwork(app, dm)
        main.postDelayed(object : Runnable { override fun run() { Gate.tune(); main.postDelayed(this, 5_000) } }, 5_000)
        return dm
    }

    /** Network (or Wi-Fi only), plus: phone storage not low, and plugged in if "Only While Charging" is on. */
    private fun requirements(s: com.anees.novamobile.data.local.Settings) = Requirements(
        (if (s.dlWifiOnly) Requirements.NETWORK_UNMETERED else Requirements.NETWORK) or
            Requirements.DEVICE_STORAGE_NOT_LOW or (if (s.dlOnlyCharging) Requirements.DEVICE_CHARGING else 0))

    fun applySettings(ctx: Context) {
        val s = LocalStore.settings()
        manager(ctx).apply {
            maxParallelDownloads = s.dlParallel.coerceIn(1, 3)
            requirements = requirements(s)
        }
    }

    /** Headers the source needs (per server), and skip slow proxies where the origin allows it. */
    private fun resolve(spec: DataSpec): DataSpec {
        var s = spec
        val inner = spec.uri.getQueryParameter("url")?.let { Uri.parse(it) }
        if (spec.uri.path?.contains("robot-proxy") == true && inner?.host != null && !LocalStore.directBad(inner.host!!)) s = s.withUri(inner)
        val headers = LocalStore.hostHeaders(s.uri.host ?: "") ?: LocalStore.hostHeaders(spec.uri.host ?: "")
        return if (headers != null) s.withAdditionalHeaders(headers) else s
    }

    /** Read-only access for offline playback (downloads pieces only if something is missing). */
    fun playbackFactory(ctx: Context): DataSource.Factory {
        manager(ctx)
        return CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream).setCacheKeyFactory(KEYS)
            .setCacheWriteDataSinkFactory(null)
    }

    fun get(ctx: Context, id: String): Download? = runCatching {
        val dm = manager(ctx)
        (if (Looper.myLooper() == Looper.getMainLooper()) dm.currentDownloads.firstOrNull { it.request.id == id } else null) ?: dm.downloadIndex.getDownload(id)
    }.getOrNull()
    fun completed(ctx: Context, id: String) = get(ctx, id)?.state == Download.STATE_COMPLETED

    fun all(ctx: Context): List<Download> = runCatching {
        val dm = manager(ctx)
        // The index on disk is written every few seconds; running downloads' live progress comes from the manager
        val live = if (Looper.myLooper() == Looper.getMainLooper()) dm.currentDownloads.associateBy { it.request.id } else emptyMap()
        dm.downloadIndex.getDownloads().use { c -> buildList { while (c.moveToNext()) add(c.download.let { d -> live[d.request.id] ?: d }) } }
    }.getOrDefault(emptyList())

    fun usedBytes(ctx: Context): Long = all(ctx).sumOf { it.bytesDownloaded }

    // ── Retry policy ──────────────────────────────────────────────────────────

    private class Policy(private val ctx: Context) : DownloadManager.Listener {
        override fun onIdle(dm: DownloadManager) { Locks.update(ctx, false) }
        override fun onDownloadChanged(dm: DownloadManager, d: Download, finalException: Exception?) {
            val id = d.request.id
            registerKey(d.request)
            Locks.update(ctx, dm.currentDownloads.any { it.state == Download.STATE_DOWNLOADING })
            when (d.state) {
                Download.STATE_COMPLETED -> {
                    LocalStore.setDlAttempts(id, 0)
                    // A finished file that's clearly too small is broken, not a download
                    if (d.bytesDownloaded < 2L * 1024 * 1024) { Log.w(TAG, "$id too small, removed"); dm.removeDownload(id); return }
                    DownloadNotifier.done(ctx, d)
                    PublicExport.enqueue(ctx, id)
                }
                Download.STATE_FAILED -> handleFailure(dm, d, finalException)
                else -> {}
            }
        }

        private fun handleFailure(dm: DownloadManager, d: Download, e: Exception?) {
            val id = d.request.id
            val n = LocalStore.dlAttempts(id) + 1
            LocalStore.setDlAttempts(id, n)
            val code = generateSequence(e as Throwable?) { it.cause }.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
            val msg = generateSequence(e as Throwable?) { it.cause }.mapNotNull { it.message }.joinToString(" ")
            Log.w(TAG, "$id failed (#$n): code=$code $msg")
            when {
                msg.contains("ENOSPC") || msg.contains("No space", true) -> {
                    storageFull = true
                    dm.pauseDownloads()
                    DownloadNotifier.storageFull(ctx)
                }
                // Fake success (an HTML error page instead of the video): a fresh link, from another source after 2 tries
                msg.contains(NOT_VIDEO) -> DownloadStarter.refresh(ctx, d, nextSource = n >= 2)
                // Busy server (rate limit / overload): wait like network trouble, then a fresh link if it keeps up
                code == 429 || (code != null && code in 500..599) -> {
                    if (n >= 6) DownloadStarter.refresh(ctx, d, nextSource = true)
                    else main.postDelayed({ retryIfStillFailed(dm, id) }, (5_000L shl (n - 1).coerceAtMost(4)).coerceAtMost(60_000L))
                }
                code in setOf(403, 404, 410) -> {
                    // The link expired: get a fresh one (next source after 2 failures); saved data is kept
                    DownloadStarter.refresh(ctx, d, nextSource = n > 2)
                }
                isNetwork(e) -> {
                    // Never give up on network trouble: short waits, and at once when the network comes back
                    waitingForNetwork += id
                    val wait = when (n) { 1 -> 5_000L; 2 -> 15_000L; 3 -> 30_000L; else -> 60_000L }
                    main.postDelayed({ retryIfStillFailed(dm, id) }, wait)
                }
                n < 3 -> main.postDelayed({ retryIfStillFailed(dm, id) }, 10_000L)
                // Still broken after 3 tries (bad segment, parser error): a fresh link from another source before giving up
                n < 5 -> DownloadStarter.refresh(ctx, d, nextSource = true)
                else -> DownloadMeta.of(d)?.let { DownloadNotifier.failed(ctx, id, DownloadNotifier.name(it), "The stream stopped working. Retry finds a fresh link.") }
            }
        }

        /** Deleted (or already retried) while waiting: leave it alone. */
        private fun retryIfStillFailed(dm: DownloadManager, id: String) {
            val now = runCatching { dm.downloadIndex.getDownload(id) }.getOrNull() ?: return
            if (now.state == Download.STATE_FAILED) dm.addDownload(now.request)
        }

        private fun isNetwork(e: Throwable?): Boolean = generateSequence(e) { it.cause }.any {
            it is java.net.UnknownHostException || it is java.net.SocketTimeoutException || it is java.net.ConnectException ||
                it is java.io.EOFException || it is java.net.ProtocolException || it is java.net.SocketException ||
                it is javax.net.ssl.SSLException || it is java.io.InterruptedIOException ||
                it.message?.contains("unexpected end", true) == true ||
                (it is HttpDataSource.HttpDataSourceException && it !is HttpDataSource.InvalidResponseCodeException) ||
                (it is IOException && (it.message?.contains("reset", true) == true || it.message?.contains("timeout", true) == true))
        }
    }

    // ── Slow / flaky networks ─────────────────────────────────────────────────

    const val NOT_VIDEO = "NOVA_NOT_VIDEO"

    /**
     * While anything downloads: a high-performance Wi-Fi lock (with the screen off Wi-Fi drops into power save
     * and crawls) and a partial wake lock — what download managers like ADM hold. Released when idle.
     */
    private object Locks {
        private var wifi: android.net.wifi.WifiManager.WifiLock? = null
        private var cpu: android.os.PowerManager.WakeLock? = null
        @Synchronized fun update(ctx: Context, on: Boolean) = runCatching {
            if (on) {
                if (wifi == null) wifi = ctx.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
                    ?.createWifiLock(if (android.os.Build.VERSION.SDK_INT >= 29) android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY else @Suppress("DEPRECATION") android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "nova:dl")
                        ?.apply { setReferenceCounted(false) }
                if (cpu == null) cpu = ctx.getSystemService(android.os.PowerManager::class.java)
                    ?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "nova:dl")?.apply { setReferenceCounted(false) }
                if (wifi?.isHeld == false) wifi?.acquire()
                if (cpu?.isHeld == false) cpu?.acquire(6 * 3600_000L)   // safety cap
            } else {
                if (wifi?.isHeld == true) wifi?.release()
                if (cpu?.isHeld == true) cpu?.release()
            }
        }
    }

    /** Bytes received by downloads, for [Gate]. */
    private object NetMeter : okhttp3.Interceptor {
        val bytes = java.util.concurrent.atomic.AtomicLong()
        override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
            val r = chain.proceed(chain.request())
            val body = r.body ?: return r
            val counted = object : okio.ForwardingSource(body.source()) {
                override fun read(sink: okio.Buffer, byteCount: Long): Long = super.read(sink, byteCount).also { if (it > 0) bytes.addAndGet(it) }
            }.buffer()
            return r.newBuilder().body(counted.asResponseBody(body.contentType(), body.contentLength())).build()
        }
    }

    /**
     * How many HLS segments are fetched at once, from the measured speed: many small requests help on
     * fast links (CDNs answer each slowly), but on a ~100 KB/s link 24 requests each crawl and time out.
     */
    private object Gate {
        private val lock = Object()
        @Volatile var limit = 8; private set
        private var active = 0
        private var lastBytes = 0L
        fun acquire() = synchronized(lock) { while (active >= limit) lock.wait(1_000); active++ }
        fun release() = synchronized(lock) { active--; lock.notifyAll() }
        /** Every 5s: speed → limit. */
        fun tune() {
            val b = NetMeter.bytes.get(); val bps = (b - lastBytes) / 5; lastBytes = b
            val want = when { bps < 150 * 1024 -> 3; bps < 400 * 1024 -> 6; bps < 1_200 * 1024 -> 12; else -> SEGMENT_THREADS }
            // Only shrink when traffic is flowing (an idle second means nothing)
            if (active == 0 && want < limit) return
            if (want != limit) { synchronized(lock) { limit = want; lock.notifyAll() }; Log.i(TAG, "segment gate: ${bps / 1024} KB/s → $want at once") }
        }
    }

    private val segmentExecutor = java.util.concurrent.Executor { task ->
        segmentPool.execute { Gate.acquire(); try { task.run() } finally { Gate.release() } }
    }
    private val segmentPool = Executors.newFixedThreadPool(SEGMENT_THREADS)

    /**
     * Debug builds only: `adb shell setprop debug.nova.throttle 100` caps all download traffic to
     * 100 KB/s (shared by every connection), to test slow networks; `setprop debug.nova.throttle 0` = off.
     */
    private object DebugThrottle : okhttp3.Interceptor {
        private val throttler = okio.Throttler()
        private var kbps = -1L
        private var checked = 0L
        private fun limit(): Long {
            val now = System.currentTimeMillis()
            if (now - checked > 3_000) {
                checked = now
                val debug = if (!com.anees.novamobile.BuildConfig.DEBUG) 0L else runCatching {
                    Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, "debug.nova.throttle") as String
                }.getOrNull()?.toLongOrNull() ?: 0
                val v = debug.takeIf { it > 0 } ?: LocalStore.settings().dlSpeedLimitKb.toLong()
                if (v != kbps) { kbps = v; throttler.bytesPerSecond(v * 1024) }
            }
            return kbps
        }
        override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
            val r = chain.proceed(chain.request())
            if (limit() <= 0) return r
            val body = r.body ?: return r
            val src = throttler.source(body.source()).buffer()
            return r.newBuilder().body(src.asResponseBody(body.contentType(), body.contentLength())).build()
        }
    }

    /** Ids that failed on network trouble; retried the moment a network is back. */
    private val waitingForNetwork = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private const val STALL_MS = 90_000L
    private val lastBytes = mutableMapOf<String, Pair<Long, Long>>()   // id → (bytes, since)

    /** A download showing "downloading" with no new bytes for [STALL_MS] is restarted (saved data is reused). */
    private fun watchStalls(dm: DownloadManager) {
        main.postDelayed(object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                val live = dm.currentDownloads.filter { it.state == Download.STATE_DOWNLOADING }
                lastBytes.keys.retainAll(live.map { it.request.id }.toSet())
                for (d in live) {
                    val id = d.request.id
                    val prev = lastBytes[id]
                    if (prev == null || d.bytesDownloaded > prev.first) lastBytes[id] = d.bytesDownloaded to now
                    else if (now - prev.second > STALL_MS) {
                        Log.w(TAG, "$id stalled at ${d.bytesDownloaded} bytes, reconnecting")
                        lastBytes[id] = d.bytesDownloaded to now
                        dm.setStopReason(id, STALL_STOP)
                        main.postDelayed({ dm.setStopReason(id, Download.STOP_REASON_NONE) }, 1_000)
                    }
                }
                main.postDelayed(this, 10_000)
            }
        }, 10_000)
    }
    private const val STALL_STOP = 7301

    private fun watchNetwork(ctx: Context, dm: DownloadManager) {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java) ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    main.postDelayed({
                        val ids = synchronized(waitingForNetwork) { waitingForNetwork.toList().also { waitingForNetwork.clear() } }
                        ids.forEach { id ->
                            val d = runCatching { dm.downloadIndex.getDownload(id) }.getOrNull() ?: return@forEach
                            if (d.state == Download.STATE_FAILED) { Log.i(TAG, "$id: network back, resuming"); dm.addDownload(d.request) }
                        }
                    }, 2_000)
                }
            })
        }
    }

    /** Every subtitle file saved with a download (they live outside the media cache). */
    fun deleteSubtitles(d: Download) {
        val m = DownloadMeta.of(d) ?: return
        (m.subtitles.orEmpty() + listOfNotNull(m.subtitle)).distinct().forEach { runCatching { File(it).delete() } }
    }

    fun clearStorageFull(ctx: Context) { storageFull = false; manager(ctx).resumeDownloads() }

    fun retry(ctx: Context, d: Download) { LocalStore.setDlAttempts(d.request.id, 0); manager(ctx).addDownload(d.request) }

    private fun cleanOrphans(ctx: Context) {
        val keep = all(ctx).flatMap { d -> DownloadMeta.of(d)?.let { it.subtitles.orEmpty() + listOfNotNull(it.subtitle) }.orEmpty() }.toSet()
        val old = System.currentTimeMillis() - 3_600_000   // files from a download being set up right now are kept
        File(dir, "subs").listFiles()?.filter { it.absolutePath !in keep && it.lastModified() < old }?.forEach { it.delete() }
    }

}
