package com.anees.novamobile.download

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.DownloaderFactory
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong

/** Direct files (MP4/MKV) → [ParallelProgressiveDownloader]; HLS/DASH → Media3's own downloaders. */
@UnstableApi
class NovaDownloaderFactory(private val cacheFactory: CacheDataSource.Factory, executor: java.util.concurrent.Executor) : DownloaderFactory {
    private val default = DefaultDownloaderFactory(cacheFactory, executor)
    override fun createDownloader(request: DownloadRequest): Downloader =
        if (Util.inferContentTypeForUriAndMimeType(request.uri, request.mimeType) == C.CONTENT_TYPE_OTHER)
            ParallelProgressiveDownloader(request, cacheFactory)
        else default.createDownloader(request)
}

/**
 * Download-manager style (ADM / 1DM / MovieBox): the file is cut into [PIECE] byte ranges fetched
 * over several connections at once, each written into the same cache entry Media3's own
 * ProgressiveDownloader would use (same key), so offline playback, export and phone transfer don't
 * change. Pieces already saved are skipped, so a restart resumes where it was.
 *
 * Slow links: more connections only fight each other below ~300 KB/s, so extra workers stop
 * there and 2 keep going. A failing piece is retried a few times before Media3's retry policy
 * takes over (which resumes from the saved pieces again).
 */
@UnstableApi
class ParallelProgressiveDownloader(request: DownloadRequest, private val factory: CacheDataSource.Factory) : Downloader {
    companion object {
        private const val TAG = "NovaDL"
        private const val PIECE = 4L * 1024 * 1024
        private const val TAIL_PIECE = 1L * 1024 * 1024
        private const val MAX_CONNS = 6
        private const val MIN_CONNS = 2
        private const val SLOW_BPS = 300L * 1024
        private val pool = Executors.newCachedThreadPool()
    }

    private val uri = request.uri
    private val spec = DataSpec.Builder().setUri(uri).setKey(request.customCacheKey)
        .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION).build()
    private val key = factory.cacheKeyFactory.buildCacheKey(spec)
    @Volatile private var cancelled = false
    private val writers = CopyOnWriteArrayList<CacheWriter>()
    private val futures = CopyOnWriteArrayList<Future<*>>()

    override fun download(listener: Downloader.ProgressListener?) {
        val cache = factory.cache ?: throw IOException("no cache")
        var length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        if (length == C.LENGTH_UNSET.toLong()) length = NovaDownloads.totalLength(uri) ?: probeLength()
        Log.i(TAG, "parallel: length=$length for $key")
        // Pieces are ranged reads, so the cache never learns the total by itself; without it, reading to the
        // end (offline playback's last second, Save to phone) asks the network "is there more?" and fails offline
        if (length > 0) storeLength(cache, key, length)
        if (length > 0) {
            // Room for the rest? Fail now with "No space" (downloads pause, you're told) rather than at 90%
            val free = runCatching { android.os.StatFs(NovaDownloads.dir.absolutePath).availableBytes }.getOrDefault(Long.MAX_VALUE)
            val need = length - cache.getCachedBytes(key, 0, length)
            if (need > free - 100L * 1024 * 1024) throw IOException("No space left for this download (ENOSPC)")
            checkVideo()
        }
        if (length <= 0) {
            // Server won't say how big: one connection, like Media3's own downloader
            single(listener); return
        }
        val pieces = ConcurrentLinkedQueue<LongArray>()
        var pos = 0L
        // Last ~10% in 1 MB pieces: the download doesn't crawl at the end waiting on one slow 4 MB piece
        val tailFrom = length - length / 10
        while (pos < length) {
            val len = minOf(if (pos >= tailFrom) TAIL_PIECE else PIECE, length - pos)
            if (cache.getCachedBytes(key, pos, len) < len) pieces += longArrayOf(pos, len)
            pos += len
        }
        // Finished pieces count now; CacheWriter reports the saved parts of unfinished ones itself
        val done = AtomicLong(length - pieces.sumOf { it[1] })
        // Exact progress = bytes really in the cache (re-read at most twice a second; the counter fills in between)
        val lastExact = AtomicLong(0)
        val report = {
            synchronized(this) {
                val now = System.currentTimeMillis()
                if (now - lastExact.get() > 500) { lastExact.set(now); done.set(cache.getCachedBytes(key, 0, length)) }
                val d = done.get().coerceAtMost(length)
                listener?.onProgress(length, d, d * 100f / length)
            }
        }
        report()
        if (pieces.isEmpty()) return
        // Speed over the last few seconds, shared by the workers to decide whether to keep extra connections
        val startedAt = System.currentTimeMillis()
        val window = AtomicLong(0); val windowStart = AtomicLong(System.currentTimeMillis()); val bps = AtomicLong(Long.MAX_VALUE)
        val errors = CopyOnWriteArrayList<Throwable>()
        val conns = minOf(MAX_CONNS, pieces.size)
        // Current writer per worker, so a slow link can retire extra connections mid-piece (saved bytes stay)
        val current = arrayOfNulls<CacheWriter>(conns)
        val retired = BooleanArray(conns)
        Log.i(TAG, "parallel: ${pieces.size} pieces of ${length / 1048576} MB over $conns connections")
        lateinit var spawn: (Int) -> Unit
        spawn = { worker -> futures += pool.submit {
                while (!cancelled && errors.isEmpty()) {
                    if (retired[worker]) break   // slow link: leave it to fewer connections
                    val p = pieces.poll() ?: break
                    var tries = 0
                    while (true) {
                        val w = CacheWriter(factory.createDataSourceForDownloading(),
                            spec.buildUpon().setPosition(p[0]).setLength(p[1]).build(), null) { _, _, newBytes ->
                            done.addAndGet(newBytes)
                            window.addAndGet(newBytes)
                            val now = System.currentTimeMillis()
                            val span = now - windowStart.get()
                            if (span >= 4_000) {
                                val speed = window.getAndSet(0) * 1000 / span
                                bps.set(speed); windowStart.set(now)
                                if (speed < SLOW_BPS && now - startedAt > 15_000) for (i in MIN_CONNS until conns) if (!retired[i]) {
                                    retired[i] = true; current[i]?.cancel()
                                    Log.i(TAG, "slow link (${speed / 1024} KB/s): connection $i retired")
                                }
                                // Network got faster again: bring one retired connection back per window
                                else if (speed > SLOW_BPS * 3 && pieces.isNotEmpty()) (MIN_CONNS until conns).firstOrNull { retired[it] && current[it] == null }?.let { i ->
                                    retired[i] = false; spawn(i)
                                    Log.i(TAG, "faster link (${speed / 1024} KB/s): connection $i back")
                                }
                            }
                            report()
                        }
                        writers += w
                        current[worker] = w
                        try { w.cache(); break }
                        catch (e: Throwable) {
                            if (cancelled || e is InterruptedException) return@submit
                            if (retired[worker]) { pieces += p; return@submit }   // handed back; a core connection finishes it
                            if (++tries >= 4) { errors += e; pieces += p; return@submit }
                            Log.w(TAG, "piece @${p[0]} retry $tries: ${e.message}")
                            try { Thread.sleep(2_000L * tries) } catch (_: InterruptedException) { return@submit }
                        } finally { writers -= w; current[worker] = null }
                    }
                }
            } }
        repeat(conns) { spawn(it) }
        // Wait for every worker, including ones brought back while waiting
        while (true) {
            val running = futures.filter { !it.isDone }
            if (running.isEmpty()) break
            running.forEach { runCatching { it.get() } }
        }
        if (cancelled) throw InterruptedException()
        errors.firstOrNull()?.let { throw if (it is IOException) it else IOException(it) }
        if (pieces.isNotEmpty()) single(listener)   // leftovers (shouldn't happen): finish on one connection
        listener?.onProgress(length, length, 100f)
    }

    /**
     * Total size from the server. Some hosts only state it for a Range request (Content-Range: bytes 0-0/TOTAL),
     * so ask for one byte first; else open the whole file and read the resolved length.
     */
    private fun probeLength(): Long {
        runCatching {
            val ds = factory.createDataSourceForDownloading()
            try {
                ds.open(spec.buildUpon().setPosition(0).setLength(1).build())
                val h = ds.responseHeaders.entries.firstOrNull { it.key.equals("Content-Range", true) }?.value?.firstOrNull()
                h?.substringAfter('/')?.trim()?.toLongOrNull()?.let { return it }
            } finally { ds.close() }
        }
        return runCatching {
            val ds = factory.createDataSourceForDownloading()
            try { ds.open(spec) } finally { ds.close() }
        }.getOrDefault(C.LENGTH_UNSET.toLong())
    }

    private fun single(listener: Downloader.ProgressListener?) {
        val w = CacheWriter(factory.createDataSourceForDownloading(), spec, null) { req, cached, _ ->
            listener?.onProgress(req, cached, if (req == C.LENGTH_UNSET.toLong() || req == 0L) C.PERCENTAGE_UNSET.toFloat() else cached * 100f / req)
        }
        writers += w
        try { w.cache() } finally { writers -= w }
    }

    /** First bytes must look like a video (MP4 ftyp / Matroska / MPEG-TS); an expired link often answers 200 with an HTML page. */
    private fun checkVideo() {
        val ds = factory.createDataSourceForDownloading()
        val head = ByteArray(12)
        val n = try {
            ds.open(spec.buildUpon().setPosition(0).setLength(12).build())
            var got = 0
            while (got < 12) { val r = ds.read(head, got, 12 - got); if (r == C.RESULT_END_OF_INPUT) break; got += r }
            got
        } finally { ds.close() }
        val ok = n >= 8 && (String(head, 4, 4, Charsets.ISO_8859_1) in setOf("ftyp", "moov", "free", "mdat", "wide", "skip", "styp") ||
            (head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() && head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte()) ||
            head[0] == 0x47.toByte())
        if (!ok) {
            Log.w(TAG, "not a video: ${String(head, 0, n, Charsets.ISO_8859_1).replace(Regex("[^\\x20-\\x7e]"), ".")}")
            factory.cache?.removeResource(key)   // the bogus bytes must not stay cached as "the video"
            throw IOException(NovaDownloads.NOT_VIDEO)
        }
    }

    private fun storeLength(cache: androidx.media3.datasource.cache.Cache, key: String, length: Long) = runCatching {
        if (ContentMetadata.getContentLength(cache.getContentMetadata(key)) != length)
            cache.applyContentMetadataMutations(key, androidx.media3.datasource.cache.ContentMetadataMutations().also {
                androidx.media3.datasource.cache.ContentMetadataMutations.setContentLength(it, length)
            })
    }

    override fun cancel() {
        cancelled = true
        writers.forEach { it.cancel() }
        futures.forEach { it.cancel(true) }
    }

    override fun remove() {
        factory.cache?.removeResource(key)
    }
}
