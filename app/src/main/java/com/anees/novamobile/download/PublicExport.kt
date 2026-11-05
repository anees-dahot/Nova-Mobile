package com.anees.novamobile.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.anees.novamobile.data.model.LangTags
import com.anees.novamobile.data.model.MediaType
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Copies finished downloads out of the app's private cache into the phone, one folder per title:
 *   Movies/Nova/Movies/<Title (Year)>/<Title (Year)>.mp4
 *   Movies/Nova/Shows/<Title>/Season 01/<Title> S01E02.mp4   (Anime/ for anime)
 * Streams are remuxed (no re-encode). Every audio language of the download is kept in that one
 * MP4: the video is exported with the preferred language, the other languages are exported
 * audio-only and merged with MediaMuxer (multi-track MP4 is supported from Android 8 / API 26).
 * Subtitles are copied beside the video with the same name.
 *
 * Every file is written to app storage first and then published through MediaStore. Writing
 * straight into Movies/ by path is refused by scoped storage on many Android 11+ phones, which
 * was why saved files could be missing from the gallery.
 */
@OptIn(UnstableApi::class)
object PublicExport {
    private const val TAG = "PublicExport"
    private const val ROOT = "Nova"
    private const val EXPORT_VERSION = 2
    private val main = Handler(Looper.getMainLooper())

    private fun autoOn(ctx: Context): Boolean {
        com.anees.novamobile.data.local.LocalStore.init(ctx)
        return com.anees.novamobile.data.local.LocalStore.settings().autoSaveGallery
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("public_export", Context.MODE_PRIVATE)

    /** A copy made by this version of the exporter (multi-audio included). */
    fun exported(ctx: Context, id: String): Boolean =
        prefs(ctx).getInt("v_$id", 0) >= EXPORT_VERSION || prefs(ctx).getBoolean(id, false)

    private fun exportVersion(ctx: Context, id: String) = prefs(ctx).getInt("v_$id", 0)

    private fun markExported(ctx: Context, id: String) {
        prefs(ctx).edit().putBoolean(id, true).putInt("v_$id", EXPORT_VERSION).apply()
    }

    /** One export request. Automatic gallery copies have no callback and give way to manual saves. */
    private class Request(val id: String, val tree: Uri?, val auto: Boolean = false) {
        val callbacks = mutableListOf<(Boolean, String) -> Unit>()
        var note: SaveProgress? = null
        fun done(ok: Boolean, msg: String) {
            note?.done(); note = null
            callbacks.forEach { it(ok, msg) }
            callbacks.clear()
        }
    }

    private class Job(
        val req: Request,
        val download: Download,
        val meta: DownloadMeta,
        val langs: List<String>,
        val multi: Boolean,
        val base: File,
    ) {
        var preferred: String? = null
        var baseLang: String? = null
        var failedExtras = 0
        val extras = ArrayDeque<Pair<String, File>>()
        val audios = mutableListOf<Pair<String, File>>()
    }

    private val queue = ArrayDeque<Request>()
    private var running: Request? = null

    /** Something is being saved or waits to be (the foreground service stays up while true). */
    val busy get() = running != null || queue.isNotEmpty()
    val pending get() = queue.size

    /**
     * "Save to phone" from the downloads screens: export now into [tree] (a folder the user picked)
     * or Movies/Nova when null. Runs even if the download was already copied before.
     */
    fun save(ctx: Context, id: String, tree: Uri?, onDone: (Boolean, String) -> Unit) = main.post {
        // Already exporting to the same place: only attach the callback, don't export twice
        running?.takeIf { it.id == id && it.tree == tree }?.let { it.callbacks += onDone; return@post }
        queue.firstOrNull { it.id == id && it.tree == tree }?.let { it.callbacks += onDone; return@post }
        // The automatic gallery copy is superseded by this explicit request
        queue.removeAll { it.id == id && it.tree == null && it.auto }
        // Manual saves keep the order they were requested in and go ahead of automatic copies
        val req = Request(id, tree).apply { callbacks += onDone }
        val firstAuto = queue.indexOfFirst { it.auto }
        if (firstAuto < 0) queue.addLast(req) else queue.add(firstAuto, req)
        next(ctx.applicationContext)
    }

    /** Queue one finished download for the automatic copy in Movies/Nova (safe to call many times). */
    fun enqueue(ctx: Context, id: String) = main.post {
        if (!autoOn(ctx)) return@post
        val d = NovaDownloads.get(ctx, id) ?: return@post
        if (DownloadMeta.of(d) == null) return@post
        // Copies from the old path-based exporter may not exist at all (scoped storage), so every
        // download without a v2 flag is copied once more through MediaStore.
        if (exportVersion(ctx, id) >= EXPORT_VERSION || running?.id == id || queue.any { it.id == id }) return@post
        queue.addLast(Request(id, null, auto = true))
        next(ctx.applicationContext)
    }

    /**
     * Remux target. Not a cache dir: Android purges an app's cache over its quota, and every export
     * now goes through this file before being published into the gallery.
     */
    private fun tmpDir(ctx: Context) = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "export_tmp").apply { mkdirs() }

    /** Finished downloads from before this feature, old single-audio copies, or interrupted exports. */
    fun catchUp(ctx: Context) {
        if (running == null) tmpDir(ctx).listFiles()?.forEach { it.delete() }   // left by a killed export
        if (!autoOn(ctx)) return
        NovaDownloads.all(ctx).forEach { d ->
            if (d.state != Download.STATE_COMPLETED) return@forEach
            val m = DownloadMeta.of(d) ?: return@forEach
            val id = d.request.id
            if (exportVersion(ctx, id) >= EXPORT_VERSION) return@forEach
            val multi = (m.audioLangs.orEmpty().mapNotNull { LangTags.norm(it) }.distinct().size) > 1
            // The old exporter set its flag even when scoped storage silently blocked the write.
            // Keep a copy that really exists; otherwise (or for a multi-audio upgrade) export again.
            if (!multi && copyExists(ctx, m)) prefs(ctx).edit().putInt("v_$id", EXPORT_VERSION).apply()
            else enqueue(ctx, id)
        }
    }

    /** Whether the gallery already has this download's video from an older Nova version. */
    private fun copyExists(ctx: Context, m: DownloadMeta): Boolean {
        val (folder, base) = place(m)
        if (Build.VERSION.SDK_INT >= 29) {
            val rel = "${Environment.DIRECTORY_MOVIES}/$folder/"
            return runCatching {
                ctx.contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                    arrayOf(rel, "$base.mp4"), null,
                )?.use { it.moveToFirst() } ?: false
            }.getOrDefault(false)
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), folder)
        return File(dir, "$base.mp4").exists()
    }

    /**
     * Deletes the phone copy (video + subtitles in Movies/Nova). Only files this install wrote can be
     * removed on Android 10+; returns false when nothing could be deleted.
     */
    fun removeCopy(ctx: Context, d: Download): Boolean {
        val id = d.request.id
        val m = DownloadMeta.of(d) ?: return false
        queue.removeAll { req ->
            (req.id == id).also { if (it) req.done(false, "Cancelled") }
        }
        prefs(ctx).edit().remove(id).remove("v_$id").apply()
        val (folder, base) = place(m)
        var removed = 0
        if (Build.VERSION.SDK_INT >= 29) {
            val r = ctx.contentResolver
            val rel = "${Environment.DIRECTORY_MOVIES}/$folder/"
            for (coll in listOf(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY))) {
                runCatching {
                    r.query(coll, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME), "${MediaStore.MediaColumns.RELATIVE_PATH}=?", arrayOf(rel), null)?.use { c ->
                        while (c.moveToNext()) {
                            val name = c.getString(1) ?: continue
                            if (name == "$base.mp4" || name.startsWith("$base.")) removed += runCatching { r.delete(android.content.ContentUris.withAppendedId(coll, c.getLong(0)), null, null) }.getOrDefault(0)
                        }
                    }
                }
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), folder)
            dir.listFiles()?.filter { it.name.startsWith("$base.") }?.forEach { if (it.delete()) removed++ }
        }
        Log.i(TAG, "removed $removed phone file(s) for $id")
        return removed > 0
    }

    // ── Queue ──────────────────────────────────────────────────────────────────

    private fun next(ctx: Context) {
        if (running != null) return
        val req = queue.removeFirstOrNull() ?: return
        val d = NovaDownloads.get(ctx, req.id)
        val m = d?.let { DownloadMeta.of(it) }
        if (d == null || m == null || d.state != Download.STATE_COMPLETED) { fail(ctx, req, "Download not finished"); return }
        if (Build.VERSION.SDK_INT < 29 && ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "no storage permission, ${req.id} kept in app only"); fail(ctx, req, "Storage permission needed"); return
        }
        val langList = m.audioLangs.orEmpty().mapNotNull { LangTags.norm(it) }.distinct()
        val multi = langList.size > 1
        val need = (d.bytesDownloaded * 1.05).toLong()   // fast path writes the MP4 once, straight to its place
        val free = runCatching { android.os.StatFs((ctx.getExternalFilesDir(null) ?: ctx.filesDir).absolutePath).availableBytes }.getOrDefault(Long.MAX_VALUE)
        if (free < need) { Log.w(TAG, "${req.id}: ${free shr 20} MB free, need ${need shr 20} MB"); fail(ctx, req, "Not enough space: ${need shr 20} MB needed"); return }
        running = req
        ExportService.start(ctx)   // keeps the save going when the app is left
        val preferred = (DownloadStarter.audioFor(m.card)?.let { LangTags.norm(it) }?.takeIf { it in langList }) ?: langList.firstOrNull()
        val base = File(tmpDir(ctx), "export_${req.id.hashCode()}.mp4").apply { delete() }
        val job = Job(req, d, m, langList, multi, base).apply { this.preferred = preferred }
        val note = SaveProgress(ctx, DownloadNotifier.name(m)).also { req.note = it }
        // Fast path: the pieces are already on the phone; join them into one MP4 with every audio
        // language, no re-encode (seconds, not minutes). Falls back to the Transformer steps.
        Thread {
            note.update(0f)
            val t0 = System.currentTimeMillis()
            val plan = FastExport.plan(ctx, d)
            val ok = plan != null && runCatching {
                val produce: (FileOutputStream) -> Unit = { out -> FastExport.write(ctx, plan, out) { note.update(it) } }
                if (req.tree != null) writeTree(ctx, req.tree, m, null, produce) else publish(ctx, m, null, produce)
            }.onFailure { Log.w(TAG, "${req.id}: fast save failed", it) }.isSuccess
            main.post {
                if (ok) {
                    Log.i(TAG, "${req.id}: fast save done in ${System.currentTimeMillis() - t0} ms (${(plan!!.bytes shr 20)} MB)")
                    val langs = langList.size
                    finish(ctx, job, true, "Saved ${DownloadNotifier.name(m)}" + if (langs > 1) " · $langs audio languages" else "")
                } else {
                    // Slow path re-encodes through temp files: needs about twice the space
                    if (free < (d.bytesDownloaded * 2.1).toLong()) { fail(ctx, req, "Not enough space: ${(d.bytesDownloaded * 2.1).toLong() shr 20} MB needed"); return@post }
                    note.update(-1f); startBase(ctx, job)
                }
            }
        }.start()
    }

    private fun fail(ctx: Context, req: Request, msg: String) {
        Log.w(TAG, "${req.id}: $msg")
        if (msg != "Cancelled") SaveProgress.result(ctx, false, "Couldn't save: $msg")
        req.done(false, msg)
        if (running === req) running = null
        next(ctx)
    }

    private fun finish(ctx: Context, job: Job, ok: Boolean, msg: String) {
        if (ok && job.req.tree == null) markExported(ctx, job.req.id)
        SaveProgress.result(ctx, ok, msg)
        job.req.done(ok, msg)
        if (running === job.req) running = null
        next(ctx)
    }

    // ── Export steps ───────────────────────────────────────────────────────────

    /** Step 1: video + the preferred audio language, so the expensive pass happens once. */
    private fun startBase(ctx: Context, job: Job) {
        if (!job.base.parentFile!!.exists()) job.base.parentFile!!.mkdirs()
        job.base.delete()
        val transformer = Transformer.Builder(ctx)
            .setAssetLoaderFactory(assetLoader(ctx, job.meta, job.preferred, video = true, audio = true))
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    Thread {
                        // The muxed language is in the file (mdhd); fall back to our pick if the source had no tag
                        val actual = audioLanguage(job.base) ?: job.preferred
                        val extras = if (job.multi) job.langs.filter { it != actual } else emptyList()
                        main.post {
                            job.baseLang = actual
                            if (extras.isEmpty()) publishJob(ctx, job)
                            else {
                                extras.forEachIndexed { i, lang ->
                                    job.extras += lang to File(job.base.parentFile, "audio_${job.req.id.hashCode()}_$i.mp4")
                                }
                                nextExtra(ctx, job)
                            }
                        }
                    }.start()
                }

                override fun onError(composition: Composition, result: ExportResult, e: ExportException) {
                    Log.w(TAG, "remux ${job.req.id} failed", e)
                    job.base.delete()
                    main.post {
                        // A language pick the stream can't resolve can fail the export: retry once with the default
                        if (job.preferred != null) { job.preferred = null; startBase(ctx, job) }
                        else finish(ctx, job, false, "This stream couldn't be converted to MP4")
                    }
                }
            })
            .build()
        transformer.start(job.download.request.toMediaItem(), job.base.absolutePath)
    }

    /** Step 2: one audio-only export per remaining language. A failed language is skipped. */
    private fun nextExtra(ctx: Context, job: Job) {
        val next = job.extras.removeFirstOrNull()
        if (next == null) { publishJob(ctx, job); return }
        val (lang, file) = next
        file.delete()
        val transformer = Transformer.Builder(ctx)
            .setAssetLoaderFactory(assetLoader(ctx, job.meta, lang, video = false, audio = true))
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    // Never merge a fallback track that the selector couldn't match: it would be a
                    // duplicate of another language under the wrong tag.
                    if (file.length() > 1_000 && audioLanguage(file) == LangTags.norm(lang)) job.audios += lang to file
                    else { Log.w(TAG, "audio $lang of ${job.req.id} came out as the default track"); file.delete(); job.failedExtras++ }
                    main.post { nextExtra(ctx, job) }
                }

                override fun onError(composition: Composition, result: ExportResult, e: ExportException) {
                    Log.w(TAG, "audio $lang of ${job.req.id} failed", e)
                    file.delete()
                    job.failedExtras++
                    main.post { nextExtra(ctx, job) }
                }
            })
            .build()
        val item = EditedMediaItem.Builder(job.download.request.toMediaItem()).setRemoveVideo(true).build()
        transformer.start(item, file.absolutePath)
    }

    /** Step 3: merge every audio track into the video file (one MP4), then publish it. */
    private fun publishJob(ctx: Context, job: Job) {
        Thread {
            val mergedFile = File(job.base.parentFile, "merged_${job.req.id.hashCode()}.mp4")
            val merged = if (job.audios.isNotEmpty()) runCatching {
                mergedFile.delete()
                merge(job.base, job.baseLang, job.audios, mergedFile)
                mergedFile
            }.onFailure {
                mergedFile.delete()
                Log.w(TAG, "multi-audio merge failed", it)
            }.getOrNull() else null
            val src = merged ?: job.base
            // Free the per-language exports before copying the merged file into the gallery.
            // Peak temp usage then stays near one copy instead of two.
            if (merged != null) {
                job.base.delete()
                job.audios.forEach { it.second.delete() }
            }
            val ok = runCatching {
                when {
                    job.req.tree != null -> writeTree(ctx, job.req.tree, job.meta, src)
                    else -> publish(ctx, job.meta, src)
                }
            }.onFailure { Log.w(TAG, "publish ${job.req.id} failed", it) }.isSuccess
            listOfNotNull(job.base, merged).forEach { it.delete() }
            job.audios.forEach { it.second.delete() }
            val name = DownloadNotifier.name(job.meta)
            val languages = if (merged != null) 1 + job.audios.size else 1
            val msg = when {
                !ok -> "Couldn't save $name"
                languages > 1 -> "Saved $name · $languages audio languages"
                job.audios.isNotEmpty() -> "Saved $name · preferred language only"
                else -> "Saved $name"
            }
            if (ok) Log.i(TAG, "${job.req.id} saved (${languages} audio, ${job.failedExtras} skipped)")
            main.post { finish(ctx, job, ok, msg) }
        }.start()
    }

    // ── Multi-audio merge ──────────────────────────────────────────────────────

    private class MuxTrack(val extractor: MediaExtractor, val from: Int, val to: Int) {
        var time = -1L
        var done = false
        var cap = 1 shl 20
        var buffer: ByteBuffer = ByteBuffer.allocateDirect(cap)
        val info = MediaCodec.BufferInfo()
    }

    /**
     * Adds every video/audio track of [base] and the audio track of every file in [extras] to one
     * MP4. MediaMuxer supports multiple audio tracks in MP4 from API 26 (minSdk is 26).
     * Each track gets its own extractor so their samples can be written interleaved: MediaMuxer
     * wants samples in roughly chronological order, not one whole track after another.
     */
    private fun merge(base: File, baseLang: String?, extras: List<Pair<String, File>>, out: File) {
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val tracks = mutableListOf<MuxTrack>()
        var rotation = 0
        try {
            fun addFile(file: File, lang: String?) {
                val probe = MediaExtractor().apply { setDataSource(file.absolutePath) }
                val formats = try {
                    (0 until probe.trackCount).map { probe.getTrackFormat(it) }
                } finally {
                    probe.release()
                }
                formats.forEachIndexed { i, format ->
                    val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                    val isAudio = mime.startsWith("audio/")
                    if (!isAudio && !mime.startsWith("video/")) return@forEachIndexed
                    if (isAudio) {
                        lang?.let { code -> iso3(code)?.let { format.setString(MediaFormat.KEY_LANGUAGE, it) } }
                    } else if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                        rotation = format.getInteger(MediaFormat.KEY_ROTATION)
                    }
                    val ex = MediaExtractor().apply {
                        setDataSource(file.absolutePath)
                        selectTrack(i)
                    }
                    // getInteger(key, default) is API 29+, but this app runs on 26+
                    val max = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                    } else 0
                    tracks += MuxTrack(ex, i, muxer.addTrack(format)).apply {
                        cap = max.coerceAtLeast(1 shl 20)
                        buffer = ByteBuffer.allocateDirect(cap)
                        time = ex.sampleTime
                        done = time < 0
                    }
                }
            }
            addFile(base, baseLang)       // video + preferred audio
            extras.forEach { (lang, file) -> addFile(file, lang) }
            if (rotation != 0) runCatching { muxer.setOrientationHint(rotation) }
            muxer.start()
            while (true) {
                val t = tracks.filter { !it.done }.minByOrNull { it.time } ?: break
                val size = readSample(t)
                if (size < 0) { t.done = true; continue }
                val flags = if (t.extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                    MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                t.info.set(0, size, t.time, flags)
                muxer.writeSampleData(t.to, t.buffer, t.info)
                t.extractor.advance()
                t.time = t.extractor.sampleTime
                if (t.time < 0) t.done = true
            }
            muxer.stop()
        } catch (e: Throwable) {
            // Finalize what was written, then let the caller fall back to the base export
            runCatching { muxer.stop() }
            throw e
        } finally {
            runCatching { muxer.release() }
            tracks.forEach { runCatching { it.extractor.release() } }
        }
    }

    /** Reads the next sample, growing the buffer for unusually large video samples. */
    private fun readSample(t: MuxTrack): Int {
        while (true) {
            val size = try {
                t.extractor.readSampleData(t.buffer, 0)
            } catch (_: IllegalArgumentException) {
                if (t.cap >= 256 shl 20) return -1
                t.cap *= 2
                t.buffer = ByteBuffer.allocateDirect(t.cap)
                continue
            }
            return size
        }
    }

    /** "hi" / "hin" → "hin" (ISO 639-2, what an MP4 track language box wants). */
    private fun iso3(code: String?): String? {
        val norm = LangTags.norm(code) ?: return null
        return runCatching { java.util.Locale(norm).isO3Language }.getOrNull()?.takeIf { it.length == 3 && it != "und" }
    }

    /** The first audio track's language in an exported MP4, if the muxer wrote one. */
    private fun audioLanguage(file: File): String? {
        var ex: MediaExtractor? = null
        return try {
            ex = MediaExtractor().apply { setDataSource(file.absolutePath) }
            for (i in 0 until ex.trackCount) {
                val format = ex.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")) {
                    val lang = LangTags.norm(format.getString(MediaFormat.KEY_LANGUAGE))
                    if (lang != null) return lang
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "can't read audio language of ${file.name}", e)
            null
        } finally {
            runCatching { ex?.release() }
        }
    }

    // ── Naming and publishing ──────────────────────────────────────────────────

    /** Subtitles saved with the download (sidecar files) plus the stream's own subtitle tracks. */
    private fun subtitleFiles(ctx: Context, m: DownloadMeta): List<Pair<File, String>> {
        val (_, base) = place(m)
        val out = mutableListOf<Pair<File, String>>()
        val names = mutableSetOf<String>()
        fun add(f: File, lang: String?, many: Boolean, i: Int) {
            if (!f.exists() || f.length() == 0L) return
            val ext = f.extension.ifEmpty { "srt" }
            val tag = lang?.takeIf { it.isNotBlank() } ?: if (many) "track$i" else null
            var name = if (tag != null) "$base.$tag.$ext" else "$base.$ext"
            var n = 2
            while (!names.add(name)) name = "$base.${tag ?: "sub"}.${n++}.$ext"
            out += f to name
        }
        val paths = m.subtitles.orEmpty()
        paths.forEachIndexed { i, path -> add(File(path), m.subtitleLangs?.getOrNull(i), paths.size > 1, i) }
        m.subtitle?.takeIf { it !in paths }?.let { add(File(it), m.subtitleLangs?.getOrNull(paths.size), false, paths.size) }
        // Subtitles that came inside the stream itself (HLS text tracks): written out from the download cache
        val d = NovaDownloads.get(ctx, DownloadMeta.id(m.card, m.season, m.episode))
        if (d != null) {
            val stream = FastExport.streamSubtitles(ctx, d, tmpDir(ctx))
            stream.forEachIndexed { i, (f, lang) -> add(f, lang, true, out.size + i) }
        }
        Log.i(TAG, "subtitles to save: ${out.joinToString { it.second }}")
        return out
    }

    /**
     * Reads the saved download (its stream keys keep it to the downloaded renditions). [pick] is the
     * preferred audio language; an MP4 holds one audio track per Transformer export, so [audio] is
     * false when only the video is wanted and [video] is false for the extra audio-only passes.
     */
    private fun assetLoader(ctx: Context, m: DownloadMeta, pick: String?, video: Boolean, audio: Boolean): androidx.media3.transformer.AssetLoader.Factory {
        val lang = pick ?: DownloadStarter.audioFor(m.card)?.takeIf { it != "orig" }
        // Sources tag audio tracks inconsistently ("en" vs "eng"), and the selector only matches
        // the strings it is given. Offer both spellings so every language can actually be picked.
        val langPrefs = lang?.let { l ->
            val n = LangTags.norm(l) ?: l
            listOfNotNull(n, iso3(n)).distinct()
        }.orEmpty()
        return androidx.media3.transformer.DefaultAssetLoaderFactory(
            ctx,
            androidx.media3.transformer.DefaultDecoderFactory.Builder(ctx).build(),
            androidx.media3.common.util.Clock.DEFAULT,
            DefaultMediaSourceFactory(NovaDownloads.playbackFactory(ctx)),
            androidx.media3.datasource.DataSourceBitmapLoader(ctx),
        ) { c ->
            androidx.media3.exoplayer.trackselection.DefaultTrackSelector(c).apply {
                setParameters(buildUponParameters()
                    .setForceHighestSupportedBitrate(true)
                    .setConstrainAudioChannelCountToDeviceCapabilities(false)
                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, !video)
                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, !audio)
                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true)
                    .apply { if (audio && langPrefs.isNotEmpty()) setPreferredAudioLanguages(*langPrefs.toTypedArray()) })
            }
        }
    }

    private fun clean(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), " ").replace(Regex("\\s+"), " ").trim().ifEmpty { "Untitled" }

    /** Relative folder under Movies/ and the base file name (no extension). */
    private fun place(m: DownloadMeta): Pair<String, String> {
        val c = m.card
        val title = clean(c.title)
        return if (c.type == MediaType.MOVIE || m.season <= 0 && m.episode <= 0) {
            val name = if (c.year.isNotBlank()) "$title (${c.year.take(4)})" else title
            "$ROOT/Movies/$name" to name
        } else {
            val kind = if (c.type == MediaType.ANIME) "Anime" else "Shows"
            val s = m.season.coerceAtLeast(1)
            "$ROOT/$kind/$title/Season %02d".format(s) to "$title S%02dE%02d".format(s, m.episode)
        }
    }

    /** Copy into a folder the user picked: <folder>/<Title>/<file>.mp4 plus subtitles, via DocumentsContract. */
    private fun writeTree(ctx: Context, tree: Uri, m: DownloadMeta, video: File?, produce: ((FileOutputStream) -> Unit)? = null) {
        val r = ctx.contentResolver
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val (_, base) = place(m)
        val title = clean(m.card.title)
        // Re-use the title folder from an earlier save. List the picked folder's CHILDREN: querying the
        // folder's own uri returns only itself, which made a new "Title (1)" folder for every file.
        val existing = runCatching {
            r.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(root)), arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
                null, null, null)?.use { c ->
                var found: Uri? = null
                while (c.moveToNext() && found == null) {
                    if (c.getString(1) == title && c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        found = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                    }
                }
                found
            }
        }.getOrNull()
        val dir = existing
            ?: DocumentsContract.createDocument(r, root, DocumentsContract.Document.MIME_TYPE_DIR, title)
            ?: root
        fun replace(name: String) {
            runCatching {
                r.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(dir)), arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        if (c.getString(1) == name) runCatching {
                            DocumentsContract.deleteDocument(r, DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)))
                        }
                    }
                }
            }
        }
        fun copy(name: String, mime: String, f: File?, produce: ((FileOutputStream) -> Unit)? = null) {
            replace(name)
            val uri = DocumentsContract.createDocument(r, dir, mime, name) ?: error("can't create $name")
            try {
                if (produce != null) r.openFileDescriptor(uri, "rw")!!.use { pfd -> FileOutputStream(pfd.fileDescriptor).use(produce) }
                else r.openOutputStream(uri)!!.use { out -> f!!.inputStream().use { it.copyTo(out, 1 shl 20) } }
            } catch (e: Exception) { runCatching { DocumentsContract.deleteDocument(r, uri) }; throw e }
        }
        copy("$base.mp4", "video/mp4", video, produce)
        subtitleFiles(ctx, m).forEach { (f, name) ->
            runCatching { copy(name, if (f.extension == "srt") "application/x-subrip" else "text/vtt", f) }
                .onFailure { Log.w(TAG, "subtitle $name not copied: ${it.message}") }
            if (f.name.startsWith("streamsub_")) f.delete()
        }
    }

    private fun publish(ctx: Context, m: DownloadMeta, video: File?, produce: ((FileOutputStream) -> Unit)? = null) {
        val (folder, base) = place(m)
        write(ctx, folder, "$base.mp4", "video/mp4", video, produce)
        // Video is safe now: a subtitle that can't be written must not make the whole export run again
        subtitleFiles(ctx, m).forEach { (f, name) ->
            runCatching { write(ctx, folder, name, if (f.extension == "srt") "application/x-subrip" else "text/vtt", f) }
                .onFailure { Log.w(TAG, "subtitle $name not copied: ${it.message}") }
            if (f.name.startsWith("streamsub_")) f.delete()
        }
    }

    private fun write(ctx: Context, folder: String, name: String, mime: String, src: File?, produce: ((FileOutputStream) -> Unit)? = null) {
        if (Build.VERSION.SDK_INT >= 29) {
            val r = ctx.contentResolver
            val coll: Uri = if (mime.startsWith("video")) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                            else MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val rel = "${Environment.DIRECTORY_MOVIES}/$folder/"
            // Replace our own earlier copy of the same file (re-download / re-save), never someone else's
            r.query(coll, arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?", arrayOf(rel, name), null)?.use { c ->
                while (c.moveToNext()) runCatching { r.delete(android.content.ContentUris.withAppendedId(coll, c.getLong(0)), null, null) }
            }
            val uri = r.insert(coll, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }) ?: error("insert failed for $name")
            try {
                if (produce != null) r.openFileDescriptor(uri, "rw")!!.use { pfd -> FileOutputStream(pfd.fileDescriptor).use(produce) }
                else r.openOutputStream(uri)!!.use { out -> src!!.inputStream().use { it.copyTo(out, 1 shl 20) } }
                r.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) { r.delete(uri, null, null); throw e }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), folder).apply { mkdirs() }
            val out = File(dir, name)
            if (produce != null) FileOutputStream(out).use(produce) else src!!.copyTo(out, overwrite = true)
            android.media.MediaScannerConnection.scanFile(ctx, arrayOf(out.absolutePath), null, null)
        }
    }
}
