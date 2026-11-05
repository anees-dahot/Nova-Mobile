package com.anees.novamobile.transfer

import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.SimpleCache
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Receiver side, fast path: the sender's cached files are copied straight into this phone's cache over
 * several parallel connections (big sequential streams, no per-piece decoding). Afterwards the
 * download manager finds everything already cached and completes without the internet.
 */
@UnstableApi
class BeamBulk(private val peer: BeamLink.Peer, private val cache: SimpleCache) {

    private class Job(val key: String, val pos: Long, val len: Long)

    val done = AtomicLong()

    /**
     * Copies every span of [pieces] this phone doesn't have yet. Returns bytes that could not be copied
     * (0 = everything arrived). Pieces that fail are left for the normal download (other phone / internet).
     */
    fun copy(pieces: List<Beam.Piece>, connections: Int = CONNECTIONS): Long {
        // Content length + real address first: playlists resolve their relative links against it
        pieces.forEach { p ->
            val m = ContentMetadataMutations()
            if (p.length >= 0) ContentMetadataMutations.setContentLength(m, p.length)
            p.redirect?.let { ContentMetadataMutations.setRedirectedUri(m, Uri.parse(it)) }
            runCatching { cache.applyContentMetadataMutations(p.key, m) }
        }
        val jobs = pieces.flatMap { p -> p.spans.map { Job(p.key, it[0], it[1]) } }
            .filter { it.len > 0 }
            .filter { j -> cache.getCachedLength(j.key, j.pos, j.len) < j.len }
            .sortedByDescending { it.len }                     // big ones first keeps every connection busy to the end
        if (jobs.isEmpty()) return 0
        val queue = ConcurrentLinkedQueue(jobs)
        val failed = AtomicLong()
        val fatal = AtomicReference<Throwable?>()
        val threads = (0 until minOf(connections, jobs.size)).map { i ->
            Thread({
                var conn: Conn? = null
                try {
                    while (true) {
                        if (BeamLink.peer !== peer) break       // cancelled
                        val j = queue.poll() ?: break
                        var tries = 0
                        while (true) {
                            try {
                                if (conn == null) conn = Conn(peer)
                                fetch(conn, j)
                                break
                            } catch (e: IOException) {
                                runCatching { conn?.close() }; conn = null
                                if (++tries >= 3) { failed.addAndGet(j.len); Log.w(TAG, "span ${j.key}@${j.pos} not copied: ${e.message}"); break }
                            }
                        }
                    }
                } catch (t: Throwable) {
                    fatal.compareAndSet(null, t)
                } finally {
                    runCatching { conn?.close() }
                }
            }, "beam-bulk-$i").apply { start() }
        }
        threads.forEach { it.join() }
        fatal.get()?.let { Log.w(TAG, "bulk copy stopped", it) }
        return failed.get() + queue.sumOf { it.len }
    }

    /** One span into the cache: hole lock → file → commit, the same steps the cache's own writer uses. */
    private fun fetch(c: Conn, j: Job) {
        // Null: someone else is writing this range right now → leave it to the regular download
        val hole = cache.startReadWriteNonBlocking(j.key, j.pos, j.len) ?: return
        if (hole.isCached) return                                  // already here (a cached span isn't locked)
        try {
            val holeLen = if (hole.length == androidx.media3.common.C.LENGTH_UNSET.toLong()) Long.MAX_VALUE else hole.length
            if (hole.position != j.pos || holeLen < j.len) return  // partly here: the download fills the gap
            val len = c.request(j)
            if (len != j.len) { c.skip(len); throw IOException("size changed ($len vs ${j.len})") }
            val file = cache.startFile(j.key, j.pos, len)
            var ok = false
            try {
                RandomAccessFile(file, "rw").use { raf -> c.bodyTo(raf.channel, len) }
                cache.commitFile(file, len)
                ok = true
            } finally {
                if (!ok) runCatching { file.delete() }
            }
        } finally {
            cache.releaseHoleSpan(hole)
        }
    }

    /** Persistent connection to the sender speaking the same small HTTP as [BeamServer]. */
    private inner class Conn(p: BeamLink.Peer) {
        val ch: SocketChannel = SocketChannel.open().apply {
            socket().receiveBufferSize = 4 shl 20
            socket().tcpNoDelay = true
            p.network?.bindSocket(socket())
            socket().connect(InetSocketAddress(p.host, p.port), 6000)
            socket().soTimeout = 60_000
        }
        private val token = p.token
        private val head = ByteBuffer.allocate(64 * 1024)
        private var leftover: ByteBuffer? = null

        /** Sends the request and reads the response headers; returns the body length. */
        fun request(j: Job): Long {
            val q = "GET /span?k=${Uri.encode(j.key)}&p=${j.pos} HTTP/1.1\r\nHost: beam\r\nX-Beam-Token: $token\r\n\r\n"
            val b = ByteBuffer.wrap(q.toByteArray())
            while (b.hasRemaining()) ch.write(b)
            head.clear()
            // Read until the blank line; whatever follows belongs to the body
            while (true) {
                if (!head.hasRemaining()) throw IOException("response headers too long")
                if (ch.read(head) < 0) throw IOException("connection closed")
                val end = indexOfBlankLine(head.array(), head.position())
                if (end >= 0) {
                    val text = String(head.array(), 0, end, Charsets.ISO_8859_1)
                    val rest = ByteBuffer.wrap(head.array().copyOfRange(end + 4, head.position()))
                    leftover = rest
                    val code = text.substringAfter(' ').take(3).toIntOrNull() ?: 0
                    val len = Regex("(?i)content-length:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toLong() ?: -1
                    if (code != 200) { skip(len); throw IOException("sender answered $code") }
                    return len
                }
            }
        }

        private val buf: ByteBuffer = ByteBuffer.allocateDirect(1 shl 20)

        /** Writes exactly [len] body bytes into [fc]. */
        fun bodyTo(fc: java.nio.channels.FileChannel, len: Long) {
            var w = 0L
            leftover?.let { l ->
                leftover = null
                l.limit(l.position() + minOf(l.remaining().toLong(), len).toInt())
                while (l.hasRemaining()) w += fc.write(l, w)
                count(w)
            }
            while (w < len) {
                buf.clear()
                if (len - w < buf.capacity()) buf.limit((len - w).toInt())
                val n = ch.read(buf)
                if (n < 0) throw IOException("the other phone stopped sending")
                buf.flip()
                while (buf.hasRemaining()) w += fc.write(buf, w)
                count(n.toLong())
            }
        }

        private fun count(n: Long) { done.addAndGet(n); peer.bytes.addAndGet(n) }

        /** Reads and drops [len] body bytes (unknown length: the connection can't be reused → close). */
        fun skip(len: Long) {
            if (len < 0) { close(); throw IOException("connection reset") }
            var left = len - (leftover?.remaining() ?: 0).toLong().also { leftover = null }
            while (left > 0) { buf.clear(); buf.limit(minOf(buf.capacity().toLong(), left).toInt()); val n = ch.read(buf); if (n < 0) break; left -= n }
        }

        fun close() = runCatching { ch.close() }
    }

    private fun indexOfBlankLine(a: ByteArray, n: Int): Int {
        for (i in 0..n - 4) if (a[i] == '\r'.code.toByte() && a[i + 1] == '\n'.code.toByte() && a[i + 2] == '\r'.code.toByte() && a[i + 3] == '\n'.code.toByte()) return i
        return -1
    }

    companion object {
        private const val TAG = "Beam"
        const val CONNECTIONS = 6
    }
}
