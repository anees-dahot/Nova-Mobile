package com.anees.novamobile.ui.player

import android.media.MediaExtractor
import android.media.MediaFormat
import com.anees.novamobile.data.api.RetrofitClient
import com.anees.novamobile.data.model.LangTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * What a found stream really carries — audio languages, number of audio tracks, picture height —
 * read from the stream itself, not guessed from the source's label:
 *  - HLS master: #EXT-X-MEDIA audio renditions + RESOLUTION
 *  - HLS without renditions (audio muxed into the video) or a single-quality playlist: the first
 *    segment (or fMP4 init segment) is fetched and its tracks read (TS PMT language tags, MediaExtractor)
 *  - DASH: AdaptationSets
 *  - MP4: track index (mdhd) when it's at the start, else MediaExtractor straight from the URL
 * Used to pick the stream with the most languages and to list every language/quality sources offer.
 */
object AudioProbe {

    /** [audioTracks]/[height]: -1 = couldn't tell. [langs]: two-letter codes of labelled audio tracks. */
    data class Info(val langs: List<String>, val audioTracks: Int, val height: Int,
                    /** Every picture height offered (HLS master variants); empty = only [height]. */
                    val heights: List<Int> = emptyList(),
                    /** Running time in seconds (HLS segment total, MP4 index, DASH duration); -1 = couldn't tell. */
                    val durationSec: Int = -1) {
        companion object { val UNKNOWN = Info(emptyList(), -1, -1) }
    }

    private const val PEEK_BYTES = 256 * 1024L
    private const val SEGMENT_BYTES = 1_500_000L
    private const val CALL_S = 4L
    private const val PLAYLIST_BYTES = 3_000_000L
    private val EXTINF = Regex("""#EXTINF:\s*([\d.]+)""")
    private val ISO_DUR = Regex("""PT(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?""")
    private val MPD_TAG = Regex("""<MPD\b[^>]*>""", RegexOption.IGNORE_CASE)
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0 Safari/537.36"
    private val HLS_AUDIO = Regex("""#EXT-X-MEDIA:[^\n]*TYPE=AUDIO[^\n]*""", RegexOption.IGNORE_CASE)
    private val DASH_SET = Regex("""<AdaptationSet\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val RES = Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE)
    private val DASH_H = Regex("""\bheight\s*=\s*"(\d+)"""", RegexOption.IGNORE_CASE)
    private fun attr(line: String, name: String) =
        Regex("""\b$name\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)

    /** url → (deep?, info): a deep result answers shallow questions too. */
    private val cache = HashMap<String, Pair<Boolean, Info>>()
    /** MediaExtractor reads over HTTP with no cancel: it runs here and is abandoned after a deadline. */
    private val extractorPool = Executors.newCachedThreadPool()

    /** Two-letter codes, or empty when nothing in the stream says (the source's declared language as a last word). */
    suspend fun langs(s: WebStreamSniffer.Sniffed): List<String> = info(s).langs

    /**
     * [deep] = false (source search, playback): playlist-level only, one small request.
     * [deep] = true (download sheet): also reads the first segment / MP4 index, up to ~1.5 MB and a few seconds.
     */
    suspend fun info(s: WebStreamSniffer.Sniffed, deep: Boolean = false): Info {
        synchronized(cache) { cache[s.url] }?.let { (wasDeep, i) -> if (wasDeep || !deep) return i }
        val read = withTimeoutOrNull(if (deep) 12_000 else 4_500) { withContext(Dispatchers.IO) { runCatching { read(s, deep) }.getOrNull() } }
            // Deep read ran out of time (slow segment host): the playlist-level answer still has qualities and length
            ?: (if (deep) withTimeoutOrNull(6_000) { withContext(Dispatchers.IO) { runCatching { read(s, false) }.getOrNull() } } else null)
            ?: Info.UNKNOWN
        // The stream can't tell: trust what the source declared
        val r = read.copy(
            langs = read.langs.ifEmpty { listOfNotNull(s.audioLang) },
            height = read.height.takeIf { it > 0 } ?: s.height ?: -1,
            audioTracks = if (read.audioTracks < 0 && s.audioLang != null) 1 else read.audioTracks,
        )
        synchronized(cache) { if (cache.size > 300) cache.clear(); cache[s.url] = deep to r }
        return r
    }

    /** Wanted language first, then how many languages: the richest stream that has yours. */
    fun score(langs: List<String>, want: String): Int =
        (if (want != "orig" && want in langs) 100 else 0) + langs.size

    // ── Reading ───────────────────────────────────────────────────────────────

    private fun headers(s: WebStreamSniffer.Sniffed) = s.headers + ("User-Agent" to s.userAgent.ifBlank { UA })

    /** First [limit] bytes of [url] (Range request; servers that ignore it are cut off after [limit]). */
    private fun fetch(url: String, s: WebStreamSniffer.Sniffed, limit: Long, range: Boolean = false): ByteArray? = runCatching {
        val req = Request.Builder().url(url).apply {
            headers(s).forEach { (k, v) -> header(k, v) }
            if (range) header("Range", "bytes=0-${limit - 1}")
        }.build()
        RetrofitClient.base.newCall(req).apply { timeout().timeout(CALL_S, TimeUnit.SECONDS) }.execute().use { r ->
            if (!r.isSuccessful) return null
            val src = r.body?.source() ?: return null
            runCatching { src.request(limit) }
            src.buffer.snapshot().let { b -> if (b.size > limit) b.substring(0, limit.toInt()) else b }.toByteArray()
        }
    }.getOrNull()

    private fun read(s: WebStreamSniffer.Sniffed, deep: Boolean): Info {
        val bytes = fetch(s.url, s, PEEK_BYTES) ?: return Info.UNKNOWN
        if (isMp4(bytes)) return mp4(bytes, s, deep)
        if (isMkv(bytes)) return mkv(bytes)
        val head = String(bytes, Charsets.UTF_8)
        return when {
            head.contains("#EXT-X-STREAM-INF") -> hlsMaster(head, s, deep).let { i ->
                // Running time: the first variant's segment list (text only, cheap)
                val variant = head.lineSequence().map { it.trim() }.dropWhile { !it.startsWith("#EXT-X-STREAM-INF") }
                    .drop(1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") }?.let { resolve(s.url, it) }
                i.copy(durationSec = variant?.let { hlsDuration(null, it, s) } ?: -1)
            }
            head.contains("#EXTINF") -> (if (deep) hlsMedia(head, s.url, s) else Info.UNKNOWN).copy(durationSec = hlsDuration(head, s.url, s))
            head.contains("<MPD", true) -> dash(head)
            else -> Info.UNKNOWN
        }
    }

    /** Sum of #EXTINF of a complete (VOD) segment list; fetched in full when the first peek was cut short. */
    private fun hlsDuration(peek: String?, url: String, s: WebStreamSniffer.Sniffed): Int {
        val text = peek?.takeIf { it.contains("#EXT-X-ENDLIST") }
            ?: fetch(url, s, PLAYLIST_BYTES)?.let { String(it, Charsets.UTF_8) } ?: return -1
        if (!text.contains("#EXT-X-ENDLIST")) return -1   // live or cut off: a partial sum would mislead
        val total = EXTINF.findAll(text).sumOf { it.groupValues[1].toDoubleOrNull() ?: 0.0 }
        return total.toInt().takeIf { it > 0 } ?: -1
    }

    /** "PT2H1M3.5S" → seconds. */
    private fun isoDuration(v: String?): Int {
        val m = v?.let { ISO_DUR.find(it) } ?: return -1
        val (h, mi, se) = m.destructured
        return ((h.toIntOrNull() ?: 0) * 3600 + (mi.toIntOrNull() ?: 0) * 60 + (se.toDoubleOrNull() ?: 0.0)).toInt().takeIf { it > 0 } ?: -1
    }

    /** Movie length from the mvhd box (timescale + duration), -1 when it isn't in [b]. */
    private fun mvhd(b: ByteArray): Int {
        fun u32(i: Int) = ((b[i].toLong() and 0xff) shl 24) or ((b[i + 1].toLong() and 0xff) shl 16) or ((b[i + 2].toLong() and 0xff) shl 8) or (b[i + 3].toLong() and 0xff)
        var j = 0
        while (j < b.size - 36) {
            if (b[j] == 'm'.code.toByte() && b[j + 1] == 'v'.code.toByte() && b[j + 2] == 'h'.code.toByte() && b[j + 3] == 'd'.code.toByte()) {
                val v1 = b[j + 4].toInt() == 1
                val scale = if (v1) u32(j + 24) else u32(j + 16)
                val dur = if (v1) (u32(j + 28) shl 32) or u32(j + 32) else u32(j + 20)
                return if (scale > 0 && dur > 0) (dur / scale).toInt() else -1
            }
            j++
        }
        return -1
    }

    private fun hlsMaster(head: String, s: WebStreamSniffer.Sniffed, deep: Boolean): Info {
        val renditions = HLS_AUDIO.findAll(head).map { it.value }.toList()
        val langs = renditions.mapNotNull { code(attr(it, "LANGUAGE")) ?: LangTags.code(attr(it, "NAME")) }.distinct()
        val all = RES.findAll(head).mapNotNull { it.groupValues[1].toIntOrNull() }.filter { it > 0 }.distinct().sorted().toList()
        val height = all.maxOrNull() ?: -1
        // Track count: one per language plus each unlabelled rendition (two Spanish variants count once)
        val tracks = (langs.size + renditions.count { (code(attr(it, "LANGUAGE")) ?: LangTags.code(attr(it, "NAME"))) == null }).takeIf { renditions.isNotEmpty() } ?: -1
        if (renditions.isNotEmpty() && height > 0 || !deep) return Info(langs, tracks, height, all)
        // Audio muxed into each variant (or no RESOLUTION): read a variant's first segment
        val variant = head.lineSequence().map { it.trim() }.dropWhile { !it.startsWith("#EXT-X-STREAM-INF") }
            .drop(1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") }?.let { resolve(s.url, it) }
            ?: return Info(langs, renditions.size.takeIf { it > 0 } ?: -1, height, all)
        val media = fetch(variant, s, PEEK_BYTES)?.let { String(it, Charsets.UTF_8) }
        val seg = media?.let { hlsMedia(it, variant, s) } ?: Info.UNKNOWN
        return if (renditions.isNotEmpty()) Info(langs, tracks, height.takeIf { it > 0 } ?: seg.height, all)
            else Info(seg.langs, seg.audioTracks, height.takeIf { it > 0 } ?: seg.height, all)
    }

    /** Single-quality playlist: tracks of its first segment (fMP4: init segment + first fragment). */
    private fun hlsMedia(media: String, base: String, s: WebStreamSniffer.Sniffed): Info {
        if (Regex("""#EXT-X-KEY:[^\n]*METHOD=(AES-128|SAMPLE-AES)""").containsMatchIn(media)) return Info.UNKNOWN   // encrypted segments
        val init = Regex("""#EXT-X-MAP:[^\n]*URI="([^"]+)"""").find(media)?.groupValues?.get(1)?.let { resolve(base, it) }
        val first = media.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }?.let { resolve(base, it) }
            ?: return Info.UNKNOWN
        val data = if (init != null) (fetch(init, s, PEEK_BYTES) ?: return Info.UNKNOWN) + (fetch(first, s, SEGMENT_BYTES, true) ?: ByteArray(0))
            else unwrapTs(fetch(first, s, SEGMENT_BYTES, true) ?: return Info.UNKNOWN)
        return if (init == null && data.isNotEmpty() && data[0] == 0x47.toByte()) ts(data) else extract(data)
    }

    private fun dash(head: String): Info {
        val sets = DASH_SET.findAll(head).map { it.value }.toList()
        val audio = sets.filter { attr(it, "contentType") == "audio" || attr(it, "mimeType")?.startsWith("audio") == true }
        val langs = audio.mapNotNull { code(attr(it, "lang")) ?: LangTags.code(attr(it, "label")) }.distinct()
        val height = DASH_H.findAll(head).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull() ?: -1
        return Info(langs, audio.size.takeIf { it > 0 } ?: -1, height,
            durationSec = isoDuration(MPD_TAG.find(head)?.value?.let { attr(it, "mediaPresentationDuration") }))
    }

    // ── Matroska (MKV) ────────────────────────────────────────────────────────

    private fun isMkv(b: ByteArray) = b.size > 4 && b[0] == 0x1A.toByte() && b[1] == 0x45.toByte() && b[2] == 0xDF.toByte() && b[3] == 0xA3.toByte()

    /**
     * Release files (4KHDHub, HDHub4u, MoviesDrive…) are MKVs: their header states the length (Info → Duration ×
     * TimecodeScale), every audio track's language ("hin", "eng") and the picture height — all in the first bytes.
     */
    private fun mkv(b: ByteArray): Info {
        fun u(i: Int) = b[i].toInt() and 0xff
        /** EBML variable-size integer at [i]: (value, length). */
        fun vint(i: Int): Pair<Long, Int>? {
            if (i >= b.size) return null
            val first = u(i); var len = 1; var mask = 0x80
            while (len <= 8 && first and mask == 0) { len++; mask = mask shr 1 }
            if (len > 8 || i + len > b.size) return null
            var v = (first and (mask - 1)).toLong()
            for (k in 1 until len) v = (v shl 8) or u(i + k).toLong()
            return v to len
        }
        fun find(id: IntArray, from: Int, to: Int): Int {
            var i = from
            while (i <= to - id.size) { if (id.indices.all { u(i + it) == id[it] }) return i; i++ }
            return -1
        }
        fun uint(at: Int, len: Int) = (0 until len).fold(0L) { acc, k -> (acc shl 8) or u(at + k).toLong() }
        val end = b.size
        // Length: TimecodeScale (ns per tick, default 1 ms) × Duration (float ticks)
        var scale = 1_000_000L
        find(intArrayOf(0x2A, 0xD7, 0xB1), 0, minOf(end, 200_000)).takeIf { it >= 0 }?.let { p -> vint(p + 3)?.let { (n, l) -> if (n in 1..8) scale = uint(p + 3 + l, n.toInt()) } }
        var sec = -1
        find(intArrayOf(0x44, 0x89), 0, minOf(end, 200_000)).takeIf { it >= 0 }?.let { p -> vint(p + 2)?.let { (n, l) ->
            val at = p + 2 + l
            val ticks = when (n.toInt()) { 8 -> java.lang.Double.longBitsToDouble(uint(at, 8)); 4 -> java.lang.Float.intBitsToFloat(uint(at, 4).toInt()).toDouble(); else -> -1.0 }
            if (ticks > 0) sec = (ticks * scale / 1e9).toInt()
        } }
        // Tracks: each TrackEntry (0xAE) → type (0x83: 1 video, 2 audio), language (0x22B59C), height (0xBA)
        val langs = mutableListOf<String>(); var audio = 0; var height = -1
        // The first hit is often the SeekHead's pointer to Tracks (SeekID 0x53AB, size 0x84): skip those
        var i = find(intArrayOf(0x16, 0x54, 0xAE, 0x6B), 0, end)
        while (i >= 3 && u(i - 1) == 0x84 && u(i - 2) == 0xAB && u(i - 3) == 0x53) i = find(intArrayOf(0x16, 0x54, 0xAE, 0x6B), i + 4, end)
        if (i >= 0) {
            i += 4; vint(i)?.let { i += it.second }
            var guard = 0
            while (i < end - 2 && guard++ < 64) {
                if (u(i) != 0xAE) { i++; continue }
                val (size, l) = vint(i + 1) ?: break
                val st = i + 1 + l; val en = minOf(end, st + size.toInt())
                if (size <= 0 || st >= end) break
                val type = find(intArrayOf(0x83), st, en).takeIf { it >= 0 && it + 2 < en }?.let { p -> vint(p + 1)?.let { (n, ll) -> uint(p + 1 + ll, n.toInt().coerceIn(1, 2)) } } ?: 0
                if (type == 2L) {
                    audio++
                    find(intArrayOf(0x22, 0xB5, 0x9C), st, en).takeIf { it >= 0 }?.let { p -> vint(p + 3)?.let { (n, ll) ->
                        val tag = String(b, p + 3 + ll, n.toInt().coerceIn(0, 8).coerceAtMost(end - p - 3 - ll), Charsets.ISO_8859_1).trimEnd('\u0000')
                        iso2(tag)?.let { langs += it } } } ?: langs.add("en")   // no Language element = "eng" (Matroska default)
                } else if (type == 1L) {
                    find(intArrayOf(0xBA), st, en).takeIf { it >= 0 }?.let { p -> vint(p + 1)?.let { (n, ll) -> if (n in 1..4) height = maxOf(height, uint(p + 1 + ll, n.toInt()).toInt()) } }
                }
                i = en
            }
        }
        return Info(langs.distinct(), if (audio > 0) audio else -1, height, durationSec = sec)
    }

    // ── MP4 ───────────────────────────────────────────────────────────────────

    private fun isMp4(b: ByteArray) = b.size > 8 && String(b, 4, 4, Charsets.ISO_8859_1).let { it == "ftyp" || it == "moov" || it == "styp" }

    /** Index up front → read it here; at the end of the file → MediaExtractor fetches it by range. */
    private fun mp4(head: ByteArray, s: WebStreamSniffer.Sniffed, deep: Boolean): Info {
        val boxes = mp4Tracks(head)
        if (boxes.isNotEmpty()) {
            val audio = boxes.filter { it.first == "soun" }
            return Info(audio.mapNotNull { it.second }.distinct(), audio.size, boxes.filter { it.first == "vide" }.maxOfOrNull { it.third } ?: -1,
                durationSec = mvhd(head))
        }
        if (!deep) return Info.UNKNOWN
        // Index at the end of the file: read the last few MB, else let MediaExtractor find it
        tail(s)?.let { t -> mp4Tracks(t).takeIf { it.isNotEmpty() }?.let { boxes ->
            val audio = boxes.filter { it.first == "soun" }
            return Info(audio.mapNotNull { it.second }.distinct(), audio.size, boxes.filter { it.first == "vide" }.maxOfOrNull { it.third } ?: -1,
                durationSec = mvhd(t))
        } }
        return extractUrl(s)
    }

    private fun tail(s: WebStreamSniffer.Sniffed): ByteArray? = runCatching {
        val req = Request.Builder().url(s.url).apply { headers(s).forEach { (k, v) -> header(k, v) }; header("Range", "bytes=-4000000") }.build()
        RetrofitClient.base.newCall(req).apply { timeout().timeout(6, TimeUnit.SECONDS) }.execute().use { r ->
            if (r.code != 206) null else r.body?.bytes()
        }
    }.getOrNull()

    /** (handler, language, height) per track from moov boxes in [b]. */
    private fun mp4Tracks(b: ByteArray): List<Triple<String, String?, Int>> {
        fun at(i: Int, tag: String) = i >= 0 && i + 4 <= b.size && (0 until 4).all { b[i + it] == tag[it].code.toByte() }
        fun u32(i: Int) = ((b[i].toLong() and 0xff) shl 24) or ((b[i + 1].toLong() and 0xff) shl 16) or ((b[i + 2].toLong() and 0xff) shl 8) or (b[i + 3].toLong() and 0xff)
        val out = mutableListOf<Triple<String, String?, Int>>()
        var i = 0
        while (i < b.size - 8) {
            if (!at(i, "trak")) { i++; continue }
            val end = minOf(b.size, i - 4 + u32(i - 4).toInt().coerceAtLeast(8))
            var lang: String? = null; var handler = ""; var height = -1
            var j = i
            while (j < end - 8) {
                when {
                    at(j, "tkhd") -> { val v1 = b[j + 4].toInt() == 1; val hi = j + 4 + (if (v1) 88 else 76) + 4; if (hi + 4 <= b.size) height = (u32(hi) shr 16).toInt() }
                    at(j, "mdhd") -> {
                        val li = j + 8 + if (b[j + 4].toInt() == 1) 28 else 16
                        if (li + 2 <= b.size) {
                            val p = ((b[li].toInt() and 0xff) shl 8) or (b[li + 1].toInt() and 0xff)
                            lang = iso2(String(charArrayOf(((p shr 10 and 31) + 0x60).toChar(), ((p shr 5 and 31) + 0x60).toChar(), ((p and 31) + 0x60).toChar())))
                        }
                    }
                    at(j, "hdlr") -> if (j + 16 <= b.size) handler = String(b, j + 12, 4, Charsets.ISO_8859_1)
                }
                j++
            }
            if (handler == "soun" || handler == "vide") out += Triple(handler, lang, if (handler == "vide") height else -1)
            i = end
        }
        return out
    }

    // ── MPEG-TS ───────────────────────────────────────────────────────────────

    /** Segments disguised as images (PNG/JPEG header in front): skip to the first run of TS sync bytes. */
    private fun unwrapTs(b: ByteArray): ByteArray {
        if (b.isEmpty() || b[0] == 0x47.toByte() || isMp4(b)) return b
        val start = (0 until minOf(b.size - 376, 65_536)).firstOrNull { k -> b[k] == 0x47.toByte() && b[k + 188] == 0x47.toByte() && b[k + 376] == 0x47.toByte() }
        return if (start != null) b.copyOfRange(start, b.size) else b
    }

    /** Languages from the PMT (ISO-639 descriptors) plus MediaExtractor for the picture height. */
    private fun ts(b: ByteArray): Info {
        val audioTypes = setOf(0x03, 0x04, 0x0F, 0x11, 0x81, 0x87, 0x8A)
        var pmtPid = -1
        val streams = mutableListOf<Pair<Int, String?>>()   // stream type → language
        var k = 0
        while (k + 188 <= b.size && (pmtPid < 0 || streams.isEmpty())) {
            if (b[k] != 0x47.toByte()) { k++; continue }
            val pid = ((b[k + 1].toInt() and 0x1f) shl 8) or (b[k + 2].toInt() and 0xff)
            val pusi = b[k + 1].toInt() and 0x40 != 0
            var p = k + 4
            if (b[k + 3].toInt() and 0x20 != 0) p += 1 + (b[k + 4].toInt() and 0xff)   // adaptation field
            if (pusi && p < k + 188) p += 1 + (b[p].toInt() and 0xff)                     // pointer field
            if (pusi && p + 12 < k + 188) {
                if (pid == 0 && b[p].toInt() == 0x00) {
                    pmtPid = ((b[p + 10].toInt() and 0x1f) shl 8) or (b[p + 11].toInt() and 0xff)
                } else if (pid == pmtPid && b[p].toInt() == 0x02) {
                    val secLen = ((b[p + 1].toInt() and 0x0f) shl 8) or (b[p + 2].toInt() and 0xff)
                    val end = minOf(k + 188, p + 3 + secLen - 4)
                    var q = p + 12 + (((b[p + 10].toInt() and 0x0f) shl 8) or (b[p + 11].toInt() and 0xff))
                    while (q + 5 <= end) {
                        val type = b[q].toInt() and 0xff
                        val infoLen = ((b[q + 3].toInt() and 0x0f) shl 8) or (b[q + 4].toInt() and 0xff)
                        var d = q + 5; var lang: String? = null; var ac3 = false
                        while (d + 2 <= minOf(end, q + 5 + infoLen)) {
                            val tag = b[d].toInt() and 0xff; val len = b[d + 1].toInt() and 0xff
                            if (tag == 0x0A && len >= 3 && d + 5 <= b.size) lang = String(b, d + 2, 3, Charsets.ISO_8859_1)
                            if (tag == 0x6A || tag == 0x7A) ac3 = true
                            d += 2 + len
                        }
                        streams += (if (ac3) 0x81 else type) to lang
                        q += 5 + infoLen
                    }
                }
            }
            k += 188
        }
        val audio = streams.filter { it.first in audioTypes }
        val video = extract(b)
        return Info(audio.mapNotNull { a -> a.second?.let { code(it) ?: iso2(it) } }.distinct(),
            audio.size.takeIf { streams.isNotEmpty() } ?: video.audioTracks, video.height)
    }

    // ── MediaExtractor ────────────────────────────────────────────────────────

    /** Tracks of a downloaded chunk (TS or fragmented MP4). */
    private fun extract(b: ByteArray): Info {
        if (b.isEmpty()) return Info.UNKNOWN
        val f = File.createTempFile("probe", if (b[0] == 0x47.toByte()) ".ts" else ".mp4")
        return try { f.writeBytes(b); tracks { it.setDataSource(f.absolutePath) } } finally { f.delete() }
    }

    /** MP4 with its index at the end: MediaExtractor reads it by range over HTTP, abandoned after 6s. */
    private fun extractUrl(s: WebStreamSniffer.Sniffed): Info = runCatching {
        extractorPool.submit<Info> { tracks { it.setDataSource(s.url, headers(s)) } }.get(6, TimeUnit.SECONDS)
    }.getOrDefault(Info.UNKNOWN)

    private fun tracks(open: (MediaExtractor) -> Unit): Info {
        val ex = MediaExtractor()
        return try {
            open(ex)
            val formats = (0 until ex.trackCount).map { ex.getTrackFormat(it) }
            val audio = formats.filter { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            val height = formats.filter { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                .mapNotNull { runCatching { it.getInteger(MediaFormat.KEY_HEIGHT) }.getOrNull() }.maxOrNull() ?: -1
            val langs = audio.mapNotNull { a -> runCatching { a.getString(MediaFormat.KEY_LANGUAGE) }.getOrNull()?.let { code(it) ?: iso2(it) } }.distinct()
            val dur = formats.mapNotNull { f -> runCatching { f.getLong(MediaFormat.KEY_DURATION) }.getOrNull() }.maxOrNull()
            Info(langs, if (formats.isEmpty()) -1 else audio.size, height, durationSec = dur?.let { (it / 1_000_000).toInt() }?.takeIf { it > 0 } ?: -1)
        } catch (e: Exception) { Info.UNKNOWN } finally { ex.release() }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun resolve(base: String, uri: String): String =
        runCatching { java.net.URI(base).resolve(uri.replace(" ", "%20")).toString() }.getOrDefault(uri)

    private fun iso2(iso3: String): String? = if (iso3 == "und" || iso3 == "```") null else LangTags.norm(iso3)

    private fun code(tag: String?): String? = LangTags.norm(tag)
}
