package com.anees.novamobile.download

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.exoplayer.offline.Download
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.anees.novamobile.data.api.RetrofitClient
import com.anees.novamobile.data.local.LocalStore
import com.anees.novamobile.data.repository.MediaRepository
import com.anees.novamobile.ui.player.OpenSubs
import com.anees.novamobile.ui.player.StreamExtractor
import com.anees.novamobile.ui.player.SubtitleCleaner
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

/**
 * Subtitles timed to the downloaded file's own audio (the idea behind ffsubsync / alass):
 * the audio is decoded once into a speech map (is someone talking in each 50 ms slice?), every
 * subtitle version is scored against it at every shift and common frame-rate stretch
 * (23.976 / 24 / 25 fps), and the version that lines up best is saved, shifted into place.
 */
object SubAligner {
    private const val TAG = "NovaTV"
    private const val FRAME_MS = 50
    /** Audio looked at: the first 50 minutes is plenty to find a shift and a stretch. */
    private const val MAX_FRAMES = 50 * 60 * 1000 / FRAME_MS
    /** Shifts tried: up to 5 minutes either way. */
    private const val MAX_LAG = 5 * 60 * 1000 / FRAME_MS
    /** Bump when the method changes, so finished downloads are matched again. */
    private const val VERSION = 3
    private val SCALES = doubleArrayOf(1.0, 25 / 23.976, 23.976 / 25, 24 / 23.976, 23.976 / 24, 25.0 / 24, 24.0 / 25)

    private val lock = Mutex()
    private val repo by lazy { MediaRepository() }

    /** How well a subtitle lines up: [score] after moving it by [scale]·t + [offsetMs]; [base] as it was. */
    class Fit(val score: Double, val scale: Double, val offsetMs: Long, val base: Double, val confident: Boolean = false, val peak: Double = 0.0) {
        val moved get() = scale != 1.0 || offsetMs != 0L
        override fun toString() = "score %.3f (was %.3f), shift %+d ms, stretch %.4f, peak %.2f%s".format(score, base, offsetMs, scale, peak, if (confident) "" else " (unsure)")
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("sub_align", Context.MODE_PRIVATE)
    fun done(ctx: Context, id: String) = prefs(ctx).getInt(id, 0) >= VERSION

    /** After a download finishes: match its subtitles in the background. */
    @OptIn(DelicateCoroutinesApi::class)
    fun enqueue(ctx: Context, id: String, then: (changed: Boolean) -> Unit = {}) {
        val app = ctx.applicationContext
        GlobalScope.launch(Dispatchers.IO) {
            val changed = runCatching { withTimeoutOrNull(20 * 60_000L) { ensure(app, id) }?.outcome == Outcome.CHANGED }.onFailure { Log.w(TAG, "subtitle match $id failed", it) }.getOrDefault(false)
            withContext(Dispatchers.Main) { then(changed) }
        }
    }

    /** What a match run did. [file] is your language's subtitle afterwards, when there is one. */
    enum class Outcome { CHANGED, ALREADY_GOOD, NO_AUDIO, NO_SUBTITLES, SKIPPED }
    class Result(val outcome: Outcome, val file: File? = null)

    /**
     * Picks and times the subtitles of download [id] once: saved files, the file's own (MKV) subtitles and
     * OpenSubtitles versions all compete. [force] runs again even when it was done before.
     */
    suspend fun ensure(ctx: Context, id: String, force: Boolean = false): Result = lock.withLock {
        if (!force && done(ctx, id)) return Result(Outcome.SKIPPED)
        val d = NovaDownloads.get(ctx, id)?.takeIf { it.state == Download.STATE_COMPLETED } ?: return Result(Outcome.SKIPPED)
        val meta = DownloadMeta.of(d) ?: return Result(Outcome.SKIPPED)
        val speech = speech(ctx, d)
        if (speech == null) { prefs(ctx).edit().putInt(id, VERSION).apply(); return Result(Outcome.NO_AUDIO) }

        val saved = (meta.subtitles ?: listOfNotNull(meta.subtitle)).mapIndexed { i, p -> File(p) to (meta.subtitleLangs?.getOrNull(i) ?: "") } +
            NovaDownloads.lateSubtitles(id)
        val files = saved.filter { it.first.exists() && it.first.length() > 20 }.distinctBy { it.first.absolutePath }
        val want = LocalStore.subtitleFor("${meta.card.type}:${meta.card.id}")?.takeIf { it != "off" } ?: "en"
        var changed = false

        // Your language: every version we have or can find competes, the best one is kept
        val mine = files.filter { it.second == want || (it.second.isBlank() && want == "en") }
        val builtIn = runCatching { EmbeddedSubs.extract(ctx, d, want) }
            .onFailure { Log.w(TAG, "built-in subtitles of $id unreadable: ${it.message}") }.getOrDefault(emptyList())
            .map { it to "built-in ${it.name}" }
        // Subtitles that came with the video (the source's, or built into the file) are made for it: they are kept,
        // and only moved when the audio says so clearly. OpenSubtitles is asked only when the video brought none.
        val withVideo = mine.map { it.first to it.first.name } + builtIn
        val online = if (withVideo.isNotEmpty()) emptyList() else runCatching {
            val t = StreamExtractor.resolveTarget(meta.card, meta.season, meta.episode, repo) ?: return@runCatching emptyList()
            withTimeoutOrNull(30_000) { OpenSubs.versions(t, repo, listOf(want)) }.orEmpty()
                .filter { it.lang == want }.take(6)
                .mapNotNull { v -> runCatching { SubtitleCleaner.cleaned(ctx, v.url, RetrofitClient.base, emptyMap()) }.getOrNull()?.let { it to v.release } }
        }.getOrDefault(emptyList())
        val candidates = withVideo + online
        val scored = candidates.mapNotNull { (f, name) -> read(f)?.let { c -> Triple(f, c, fit(speech, c)).also { Log.i(TAG, "subtitles $id  $name: ${it.third}") } } }
        // A sure match wins; with none, the video's own subtitle (or the most downloaded one) as it is
        val best = scored.filter { it.third.confident }.maxByOrNull { it.third.score }
            ?: scored.firstOrNull()?.let { (f, c, fit) -> Triple(f, c, Fit(fit.base, 1.0, 0, fit.base)) }
        var out: File? = mine.firstOrNull()?.first
        if (best != null) {
            val (src, cues, fit) = best
            Log.i(TAG, "subtitles $id: using ${candidates.firstOrNull { it.first == src }?.second}: $fit")
            val fromFile = builtIn.any { it.first == src }
            if (fromFile && !fit.moved) {
                out = null   // the file's own track is right as it is: the player keeps showing it
            } else {
                val target = out ?: NovaDownloads.lateSubtitleFile(id, 0, want)
                if (src != target || fit.moved) { writeVtt(target, apply(cues, fit)); changed = true }
                out = target
            }
        }
        builtIn.forEach { runCatching { it.first.delete() } }
        // Other languages: each one moved into place
        files.filter { it !in mine }.forEach { (f, _) ->
            val cues = read(f) ?: return@forEach
            val fit = fit(speech, cues)
            if (fit.moved && fit.confident) { writeVtt(f, apply(cues, fit)); changed = true; Log.i(TAG, "subtitles $id ${f.name}: $fit") }
        }
        prefs(ctx).edit().putInt(id, VERSION).apply()
        Result(when { changed -> Outcome.CHANGED; best == null -> Outcome.NO_SUBTITLES; else -> Outcome.ALREADY_GOOD }, out)
    }

    /** Times [src] to download [id]'s audio and saves it as [out]. False when the audio can't be read. */
    suspend fun alignInto(ctx: Context, id: String, src: File, out: File): Boolean = lock.withLock {
        val d = NovaDownloads.get(ctx, id)?.takeIf { it.state == Download.STATE_COMPLETED } ?: return false
        val cues = read(src) ?: return false
        val speech = speech(ctx, d) ?: return false
        val fit = fit(speech, cues)
        Log.i(TAG, "subtitles $id picked ${src.name}: $fit")
        writeVtt(out, if (fit.confident) apply(cues, fit) else cues)
        true
    }

    /** Best-lining-up subtitle of [files]: the file, its cues and how to move them. */
    fun bestOf(speech: FloatArray, files: List<File>): Triple<File, List<SubText.Cue>, Fit>? =
        files.mapNotNull { f -> read(f)?.let { c -> Triple(f, c, fit(speech, c)) } }.maxByOrNull { it.third.score }

    private fun read(f: File) = runCatching { SubText.cues(f.readText()) }.getOrNull()?.takeIf { it.size >= 10 }

    fun apply(cues: List<SubText.Cue>, fit: Fit): List<SubText.Cue> = if (!fit.moved) cues else cues.mapNotNull {
        val s = (it.startMs * fit.scale).toLong() + fit.offsetMs
        val e = (it.endMs * fit.scale).toLong() + fit.offsetMs
        if (e <= 0) null else SubText.Cue(s.coerceAtLeast(0), e, it.text)
    }

    fun writeVtt(f: File, cues: List<SubText.Cue>) {
        fun t(ms: Long) = "%02d:%02d:%02d.%03d".format(java.util.Locale.US, ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
        val text = buildString {
            append("WEBVTT\n\n")
            cues.forEach { c -> append(t(c.startMs)).append(" --> ").append(t(c.endMs)).append('\n')
                append(c.text.replace("&", "&amp;")).append("\n\n") }
        }
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.writeText(text); tmp.delete() }
    }

    // ---------- matching ----------

    /** Scores [cues] against the speech map at every shift and stretch; keeps the original unless clearly worse. */
    fun fit(speech: FloatArray, cues: List<SubText.Cue>): Fit {
        val n = speech.size
        var m = 1; while (m < 2 * n) m = m shl 1
        val aRe = DoubleArray(m); val aIm = DoubleArray(m)
        val mean = speech.average()
        for (i in 0 until n) aRe[i] = speech[i] - mean
        fft(aRe, aIm, false)
        var base = 0.0
        var best = Fit(-1.0, 1.0, 0, 0.0)
        for (scale in SCALES) {
            val s = DoubleArray(n) { -1.0 }
            for (c in cues) {
                val from = ((c.startMs * scale) / FRAME_MS).toInt().coerceAtLeast(0)
                val to = ((c.endMs * scale) / FRAME_MS).toInt().coerceAtMost(n)
                for (i in from until to) s[i] = 1.0
            }
            val sm = s.average()
            val re = DoubleArray(m); val im = DoubleArray(m)
            for (i in 0 until n) re[i] = s[i] - sm
            fft(re, im, false)
            // corr[o] = Σ a[i + o]·s[i]: A · conj(S)
            for (k in 0 until m) {
                val r = aRe[k] * re[k] + aIm[k] * im[k]
                val i = aIm[k] * re[k] - aRe[k] * im[k]
                re[k] = r; im[k] = i
            }
            fft(re, im, true)
            val stretchPenalty = if (scale == 1.0) 0.0 else 0.004
            if (scale == 1.0) base = re[0] / n
            val lags = DoubleArray(2 * MAX_LAG + 1) { re[(it - MAX_LAG).let { l -> if (l >= 0) l else m + l }] / n }
            var bi = 0
            for (i in lags.indices) if (lags[i] > lags[bi]) bi = i
            val v = lags[bi] - stretchPenalty
            if (v > best.score) {
                // A real match is one sharp peak: well above every other shift (more than 3 s away) and not at the edge
                var second = Double.NEGATIVE_INFINITY
                for (i in lags.indices) if (kotlin.math.abs(i - bi) > 60 && lags[i] > second) second = lags[i]
                val median = lags.sortedArray()[lags.size / 2]
                val peak = (lags[bi] - second) / (lags[bi] - median).coerceAtLeast(1e-9)
                val edge = kotlin.math.abs(bi - MAX_LAG) > MAX_LAG - 40
                best = Fit(v, scale, (bi - MAX_LAG).toLong() * FRAME_MS, 0.0, confident = peak >= 0.25 && !edge, peak = peak)
            }
        }
        // Small gains, or no clear peak, are noise: the subtitle is left as it is
        return if (!best.confident || best.score - base < 0.01) Fit(base, 1.0, 0, base, best.confident && best.score - base < 0.01, best.peak)
        else Fit(best.score, best.scale, best.offsetMs, base, true, best.peak)
    }

    private fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = 2 * PI / len * (if (inverse) 1 else -1)
            val wr = cos(ang); val wi = sin(ang)
            val half = len / 2
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until half) {
                    val a = i + k; val b = a + half
                    val xr = re[b] * cr - im[b] * ci
                    val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    val ncr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) { val inv = 1.0 / n; for (q in 0 until n) { re[q] = re[q] * inv; im[q] = im[q] * inv } }
    }

    // ---------- speech map ----------

    private fun cacheFile(ctx: Context, id: String) = File(File(NovaDownloads.dir, "subs").apply { mkdirs() }, "${id.hashCode()}.speech")

    /** Speech map of the download (cached beside its subtitles): +1 talking … -1 quiet, per 50 ms. */
    suspend fun speech(ctx: Context, d: Download): FloatArray? {
        val cache = cacheFile(ctx, d.request.id)
        if (cache.exists()) runCatching {
            DataInputStream(cache.inputStream().buffered()).use { s -> FloatArray(s.readInt()) { s.readFloat() } }
        }.getOrNull()?.takeIf { it.size > 600 }?.let { return it }
        val energy = decode(ctx, d) ?: return null
        if (energy.size < 600) { Log.w(TAG, "speech map: only ${energy.size} frames"); return null }
        val map = toSpeech(energy)
        runCatching { DataOutputStream(cache.outputStream().buffered()).use { s -> s.writeInt(map.size); map.forEach { s.writeFloat(it) } } }
        return map
    }

    /** Loudness of the voice band → soft talking / quiet values, thresholded per film. */
    private fun toSpeech(energy: FloatArray): FloatArray {
        val logs = DoubleArray(energy.size) { ln(energy[it] + 1e-9) }
        val sorted = logs.sortedArray()
        val p10 = sorted[sorted.size / 10]; val p90 = sorted[sorted.size * 9 / 10]
        val range = (p90 - p10).coerceAtLeast(1e-3)
        val thr = p10 + 0.45 * range
        return FloatArray(logs.size) { ((logs[it] - thr) / (0.25 * range)).coerceIn(-1.0, 1.0).toFloat() }
    }

    /** Decodes the download's audio (audio-only export, output thrown away) and returns voice-band energy per frame. */
    private suspend fun decode(ctx: Context, d: Download): FloatArray? = withContext(Dispatchers.Main) {
        val meta = DownloadMeta.of(d) ?: return@withContext null
        val tap = EnergyTap()
        val out = File(ctx.cacheDir, "speech_${d.request.id.hashCode()}.m4a").apply { delete() }
        val started = System.currentTimeMillis()
        val ok = suspendCancellableCoroutine { cont ->
            val sonic = SonicAudioProcessor().apply { setOutputSampleRateHz(16_000) }
            lateinit var transformer: Transformer
            var finished = false
            fun finish(v: Boolean) { if (!finished) { finished = true; if (cont.isActive) cont.resume(v) } }
            tap.onFull = { Handler(Looper.getMainLooper()).post { if (!finished) { transformer.cancel(); finish(true) } } }
            transformer = Transformer.Builder(ctx)
                .setAssetLoaderFactory(PublicExport.assetLoader(ctx, meta, null, video = false, audio = true))
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) = finish(true)
                    override fun onError(composition: Composition, result: ExportResult, e: ExportException) {
                        Log.w(TAG, "speech map: audio couldn't be read (${e.errorCodeName})"); finish(tap.frames.size > 600)
                    }
                })
                .build()
            val item = EditedMediaItem.Builder(d.request.toMediaItem()).setRemoveVideo(true)
                .setEffects(Effects(listOf<AudioProcessor>(tap, sonic), emptyList())).build()
            runCatching { transformer.start(item, out.absolutePath) }.onFailure { Log.w(TAG, "speech map: ${it.message}"); finish(false) }
            cont.invokeOnCancellation { Handler(Looper.getMainLooper()).post { runCatching { transformer.cancel() } } }
        }
        out.delete()
        Log.i(TAG, "speech map: ${tap.frames.size} frames in ${(System.currentTimeMillis() - started) / 1000}s")
        if (ok) tap.frames.toFloatArray() else null
    }

    /**
     * Passes audio through untouched while adding up the voice band (≈150 Hz–3.4 kHz, one-pole filters)
     * per 50 ms of the first [MAX_FRAMES].
     */
    private class EnergyTap : BaseAudioProcessor() {
        val frames = ArrayList<Float>()
        @Volatile var onFull: (() -> Unit)? = null
        private var rate = 0; private var channels = 0; private var float = false
        private var perFrame = 0; private var count = 0; private var sum = 0.0
        private var hpPrevIn = 0.0; private var hpPrevOut = 0.0; private var lp = 0.0
        private var hpA = 0.0; private var lpA = 0.0

        override fun onConfigure(input: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
            if (input.encoding != C.ENCODING_PCM_16BIT && input.encoding != C.ENCODING_PCM_FLOAT) throw AudioProcessor.UnhandledAudioFormatException(input)
            rate = input.sampleRate; channels = input.channelCount; float = input.encoding == C.ENCODING_PCM_FLOAT
            perFrame = rate * FRAME_MS / 1000
            val dt = 1.0 / rate
            hpA = 1.0 / (2 * PI * 150 * dt + 1)
            val rc = 1.0 / (2 * PI * 3400); lpA = dt / (rc + dt)
            return AudioProcessor.AudioFormat(input.sampleRate, 1, C.ENCODING_PCM_16BIT)
        }

        override fun queueInput(inputBuffer: ByteBuffer) {
            val size = inputBuffer.remaining()
            if (size == 0) return
            val b = inputBuffer.order(ByteOrder.nativeOrder())
            val bytes = if (float) 4 else 2
            val samples = size / (bytes * channels)
            // Output is mono 16-bit: cheap to encode, and any channel layout (3.0, 5.1, 7.1) works
            val out = replaceOutputBuffer(samples * 2).order(ByteOrder.nativeOrder())
            for (s in 0 until samples) {
                var v = 0.0
                for (c in 0 until channels) v += if (float) b.float.toDouble() else b.short / 32768.0
                v /= channels
                out.putShort((v.coerceIn(-1.0, 1.0) * 32767).toInt().toShort())
                if (frames.size >= MAX_FRAMES) continue
                val hp = hpA * (hpPrevOut + v - hpPrevIn); hpPrevIn = v; hpPrevOut = hp
                lp += lpA * (hp - lp)
                sum += lp * lp
                if (++count >= perFrame) {
                    frames += (sum / count).toFloat(); sum = 0.0; count = 0
                    if (frames.size == MAX_FRAMES) onFull?.invoke()
                }
            }
            inputBuffer.position(inputBuffer.limit())
            out.flip()
        }
    }
}
