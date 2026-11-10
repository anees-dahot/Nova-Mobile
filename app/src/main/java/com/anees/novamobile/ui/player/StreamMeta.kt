package com.anees.novamobile.ui.player

import com.anees.novamobile.data.model.LangTags

/**
 * Everything a source states about one stream, beyond its link. All fields optional: each source fills
 * what it knows ([ReleaseInfo.parse] turns a release / file name into most of them).
 */
data class StreamMeta(
    /** Release or file name as the source lists it ("LOTR (2001) Extended BluRay 1080p x264 [Hindi-English] 3.2GB"). */
    val release: String? = null,
    /** "Extended", "Director's Cut", "Theatrical", "Unrated", "IMAX"; null = not stated. */
    val edition: String? = null,
    /** "WEB-DL", "WEBRip", "BluRay", "HDTV", "DVDRip", "HDRip", "CAM", "TS", "TC". */
    val sourceType: String? = null,
    /** "x264", "HEVC", "AV1". */
    val codec: String? = null,
    /** "10-bit", "HDR", "Dolby Vision". */
    val hdr: String? = null,
    /** Server / mirror name inside the source ("Filmu", "V-Cloud"). */
    val server: String? = null,
    /** Audio languages the source states (two-letter codes). */
    val langs: List<String>? = null,
    /** Running time the source states, seconds. */
    val durationSec: Int? = null,
    /** File size the source states. */
    val bytes: Long? = null,
    /** Language of each subtitle, same order as [WebStreamSniffer.Sniffed.subtitles] ("" = unknown). */
    val subLangs: List<String>? = null,
) {
    /** Camera / telesync recording: bad picture and sound, never picked automatically. */
    val isCam get() = sourceType in CAM_TYPES

    /** Fields from [other] fill whatever this one leaves empty. */
    fun orElse(other: StreamMeta?): StreamMeta = if (other == null) this else StreamMeta(
        release ?: other.release, edition ?: other.edition, sourceType ?: other.sourceType, codec ?: other.codec,
        hdr ?: other.hdr, server ?: other.server, langs ?: other.langs, durationSec ?: other.durationSec,
        bytes ?: other.bytes, subLangs ?: other.subLangs)

    /** Short badges for a list row: "WEB-DL · HEVC · 10-bit". */
    fun badges(): List<String> = listOfNotNull(sourceType, codec, hdr)

    companion object {
        val CAM_TYPES = setOf("CAM", "TS", "TC")
    }
}

/** Reads release / file names: "Movie.2001.EXTENDED.1080p.BluRay.x265.10bit.Hindi-English.3.2GB.mkv". */
object ReleaseInfo {
    private val EDITIONS = listOf(
        Regex("""(?i)\bextended\b""") to "Extended",
        Regex("""(?i)\bdirector'?s[\s._-]*cut\b|\bDC\b""") to "Director's Cut",
        Regex("""(?i)\btheatrical\b""") to "Theatrical",
        Regex("""(?i)\bunrated\b|\buncut\b""") to "Unrated",
        Regex("""(?i)\bimax\b""") to "IMAX",
        Regex("""(?i)\bremastered\b""") to "Remastered",
    )
    private val SOURCES = listOf(
        Regex("""(?i)\b(hd)?cam(rip)?\b""") to "CAM",
        Regex("""(?i)\b(hd)?ts(rip)?\b|\btelesync\b|\bpre-?dvd\b""") to "TS",
        Regex("""(?i)\b(hd)?tc\b|\btelecine\b""") to "TC",
        Regex("""(?i)\bweb[\s._-]?dl\b""") to "WEB-DL",
        Regex("""(?i)\bweb[\s._-]?rip\b|\bweb\b""") to "WEBRip",
        Regex("""(?i)\bblu[\s._-]?ray\b|\bbd[\s._-]?rip\b|\bbrrip\b|\bremux\b""") to "BluRay",
        Regex("""(?i)\bhdtv\b""") to "HDTV",
        Regex("""(?i)\bdvd[\s._-]?rip\b|\bdvd\b""") to "DVDRip",
        Regex("""(?i)\bhd[\s._-]?rip\b""") to "HDRip",
    )
    private val CODECS = listOf(
        Regex("""(?i)\bx265\b|\bh\.?265\b|\bhevc\b""") to "HEVC",
        Regex("""(?i)\bx264\b|\bh\.?264\b|\bavc\b""") to "x264",
        Regex("""(?i)\bav1\b""") to "AV1",
    )
    private val HDRS = listOf(
        Regex("""(?i)\bdolby[\s._-]?vision\b|\bDoVi\b|\bDV\b""") to "Dolby Vision",
        Regex("""(?i)\bhdr(10\+?)?\b""") to "HDR",
        Regex("""(?i)\b10[\s._-]?bit\b""") to "10-bit",
    )
    private val SIZE = Regex("""(?i)\b(\d+(?:\.\d+)?)\s?(GB|MB)\b""")
    private val HEIGHT = Regex("""(?i)\b(\d{3,4})p\b""")
    private val LANG_WORDS = mapOf("hindi" to "hi", "english" to "en", "eng" to "en", "tamil" to "ta", "telugu" to "te",
        "malayalam" to "ml", "kannada" to "kn", "bengali" to "bn", "urdu" to "ur", "punjabi" to "pa", "japanese" to "ja",
        "korean" to "ko", "spanish" to "es", "french" to "fr", "chinese" to "zh", "marathi" to "mr", "gujarati" to "gu")

    /** Details a release name states; [server] / [durationSec] etc. are left to the caller. */
    fun parse(name: String?): StreamMeta? {
        if (name.isNullOrBlank()) return null
        val n = name.replace('_', ' ')
        fun first(list: List<Pair<Regex, String>>) = list.firstOrNull { it.first.containsMatchIn(n) }?.second
        val langs = LANG_WORDS.entries.filter { Regex("""(?i)\b${it.key}\b""").containsMatchIn(n) }.map { it.value }.distinct()
        val bytes = SIZE.findAll(n).lastOrNull()?.let { m ->
            val v = m.groupValues[1].toDoubleOrNull() ?: return@let null
            (v * if (m.groupValues[2].equals("GB", true)) 1024.0 * 1024 * 1024 else 1024.0 * 1024).toLong()
        }
        val hdr = HDRS.filter { it.first.containsMatchIn(n) }.map { it.second }.let { l -> l.firstOrNull { it != "10-bit" } ?: l.firstOrNull() }
        return StreamMeta(release = name.trim(), edition = first(EDITIONS), sourceType = first(SOURCES), codec = first(CODECS),
            hdr = hdr, langs = langs.takeIf { it.isNotEmpty() }, bytes = bytes)
    }

    /**
     * Whether a release name is about [title] (most of its words present). False = the source matched another
     * film ("People Just Do Nothing…" for "Jawan"). Null when the name says too little to judge.
     */
    fun mentions(release: String?, title: String?): Boolean? {
        if (release.isNullOrBlank() || title.isNullOrBlank()) return null
        fun words(s: String) = s.lowercase().replace(Regex("""[^a-z0-9 ]"""), " ").split(' ').filter { it.length >= 3 && it !in STOP }
        val want = words(title).ifEmpty { return null }
        val have = words(release).toSet()
        if (have.size < 2) return null
        return want.count { it in have } * 2 >= want.size
    }
    private val STOP = setOf("the", "and", "for", "with", "from", "part", "movie", "film")

    /** Widescreen pictures are short (1920×804 is "1080p", 1280×536 "720p"): the standard class of a height. */
    fun classOf(h: Int): Int = when { h <= 0 -> h; h >= 1500 -> 2160; h >= 780 -> 1080; h >= 500 -> 720; h >= 330 -> 480; h >= 220 -> 360; else -> h }

    /** "1080p" in a release name; 2160 for 4K; null when not stated. */
    fun height(name: String?): Int? = name?.let { n ->
        HEIGHT.findAll(n).mapNotNull { it.groupValues[1].toIntOrNull() }.filter { it in 144..4320 }.maxOrNull()
            ?: if (Regex("""(?i)\b4K\b|\bUHD\b""").containsMatchIn(n)) 2160 else null
    }

    /** Two-letter code of a language label ("Hindi", "hin", "Hindi [CC]"); "" when unknown. */
    fun langCode(label: String?): String = LangTags.norm(label) ?: LangTags.code(label) ?: label?.lowercase()?.let { l ->
        LANG_WORDS.entries.firstOrNull { l.contains(it.key) }?.value } ?: ""
}

/**
 * Shared judgement of a stream's cut and quality, used by auto-pick (play + download) and the Sources page:
 * CAM recordings, your preferred cut (Settings), and lengths so far off it's probably another title.
 */
object CutRules {
    /** Settings.prefCut: 0 any, 1 official (theatrical) length, 2 extended / longest cut. */
    private fun pref() = com.anees.novamobile.data.local.LocalStore.settings().prefCut

    fun isLongCut(sec: Int, meta: StreamMeta?, expectedMin: Int?): Boolean =
        meta?.edition in setOf("Extended", "Director's Cut", "Unrated") || StreamLength.kind(sec, expectedMin) == StreamLength.Kind.LONGER

    fun isOfficialCut(sec: Int, meta: StreamMeta?, expectedMin: Int?): Boolean =
        meta?.edition == "Theatrical" || (meta?.edition == null && StreamLength.kind(sec, expectedMin) == StreamLength.Kind.MATCH)

    /** Much shorter than the official runtime (under 60%): most likely a different title or a trailer. */
    fun looksWrong(sec: Int, expectedMin: Int?): Boolean =
        sec > 0 && expectedMin != null && expectedMin > 20 && sec < expectedMin * 60 * 0.6

    /** Points off an auto-pick score (0 = fine). */
    fun penalty(sec: Int, meta: StreamMeta?, expectedMin: Int?, movie: Boolean, title: String? = null): Int {
        var p = 0
        if (ReleaseInfo.mentions(meta?.release, title) == false) p -= 4000   // release named after another film
        if (meta?.isCam == true) p -= 5000
        if (looksWrong(sec, expectedMin)) p -= 2500
        if (movie) when (pref()) {
            1 -> if (isLongCut(sec, meta, expectedMin)) p -= 1500
            2 -> if (isOfficialCut(sec, meta, expectedMin)) p -= 1500
        }
        return p
    }

    /** Auto-pick should pass on it while other sources remain. */
    fun avoid(sec: Int, meta: StreamMeta?, expectedMin: Int?, movie: Boolean, title: String? = null) = penalty(sec, meta, expectedMin, movie, title) < 0
}
