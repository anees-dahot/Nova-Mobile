package com.anees.novamobile.ui.player

import com.anees.novamobile.data.model.LangTags

/**
 * Everything a source states about one stream, beyond its link. All fields optional: each source fills
 * what it knows ([ReleaseInfo.parse] turns a release / file name into most of them, [HlsInfo] reads the
 * rest straight out of an HLS master playlist).
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
    /** "10-bit", "HDR", "HDR10+", "Dolby Vision". */
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
    /** Audio format: "DD+", "DD", "AAC", "DTS-HD MA", "TrueHD", "Opus" (+ " Atmos"). */
    val audio: String? = null,
    /** Audio channel layout: "2.0", "5.1", "7.1". */
    val channels: String? = null,
    /** "Dual Audio" / "Multi Audio" as the release states it. */
    val audioMode: String? = null,
    /** Subtitles the release states: "ESub", "Multi Subs", "Hardsub". */
    val subs: String? = null,
    /** Lossless disc copy (BluRay REMUX). */
    val remux: Boolean? = null,
    /** Streaming service the WEB release was taken from ("Netflix", "Prime Video"). */
    val service: String? = null,
    /** Release group ("RARBG", "SubsPlease"). */
    val group: String? = null,
    /** Picture height the source states (1080 for "1080p"). */
    val height: Int? = null,
    /** Frame rate the source states (24, 50, 60). */
    val fps: Int? = null,
    /** Average / peak bitrate, kbit/s (HLS BANDWIDTH or stated). */
    val bitrateKbps: Int? = null,
    /** Season / episode the release is named for. */
    val season: Int? = null,
    val episode: Int? = null,
    /** "Proper", "Repack", "Dubbed", "Uncensored", … */
    val flags: List<String>? = null,
) {
    /** Camera / telesync recording: bad picture and sound, never picked automatically. */
    val isCam get() = sourceType in CAM_TYPES

    /** Fields from [other] fill whatever this one leaves empty. */
    fun orElse(other: StreamMeta?): StreamMeta = if (other == null) this else StreamMeta(
        release = release ?: other.release, edition = edition ?: other.edition, sourceType = sourceType ?: other.sourceType,
        codec = codec ?: other.codec, hdr = hdr ?: other.hdr, server = server ?: other.server, langs = langs ?: other.langs,
        durationSec = durationSec ?: other.durationSec, bytes = bytes ?: other.bytes, subLangs = subLangs ?: other.subLangs,
        audio = audio ?: other.audio, channels = channels ?: other.channels, audioMode = audioMode ?: other.audioMode,
        subs = subs ?: other.subs, remux = remux ?: other.remux, service = service ?: other.service, group = group ?: other.group,
        height = height ?: other.height, fps = fps ?: other.fps, bitrateKbps = bitrateKbps ?: other.bitrateKbps,
        season = season ?: other.season, episode = episode ?: other.episode,
        flags = (flags.orEmpty() + other.flags.orEmpty()).distinct().takeIf { it.isNotEmpty() })

    /** Short badges for a list row: "REMUX · HEVC · Dolby Vision · DD+ 5.1 Atmos · 60fps · ESub". */
    fun badges(): List<String> = listOfNotNull(
        if (remux == true) "REMUX" else sourceType, codec, hdr,
        listOfNotNull(audio?.removeSuffix(" Atmos"), channels).joinToString(" ").ifBlank { null }
            ?.let { if (audio?.endsWith("Atmos") == true) "$it Atmos" else it } ?: audio,
        fps?.takeIf { it >= 48 }?.let { "${it}fps" }, subs)

    companion object {
        val CAM_TYPES = setOf("CAM", "TS", "TC")
    }
}

/** Reads release / file names: "Movie.2001.EXTENDED.1080p.BluRay.x265.10bit.Hindi-English.3.2GB.mkv". */
object ReleaseInfo {
    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)
    private val EDITIONS = listOf(
        rx("""\bextended\b""") to "Extended",
        rx("""\bdirector'?s[\s._-]*cut\b|\bDC\b""") to "Director's Cut",
        rx("""\btheatrical\b""") to "Theatrical",
        rx("""\bunrated\b|\buncut\b""") to "Unrated",
        rx("""\bimax\b""") to "IMAX",
        rx("""\bcriterion\b""") to "Criterion",
        rx("""\bfinal[\s._-]?cut\b""") to "Final Cut",
        rx("""\bspecial[\s._-]?edition\b|\bultimate[\s._-]?(edition|cut)\b""") to "Special Edition",
        rx("""\bremastered\b""") to "Remastered",
    )
    private val SOURCES = listOf(
        rx("""\b(hq)?(hd)?cam(rip)?\b""") to "CAM",
        rx("""\b(hd)?ts(rip)?\b|\btelesync\b|\bpre-?dvd(rip)?\b""") to "TS",
        rx("""\b(hd)?tc\b|\btelecine\b""") to "TC",
        rx("""\bweb[\s._-]?dl\b""") to "WEB-DL",
        rx("""\bweb[\s._-]?rip\b|\bweb\b""") to "WEBRip",
        rx("""\bblu[\s._-]?ray\b|\bbd[\s._-]?rip\b|\bbrrip\b|\bremux\b|\bBDMV\b""") to "BluRay",
        rx("""\bhdtv(rip)?\b|\bPDTV\b""") to "HDTV",
        rx("""\bdvd[\s._-]?rip\b|\bdvd(9|5|r)?\b""") to "DVDRip",
        rx("""\bhd[\s._-]?rip\b""") to "HDRip",
    )
    private val CODECS = listOf(
        rx("""\bx265\b|\bh[\s.]?265\b|\bhevc\b""") to "HEVC",
        rx("""\bx264\b|\bh[\s.]?264\b|\bavc\b""") to "x264",
        rx("""\bav1\b""") to "AV1",
        rx("""\bvp9\b""") to "VP9",
        rx("""\bxvid\b|\bdivx\b""") to "XviD",
    )
    private val HDRS = listOf(
        rx("""\bdolby[\s._-]?vision\b|\bDoVi\b|\bDV\b""") to "Dolby Vision",
        rx("""\bhdr10\+|\bhdr10plus\b""") to "HDR10+",
        rx("""\bhdr(10)?\b""") to "HDR",
        rx("""\bHLG\b""") to "HLG",
        rx("""\b10[\s._-]?bit\b""") to "10-bit",
    )
    /** Most specific first: "DTS-HD MA" before "DTS", "DD+" before "DD". */
    private val AUDIOS = listOf(
        rx("""\btrue[\s._-]?hd\b""") to "TrueHD",
        rx("""\bDTS[\s._-]?HD[\s._-]?MA\b|\bDTS[\s._-]?MA\b""") to "DTS-HD MA",
        rx("""\bDTS[\s._-]?X\b""") to "DTS:X",
        rx("""\bDTS(?:[\s._-]?HD)?(?=[\s._\-\d]|$)""") to "DTS",
        rx("""\bDD\+|\bDDP(?=[\s._\d]|$)|\bE-?AC-?3\b|\bdolby[\s._-]?digital[\s._-]?plus\b""") to "DD+",
        rx("""\bDD(?=[\s._]?\d[\s._]\d)|\bAC-?3\b|\bdolby[\s._-]?digital\b""") to "DD",
        rx("""\bL?PCM\b""") to "PCM",
        rx("""\bFLAC\b""") to "FLAC",
        rx("""\bOpus\b""") to "Opus",
        rx("""\bAAC(?:[\s._-]?LC)?(?=[\s._\-\d]|$)""") to "AAC",
        rx("""\bMP3\b""") to "MP3",
    )
    private val ATMOS = rx("""\batmos\b""")
    private val CHANNELS_AFTER_CODEC = rx("""(?:DD\+?|DDP|E?AC-?3|AAC(?:[\s._-]?LC)?|DTS(?:[\s._-]?HD)?(?:[\s._-]?MA)?|TrueHD|FLAC|Opus|L?PCM|Atmos)[\s._-]?([1-7])[\s._]([01])\b""")
    private val CHANNELS_LOOSE = rx("""(?<![\d.])([57])\.([01])(?![\d.]*\d)""")
    private val CHANNELS_CH = rx("""\b([2-8])[\s.]?CH\b""")
    private val AUDIO_MODES = listOf(
        rx("""\bmulti[\s._-]?audio\b|\b(tri|triple|quad)[\s._-]?audio\b|\bmulti[\s._-]?lang""") to "Multi Audio",
        rx("""\bdual[\s._-]?audio\b|\b2[\s._-]?audio\b""") to "Dual Audio",
    )
    private val SUBS = listOf(
        rx("""\bMSubs?\b|\bmulti[\s._-]?subs?\b""") to "Multi Subs",
        rx("""\bESubs?\b|\beng(lish)?[\s._-]?subs?\b""") to "ESub",
        rx("""\bHC\b|\bhard[\s._-]?sub(bed|s)?\b|\bhardcoded\b""") to "Hardsub",
        rx("""\bsubbed\b|\bsubs?\b""") to "Subs",
    )
    private val SERVICES = listOf(
        rx("""\bNF\b|\bnetflix\b""") to "Netflix",
        rx("""\bAMZN\b|\bprime[\s._-]?video\b""") to "Prime Video",
        rx("""\bDSNP\b|\bdisney\+|\bdisney[\s._-]?plus\b""") to "Disney+",
        rx("""\bHMAX\b|\bHBO[\s._-]?max\b""") to "Max",
        rx("""\bATVP\b|\bapple[\s._-]?tv\+?""") to "Apple TV+",
        rx("""\bHULU\b""") to "Hulu",
        rx("""\bPCOK\b|\bpeacock\b""") to "Peacock",
        rx("""\bPMTP\b|\bparamount\+""") to "Paramount+",
        rx("""\bJHS\b|\bHTSR\b|\bhotstar\b""") to "Hotstar",
        rx("""\bjio[\s._-]?cinema\b|\bJC[\s._-]WEB""") to "JioCinema",
        rx("""\bZEE5\b""") to "ZEE5",
        rx("""\bsony[\s._-]?liv\b|\bSLIV\b""") to "SonyLIV",
        rx("""\bCR[\s._-]WEB|\bcrunchyroll\b""") to "Crunchyroll",
        rx("""\bMX[\s._-]?player\b""") to "MX Player",
    )
    private val FLAGS = listOf(
        rx("""\bproper\b""") to "Proper",
        rx("""\brepack\b|\brerip\b""") to "Repack",
        rx("""\bdub(bed)?\b|\bfan[\s._-]?dub\b""") to "Dubbed",
        rx("""\buncensored\b""") to "Uncensored",
        rx("""\bopen[\s._-]?matte\b""") to "Open Matte",
        rx("""\bwatermark(ed)?\b""") to "Watermark",
    )
    private val REMUX = rx("""\bremux\b|\bBDMV\b""")
    private val SIZE = rx("""(?<![\w.])(\d+(?:[.,]\d+)?)\s?(TB|GB|GiB|MB|MiB)\b""")
    private val HEIGHT = rx("""\b(\d{3,4})p\b""")
    private val FPS = rx("""\b(\d{2,3})(?:\.\d{1,3})?\s?fps\b""")
    private val SXE = rx("""\bS(\d{1,2})[\s._-]?E(\d{1,4})\b|\b(\d{1,2})x(\d{2,3})\b""")
    private val SEASON = rx("""\bS(\d{1,2})\b|\bseason[\s._-]?(\d{1,2})\b""")
    private val EPISODE = rx("""\b(?:episode|ep)[\s._-]?(\d{1,4})\b|\bE(\d{2,4})\b|\s-\s(\d{1,3})(?:v\d)?\s*(?:[(\[]|$)""")
    private val GROUP_TAIL = Regex("""-\s?([A-Za-z0-9][A-Za-z0-9@&]{1,19})\s*(?:\[[^\]]*\])?\s*(?:\.(?:mkv|mp4|avi|webm|m4v|ts))?\s*$""")
    private val GROUP_HEAD = Regex("""^\s*\[([^\]\d][^\]]{1,24})\]""")
    private val LANG_WORDS = mapOf("hindi" to "hi", "hin" to "hi", "english" to "en", "eng" to "en", "tamil" to "ta", "tam" to "ta",
        "telugu" to "te", "tel" to "te", "malayalam" to "ml", "mal" to "ml", "kannada" to "kn", "kan" to "kn", "bengali" to "bn",
        "bangla" to "bn", "urdu" to "ur", "punjabi" to "pa", "japanese" to "ja", "jap" to "ja", "jpn" to "ja", "korean" to "ko",
        "kor" to "ko", "spanish" to "es", "latino" to "es", "french" to "fr", "german" to "de", "italian" to "it", "russian" to "ru",
        "portuguese" to "pt", "arabic" to "ar", "turkish" to "tr", "thai" to "th", "indonesian" to "id", "vietnamese" to "vi",
        "chinese" to "zh", "mandarin" to "zh", "cantonese" to "zh", "marathi" to "mr", "gujarati" to "gu", "odia" to "or",
        "bhojpuri" to "bh", "nepali" to "ne", "sinhala" to "si", "tagalog" to "tl", "filipino" to "tl")
    /** One pass for every language word (was one regex per word per call). */
    private val LANG_RE = Regex("""(?i)(?<![a-z])(${LANG_WORDS.keys.sortedByDescending { it.length }.joinToString("|")})(?![a-z])""")
    /** Tokens that end a name but are not a release group. */
    private val NOT_GROUP = setOf("x264", "x265", "hevc", "avc", "web", "dl", "rip", "webrip", "bluray", "aac", "ddp", "ac3", "esub",
        "esubs", "msubs", "hdr", "sdr", "dual", "audio", "multi", "10bit", "proper", "repack") + LANG_WORDS.keys

    /** Details a release name states; [server] / [durationSec] etc. are left to the caller. */
    fun parse(name: String?): StreamMeta? {
        if (name.isNullOrBlank()) return null
        val n = name.replace('_', ' ')
        fun first(list: List<Pair<Regex, String>>) = list.firstOrNull { it.first.containsMatchIn(n) }?.second
        fun all(list: List<Pair<Regex, String>>) = list.filter { it.first.containsMatchIn(n) }.map { it.second }
        fun num(m: MatchResult?) = m?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toIntOrNull()
        val langs = LANG_RE.findAll(n).mapNotNull { LANG_WORDS[it.groupValues[1].lowercase()] }.distinct().toList()
        val bytes = SIZE.findAll(n).lastOrNull()?.let { m ->
            val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@let null
            val k = 1024.0
            (v * when (m.groupValues[2].uppercase()) { "TB" -> k * k * k * k; "GB", "GIB" -> k * k * k; else -> k * k }).toLong()
        }
        val hdr = all(HDRS).let { l -> l.firstOrNull { it != "10-bit" } ?: l.firstOrNull() }
        val atmos = ATMOS.containsMatchIn(n)
        val audio = (first(AUDIOS) ?: if (atmos) "TrueHD" else null)?.let { if (atmos) "$it Atmos" else it }
        val channels = (CHANNELS_AFTER_CODEC.find(n) ?: CHANNELS_LOOSE.find(n))?.let { "${it.groupValues[1]}.${it.groupValues[2]}" }
            ?: CHANNELS_CH.find(n)?.groupValues?.get(1)?.toIntOrNull()?.let { when (it) { 2 -> "2.0"; 6 -> "5.1"; 8 -> "7.1"; else -> null } }
        val sxe = SXE.find(n)
        val season = sxe?.let { num(it) } ?: num(SEASON.find(n))
        val episode = sxe?.groupValues?.let { (it.getOrNull(2)?.takeIf { s -> s.isNotEmpty() } ?: it.getOrNull(4))?.toIntOrNull() }
            ?: num(EPISODE.find(n))
        val group = (GROUP_HEAD.find(n)?.groupValues?.get(1) ?: GROUP_TAIL.find(n)?.groupValues?.get(1))
            ?.trim()?.takeIf { g -> g.lowercase() !in NOT_GROUP && !g.all { it.isDigit() } && !Regex("""(?i)^\d{3,4}p$|^\d+(\.\d+)?(gb|mb)$""").matches(g) }
        val sourceType = first(SOURCES)
        return StreamMeta(release = name.trim(), edition = first(EDITIONS), sourceType = sourceType, codec = first(CODECS),
            hdr = hdr, langs = langs.takeIf { it.isNotEmpty() }, bytes = bytes,
            audio = audio, channels = channels, audioMode = first(AUDIO_MODES) ?: if (langs.size >= 3) "Multi Audio" else if (langs.size == 2) "Dual Audio" else null,
            subs = first(SUBS), remux = REMUX.containsMatchIn(n).takeIf { it },
            service = if (sourceType == null || sourceType.startsWith("WEB")) first(SERVICES) else null,
            group = group, height = height(n), fps = num(FPS.find(n))?.takeIf { it in 10..240 },
            season = season, episode = episode, flags = all(FLAGS).takeIf { it.isNotEmpty() })
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

/**
 * Source APIs (MovieBox, Castle, NetMirror, players' JSON) name the same facts many ways: reads whichever
 * of the usual keys a stream / file object has. Missing keys are simply left empty.
 */
object JsonMeta {
    private val NAME = listOf("fileName", "filename", "file_name", "name", "title", "releaseName", "release", "label")
    private val CODEC = listOf("codecName", "codec", "videoCodec", "video_codec", "vcodec", "encode")
    private val AUDIO = listOf("audioCodec", "audio_codec", "acodec", "audioFormat", "audio")
    private val CHANNELS = listOf("audioChannels", "channels", "audio_channels")
    private val BITRATE = listOf("bitrate", "bitRate", "bit_rate", "bandwidth")
    private val FPS = listOf("fps", "frameRate", "frame_rate", "framerate")
    private val SIZE = listOf("size", "fileSize", "file_size", "filesize", "bytes", "length")
    private val DUR = listOf("duration", "durationSeconds", "runtime", "videoDuration", "totalTime")
    private val HDR = listOf("hdr", "dynamicRange", "videoRange", "colorRange")
    private val LANG = listOf("language", "lang", "audioLang", "audio_lang", "languageName")

    fun from(o: org.json.JSONObject?): StreamMeta? {
        if (o == null) return null
        fun str(keys: List<String>) = keys.firstNotNullOfOrNull { k -> o.opt(k)?.takeIf { it is String || it is Number }?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" } }
        fun num(keys: List<String>) = str(keys)?.toDoubleOrNull()
        val name = str(NAME)?.takeIf { it.length > 6 }
        val parsed = ReleaseInfo.parse(name)
        val codec = str(CODEC)?.let { c -> ReleaseInfo.parse(c)?.codec ?: HlsInfo.videoCodec(c.lowercase()) }
        val audioRaw = str(AUDIO)
        val audio = audioRaw?.let { a -> ReleaseInfo.parse(a)?.audio ?: HlsInfo.audioCodec(a.lowercase()) }
        val ch = num(CHANNELS)?.toInt()?.let { when (it) { 2 -> "2.0"; 6 -> "5.1"; 8 -> "7.1"; else -> null } }
            ?: audioRaw?.let { ReleaseInfo.parse(it)?.channels }
        val bitrate = num(BITRATE)?.let { if (it > 100_000) it / 1000 else it }?.toInt()?.takeIf { it in 50..200_000 }
        val dur = num(DUR)?.toInt()?.takeIf { it > 0 }?.let { if (it > 100_000) it / 1000 else if (it < 400) it * 60 else it }
        val hdr = str(HDR)?.let { h -> when { h.contains("vision", true) || h.equals("DV", true) -> "Dolby Vision"; h.contains("PQ", true) || h.contains("HDR", true) -> "HDR"; h.contains("HLG", true) -> "HLG"; else -> null } }
        val lang = str(LANG)?.let { ReleaseInfo.langCode(it) }?.takeIf { it.isNotEmpty() }
        val m = StreamMeta(release = name, codec = codec, audio = audio, channels = ch, bitrateKbps = bitrate,
            fps = num(FPS)?.let { Math.round(it).toInt() }?.takeIf { it in 10..240 },
            bytes = num(SIZE)?.toLong()?.takeIf { it > 1_000_000 }, durationSec = dur, hdr = hdr, langs = lang?.let { listOf(it) })
        return m.orElse(parsed)
    }
}
