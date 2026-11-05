package com.anees.novamobile.download

/** Subtitle text helpers for Save to phone: WebVTT / SRT in, cues or clean SRT out. */
object SubText {

    class Cue(val startMs: Long, val endMs: Long, val text: String)

    private val TIME = Regex("""^\s*((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})\s*-->\s*((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})""")
    private val KEEP_TAGS = Regex("""</?([ibu])>""", RegexOption.IGNORE_CASE)
    private val ANY_TAG = Regex("""<[^>]+>""")

    /**
     * Cues of a WebVTT or SRT file, sorted by start. Null when the track has no usable cues or looks
     * broken (e.g. karaoke tracks: thousands of one-syllable cues stacked on the same timestamps).
     */
    fun cues(src: String): List<Cue>? {
        val cues = parse(src).sortedBy { it.startMs }
        if (cues.isEmpty() || broken(cues)) return null
        return cues
    }

    fun toSrt(cues: List<Cue>): String = buildString {
        cues.forEachIndexed { i, c ->
            append(i + 1).append('\n')
            append(srtTime(c.startMs)).append(" --> ").append(srtTime(c.endMs)).append('\n')
            append(c.text).append("\n\n")
        }
    }

    /** Text without any markup (for tracks embedded in the MP4). */
    fun plain(text: String) = ANY_TAG.replace(text, "")

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
            val s = ms(m.groupValues[1]); val e = ms(m.groupValues[2])
            if (body.isNotBlank() && e > s) out += Cue(s, e, body)
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

    /** "mm:ss.mmm" or "hh:mm:ss.mmm" → milliseconds. */
    private fun ms(t: String): Long {
        val (clock, frac) = t.replace(',', '.').split('.').let { it[0] to it.getOrElse(1) { "0" } }
        val p = clock.split(':').map { it.toLong() }
        val (h, m, s) = if (p.size == 3) Triple(p[0], p[1], p[2]) else Triple(0L, p[0], p[1])
        return ((h * 60 + m) * 60 + s) * 1000 + frac.padEnd(3, '0').take(3).toLong()
    }

    private fun srtTime(ms: Long) = "%02d:%02d:%02d,%03d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)

    private fun broken(cues: List<Cue>): Boolean {
        if (cues.size < 20) return false
        val sameStart = cues.zipWithNext().count { (a, b) -> a.startMs == b.startMs }
        val avgLen = cues.sumOf { it.text.length } / cues.size.toDouble()
        return sameStart > cues.size / 2 || avgLen < 4
    }
}
