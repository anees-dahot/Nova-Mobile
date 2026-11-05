package com.anees.novamobile.download

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Locale

/**
 * Adds subtitle tracks (3GPP timed text, "tx3g" — what ffmpeg writes as mov_text) to a finished MP4,
 * so VLC, MX Player, Samsung Video and the like show them with no extra files.
 *
 * The video data is never touched: the text samples go into a new mdat at the end of the file, a
 * rebuilt moov (old tracks + the new ones) follows it, and the old moov becomes a "free" box. The old
 * tracks' chunk offsets point into the original mdat, which stays where it was.
 */
object Mp4Subs {

    class Track(val lang: String?, val cues: List<SubText.Cue>)

    private class Box(val type: String, val offset: Long, val size: Long, val header: Int)

    /** [fd] must be open read-write. The first track is the default one. */
    fun embed(fd: FileDescriptor, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val rd = FileInputStream(fd).channel
        val wr = FileOutputStream(fd).channel
        val len = rd.size()
        val boxes = topLevel(rd, len)
        val moov = boxes.firstOrNull { it.type == "moov" } ?: error("no moov box")
        // A box running "to the end of the file" (size 0) would swallow what we append: give it a real size
        boxes.lastOrNull()?.takeIf { it.size == 0L }?.let { last ->
            val real = len - last.offset
            check(last.header == 8 && real <= 0xFFFFFFFFL) { "open-ended ${last.type} box too large" }
            writeFully(wr, ByteBuffer.allocate(4).putInt(real.toInt()).also { it.flip() }, last.offset)
        }
        val moovSize = if (moov.size == 0L) len - moov.offset else moov.size
        val body = ByteBuffer.allocate((moovSize - moov.header).toInt())
        readFully(rd, body, moov.offset + moov.header); body.flip()
        val children = children(body)

        val mvhd = children.firstOrNull { it.first == "mvhd" }?.second ?: error("no mvhd")
        val movieScale = if (mvhd[0].toInt() == 1) ByteBuffer.wrap(mvhd, 20, 4).int else ByteBuffer.wrap(mvhd, 12, 4).int
        val usedIds = children.filter { it.first == "trak" }.mapNotNull { trackId(it.second) }
        var nextId = maxOf(ByteBuffer.wrap(mvhd, mvhd.size - 4, 4).int, (usedIds.maxOrNull() ?: 0) + 1)

        // Samples: all tracks one after another in one new mdat
        val samples = tracks.map { samples(it.cues) }
        val mdatPayload = samples.sumOf { s -> s.sumOf { it.second.size.toLong() } }
        val mdatAt = len
        val mdatHeader = if (mdatPayload + 8 > 0xFFFFFFFFL) 16 else 8
        var dataAt = mdatAt + mdatHeader

        val newTraks = ByteArrayOutputStream()
        tracks.forEachIndexed { i, t ->
            val s = samples[i]
            if (s.isEmpty()) return@forEachIndexed
            newTraks.write(trak(nextId++, t.lang, s, dataAt, movieScale, default = i == 0))
            dataAt += s.sumOf { it.second.size.toLong() }
        }
        ByteBuffer.wrap(mvhd).putInt(mvhd.size - 4, nextId)

        val moovOut = ByteArrayOutputStream()
        children.forEach { (type, data) -> moovOut.write(box(type, data)) }
        moovOut.write(newTraks.toByteArray())
        val newMoov = box("moov", moovOut.toByteArray())

        try {
            var pos = mdatAt
            val head = ByteBuffer.allocate(mdatHeader)
            if (mdatHeader == 8) head.putInt((mdatPayload + 8).toInt()).put("mdat".toByteArray())
            else head.putInt(1).put("mdat".toByteArray()).putLong(mdatPayload + 16)
            head.flip(); pos += writeFully(wr, head, pos)
            samples.forEach { s -> s.forEach { pos += writeFully(wr, ByteBuffer.wrap(it.second), pos) } }
            writeFully(wr, ByteBuffer.wrap(newMoov), pos)
            wr.force(false)
            // Only now retire the old moov: until here the file is still the original, valid MP4
            writeFully(wr, ByteBuffer.wrap("free".toByteArray()), moov.offset + 4)
            wr.force(false)
        } catch (e: Exception) {
            runCatching { wr.truncate(len) }
            throw e
        }
    }

    // ── Reading ──────────────────────────────────────────────────────────────

    private fun topLevel(ch: FileChannel, len: Long): List<Box> {
        val out = mutableListOf<Box>()
        var pos = 0L
        val h = ByteBuffer.allocate(16)
        while (pos + 8 <= len) {
            h.clear().limit(8); readFully(ch, h, pos); h.flip()
            var size = h.int.toLong() and 0xFFFFFFFFL
            val type = String(ByteArray(4).also { h.get(it) }, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) { h.clear().limit(8); readFully(ch, h, pos + 8); h.flip(); size = h.long; header = 16 }
            out += Box(type, pos, size, header)
            if (size == 0L) break
            check(size >= header) { "bad box $type at $pos" }
            pos += size
        }
        return out
    }

    /** Direct children of a container payload as (type, payload). */
    private fun children(b: ByteBuffer): List<Pair<String, ByteArray>> {
        val out = mutableListOf<Pair<String, ByteArray>>()
        while (b.remaining() >= 8) {
            val start = b.position()
            var size = b.int.toLong() and 0xFFFFFFFFL
            val type = String(ByteArray(4).also { b.get(it) }, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) { size = b.long; header = 16 }
            if (size == 0L) size = (b.limit() - start).toLong()
            check(size >= header && start + size <= b.limit()) { "bad child box $type" }
            val data = ByteArray((size - header).toInt()); b.get(data)
            out += type to data
            b.position((start + size).toInt())
        }
        return out
    }

    private fun trackId(trak: ByteArray): Int? {
        val tkhd = children(ByteBuffer.wrap(trak)).firstOrNull { it.first == "tkhd" }?.second ?: return null
        return ByteBuffer.wrap(tkhd, if (tkhd[0].toInt() == 1) 20 else 12, 4).int
    }

    private fun readFully(ch: FileChannel, b: ByteBuffer, at: Long) {
        var pos = at
        while (b.hasRemaining()) { val n = ch.read(b, pos); check(n > 0) { "unexpected end of file" }; pos += n }
    }

    private fun writeFully(ch: FileChannel, b: ByteBuffer, at: Long): Long {
        var pos = at
        while (b.hasRemaining()) pos += ch.write(b, pos)
        return pos - at
    }

    // ── Writing ──────────────────────────────────────────────────────────────

    /** (duration ms, sample bytes): text samples with empty ones filling the gaps. */
    private fun samples(cues: List<SubText.Cue>): List<Pair<Long, ByteArray>> {
        val out = mutableListOf<Pair<Long, ByteArray>>()
        var cursor = 0L
        for (c in cues) {
            val s = maxOf(c.startMs, cursor)
            if (c.endMs <= s) continue
            if (s > cursor) out += (s - cursor) to byteArrayOf(0, 0)
            val text = SubText.plain(c.text).toByteArray(Charsets.UTF_8).let { if (it.size > 0xFFFF) it.copyOf(0xFFFF) else it }
            out += (c.endMs - s) to (ByteBuffer.allocate(2 + text.size).putShort(text.size.toShort()).put(text).array())
            cursor = c.endMs
        }
        return out
    }

    private fun trak(id: Int, lang: String?, s: List<Pair<Long, ByteArray>>, chunkAt: Long, movieScale: Int, default: Boolean): ByteArray {
        val durMs = s.sumOf { it.first }
        val tkhd = data {
            writeInt(if (default) 3 else 2)                 // version 0; enabled (default only) + in movie
            writeInt(0); writeInt(0); writeInt(id); writeInt(0)
            writeInt((durMs * movieScale / 1000).coerceAtMost(0xFFFFFFFFL).toInt())
            writeLong(0); writeShort(0); writeShort(2)      // layer, alternate group (all text tracks)
            writeShort(0); writeShort(0)                    // volume, reserved
            intArrayOf(0x10000, 0, 0, 0, 0x10000, 0, 0, 0, 0x40000000).forEach { writeInt(it) }
            writeInt(0); writeInt(0)                        // width, height
        }
        val mdhd = data {
            writeInt(0); writeInt(0); writeInt(0); writeInt(1000)
            writeInt(durMs.coerceAtMost(0xFFFFFFFFL).toInt())
            writeShort(packLang(lang)); writeShort(0)
        }
        val hdlr = data {
            writeInt(0); writeInt(0); write("sbtl".toByteArray()); writeInt(0); writeInt(0); writeInt(0)
            write("SubtitleHandler".toByteArray()); writeByte(0)
        }
        val tx3g = data {
            write(ByteArray(6)); writeShort(1)              // reserved, data reference index
            writeInt(0)                                     // display flags
            writeByte(1); writeByte(-1)                     // centred, bottom
            writeInt(0)                                     // background rgba
            writeLong(0)                                    // default text box
            writeShort(0); writeShort(0); writeShort(1)     // style record: chars 0-0, font 1
            writeByte(0); writeByte(18); writeInt(-1)       // face style, size, white
            write(box("ftab", data { writeShort(1); writeShort(1); writeByte(5); write("Serif".toByteArray()) }))
        }
        val stsd = full(0) { writeInt(1); write(box("tx3g", tx3g)) }
        val stts = full(0) { writeInt(s.size); s.forEach { writeInt(1); writeInt(it.first.toInt()) } }
        val stsc = full(0) { writeInt(1); writeInt(1); writeInt(s.size); writeInt(1) }
        val stsz = full(0) { writeInt(0); writeInt(s.size); s.forEach { writeInt(it.second.size) } }
        val stco = if (chunkAt > 0xFFFFFFFFL) box("co64", full(0) { writeInt(1); writeLong(chunkAt) })
                   else box("stco", full(0) { writeInt(1); writeInt(chunkAt.toInt()) })
        val stbl = box("stbl", box("stsd", stsd) + box("stts", stts) + box("stsc", stsc) + box("stsz", stsz) + stco)
        val dinf = box("dinf", box("dref", full(0) { writeInt(1); write(box("url ", full(1) {})) }))
        val minf = box("minf", box("nmhd", full(0) {}) + dinf + stbl)
        val mdia = box("mdia", box("mdhd", mdhd) + box("hdlr", hdlr) + minf)
        return box("trak", box("tkhd", tkhd) + mdia)
    }

    /** ISO 639-2/T code packed into 15 bits ("und" when unknown). */
    private fun packLang(lang: String?): Int {
        val three = lang?.let { runCatching { Locale(it.substringBefore('-')).isO3Language }.getOrNull() }
            ?.takeIf { it.length == 3 && it.all { c -> c in 'a'..'z' } } ?: "und"
        return three.fold(0) { acc, c -> (acc shl 5) or (c - 0x60).code }
    }

    private fun data(block: DataOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().also { DataOutputStream(it).apply(block).flush() }.toByteArray()

    /** Full box payload: version 0 + flags, then the body. */
    private fun full(flags: Int, block: DataOutputStream.() -> Unit) = data { writeInt(flags); block() }

    private fun box(type: String, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + payload.size).putInt(8 + payload.size).put(type.toByteArray(Charsets.ISO_8859_1)).put(payload).array()
}
