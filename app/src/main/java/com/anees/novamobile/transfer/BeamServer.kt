package com.anees.novamobile.transfer

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.PlaceholderDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.offline.Download
import com.anees.novamobile.download.DownloadMeta
import com.anees.novamobile.download.NovaDownloads
import com.google.gson.Gson
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.io.RandomAccessFile
import java.net.SocketException
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Sender side: a small HTTP/1.1 server (keep-alive) that hands out finished downloads straight from
 * the media cache. Every request must carry the QR's token.
 *
 *  GET /list                 offered downloads (json)
 *  GET /c?k=<key>&p=&l=      bytes of one cached piece (by cache key)
 *  GET /f?id=<id>&i=<n>      sidecar subtitle file n of a download
 */
@UnstableApi
class BeamServer(private val ctx: Context, private val token: String) {

    val bytesSent = AtomicLong()
    val clients = AtomicInteger()
    @Volatile var lastRequestAt = 0L; private set

    private var server: ServerSocketChannel? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "beam-conn").apply { isDaemon = true } }
    private val gson = Gson()
    /** Piece lists per download id: working them out walks the whole playlist, so do it once. */
    private val manifests = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun start(port: Int = Beam.PORT): Int {
        val s = ServerSocketChannel.open()
        s.socket().reuseAddress = true
        s.socket().receiveBufferSize = 4 shl 20
        runCatching { s.bind(InetSocketAddress(port), 64) }.getOrElse { s.bind(InetSocketAddress(0), 64) }
        server = s
        Thread({
            while (s.isOpen) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                pool.execute { serve(c) }
            }
        }, "beam-accept").apply { isDaemon = true }.start()
        Log.i(TAG, "listening on ${s.socket().localPort}")
        return s.socket().localPort
    }

    fun stop() {
        runCatching { server?.close() }
        pool.shutdownNow()
    }

    private fun offers(): List<Download> = NovaDownloads.all(ctx).filter { it.state == Download.STATE_COMPLETED && DownloadMeta.of(it) != null }

    private fun serve(ch: SocketChannel) {
        clients.incrementAndGet()
        try {
            ch.socket().tcpNoDelay = true
            ch.socket().sendBufferSize = 4 shl 20
            ch.socket().soTimeout = 120_000
            val input = BufferedInputStream(Channels.newInputStream(ch), 16 * 1024)
            val out = BufferedOutputStream(Channels.newOutputStream(ch), 64 * 1024)
            while (ch.isOpen) {
                val req = readRequest(input) ?: break
                lastRequestAt = System.currentTimeMillis()
                if (req.headers["x-beam-token"] != token) { respond(out, 403, "text/plain", "bad token".toByteArray()); out.flush(); break }
                val u = Uri.parse("http://x" + req.path)
                when (u.path) {
                    "/hello" -> respond(out, 200, "application/json", gson.toJson(mapOf("app" to "nova", "count" to offers().size)).toByteArray())
                    "/list" -> respond(out, 200, "application/json", gson.toJson(offers().map { offer(it) }).toByteArray())
                    "/manifest" -> manifest(out, u)
                    "/span" -> { span(out, ch, u); continue }
                    "/c" -> piece(out, u)
                    "/f" -> subtitle(out, u)
                    else -> respond(out, 404, "text/plain", "not found".toByteArray())
                }
                out.flush()
            }
        } catch (_: java.io.IOException) {
        } catch (e: Exception) {
            Log.w(TAG, "connection ended: ${e.message}")
        } finally {
            runCatching { ch.close() }
            clients.decrementAndGet()
        }
    }

    /**
     * Every cached piece a download uses (playlists, segments, keys, subtitle tracks): the download
     * is walked once against the cache with a key factory that writes down each key it is asked for.
     */
    private fun manifest(out: OutputStream, u: Uri) {
        val id = u.getQueryParameter("id")
        val d = offers().firstOrNull { it.request.id == id } ?: return respond(out, 404, "text/plain", "no such download".toByteArray())
        val json = manifests.getOrPut(d.request.id) {
            val cache = NovaDownloads.cache
            val keys = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
            val rec = CacheKeyFactory { spec -> NovaDownloads.KEYS.buildCacheKey(spec).also { keys += it } }
            val f = CacheDataSource.Factory().setCache(cache).setCacheKeyFactory(rec)
                .setUpstreamDataSourceFactory(PlaceholderDataSource.FACTORY).setCacheWriteDataSinkFactory(null)
            runCatching { DefaultDownloaderFactory(f, Runnable::run).createDownloader(d.request).download(null) }
                .onFailure { Log.w(TAG, "walk of ${d.request.id} incomplete: ${it.message}") }
            val entries = keys.mapNotNull { k ->
                val spans = cache.getCachedSpans(k).filter { it.isCached }.sortedBy { it.position }
                if (spans.isEmpty()) return@mapNotNull null
                val meta = cache.getContentMetadata(k)
                Beam.Piece(k, ContentMetadata.getContentLength(meta), ContentMetadata.getRedirectedUri(meta)?.toString(),
                    spans.map { longArrayOf(it.position, it.length) })
            }
            Log.i(TAG, "manifest ${d.request.id}: ${entries.size} keys, ${entries.sumOf { e -> e.spans.sumOf { it[1] } } shr 20} MB")
            gson.toJson(entries)
        }
        respond(out, 200, "application/json", json.toByteArray())
    }

    /** One cached span, straight from its file to the socket (no copies through the app). */
    private fun span(out: BufferedOutputStream, ch: SocketChannel, u: Uri) {
        val key = u.getQueryParameter("k"); val pos = u.getQueryParameter("p")?.toLongOrNull()
        val sp = if (key != null && pos != null) NovaDownloads.cache.getCachedSpans(key).firstOrNull { it.isCached && it.position == pos } else null
        val file = sp?.file
        if (sp == null || file == null || !file.exists()) { respond(out, 404, "text/plain", "missing".toByteArray()); out.flush(); return }
        RandomAccessFile(file, "r").use { raf ->
            val fc = raf.channel
            val n = minOf(sp.length, fc.size())
            header(out, 200, "application/octet-stream", n)
            out.flush()
            var done = 0L
            while (done < n) {
                val w = fc.transferTo(done, n - done, ch)
                if (w <= 0) { if (!ch.isOpen) throw java.io.IOException("closed"); continue }
                done += w
                bytesSent.addAndGet(w)
            }
        }
    }

    private fun offer(d: Download): Beam.Offer {
        val m = DownloadMeta.of(d)!!
        val subs = (m.subtitles.orEmpty() + listOfNotNull(m.subtitle)).distinct()
        return Beam.Offer(
            d.request.id, d.request.uri.toString(), d.request.mimeType,
            d.request.streamKeys.map { intArrayOf(it.periodIndex, it.groupIndex, it.streamIndex) },
            d.request.customCacheKey, String(d.request.data), d.bytesDownloaded, subs.size,
        )
    }

    /** Streams one cached piece. Only cached bytes: a missing piece is a 404 (the receiver then uses the internet). */
    private fun piece(out: OutputStream, u: Uri) {
        val key = u.getQueryParameter("k") ?: return respond(out, 400, "text/plain", "no key".toByteArray())
        val pos = u.getQueryParameter("p")?.toLongOrNull() ?: 0L
        val len = u.getQueryParameter("l")?.toLongOrNull() ?: C.LENGTH_UNSET.toLong()
        NovaDownloads.manager(ctx)
        val cache = NovaDownloads.cache
        // Exactly what's cached for this key, from pos: unknown content length → serve the contiguous cached run
        val cached = cache.getCachedLength(key, pos, if (len > 0) len else Long.MAX_VALUE)
        if (cached <= 0) {
            Log.w(TAG, "missing piece $key @$pos+$len")
            return respond(out, 404, "text/plain", "missing".toByteArray())
        }
        val n = if (len > 0) minOf(len, cached) else cached
        if (len > 0 && n < len) return respond(out, 404, "text/plain", "partly missing".toByteArray())
        val src = CacheDataSource(cache, PlaceholderDataSource.INSTANCE, 0)
        try {
            src.open(DataSpec.Builder().setUri(Uri.parse(key)).setKey(key).setPosition(pos).setLength(n).build())
            // Where the bytes really came from (after redirects / proxy unwrapping): relative links in playlists resolve against it
            val real = androidx.media3.datasource.cache.ContentMetadata.getRedirectedUri(cache.getContentMetadata(key))
            header(out, 200, "application/octet-stream", n, real?.toString())
            val buf = ByteArray(256 * 1024)
            var left = n
            while (left > 0) {
                val r = src.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (r == C.RESULT_END_OF_INPUT) break
                out.write(buf, 0, r)
                left -= r
                bytesSent.addAndGet(r.toLong())
            }
            if (left > 0) throw SocketException("piece ended early")   // length was promised: drop the connection
        } finally {
            runCatching { src.close() }
        }
    }

    private fun subtitle(out: OutputStream, u: Uri) {
        val id = u.getQueryParameter("id"); val i = u.getQueryParameter("i")?.toIntOrNull()
        val d = offers().firstOrNull { it.request.id == id }
        val m = d?.let { DownloadMeta.of(it) }
        val f = m?.let { (it.subtitles.orEmpty() + listOfNotNull(it.subtitle)).distinct().getOrNull(i ?: -1) }?.let { File(it) }
        if (f == null || !f.exists()) return respond(out, 404, "text/plain", "missing".toByteArray())
        val bytes = f.readBytes()
        respond(out, 200, "application/octet-stream", bytes)
        bytesSent.addAndGet(bytes.size.toLong())
    }

    // ── Tiny HTTP ────────────────────────────────────────────────────────────

    private class Req(val path: String, val headers: Map<String, String>)

    private fun readRequest(input: InputStream): Req? {
        val line = readLine(input) ?: return null
        val parts = line.split(' ')
        if (parts.size < 2) return null
        val headers = mutableMapOf<String, String>()
        while (true) {
            val h = readLine(input) ?: return null
            if (h.isEmpty()) break
            val c = h.indexOf(':')
            if (c > 0) headers[h.substring(0, c).trim().lowercase()] = h.substring(c + 1).trim()
        }
        return Req(parts[1], headers)
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 16 * 1024) return null
        }
    }

    private fun header(out: OutputStream, code: Int, type: String, length: Long, realUri: String? = null) {
        val reason = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; else -> "Not Found" }
        val extra = realUri?.let { "X-Beam-Uri: ${Uri.encode(it)}\r\n" }.orEmpty()
        out.write("HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: $length\r\n${extra}Connection: keep-alive\r\n\r\n".toByteArray())
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: ByteArray) {
        header(out, code, type, body.size.toLong())
        out.write(body)
    }

    companion object { private const val TAG = "Beam" }
}
