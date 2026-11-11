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
        private const val MIN_PIECE = 2L * 1024 * 1024
        private const val MAX_PIECE = 32L * 1024 * 1024
        private const val TAIL_PIECE = 512L * 1024
        private const val PIECE_SECONDS = 8L
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
        val fresh = cache.getCachedSpans(key).isEmpty()
        // The three checks before the first byte (size, ranges honoured, really a video) each cost a round trip
        // to a slow host: ask them side by side instead of one after another (~1-3 s sooner on every start)
        val known = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        val lengthF = if (known != C.LENGTH_UNSET.toLong()) null else pool.submit<Long> { NovaDownloads.totalLength(uri) ?: probeLength() }
        val rangeF = if (fresh) pool.submit<Boolean> { NovaDownloads.supportsRange(uri) } else null
        val videoF = pool.submit<Boolean> { looksLikeVideo() }
        val length = lengthF?.let { await(it) } ?: known
        Log.i(TAG, "parallel: length=$length for $key")
        // Pieces are ranged reads, so the cache never learns the total by itself; without it, reading to the
        // end (offline playback's last second, Save to phone) asks the network "is there more?" and fails offline
        if (length > 0) {
            storeLength(cache, key, length)
            // Room for the rest? Fail now with "No space" (downloads pause, you're told) rather than at 90%
            val free = runCatching { android.os.StatFs(NovaDownloads.dir.absolutePath).availableBytes }.getOrDefault(Long.MAX_VALUE)
            val need = length - cache.getCachedBytes(key, 0, length)
            if (need > free - 100L * 1024 * 1024) { rangeF?.cancel(true); videoF.cancel(true); throw IOException("No space left for this download (ENOSPC)") }
            if (!await(videoF)) {
                factory.cache?.removeResource(key)   // the bogus bytes must not stay cached as "the video"
                throw IOException(NovaDownloads.NOT_VIDEO)
            }
        } else videoF.cancel(true)
        if (length <= 0) {
            // Server won't say how big: one connection, like Media3's own downloader
            rangeF?.cancel(true)
            single(listener); return
        }
        // Server ignores ranges (some Google file links): every piece would restart at byte 0 — one connection
        if (rangeF != null && !await(rangeF)) {
            Log.i(TAG, "parallel: server ignores ranges, one connection")
            single(listener); return
        }
        val ranges = Ranges(gaps(cache, length))
        // Finished bytes count now; CacheWriter reports the saved parts of unfinished pieces itself
        val done = AtomicLong(length - ranges.remaining)
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
        if (ranges.isEmpty()) return
        // Speed over the last few seconds, shared by the workers to decide piece size and how many connections to keep
        val startedAt = System.currentTimeMillis()
        val window = AtomicLong(0); val windowStart = AtomicLong(System.currentTimeMillis()); val bps = AtomicLong(-1)
        val errors = CopyOnWriteArrayList<Throwable>()
        val conns = MAX_CONNS.toLong().coerceAtMost((ranges.remaining + PIECE - 1) / PIECE).toInt().coerceAtLeast(1)
        // Current writer per worker, so a slow link can retire extra connections mid-piece (saved bytes stay)
        val current = arrayOfNulls<CacheWriter>(conns)
        val retired = BooleanArray(conns)
        /**
         * Next piece size. Every piece is a new request, and slow hosts take ~0.5 s to answer each: on a fast link
         * fixed 4 MB pieces spent a fifth of the time waiting. So a piece is ~[PIECE_SECONDS] of this connection's
         * speed (2-32 MB), and near the end it shrinks with what's left, so no connection is stuck alone on a
         * big piece while the others sit idle.
         */
        val nextSize = {
            val live = (0 until conns).count { !retired[it] }.coerceAtLeast(1)
            val speed = bps.get()
            val bySpeed = if (speed <= 0) PIECE else speed / live * PIECE_SECONDS
            val endgame = ranges.remaining / (live * 2)
            minOf(bySpeed.coerceIn(MIN_PIECE, MAX_PIECE), endgame).coerceAtLeast(TAIL_PIECE)
        }
        Log.i(TAG, "parallel: ${ranges.remaining / 1048576} MB of ${length / 1048576} MB left over $conns connections")
        lateinit var spawn: (Int) -> Unit
        spawn = { worker -> futures += pool.submit {
                while (!cancelled && errors.isEmpty()) {
                    if (retired[worker]) break   // slow link: leave it to fewer connections
                    val p = ranges.take(nextSize()) ?: break
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
                                else if (speed > SLOW_BPS * 3 && !ranges.isEmpty()) (MIN_CONNS until conns).firstOrNull { retired[it] && current[it] == null }?.let { i ->
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
                            if (cancelled || e is InterruptedException) { ranges.giveBack(p); return@submit }
                            if (retired[worker]) { ranges.giveBack(p); return@submit }   // handed back; a core connection finishes it
                            if (++tries >= 4) { errors += e; ranges.giveBack(p); return@submit }
                            Log.w(TAG, "piece @${p[0]} retry $tries: ${e.message}")
                            try { Thread.sleep(2_000L * tries) } catch (_: InterruptedException) { ranges.giveBack(p); return@submit }
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
        if (!ranges.isEmpty()) single(listener)   // leftovers (shouldn't happen): finish on one connection
        listener?.onProgress(length, length, 100f)
    }

    /** Parts of the file not yet in the cache, as [start, length] runs. */
    private fun gaps(cache: androidx.media3.datasource.cache.Cache, length: Long): List<LongArray> {
        val out = ArrayList<LongArray>()
        var pos = 0L
        while (pos < length) {
            val run = cache.getCachedLength(key, pos, length - pos)
            if (run == 0L) { out += longArrayOf(pos, length - pos); break }
            if (run < 0) out += longArrayOf(pos, -run)
            pos += kotlin.math.abs(run)
        }
        return out
    }

    /** Work still to fetch; pieces are cut off the front at the size asked for when a connection is free. */
    private class Ranges(gaps: List<LongArray>) {
        private val q = ArrayDeque(gaps)
        @Volatile var remaining = gaps.sumOf { it[1] }; private set
        @Synchronized fun isEmpty() = q.isEmpty()
        @Synchronized fun take(size: Long): LongArray? {
            val g = q.removeFirstOrNull() ?: return null
            val n = if (g[1] <= size + size / 4) g[1] else size   // don't leave a tiny sliver behind
            if (n < g[1]) q.addFirst(longArrayOf(g[0] + n, g[1] - n))
            remaining -= n
            return longArrayOf(g[0], n)
        }
        @Synchronized fun giveBack(p: LongArray) { q.addFirst(p); remaining += p[1] }
    }

    private fun <T> await(f: Future<T>): T = try { f.get() } catch (e: java.util.concurrent.ExecutionException) { throw e.cause ?: e }

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

    /** First bytes look like a video (MP4 ftyp / Matroska / MPEG-TS)? An expired link often answers 200 with an HTML page. */
    private fun looksLikeVideo(): Boolean {
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
        if (!ok) Log.w(TAG, "not a video: ${String(head, 0, n, Charsets.ISO_8859_1).replace(Regex("[^\\x20-\\x7e]"), ".")}")
        return ok
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
