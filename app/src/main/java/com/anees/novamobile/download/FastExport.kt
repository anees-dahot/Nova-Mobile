package com.anees.novamobile.download

import android.content.Context
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.UriUtil
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceUtil
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import androidx.media3.exoplayer.offline.Download
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Mp4Muxer
import java.io.ByteArrayInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Saves a finished download as one MP4 in a single pass, straight from the pieces already on the
 * phone: no re-encode, no temp files. Video and EVERY audio language go into the same file
 * (VLC / MX Player switch between them). Speed is bounded by storage: each byte is read once from
 * the download store and written once to the destination.
 *
 *  - HLS: the kept video variant + every kept audio rendition are read segment by segment from the
 *    download cache (a [MediaDataSource] over the pieces), samples go into Mp4Muxer.
 *  - Progressive MP4: streamed through as is.
 * [plan] returns null when this can't work (encrypted segments, DASH): the caller falls back to Transformer.
 */
@UnstableApi
object FastExport {
    private const val TAG = "PublicExport"

    private class Seg(val uri: Uri, val offset: Long, val length: Long)
    private class Part(val segs: List<Seg>, val lang: String?, val videoAllowed: Boolean)
    class Plan internal constructor(internal val parts: List<Any>?, internal val progressive: Uri?, val bytes: Long)

    fun plan(ctx: Context, d: Download): Plan? = runCatching {
        val uri = d.request.uri
        val first = read(ctx, uri, 64 * 1024)
        val head = String(first, 0, minOf(first.size, 4096), Charsets.UTF_8)
        if (!head.contains("#EXTM3U")) {
            if (head.contains("<MPD", true)) return null
            return Plan(null, uri, length(ctx, uri, 0, C.LENGTH_UNSET.toLong()).coerceAtLeast(0))
        }
        val full = read(ctx, uri)
        val parsed = HlsPlaylistParser().parse(actualUri(ctx, uri), ByteArrayInputStream(full))
        val lists = mutableListOf<Pair<Uri, String?>>()
        if (parsed is HlsMultivariantPlaylist) {
            val keys = d.request.streamKeys
            val variants = keys.filter { it.groupIndex == HlsMultivariantPlaylist.GROUP_INDEX_VARIANT }.mapNotNull { parsed.variants.getOrNull(it.streamIndex) }
                .ifEmpty { parsed.variants.take(1) }
            lists += variants.maxByOrNull { it.format.height }!!.url to null
            val audios = keys.filter { it.groupIndex == HlsMultivariantPlaylist.GROUP_INDEX_AUDIO }.mapNotNull { parsed.audios.getOrNull(it.streamIndex) }
                .ifEmpty { if (keys.isEmpty()) parsed.audios else emptyList() }
            audios.filter { it.url != null }.distinctBy { it.url }.forEach { a ->
                lists += a.url!! to (a.format.language ?: com.anees.novamobile.data.model.LangTags.code(a.name))
            }
        } else lists += uri to null

        val parts = lists.mapIndexed { i, (u, lang) ->
            val m = HlsPlaylistParser().parse(actualUri(ctx, u), ByteArrayInputStream(read(ctx, u))) as HlsMediaPlaylist
            if (m.segments.any { it.fullSegmentEncryptionKeyUri != null || it.encryptionIV != null }) { Log.i(TAG, "fast save: encrypted, using Transformer"); return null }
            val segs = mutableListOf<Seg>()
            m.segments.firstOrNull()?.initializationSegment?.let { init ->
                val iu = Uri.parse(UriUtil.resolve(m.baseUri, init.url))
                segs += Seg(iu, init.byteRangeOffset, length(ctx, iu, init.byteRangeOffset, init.byteRangeLength))
            }
            m.segments.forEach { s ->
                val su = Uri.parse(UriUtil.resolve(m.baseUri, s.url))
                segs += Seg(su, s.byteRangeOffset, length(ctx, su, s.byteRangeOffset, s.byteRangeLength))
            }
            if (segs.any { it.length <= 0 }) { Log.i(TAG, "fast save: piece missing from cache"); return null }
            Part(segs, lang, videoAllowed = i == 0)
        }
        Plan(parts, null, parts.sumOf { p -> p.segs.sumOf { it.length } })
    }.onFailure { Log.w(TAG, "fast save not possible: ${it.message}") }.getOrNull()

    /** One pass into [out] (a seekable file: MediaStore / folder entry opened "rw"). */
    fun write(ctx: Context, plan: Plan, out: FileOutputStream, onProgress: (Float) -> Unit) {
        val total = plan.bytes.coerceAtLeast(1)
        var done = 0L
        fun progress(n: Long) { done += n; onProgress((done.toFloat() / total).coerceAtMost(0.99f)) }
        plan.progressive?.let { uri ->
            val ds = NovaDownloads.playbackFactory(ctx).createDataSource()
            try {
                ds.open(DataSpec(uri))
                val b = ByteArray(1 shl 20)
                while (true) { val n = ds.read(b, 0, b.size); if (n == C.RESULT_END_OF_INPUT) break; out.write(b, 0, n); progress(n.toLong()) }
            } finally { ds.close() }
            onProgress(1f); return
        }
        @Suppress("UNCHECKED_CAST") val parts = plan.parts as List<Part>
        mux(ctx, parts, out, ::progress)
        onProgress(1f)
    }

    /** Each part read once by one extractor (all its tracks), samples written as they come. */
    private fun mux(ctx: Context, parts: List<Part>, out: FileOutputStream, progress: (Long) -> Unit) {
        class Src(val ex: MediaExtractor, val src: CacheSource, val tracks: Map<Int, Format>)
        val srcs = parts.map { p ->
            val cs = CacheSource(ctx, p.segs, progress)
            val ex = MediaExtractor().apply { setDataSource(cs) }
            val tracks = HashMap<Int, Format>()
            for (t in 0 until ex.trackCount) {
                val mf = ex.getTrackFormat(t)
                val mime = mf.getString(MediaFormat.KEY_MIME) ?: continue
                if (!(mime.startsWith("audio/") || (p.videoAllowed && mime.startsWith("video/")))) continue
                var fmt = MediaFormatUtil.createFormatFromMediaFormat(mf)
                if (p.lang != null && mime.startsWith("audio/")) fmt = fmt.buildUpon().setLanguage(p.lang).build()
                if (fmt.sampleMimeType !in Mp4Muxer.SUPPORTED_VIDEO_SAMPLE_MIME_TYPES + Mp4Muxer.SUPPORTED_AUDIO_SAMPLE_MIME_TYPES) {
                    Log.w(TAG, "fast save: skipping unsupported $mime"); continue
                }
                ex.selectTrack(t); tracks[t] = fmt
            }
            Src(ex, cs, tracks)
        }
        require(srcs.any { s -> s.tracks.values.any { it.sampleMimeType?.startsWith("video/") == true } }) { "no video track" }
        // Shared clock: TS timestamps don't start at 0
        val base = srcs.mapNotNull { s -> s.ex.sampleTime.takeIf { it >= 0 } }.minOrNull() ?: 0L
        // Copying: the read buffer is reused. Batching: many samples per write, far fewer (slow) storage calls
        val muxer = Mp4Muxer.Builder(out).setSampleCopyingEnabled(true).setSampleBatchingEnabled(true).build()
        val t0 = System.nanoTime(); var writeNs = 0L; var extractNs = 0L
        try {
            val ids = srcs.map { s -> s.tracks.mapValues { (_, f) -> muxer.addTrack(f) } }
            val buf = ByteBuffer.allocateDirect(8 * 1024 * 1024)
            srcs.forEachIndexed { i, s ->
                while (true) {
                    buf.clear()
                    val te = System.nanoTime()
                    val n = s.ex.readSampleData(buf, 0)
                    extractNs += System.nanoTime() - te
                    if (n < 0) break
                    val id = ids[i][s.ex.sampleTrackIndex]
                    if (id != null) {
                        val flags = if (s.ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) C.BUFFER_FLAG_KEY_FRAME else 0
                        buf.position(0); buf.limit(n)
                        val tw = System.nanoTime()
                        muxer.writeSampleData(id, buf, BufferInfo((s.ex.sampleTime - base).coerceAtLeast(0), n, flags))
                        writeNs += System.nanoTime() - tw
                    }
                    s.ex.advance()
                }
            }
        } finally {
            val tc = System.nanoTime()
            muxer.close()
            writeNs += System.nanoTime() - tc
            srcs.forEach { runCatching { it.ex.release() }; it.src.close() }
            val readMs = srcs.sumOf { it.src.readNs } / 1_000_000
            Log.i(TAG, "fast save timing: total ${(System.nanoTime() - t0) / 1_000_000} ms · cache read $readMs ms · extractor ${(extractNs / 1_000_000) - readMs} ms · write ${writeNs / 1_000_000} ms")
        }
    }

    /** The joined pieces of one playlist as a random-access source, read straight from the download cache. */
    private class CacheSource(ctx: Context, private val segs: List<Seg>, private val progress: (Long) -> Unit) : MediaDataSource() {
        private val factory = NovaDownloads.playbackFactory(ctx)
        private val starts = LongArray(segs.size).also { a -> var p = 0L; segs.forEachIndexed { i, s -> a[i] = p; p += s.length } }
        private val total = segs.sumOf { it.length }
        private var open: DataSource? = null
        private var openSeg = -1
        private var openPos = -1L
        private var furthest = 0L

        override fun getSize() = total

        /** Read-ahead: the extractor asks for tiny chunks (TS packets); refill 2 MB at a time with one big read. */
        private val ahead = ByteArray(2 * 1024 * 1024)
        private var aheadStart = -1L
        private var aheadLen = 0

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= total) return -1
            if (position < aheadStart || position >= aheadStart + aheadLen) { if (!fill(position)) return -1 }
            val from = (position - aheadStart).toInt()
            val n = minOf(size, aheadLen - from)
            System.arraycopy(ahead, from, buffer, offset, n)
            if (position + n > furthest) { progress(position + n - furthest); furthest = position + n }
            return n
        }

        /** Fills [ahead] from [position] up to the end of that piece (sequential refills reuse the open source). */
        var readNs = 0L

        private fun fill(position: Long): Boolean {
            val t = System.nanoTime()
            try { return fillInner(position) } finally { readNs += System.nanoTime() - t }
        }

        private fun fillInner(position: Long): Boolean {
            val i = starts.binarySearch(position).let { if (it >= 0) it else -it - 2 }.coerceIn(0, segs.size - 1)
            val seg = segs[i]
            val within = position - starts[i]
            if (open == null || openSeg != i || openPos != position) {
                open?.close()
                open = factory.createDataSource().also { it.open(DataSpec.Builder().setUri(seg.uri).setPosition(seg.offset + within).setLength(seg.length - within).build()) }
                openSeg = i; openPos = position
            }
            val want = minOf(ahead.size.toLong(), seg.length - within).toInt()
            var got = 0
            while (got < want) {
                val n = open!!.read(ahead, got, want - got)
                if (n == C.RESULT_END_OF_INPUT) break
                got += n
            }
            openPos += got
            if (got == 0 || openPos >= starts[i] + seg.length) { open?.close(); open = null }
            aheadStart = position; aheadLen = got
            return got > 0
        }

        override fun close() { runCatching { open?.close() }; open = null }
    }

    // ── Cache helpers ─────────────────────────────────────────────────────────

    /** The address the bytes really came from (a proxy link resolves to its origin): relative playlist entries resolve against it. */
    private fun actualUri(ctx: Context, uri: Uri): Uri {
        val ds = NovaDownloads.playbackFactory(ctx).createDataSource()
        return try { ds.open(DataSpec(uri)); ds.uri ?: uri } catch (e: Exception) { uri } finally { ds.close() }
    }

    private fun read(ctx: Context, uri: Uri, limit: Int = Int.MAX_VALUE): ByteArray {
        val ds = NovaDownloads.playbackFactory(ctx).createDataSource()
        return try {
            runCatching { ds.open(DataSpec(uri)) }.onFailure { Log.w(TAG, "fast save: not in cache: ${uri.toString().take(160)} key=${NovaDownloads.KEYS.buildCacheKey(DataSpec(uri)).take(160)}") }.getOrThrow()
            if (limit == Int.MAX_VALUE) DataSourceUtil.readToEnd(ds)
            else { val b = ByteArray(limit); var got = 0; while (got < limit) { val n = ds.read(b, got, limit - got); if (n == C.RESULT_END_OF_INPUT) break; got += n }; b.copyOf(got) }
        } finally { ds.close() }
    }

    /** Length of a piece: the playlist's byte range, else what the cache knows about it. */
    private fun length(ctx: Context, uri: Uri, offset: Long, rangeLen: Long): Long {
        if (rangeLen != C.LENGTH_UNSET.toLong()) return rangeLen
        NovaDownloads.manager(ctx)
        val key = NovaDownloads.KEYS.buildCacheKey(DataSpec(uri))
        val len = ContentMetadata.getContentLength(NovaDownloads.cache.getContentMetadata(key))
        return if (len > 0) len - offset else NovaDownloads.cache.getCachedLength(key, offset, Long.MAX_VALUE)
    }
}
