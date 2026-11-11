package com.anees.novamobile.ui.player

/**
 * Reads everything an HLS master playlist states about its stream, no extra requests: per variant the
 * picture height, bitrate, frame rate, video codec (+ 10-bit / Dolby Vision from the codec profile),
 * HDR range; per audio rendition the format (AAC / DD / DD+ / Atmos), channel layout and language;
 * and how many subtitle tracks it carries.
 */
object HlsInfo {

    data class Result(
        /** The best variant's details plus the stream-wide audio / subtitles. */
        val meta: StreamMeta,
        /** Each picture height → that variant's details (highest bitrate per height). */
        val variants: Map<Int, StreamMeta>,
    )

    private val ATTR = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

    /** `#EXT-X-STREAM-INF:BANDWIDTH=1,CODECS="avc1.64001f,mp4a.40.2"` → map (quoted commas kept). */
    fun attrs(line: String): Map<String, String> =
        ATTR.findAll(line.substringAfter(':')).associate { it.groupValues[1].uppercase() to it.groupValues[2].trim('"') }

    /** null when [text] isn't a master playlist. */
    fun parse(text: String): Result? {
        if (!text.contains("#EXT-X-STREAM-INF")) return null
        val lines = text.lineSequence().map { it.trim() }.toList()
        val media = lines.filter { it.startsWith("#EXT-X-MEDIA:") }.map { attrs(it) }
        val audios = media.filter { it["TYPE"].equals("AUDIO", true) }
        val subCount = media.count { it["TYPE"].equals("SUBTITLES", true) }
        val ccCount = media.count { it["TYPE"].equals("CLOSED-CAPTIONS", true) }

        data class V(val h: Int, val bw: Int, val m: StreamMeta)
        val vs = lines.filter { it.startsWith("#EXT-X-STREAM-INF:") }.map { l ->
            val a = attrs(l)
            val h = a["RESOLUTION"]?.substringAfter('x', "")?.toIntOrNull() ?: 0
            val bw = (a["AVERAGE-BANDWIDTH"] ?: a["BANDWIDTH"])?.toLongOrNull()?.let { (it / 1000).toInt() } ?: 0
            val codecs = a["CODECS"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val video = codecs.firstNotNullOfOrNull { videoCodec(it) }
            val dv = codecs.any { it.startsWith("dvh1") || it.startsWith("dvhe") || it.startsWith("dav1") }
            val range = when (a["VIDEO-RANGE"]?.uppercase()) { "PQ" -> "HDR"; "HLG" -> "HLG"; else -> null }
            val tenBit = codecs.any { tenBit(it) }
            val muxedAudio = codecs.firstNotNullOfOrNull { audioCodec(it) }
            V(h, bw, StreamMeta(
                codec = video, hdr = if (dv) "Dolby Vision" else range ?: if (tenBit) "10-bit" else null,
                height = h.takeIf { it > 0 }, fps = a["FRAME-RATE"]?.toDoubleOrNull()?.let { Math.round(it).toInt() }?.takeIf { it in 10..240 },
                bitrateKbps = bw.takeIf { it > 0 }, audio = muxedAudio))
        }
        if (vs.isEmpty()) return null

        // Audio: the richest rendition (most channels; Atmos wins)
        val bestAudio = audios.maxByOrNull { channelsOf(it["CHANNELS"])?.first ?: 0 }
        val ch = channelsOf(bestAudio?.get("CHANNELS"))
        val renditionFormat = audios.firstNotNullOfOrNull { r -> r["GROUP-ID"]?.let { audioFromGroup(it) } }
        val audioFormat = (vs.firstNotNullOfOrNull { it.m.audio } ?: renditionFormat)
            ?.let { if (ch?.second == true && !it.endsWith("Atmos")) "$it Atmos" else it } ?: if (ch?.second == true) "DD+ Atmos" else null
        val langs = audios.mapNotNull { ReleaseInfo.langCode(it["LANGUAGE"] ?: it["NAME"]).takeIf { c -> c.isNotEmpty() } }.distinct()

        val byHeight = vs.filter { it.h > 0 }.groupBy { it.h }.mapValues { (_, l) -> l.maxByOrNull { it.bw }!!.m }
        val best = vs.maxWithOrNull(compareBy<V> { it.h }.thenBy { it.bw })!!.m
        val subs = (subCount + ccCount).takeIf { it > 0 }?.let { if (it == 1) "1 sub" else "$it subs" }
        val meta = best.copy(
            audio = audioFormat, channels = ch?.let { channelLabel(it.first) },
            langs = langs.takeIf { it.isNotEmpty() },
            audioMode = if (langs.size >= 3) "Multi Audio" else if (langs.size == 2) "Dual Audio" else null,
            subs = subs)
        return Result(meta, byHeight.mapValues { (_, m) -> m.copy(audio = m.audio ?: meta.audio, channels = meta.channels) })
    }

    /** RFC 6381 codec string → name. */
    fun videoCodec(c: String): String? = when {
        c.startsWith("avc1") || c.startsWith("avc3") -> "x264"
        c.startsWith("hvc1") || c.startsWith("hev1") || c.startsWith("dvh1") || c.startsWith("dvhe") -> "HEVC"
        c.startsWith("av01") || c.startsWith("dav1") -> "AV1"
        c.startsWith("vp09") || c.startsWith("vp9") -> "VP9"
        else -> null
    }

    fun audioCodec(c: String): String? = when {
        c.startsWith("mp4a.40") -> "AAC"
        c == "mp4a.a6" || c.startsWith("ec-3") || c.startsWith("ec+3") -> "DD+"
        c == "mp4a.a5" || c.startsWith("ac-3") -> "DD"
        c.startsWith("ac-4") -> "AC-4"
        c.startsWith("opus") || c == "Opus" -> "Opus"
        c.startsWith("flac") || c == "fLaC" -> "FLAC"
        c.startsWith("mp4a.69") || c.startsWith("mp4a.6B") || c.startsWith("mp4a.6b") -> "MP3"
        else -> null
    }

    /** HEVC Main10 (profile 2) / AV1 10-bit / VP9 profile 2 → 10-bit picture. */
    private fun tenBit(c: String): Boolean = when {
        c.startsWith("hvc1.2") || c.startsWith("hev1.2") -> true
        c.startsWith("av01.") -> c.split('.').getOrNull(3) == "10"
        c.startsWith("vp09.") -> c.split('.').getOrNull(3) == "10"
        else -> false
    }

    /** Group ids are often named after the format ("aac-128k", "ec3-768", "atmos"). */
    private fun audioFromGroup(g: String): String? = g.lowercase().let { when {
        "atmos" in it || "joc" in it -> "DD+ Atmos"
        "ec3" in it || "ec-3" in it || "eac3" in it || "ddp" in it -> "DD+"
        "ac3" in it || "ac-3" in it -> "DD"
        "aac" in it -> "AAC"
        "opus" in it -> "Opus"
        else -> null
    } }

    /** `CHANNELS="6"` → (6, false); `"16/JOC"` → (16, true = Atmos). */
    private fun channelsOf(v: String?): Pair<Int, Boolean>? {
        val parts = v?.split('/') ?: return null
        val n = parts[0].toIntOrNull() ?: return null
        return n to parts.drop(1).any { it.contains("JOC", true) }
    }

    private fun channelLabel(n: Int) = when (n) {
        1 -> "1.0"; 2 -> "2.0"; 3 -> "2.1"; 6 -> "5.1"; 8 -> "7.1"
        in 10..24 -> "7.1"   // Atmos object counts
        else -> null
    }
}
