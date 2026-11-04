package com.anees.novamobile.ui.player

import com.anees.novamobile.data.api.RetrofitClient
import com.anees.novamobile.data.model.LangTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request

/**
 * Real audio languages of a found stream, read from its manifest (HLS #EXT-X-MEDIA / DASH
 * AdaptationSet), not guessed from the source's label. Used to pick the stream that carries
 * the most languages and to list every language the found sources offer.
 */
object AudioProbe {

    private const val PEEK_BYTES = 256 * 1024L
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0 Safari/537.36"
    private val HLS_AUDIO = Regex("""#EXT-X-MEDIA:[^\n]*TYPE=AUDIO[^\n]*""", RegexOption.IGNORE_CASE)
    private val DASH_SET = Regex("""<AdaptationSet\b[^>]*>""", RegexOption.IGNORE_CASE)
    private fun attr(line: String, name: String) =
        Regex("""\b$name\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)

    private val cache = HashMap<String, List<String>>()

    /** Two-letter codes, or empty when the manifest can't tell (single muxed track, mp4, error). */
    suspend fun langs(s: WebStreamSniffer.Sniffed): List<String> {
        synchronized(cache) { cache[s.url] }?.let { return it }
        val r = withTimeoutOrNull(4_000) { withContext(Dispatchers.IO) { read(s) } } ?: emptyList()
        synchronized(cache) { cache[s.url] = r }
        return r
    }

    /** Wanted language first, then how many languages: the richest stream that has yours. */
    fun score(langs: List<String>, want: String): Int =
        (if (want != "orig" && want in langs) 100 else 0) + langs.size

    private fun read(s: WebStreamSniffer.Sniffed): List<String> = runCatching {
        val req = Request.Builder().url(s.url).apply {
            header("User-Agent", s.userAgent.ifBlank { UA })
            s.headers.forEach { (k, v) -> header(k, v) }
        }.build()
        // Hard cap: a blocking call ignores coroutine timeouts
        RetrofitClient.base.newCall(req).apply { timeout().timeout(4, java.util.concurrent.TimeUnit.SECONDS) }.execute().use { r ->
            if (!r.isSuccessful) return emptyList()
            val src = r.body?.source() ?: return emptyList()
            src.request(PEEK_BYTES)
            val bytes = src.buffer.snapshot()
            if (bytes.size > 8 && bytes.substring(4, 8).utf8() == "ftyp") return mp4Langs(bytes.toByteArray())
            val head = bytes.utf8()
            val codes = if (head.contains("#EXTM3U")) {
                HLS_AUDIO.findAll(head).mapNotNull { m ->
                    code(attr(m.value, "LANGUAGE")) ?: LangTags.code(attr(m.value, "NAME"))
                }
            } else {
                DASH_SET.findAll(head).filter { m ->
                    attr(m.value, "contentType") == "audio" || attr(m.value, "mimeType")?.startsWith("audio") == true
                }.mapNotNull { m -> code(attr(m.value, "lang")) ?: LangTags.code(attr(m.value, "label")) }
            }
            codes.distinct().toList()
        }
    }.getOrDefault(emptyList())

    /**
     * MP4 with its index (moov) up front: each track's mdhd carries a packed ISO-639-2 language,
     * and the hdlr after it says whether the track is audio ("soun").
     */
    private fun mp4Langs(b: ByteArray): List<String> {
        fun at(i: Int, tag: String) = i + 4 <= b.size && (0 until 4).all { b[i + it] == tag[it].code.toByte() }
        val out = mutableListOf<String>()
        var i = 0
        while (i < b.size - 40) {
            if (!at(i, "mdhd")) { i++; continue }
            val v1 = b[i + 4].toInt() == 1
            val li = i + 8 + if (v1) 28 else 16
            if (li + 2 > b.size) break
            val packed = ((b[li].toInt() and 0xff) shl 8) or (b[li + 1].toInt() and 0xff)
            val iso3 = String(charArrayOf(((packed shr 10 and 31) + 0x60).toChar(), ((packed shr 5 and 31) + 0x60).toChar(), ((packed and 31) + 0x60).toChar()))
            // hdlr follows within the same mdia box
            val h = (li until minOf(b.size - 16, li + 200)).firstOrNull { at(it, "hdlr") }
            if (h != null && at(h + 12, "soun")) iso2(iso3)?.let { out += it }
            i = li
        }
        return out.distinct()
    }

    private val ISO3 by lazy {
        java.util.Locale.getISOLanguages().associateBy { runCatching { java.util.Locale(it).isO3Language }.getOrDefault("") }
    }
    private fun iso2(iso3: String): String? = if (iso3 == "und" || iso3 == "```") null else ISO3[iso3] ?: when (iso3) {
        "hin" -> "hi"; "jpn" -> "ja"; "eng" -> "en"; "tam" -> "ta"; "tel" -> "te"; "mal" -> "ml"; "ben" -> "bn"; else -> null
    }

    private fun code(tag: String?): String? = LangTags.norm(tag)
}
