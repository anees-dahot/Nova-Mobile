package com.anees.novamobile.transfer

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.StreamKey
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import com.anees.novamobile.download.DownloadMeta
import com.anees.novamobile.download.NovaDownloadService
import com.anees.novamobile.download.NovaDownloads
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Receiver side. While a transfer runs, every piece the download manager asks for is first requested
 * from the sending phone (same cache key), and only fetched from the internet when the sender
 * doesn't have it. The received downloads are ordinary downloads afterwards.
 */
@UnstableApi
object BeamLink {
    private const val TAG = "Beam"

    class Peer(val host: String, val port: Int, val token: String, val direct: Boolean, val network: android.net.Network? = null) {
        val base = "http://$host:$port"
        val bytes = AtomicLong()
        val ids = mutableSetOf<String>()
        val startedAt = System.currentTimeMillis()
        @Volatile var allAdded = false
        /** HTTP bound to the link's network (needed for the hotspot, harmless otherwise). */
        val http: OkHttpClient = BeamLink.http.newBuilder().apply { network?.let { socketFactory(it.socketFactory) } }.build()
        val how get() = when { direct -> "direct link"; network != null -> "hotspot"; else -> "same Wi-Fi" }
    }

    /** What the receive screen shows. */
    data class Progress(val title: String = "", val index: Int = 0, val count: Int = 0, val done: Long = 0, val total: Long = 0,
                        val finished: Boolean = false, val ok: Int = 0, val failed: Int = 0, val how: String = "")

    @Volatile var peer: Peer? = null; private set
    @Volatile var progress = Progress(); private set
    /** UI hook: called on the main thread when the transfer finishes (done count, failed count). */
    var onFinished: ((Int, Int) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val gson = Gson()

    internal val http: OkHttpClient = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectionPool(ConnectionPool(48, 2, TimeUnit.MINUTES))
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .proxy(java.net.Proxy.NO_PROXY)
        .build()

    // ── Connecting ───────────────────────────────────────────────────────────

    /**
     * Finds the fastest way to the sender: its Wi-Fi Direct group (phone to phone, no router in
     * between), then a network both phones are on (same Wi-Fi, or one phone's hotspot), then the
     * app hotspot the sender opened when it has no Wi-Fi Direct.
     */
    suspend fun connect(ctx: Context, inv: Beam.Invite, status: (String) -> Unit): Peer = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        if (inv.hasDirect && Build.VERSION.SDK_INT >= 29 && BeamPermissions.granted(app)) {
            status("Connecting directly…")
            try {
                BeamWifi.join(app, inv)
                probe(BeamWifi.HOST_IP, inv, null, 20_000)?.let { Log.i(TAG, "wifi direct link up"); return@withContext it }
                BeamWifi.teardown()
            } catch (e: Exception) {
                Log.w(TAG, "wifi direct failed: ${e.message}")
                BeamWifi.teardown()
            }
        }
        status("Looking for the other phone…")
        for (ip in inv.ips) probe(ip, inv, null, 1500)?.let { Log.i(TAG, "same network: $ip"); return@withContext it }
        if (inv.hasHotspot && Build.VERSION.SDK_INT >= 29) {
            status("Joining the other phone's hotspot…")
            val net = BeamHotspot.join(app, inv.hsSsid, inv.hsPass)
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline) {
                // The hotspot's own address is usually the gateway; the QR also lists the sender's addresses
                val hosts = (listOfNotNull(gateway(app, net)) + inv.ips).distinct()
                for (h in hosts) probe(h, inv, net, 1500)?.let { Log.i(TAG, "hotspot link up: $h"); return@withContext it }
                kotlinx.coroutines.delay(700)
            }
            BeamHotspot.release(app)
        }
        throw IOException(when {
            inv.hasDirect || inv.hasHotspot -> "Couldn't connect to the other phone. Keep both phones close with Wi-Fi on, then scan again."
            else -> "Can't reach the other phone. Connect both phones to the same Wi-Fi (or one phone's hotspot) and scan again."
        })
    }

    private suspend fun probe(host: String, inv: Beam.Invite, net: android.net.Network?, waitMs: Long): Peer? {
        val deadline = System.currentTimeMillis() + waitMs
        do {
            val p = Peer(host, inv.port, inv.token, direct = host == BeamWifi.HOST_IP && net == null, network = net)
            if (hello(p, 2000)) return p
            if (waitMs > 2500) kotlinx.coroutines.delay(500)
        } while (System.currentTimeMillis() < deadline)
        return null
    }

    private fun gateway(ctx: Context, net: android.net.Network): String? = runCatching {
        ctx.getSystemService(android.net.ConnectivityManager::class.java).getLinkProperties(net)?.routes
            ?.firstOrNull { it.isDefaultRoute && it.gateway != null }?.gateway?.hostAddress
            ?: ctx.getSystemService(android.net.ConnectivityManager::class.java).getLinkProperties(net)?.dhcpServerAddress?.hostAddress
    }.getOrNull()

    private fun hello(p: Peer, timeoutMs: Long): Boolean = runCatching {
        p.http.newBuilder().connectTimeout(timeoutMs, TimeUnit.MILLISECONDS).readTimeout(timeoutMs * 2, TimeUnit.MILLISECONDS).build()
            .newCall(req(p, "/hello")).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    suspend fun list(p: Peer): List<Beam.Offer> = withContext(Dispatchers.IO) {
        p.http.newCall(req(p, "/list")).execute().use { r ->
            if (!r.isSuccessful) throw IOException("The other phone refused (${r.code})")
            gson.fromJson<List<Beam.Offer>>(r.body!!.string(), object : TypeToken<List<Beam.Offer>>() {}.type)
        }
    }

    private fun req(p: Peer, path: String) = Request.Builder().url(p.base + path).header("X-Beam-Token", p.token).build()

    /** Drops a link that was set up but not used (receive screen closed). */
    fun abandon(ctx: Context, p: Peer) {
        if (peer === p) return
        if (p.direct && Build.VERSION.SDK_INT >= 29) BeamWifi.teardown()
        if (p.network != null) BeamHotspot.release(ctx)
    }

    // ── Receiving ────────────────────────────────────────────────────────────

    /**
     * Copies [offers] from [p] and adds them as downloads. Runs in the background (a foreground service
     * keeps it alive); follow it with [progress]. Returns how many will be received.
     */
    fun receive(ctx: Context, p: Peer, offers: List<Beam.Offer>): Int {
        val app = ctx.applicationContext
        val dm = NovaDownloads.manager(app)
        val have = NovaDownloads.all(app).filter { it.state == Download.STATE_COMPLETED }.map { it.request.id }.toSet()
        val todo = offers.filter { it.id !in have }
        if (todo.isEmpty()) return 0
        peer = p
        p.ids += todo.map { it.id }
        progress = Progress(count = todo.size, total = todo.sumOf { it.bytes }, how = p.how)
        // The link may have no internet: don't let "waiting for network" hold the queue
        dm.requirements = Requirements(0)
        watch(app, dm)
        BeamService.receiving(app)
        Thread({ run(app, p, todo) }, "beam-receive").start()
        return todo.size
    }

    private fun run(app: Context, p: Peer, todo: List<Beam.Offer>) {
        val cache = NovaDownloads.cache
        val bulk = BeamBulk(p, cache)
        todo.forEachIndexed { i, o ->
            if (peer !== p) return
            var meta = runCatching { gson.fromJson(o.data, DownloadMeta::class.java) }.getOrNull()
            progress = progress.copy(title = meta?.let { com.anees.novamobile.ui.downloads.DownloadUi.name(it) } ?: o.id, index = i + 1)
            try {
                // An unfinished copy (e.g. a failed internet download) may hold pieces saved under other
                // addresses: remove it first, and wait, so it can't delete what arrives next
                if (NovaDownloads.get(app, o.id) != null) {
                    DownloadService.sendRemoveDownload(app, NovaDownloadService::class.java, o.id, false)
                    val until = System.currentTimeMillis() + 20_000
                    while (NovaDownloads.get(app, o.id) != null && System.currentTimeMillis() < until) Thread.sleep(200)
                }
                meta = subtitles(p, o, meta)
                val pieces = runCatching { manifest(p, o.id) }.onFailure { Log.w(TAG, "no piece list for ${o.id}: ${it.message}") }.getOrDefault(emptyList())
                val t0 = System.currentTimeMillis(); val b0 = bulk.done.get()
                val missing = bulk.copy(pieces)
                val mb = (bulk.done.get() - b0) / 1_048_576.0
                Log.i(TAG, "${o.id}: %.0f MB in %.1fs (%.0f MB/s), %d bytes left for the download".format(mb, (System.currentTimeMillis() - t0) / 1000.0,
                    mb / ((System.currentTimeMillis() - t0).coerceAtLeast(1) / 1000.0), missing))
            } catch (e: Exception) {
                Log.w(TAG, "fast copy of ${o.id} failed, the download fetches it", e)
            }
            // Everything copied is in the cache now: the download completes from it (gaps: other phone, then internet)
            val request = DownloadRequest.Builder(o.id, Uri.parse(o.uri))
                .setMimeType(o.mimeType)
                .setStreamKeys(o.streamKeys.map { StreamKey(it[0], it[1], it[2]) })
                .setCustomCacheKey(o.customCacheKey)
                .setData(meta?.toBytes() ?: o.data.toByteArray())
                .build()
            DownloadService.sendAddDownload(app, NovaDownloadService::class.java, request, false)
        }
        p.allAdded = true
        main.post { check(app, NovaDownloads.manager(app)) }
    }

    private fun manifest(p: Peer, id: String): List<Beam.Piece> =
        p.http.newBuilder().readTimeout(5, TimeUnit.MINUTES).build().newCall(req(p, "/manifest?id=${Uri.encode(id)}")).execute().use { r ->
            if (!r.isSuccessful) throw IOException("piece list refused (${r.code})")
            gson.fromJson<List<Beam.Piece>>(r.body!!.string(), object : TypeToken<List<Beam.Piece>>() {}.type)
        }

    /** Sidecar subtitle files; returns the meta pointing at this phone's copies. */
    private fun subtitles(p: Peer, o: Beam.Offer, meta: DownloadMeta?): DownloadMeta? {
        if (meta == null || o.subtitles == 0) return meta
        val subDir = File(NovaDownloads.dir, "subtitles").apply { mkdirs() }
        val files = (0 until o.subtitles).mapNotNull { i ->
            runCatching {
                p.http.newCall(req(p, "/f?id=${Uri.encode(o.id)}&i=$i")).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    val bytes = r.body!!.bytes()
                    p.bytes.addAndGet(bytes.size.toLong())
                    File(subDir, "beam_${o.id.hashCode()}_$i.${if (String(bytes, 0, minOf(bytes.size, 16)).contains("WEBVTT")) "vtt" else "srt"}")
                        .apply { writeBytes(bytes) }.absolutePath
                }
            }.getOrNull()
        }
        // Same order as the sender's list: subtitles first, then the single legacy "subtitle"
        val n = meta.subtitles.orEmpty().size
        return meta.copy(subtitles = files.take(n).ifEmpty { null }, subtitle = if (meta.subtitle != null) (files.getOrNull(n) ?: files.firstOrNull()) else null)
    }

    private var listener: DownloadManager.Listener? = null

    private fun watch(app: Context, dm: DownloadManager) {
        listener?.let { dm.removeListener(it) }
        val l = object : DownloadManager.Listener {
            override fun onDownloadChanged(m: DownloadManager, d: Download, e: Exception?) = check(app, m)
            override fun onDownloadRemoved(m: DownloadManager, d: Download) = check(app, m)
        }
        listener = l
        dm.addListener(l)
    }

    private fun check(app: Context, dm: DownloadManager) {
        val p = peer ?: return
        if (!p.allAdded) return
        val states = p.ids.map { id -> NovaDownloads.get(app, id)?.state }
        val open = states.count { it != null && it != Download.STATE_COMPLETED && it != Download.STATE_FAILED }
        if (open > 0) return
        val done = states.count { it == Download.STATE_COMPLETED }
        val failed = p.ids.size - done
        Log.i(TAG, "transfer finished: $done done, $failed not, ${p.bytes.get() shr 20} MB in ${(System.currentTimeMillis() - p.startedAt) / 1000}s")
        progress = progress.copy(finished = true, ok = done, failed = failed, done = p.bytes.get())
        finish(app)
        onFinished?.invoke(done, failed)
    }

    /** Stops a running transfer: what already arrived stays, unfinished items are removed. */
    fun cancel(app: Context) {
        val p = peer ?: return
        progress = progress.copy(finished = true)
        finish(app)
        p.ids.forEach { id ->
            if (NovaDownloads.get(app, id)?.state != Download.STATE_COMPLETED)
                DownloadService.sendRemoveDownload(app, NovaDownloadService::class.java, id, false)
        }
    }

    /** Stops routing through the other phone and drops the link. */
    fun finish(app: Context) {
        val p = peer ?: return
        peer = null
        main.post {
            val dm = NovaDownloads.manager(app)
            listener?.let { dm.removeListener(it) }; listener = null
            NovaDownloads.applySettings(app)   // restores the normal network requirements
        }
        if (p.direct && Build.VERSION.SDK_INT >= 29) BeamWifi.teardown()
        if (p.network != null) BeamHotspot.release(app)
        BeamService.stopReceiving(app)
    }

    fun speed(): Long {
        val p = peer ?: return 0
        val secs = ((System.currentTimeMillis() - p.startedAt) / 1000).coerceAtLeast(1)
        return p.bytes.get() / secs
    }

    // ── Routing ──────────────────────────────────────────────────────────────

    /** Wraps the internet source: pieces come from the other phone while a transfer runs. */
    fun route(upstream: DataSource.Factory, keys: CacheKeyFactory): DataSource.Factory =
        DataSource.Factory { Routed(upstream.createDataSource(), keys) }

    private class Routed(private val net: DataSource, private val keys: CacheKeyFactory) : BaseDataSource(true) {
        private var body: InputStream? = null
        private var response: Response? = null
        private var useNet = false
        private var uri: Uri? = null
        private var left = C.LENGTH_UNSET.toLong()

        override fun open(spec: DataSpec): Long {
            uri = spec.uri
            val p = peer
            if (p != null) {
                val key = spec.key ?: keys.buildCacheKey(spec)
                val url = "${p.base}/c?k=${Uri.encode(key)}&p=${spec.position}&l=${spec.length}"
                val r = runCatching { p.http.newCall(Request.Builder().url(url).header("X-Beam-Token", p.token).build()).execute() }.getOrNull()
                if (r != null && r.isSuccessful) {
                    transferInitializing(spec)
                    response = r
                    r.header("X-Beam-Uri")?.let { uri = Uri.parse(Uri.decode(it)) }
                    body = r.body!!.byteStream()
                    val n = r.body!!.contentLength()
                    left = if (n >= 0) n else C.LENGTH_UNSET.toLong()
                    transferStarted(spec)
                    return if (spec.length != C.LENGTH_UNSET.toLong()) spec.length else left
                }
                r?.close()
            }
            useNet = true
            return net.open(spec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (useNet) return net.read(buffer, offset, length)
            if (length == 0) return 0
            if (left == 0L) return C.RESULT_END_OF_INPUT
            val want = if (left > 0) minOf(left, length.toLong()).toInt() else length
            val n = body!!.read(buffer, offset, want)
            if (n < 0) {
                if (left > 0) throw IOException("the other phone stopped sending")
                return C.RESULT_END_OF_INPUT
            }
            if (left > 0) left -= n
            peer?.bytes?.addAndGet(n.toLong())
            bytesTransferred(n)
            return n
        }

        override fun getUri(): Uri? = if (useNet) net.uri else uri

        override fun getResponseHeaders(): Map<String, List<String>> = if (useNet) net.responseHeaders else emptyMap()

        override fun close() {
            if (useNet) { useNet = false; net.close(); return }
            val had = body != null
            runCatching { body?.close() }
            runCatching { response?.close() }
            body = null; response = null
            if (had) transferEnded()
        }
    }
}
