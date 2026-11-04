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
        fun of(d: Download): DownloadMeta? = runCatching { Gson().fromJson(String(d.request.data), DownloadMeta::class.java) }.getOrNull()
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

    val KEYS = CacheKeyFactory { spec -> spec.key ?: stableKey(spec.uri) }

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
        val client = RetrofitClient.base.newBuilder()
            .connectionPool(okhttp3.ConnectionPool(SEGMENT_THREADS, 5, java.util.concurrent.TimeUnit.MINUTES))
            .build()
        val http = OkHttpDataSource.Factory(client).setUserAgent(LocalStore.downloadUserAgent())
        upstream = ResolvingDataSource.Factory(
            DataSource.Factory { PngStripDataSource(DefaultDataSource.Factory(app, http).createDataSource()) }
        ) { spec -> resolve(spec) }
        val cacheFactory = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream).setCacheKeyFactory(KEYS)

        val s = LocalStore.settings()
        val dm = DownloadManager(app, DefaultDownloadIndex(db), DefaultDownloaderFactory(cacheFactory, Executors.newFixedThreadPool(SEGMENT_THREADS)))
        dm.maxParallelDownloads = s.dlParallel.coerceIn(1, 3)
        dm.minRetryCount = 3
        dm.requirements = Requirements(if (s.dlWifiOnly) Requirements.NETWORK_UNMETERED else Requirements.NETWORK)
        dm.addListener(Policy(app))
        manager = dm
        // Failed by network trouble while the app was closed: try again now
        main.postDelayed({ all(app).filter { it.state == Download.STATE_FAILED && LocalStore.dlAttempts(it.request.id) < 3 }.forEach { dm.addDownload(it.request) } }, 3000)
        // Subtitle files of downloads that no longer exist
        main.postDelayed({ cleanOrphans(app) }, 10_000)
        // Finished downloads not yet copied to the phone's Movies/Nova folder
        main.postDelayed({ PublicExport.catchUp(app) }, 15_000)
        return dm
    }

    fun applySettings(ctx: Context) {
        val s = LocalStore.settings()
        manager(ctx).apply {
            maxParallelDownloads = s.dlParallel.coerceIn(1, 3)
            requirements = Requirements(if (s.dlWifiOnly) Requirements.NETWORK_UNMETERED else Requirements.NETWORK)
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

    fun get(ctx: Context, id: String): Download? = runCatching { manager(ctx).downloadIndex.getDownload(id) }.getOrNull()
    fun completed(ctx: Context, id: String) = get(ctx, id)?.state == Download.STATE_COMPLETED

    fun all(ctx: Context): List<Download> = runCatching {
        manager(ctx).downloadIndex.getDownloads().use { c -> buildList { while (c.moveToNext()) add(c.download) } }
    }.getOrDefault(emptyList())

    fun usedBytes(ctx: Context): Long = all(ctx).sumOf { it.bytesDownloaded }

    // ── Retry policy ──────────────────────────────────────────────────────────

    private class Policy(private val ctx: Context) : DownloadManager.Listener {
        override fun onDownloadChanged(dm: DownloadManager, d: Download, finalException: Exception?) {
            val id = d.request.id
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
                code in setOf(403, 404, 410) -> {
                    // The link expired: get a fresh one (next source after 2 failures); saved data is kept
                    DownloadStarter.refresh(ctx, d, nextSource = n > 2)
                }
                isNetwork(e) -> {
                    val wait = when (n) { 1 -> 30_000L; 2 -> 60_000L; 3 -> 300_000L; else -> 900_000L }
                    main.postDelayed({ dm.addDownload(d.request) }, wait)
                }
                n < 3 -> main.postDelayed({ dm.addDownload(d.request) }, 10_000L)
                else -> DownloadMeta.of(d)?.let { DownloadNotifier.failed(ctx, id, DownloadNotifier.name(it), "The stream stopped working. Retry finds a fresh link.") }
            }
        }

        private fun isNetwork(e: Throwable?): Boolean = generateSequence(e) { it.cause }.any {
            it is java.net.UnknownHostException || it is java.net.SocketTimeoutException || it is java.net.ConnectException ||
                (it is HttpDataSource.HttpDataSourceException && it !is HttpDataSource.InvalidResponseCodeException) ||
                (it is IOException && (it.message?.contains("reset", true) == true || it.message?.contains("timeout", true) == true))
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
