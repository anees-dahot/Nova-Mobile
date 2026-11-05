package com.anees.novamobile.download

/** Subtitle text helpers for Save to phone: WebVTT / SRT in, clean SRT out. */
object SubText {

    private val TIME = Regex("""^\s*((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})\s*-->\s*((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})""")
    private val KEEP_TAGS = Regex("""</?([ibu])>""", RegexOption.IGNORE_CASE)
    private val ANY_TAG = Regex("""<[^>]+>""")

    private class Cue(val start: String, val end: String, val text: String)

    /**
     * Converts WebVTT or SRT to SRT. Returns null when the track has no usable cues or looks broken
     * (e.g. karaoke tracks: thousands of one-syllable cues stacked on the same timestamps).
     */
    fun toSrt(src: String): String? {
        val cues = parse(src)
        if (cues.isEmpty()) return null
        if (broken(cues)) return null
        return buildString {
            cues.forEachIndexed { i, c ->
                append(i + 1).append('\n')
                append(c.start).append(" --> ").append(c.end).append('\n')
                append(c.text).append("\n\n")
            }
        }
    }

    private fun parse(src: String): List<Cue> {
        val lines = src.replace("\uFEFF", "").replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = mutableListOf<Cue>()
        var i = 0
        while (i < lines.size) {
            val m = TIME.find(lines[i])
            if (m == null) { i++; continue }
            val text = mutableListOf<String>()
            i++
            while (i < lines.size && lines[i].isNotBlank() && TIME.find(lines[i]) == null) {
                text += clean(lines[i]); i++
            }
            val body = text.filter { it.isNotBlank() }.joinToString("\n")
            if (body.isNotBlank()) out += Cue(srtTime(m.groupValues[1]), srtTime(m.groupValues[2]), body)
        }
        return out
    }

    /** Drop VTT-only markup (voice/class/timestamp tags), keep <i>/<b>/<u>, unescape entities. */
    private fun clean(line: String): String {
        val kept = KEEP_TAGS.replace(line) { "\u0001${it.value.substring(1, it.value.length - 1)}\u0002" }
        return ANY_TAG.replace(kept, "")
            .replace("\u0001", "<").replace("\u0002", ">")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
            .replace("\u202A", "").replace("\u202B", "").replace("\u202C", "")
            .trim()
    }

    /** "mm:ss.mmm" or "hh:mm:ss.mmm" → "hh:mm:ss,mmm". */
    private fun srtTime(t: String): String {
        val (clock, frac) = t.replace(',', '.').split('.').let { it[0] to it.getOrElse(1) { "0" } }
        val parts = clock.split(':').map { it.toInt() }
        val (h, m, s) = if (parts.size == 3) Triple(parts[0], parts[1], parts[2]) else Triple(0, parts[0], parts[1])
        return "%02d:%02d:%02d,%s".format(h, m, s, frac.padEnd(3, '0').take(3))
    }

    private fun broken(cues: List<Cue>): Boolean {
        if (cues.size < 20) return false
        val sameStart = cues.zipWithNext().count { (a, b) -> a.start == b.start }
        val avgLen = cues.sumOf { it.text.length } / cues.size.toDouble()
        return sameStart > cues.size / 2 || avgLen < 4
    }
}
