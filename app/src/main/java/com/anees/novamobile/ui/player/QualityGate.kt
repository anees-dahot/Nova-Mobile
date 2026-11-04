package com.anees.novamobile.ui.player

import com.anees.novamobile.data.api.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Pre-play quality gate (Indflix-style): a source whose HLS master playlist tops out below
 * [MIN_HEIGHT] is skipped while better sources remain, so a 480p-only result doesn't beat
 * a source that would have answered with 720p+ a moment later.
 *
 * Only *known* low streams are rejected — anything unreadable or non-HLS is accepted and
 * judged by playback instead (a wrong rejection would mean no source at all).
 */
object QualityGate {

    /** Streams below this are filtered out; up to the Settings cap (720p/1080p/4K). */
    const val MIN_HEIGHT = 720

    private const val PEEK_BYTES = 256 * 1024L
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0 Safari/537.36"
    private val RES_RE = Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE)

    /** Highest variant height in the master playlist, or -1 when it can't be told (media playlist, mp4, error). */
    fun maxHeight(s: WebStreamSniffer.Sniffed): Int = maxHeightBlocking(s)

    private fun maxHeightBlocking(s: WebStreamSniffer.Sniffed): Int = runCatching {
        val req = Request.Builder().url(s.url).apply {
            header("User-Agent", s.userAgent.ifBlank { UA })
            s.headers.forEach { (k, v) -> header(k, v) }
        }.build()
        RetrofitClient.base.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return -1
            // Peek instead of reading it all: a media playlist for a long VOD can be large
            val src = r.body?.source() ?: return -1
            src.request(PEEK_BYTES)
            val head = src.buffer.snapshot().utf8()
            if (!head.contains("#EXT-X-STREAM-INF")) return -1   // single-variant playlist / not HLS
            RES_RE.findAll(head).maxOfOrNull { it.groupValues[1].toIntOrNull() ?: 0 } ?: -1
        }
    }.getOrDefault(-1)

    /** Cheap enough to call from the source search: one small HTTP GET on a dispatcher thread. */
    suspend fun maxHeightAsync(s: WebStreamSniffer.Sniffed): Int =
        s.height ?: withContext(Dispatchers.IO) { maxHeight(s) }.takeIf { it > 0 } ?: AudioProbe.info(s).height   // shallow: never slows the source search
}
