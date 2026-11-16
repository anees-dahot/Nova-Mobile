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
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

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
        private const val SPLIT_MIN = 2L * 1024 * 1024
        private const val SPLIT_SECONDS = 8L
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
                // The cache only counts a fragment once its file is closed (every 5 MB or at the piece's end). Setting
                // progress to that number froze it for a minute on slow links, then the stall watcher restarted the
                // download. So the live counter leads; the cache only corrects it upward.
                if (now - lastExact.get() > 500) { lastExact.set(now); val c = cache.getCachedBytes(key, 0, length); done.accumulateAndGet(c) { a, b -> maxOf(a, b) } }
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
        val workerFut = arrayOfNulls<Future<*>>(conns)
        // Piece each worker is on (start, end) and bytes its writer got so far: for endgame splits
        val pieceStart = AtomicLongArray(conns); val pieceEnd = AtomicLongArray(conns); val pieceGot = AtomicLongArray(conns)
        val split = AtomicIntegerArray(conns)   // 1 = cut short so idle connections can share the rest
        val pieceLeft = { i: Int -> pieceEnd.get(i) - pieceStart.get(i) - pieceGot.get(i) }
        /** Connection-count tuning state; only touched by the worker that closes a speed window. */
        class Tune { var slowRun = 0; var before = 0L; var since = 0; var noRetire = false }
        val tune = Tune()
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
        /** Hands back only the part of [p] not yet in the cache (bytes saved before a cut are not fetched twice). */
        val giveBack = { p: LongArray -> gapsIn(cache, p[0], p[0] + p[1]).asReversed().forEach { ranges.giveBack(it) } }
        /**
         * Queue empty, but another connection is still on a big piece (one slow socket held the whole download at
         * 9x% while the rest sat idle): cut that piece so its remainder goes back to the queue in smaller parts.
         * True while such a cut is pending, so this worker waits for it instead of quitting.
         */
        val helpOut = { me: Int ->
            val live = (0 until conns).count { !retired[it] }.coerceAtLeast(1)
            val perConn = (bps.get() / live).coerceAtLeast(16L * 1024)
            val busy = (0 until conns).filter { it != me && current[it] != null }
            val victim = busy.filter { split.get(it) == 0 }.maxByOrNull { pieceLeft(it) }
            if (victim != null && pieceLeft(victim) >= SPLIT_MIN && pieceLeft(victim) / perConn >= SPLIT_SECONDS && split.compareAndSet(victim, 0, 1)) {
                Log.i(TAG, "endgame: splitting connection $victim (${pieceLeft(victim) / 1024} KB left)")
                current[victim]?.cancel()
            }
            busy.any { split.get(it) == 1 }
        }
        Log.i(TAG, "parallel: ${ranges.remaining / 1048576} MB of ${length / 1048576} MB left over $conns connections")
        lateinit var spawn: (Int) -> Unit
        val spawnIfIdle = { i: Int -> synchronized(workerFut) { if (workerFut[i]?.isDone != false) spawn(i) } }
        /** Called by the one worker that closes a speed window: piece size and how many connections to keep. */
        val tuneConns = { speed: Long, now: Long ->
            tune.slowRun = if (speed < SLOW_BPS) tune.slowRun + 1 else 0
            // Two windows after retiring: did fewer connections make it slower? Then this host caps each connection
            // (common on file hosts) and more connections is what makes it fast: bring them back and stop retiring.
            if (tune.before > 0 && ++tune.since >= 2) {
                if (speed < tune.before * 7 / 10) {
                    tune.noRetire = true
                    for (i in MIN_CONNS until conns) if (retired[i]) { retired[i] = false; spawnIfIdle(i) }
                    Log.i(TAG, "fewer connections slower (${tune.before / 1024} -> ${speed / 1024} KB/s): all back")
                }
                tune.before = 0
            }
            // Slow for two windows in a row (one dip is noise): more connections only fight each other, keep 2
            if (!tune.noRetire && tune.before == 0L && tune.slowRun >= 2 && now - startedAt > 15_000 && (MIN_CONNS until conns).any { !retired[it] }) {
                tune.before = speed; tune.since = 0
                for (i in MIN_CONNS until conns) if (!retired[i]) { retired[i] = true; current[i]?.cancel() }
                Log.i(TAG, "slow link (${speed / 1024} KB/s): extra connections retired")
            }
            // Network got faster again: bring one retired connection back per window
            else if (speed > SLOW_BPS * 3 && !ranges.isEmpty()) (MIN_CONNS until conns).firstOrNull { retired[it] }?.let { i ->
                retired[i] = false; spawnIfIdle(i)
                Log.i(TAG, "faster link (${speed / 1024} KB/s): connection $i back")
            }
            Unit
        }
        spawn = { worker -> workerFut[worker] = pool.submit {
                while (!cancelled && errors.isEmpty()) {
                    if (retired[worker]) break   // slow link: leave it to fewer connections
                    val p = ranges.take(nextSize())
                    if (p == null) {
                        if (!helpOut(worker)) break
                        try { Thread.sleep(300) } catch (_: InterruptedException) { return@submit }
                        continue
                    }
                    var tries = 0
                    while (true) {
                        pieceStart.set(worker, p[0]); pieceEnd.set(worker, p[0] + p[1]); pieceGot.set(worker, 0)
                        val w = CacheWriter(factory.createDataSourceForDownloading(),
                            spec.buildUpon().setPosition(p[0]).setLength(p[1]).build(), null) { _, _, newBytes ->
                            done.addAndGet(newBytes)
                            pieceGot.addAndGet(worker, newBytes)
                            window.addAndGet(newBytes)
                            val now = System.currentTimeMillis()
                            val since = windowStart.get()
                            val span = now - since
                            // Only one worker closes a window: two at the same instant measured the second "window" at
                            // ~0 bytes and retired connections on a fast link
                            if (span >= 4_000 && windowStart.compareAndSet(since, now)) synchronized(tune) {
                                val speed = window.getAndSet(0) * 1000 / span
                                bps.set(speed)
                                tuneConns(speed, now)
                            }
                            report()
                        }
                        writers += w
                        current[worker] = w
                        try { w.cache(); break }
                        catch (e: Throwable) {
                            if (cancelled || e is InterruptedException) { giveBack(p); return@submit }
                            if (retired[worker]) { giveBack(p); return@submit }   // handed back; a core connection finishes it
                            if (split.get(worker) == 1) { giveBack(p); break }   // endgame cut: the rest is shared out
                            if (++tries >= 4) { errors += e; giveBack(p); return@submit }
                            Log.w(TAG, "piece @${p[0]} retry $tries: ${e.message}")
                            try { Thread.sleep(2_000L * tries) } catch (_: InterruptedException) { giveBack(p); return@submit }
                        } finally { writers -= w; current[worker] = null; pieceGot.set(worker, 0); pieceEnd.set(worker, 0); pieceStart.set(worker, 0); split.set(worker, 0) }
                    }
                }
            }.also { futures += it } }
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
    private fun gaps(cache: androidx.media3.datasource.cache.Cache, length: Long) = gapsIn(cache, 0, length)

    /** Uncached runs inside [start, end), as [start, length]. */
    private fun gapsIn(cache: androidx.media3.datasource.cache.Cache, start: Long, end: Long): List<LongArray> {
        val out = ArrayList<LongArray>()
        var pos = start
        while (pos < end) {
            val run = cache.getCachedLength(key, pos, end - pos)
            if (run == 0L) { out += longArrayOf(pos, end - pos); break }
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
