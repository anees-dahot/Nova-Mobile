package com.anees.novamobile.download

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.offline.Download
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.CueDecoder
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import com.anees.novamobile.data.model.LangTags
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Subtitles built into a downloaded MKV, read straight from the saved file so they can be timed to its
 * audio like any other. Only text tracks (SubRip, SSA, WebVTT); picture subtitles (PGS) are skipped.
 */
object EmbeddedSubs {
    private const val TAG = "NovaTV"

    /** One WebVTT file (in the cache) per built-in text track in [lang] (untagged counts as English), forced ones left out. */
    fun extract(ctx: Context, d: Download, lang: String): List<File> {
        val tracks = read(ctx, d) ?: return emptyList()
        val dir = File(ctx.cacheDir, "embedded_subs").apply { mkdirs() }
        return tracks.filter { t ->
            val l = LangTags.norm(t.format?.language)
            val forced = (t.format?.selectionFlags ?: 0) and C.SELECTION_FLAG_FORCED != 0 || t.format?.label?.contains("forced", true) == true
            !forced && (l == lang || (l == null && lang == "en")) && t.cues.size >= 10
        }.mapIndexed { i, t ->
            File(dir, "${d.request.id.hashCode()}_$i.vtt").also { SubAligner.writeVtt(it, t.cues.sortedBy { c -> c.startMs }) }
        }
    }

    private class Track : TrackOutput {
        var format: Format? = null
        val cues = mutableListOf<SubText.Cue>()
        private val pending = ByteArrayOutputStream()
        private val scratch = ByteArray(8192)
        private val decoder = CueDecoder()

        override fun format(format: Format) { this.format = format }
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val n = input.read(scratch, 0, minOf(length, scratch.size))
            if (n == C.RESULT_END_OF_INPUT) { if (allowEndOfInput) return C.RESULT_END_OF_INPUT; throw java.io.EOFException() }
            pending.write(scratch, 0, n)
            return n
        }
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            val b = ByteArray(length); data.readBytes(b, 0, length); pending.write(b)
        }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            val all = pending.toByteArray()
            val end = all.size - offset
            val start = end - size
            if (start >= 0 && size > 0) runCatching {
                val c = decoder.decode(timeUs, all, start, size)
                val text = c.cues.mapNotNull { it.text?.toString()?.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
                val dur = if (c.durationUs == C.TIME_UNSET) 3_000_000 else c.durationUs
                if (text.isNotEmpty()) cues += SubText.Cue(c.startTimeUs / 1000, (c.startTimeUs + dur) / 1000, text)
            }
            pending.reset()
            if (offset > 0) pending.write(all, end, offset)
        }
    }

    private class Ignore : TrackOutput {
        private val skip = ByteArray(16384)
        /** Furthest audio/video time seen, to stop once the matched part of the film is read. */
        @Volatile var lastUs = 0L
        override fun format(format: Format) {}
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val n = input.read(skip, 0, minOf(length, skip.size))
            if (n == C.RESULT_END_OF_INPUT && !allowEndOfInput) throw java.io.EOFException()
            return n
        }
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) { data.skipBytes(length) }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) { if (timeUs > lastUs) lastUs = timeUs }
    }

    /** The speech map covers 50 minutes; a little more is read so stretched subtitles still have cues there. */
    private const val READ_US = 60L * 60 * 1_000_000

    /** Every text track of the saved file, or null when it isn't an MKV. Reads the whole file (seconds for a local copy). */
    private fun read(ctx: Context, d: Download): List<Track>? {
        val started = System.currentTimeMillis()
        val factory = NovaDownloads.playbackFactory(ctx)
        val uri = d.request.uri
        val texts = mutableListOf<Track>()
        val ignore = Ignore()
        val output = object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = if (type == C.TRACK_TYPE_TEXT) Track().also { texts += it } else ignore
            override fun endTracks() {}
            override fun seekMap(seekMap: SeekMap) {}
        }
        val ext = MatroskaExtractor(DefaultSubtitleParserFactory(), MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES)
        var position = 0L
        var source: DataSource? = null
        fun open(at: Long): DefaultExtractorInput {
            source?.close()
            val s = factory.createDataSource().also { source = it }
            val len = s.open(DataSpec.Builder().setUri(uri).setPosition(at).setKey(d.request.customCacheKey).build())
            return DefaultExtractorInput(s, at, if (len == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else at + len)
        }
        try {
            var input = open(0)
            if (!ext.sniff(input)) return null
            input.resetPeekPosition()
            ext.init(output)
            val holder = PositionHolder()
            try {
                while (ignore.lastUs < READ_US) {
                    when (ext.read(input, holder)) {
                        Extractor.RESULT_END_OF_INPUT -> break
                        Extractor.RESULT_SEEK -> { Log.i(TAG, "built-in subtitles: seek to ${holder.position}"); position = holder.position; input = open(position) }
                    }
                }
            } catch (e: Exception) {
                // A damaged stretch later in the file: what was read up to here is still good
                Log.w(TAG, "built-in subtitles: stopped at byte ${input.position}, ${ignore.lastUs / 60_000_000} min: ${e.message}")
                if (texts.sumOf { it.cues.size } == 0) throw e
            }
        } finally {
            runCatching { source?.close() }
            ext.release()
        }
        Log.i(TAG, "built-in subtitles: ${texts.joinToString { "${it.format?.language}/${it.format?.label}: ${it.cues.size} cues" }} in ${(System.currentTimeMillis() - started) / 1000}s")
        return texts
    }
}
