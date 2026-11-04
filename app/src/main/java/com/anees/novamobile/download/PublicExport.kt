package com.anees.novamobile.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.anees.novamobile.data.model.MediaType
import java.io.File

/**
 * Copies every finished download out of the app's private cache into the phone's public
 * Movies/Nova folder, one folder per title, so it plays in any player and survives uninstall:
 *   Movies/Nova/Movies/<Title (Year)>/<Title (Year)>.mp4
 *   Movies/Nova/Shows/<Title>/Season 01/<Title> S01E02.mp4   (Anime/ for anime)
 * Streams are remuxed (no re-encode). Subtitles are copied beside the video with the same name.
 */
@OptIn(UnstableApi::class)
object PublicExport {
    private const val TAG = "PublicExport"
    private const val ROOT = "Nova"
    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<String>()
    private var running: String? = null

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("public_export", Context.MODE_PRIVATE)
    fun exported(ctx: Context, id: String) = prefs(ctx).getBoolean(id, false)

    /** A save the user asked for: audio language to keep, folder (null = Movies/Nova), result callback. */
    private class Manual(val lang: String?, val tree: Uri?, val onDone: (Boolean, String) -> Unit)
    private val manual = HashMap<String, Manual>()

    /**
     * "Save to phone" from Downloads: export now with [lang] as the audio track (an MP4 copy holds one)
     * into [tree] (a folder the user picked) or Movies/Nova when null. Runs even if already saved.
     */
    fun save(ctx: Context, id: String, lang: String?, tree: Uri?, onDone: (Boolean, String) -> Unit) = main.post {
        manual[id] = Manual(lang, tree, onDone)
        queue.remove(id)
        if (id != running) queue.addFirst(id)
        next(ctx.applicationContext)
    }

    /** Queue one finished download (safe to call many times). */
    fun enqueue(ctx: Context, id: String) = main.post {
        if (exported(ctx, id) || id == running || id in queue) return@post
        queue.addLast(id)
        next(ctx.applicationContext)
    }

    /**
     * Remux target. Not a cache dir: Android purges an app's cache over its quota while the
     * export still runs (Nov 2026: installd deleted a 177 MB export_….mp4 mid-remux → ENOENT).
     */
    private fun tmpDir(ctx: Context) = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "export_tmp").apply { mkdirs() }

    /** Finished downloads from before this feature, or whose export was interrupted. */
    fun catchUp(ctx: Context) {
        if (running == null) tmpDir(ctx).listFiles()?.forEach { it.delete() }   // left by a killed export
        NovaDownloads.all(ctx)
            .filter { it.state == Download.STATE_COMPLETED && !exported(ctx, it.request.id) }
            .forEach { enqueue(ctx, it.request.id) }
    }

    /**
     * Deletes the phone copy (video + subtitles in Movies/Nova). Only files this install wrote can be
     * removed on Android 10+; returns false when nothing could be deleted.
     */
    fun removeCopy(ctx: Context, d: Download): Boolean {
        val id = d.request.id
        val m = DownloadMeta.of(d) ?: return false
        queue.remove(id)
        prefs(ctx).edit().remove(id).apply()
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
        if (Build.VERSION.SDK_INT >= 30) publicDir(folder).listFiles()?.filter { it.name.startsWith("$base.") || it.name.startsWith(".$base.") }?.forEach { if (it.delete()) removed++ }
        Log.i(TAG, "removed $removed phone file(s) for $id")
        return removed > 0
    }

    /** Movies/<folder> on shared storage (file paths work there for our own files, except on Android 10). */
    @Suppress("DEPRECATION")
    private fun publicDir(folder: String) = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), folder)

    /**
     * Android 11+ and 9-: the remux is written straight into Movies/Nova (hidden name, renamed when done),
     * so the copy needs the video's size once. Android 10 has no file-path access there: temp file + MediaStore copy.
     */
    private val direct get() = Build.VERSION.SDK_INT != 29

    private fun next(ctx: Context, viaTemp: Boolean = !direct) {
        if (running != null) return
        val id = queue.removeFirstOrNull() ?: return
        val d = NovaDownloads.get(ctx, id)
        val m = d?.let { DownloadMeta.of(it) }
        val opt = manual[id]
        fun fail(msg: String) { manual.remove(id); opt?.onDone?.invoke(false, msg) }
        if (d == null || m == null || d.state != Download.STATE_COMPLETED) { fail("Download not finished"); next(ctx); return }
        // A folder the user picked is written through the system's document access: always via a temp file
        @Suppress("NAME_SHADOWING") val viaTemp = viaTemp || opt?.tree != null
        if (Build.VERSION.SDK_INT < 29 && ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "no storage permission, $id kept in app only"); fail("Storage permission needed"); next(ctx); return
        }
        val (folder, base) = place(m)
        // Not enough room for the copy (twice over through a temp file): skip now, try again next launch
        val need = (d.bytesDownloaded * if (viaTemp) 2.1 else 1.1).toLong()
        val free = runCatching { android.os.StatFs((ctx.getExternalFilesDir(null) ?: ctx.filesDir).absolutePath).availableBytes }.getOrDefault(Long.MAX_VALUE)
        if (free < need) { Log.w(TAG, "$id: ${free shr 20} MB free, need ${need shr 20} MB, not copied to Movies/Nova"); fail("Not enough space: ${need shr 20} MB needed"); next(ctx); return }
        running = id
        val out = if (viaTemp) File(tmpDir(ctx), "export_${id.hashCode()}.mp4")
            else File(publicDir(folder).apply { mkdirs() }, ".$base.nova-part.mp4")   // dot name: hidden from galleries until done
        out.delete()
        val transformer = Transformer.Builder(ctx)
            .setAssetLoaderFactory(assetLoader(ctx, m, opt?.lang))
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    Thread {
                        val ok = runCatching { when { opt?.tree != null -> writeTree(ctx, opt.tree, m, out); viaTemp -> publish(ctx, m, out); else -> finishDirect(ctx, m, out) } }
                            .onFailure { Log.w(TAG, "publish $id failed", it) }.isSuccess
                        if (viaTemp || !ok) out.delete()
                        main.post {
                            if (ok) { prefs(ctx).edit().putBoolean(id, true).apply(); Log.i(TAG, "$id saved to Movies/$folder") }
                            manual.remove(id)?.onDone?.invoke(ok, if (ok) "Saved ${DownloadNotifier.name(m)}" else "Couldn't save ${DownloadNotifier.name(m)}")
                            running = null; next(ctx)
                        }
                    }.start()
                }
                override fun onError(composition: Composition, result: ExportResult, e: ExportException) {
                    Log.w(TAG, "remux $id failed", e)
                    out.delete(); running = null
                    // Shared-folder write refused on this phone: once more through a temp file
                    if (!viaTemp) { queue.addFirst(id); next(ctx, viaTemp = true) } else { fail("This stream couldn't be converted to MP4"); next(ctx) }
                }
            })
            .build()
        transformer.start(d.request.toMediaItem(), out.absolutePath)
    }

    /** Direct export done: give the video its real name, copy subtitles beside it, let galleries index it. */
    private fun finishDirect(ctx: Context, m: DownloadMeta, part: File) {
        val (folder, base) = place(m)
        val dir = part.parentFile!!
        val video = File(dir, "$base.mp4")
        if (video.exists() && !video.delete()) deleteFromMediaStore(ctx, folder, "$base.mp4")
        if (!part.renameTo(video)) { part.copyTo(video, overwrite = true); part.delete() }
        val scan = mutableListOf(video.absolutePath)
        subtitleFiles(m).forEach { (f, name) ->
            // Android 11+ refuses non-video files in Movies by file path: MediaStore is the second try
            runCatching { f.copyTo(File(dir, name), overwrite = true); scan += File(dir, name).absolutePath }
                .recoverCatching { if (Build.VERSION.SDK_INT >= 29) write(ctx, folder, name, if (f.extension == "srt") "application/x-subrip" else "text/vtt", f) else throw it }
                .onFailure { Log.w(TAG, "subtitle $name not copied: ${it.message}") }
        }
        android.media.MediaScannerConnection.scanFile(ctx, scan.toTypedArray(), null, null)
    }

    private fun deleteFromMediaStore(ctx: Context, folder: String, name: String) {
        if (Build.VERSION.SDK_INT < 29) return
        val r = ctx.contentResolver
        val coll = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        runCatching {
            r.query(coll, arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf("${Environment.DIRECTORY_MOVIES}/$folder/", name), null)?.use { c ->
                while (c.moveToNext()) r.delete(android.content.ContentUris.withAppendedId(coll, c.getLong(0)), null, null)
            }
        }
    }

    /** Subtitle files of a download with the names they get beside the video ("Title S01E02.en.vtt"). */
    private fun subtitleFiles(m: DownloadMeta): List<Pair<File, String>> {
        val (_, base) = place(m)
        val subs = (m.subtitles.orEmpty() + listOfNotNull(m.subtitle)).distinct().map(::File).filter { it.exists() }
        return subs.mapIndexed { i, f ->
            val lang = m.subtitleLangs?.getOrNull(m.subtitles.orEmpty().indexOf(f.path))?.takeIf { it.isNotBlank() }
            val ext = f.extension.ifEmpty { "srt" }
            val tag = lang ?: if (subs.size > 1) "$i" else null
            f to (if (tag != null) "$base.$tag.$ext" else "$base.$ext")
        }
    }

    /**
     * Reads the saved download (its stream keys keep it to the downloaded renditions) and, since
     * an MP4 carries one audio track here, keeps the language from Settings.
     */
    private fun assetLoader(ctx: Context, m: DownloadMeta, pick: String? = null): androidx.media3.transformer.AssetLoader.Factory {
        val lang = (pick ?: DownloadStarter.audioFor(m.card))?.takeIf { it != "orig" }   // the MP4 holds one audio track: your pick for this title, else the stream default
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
                    .apply { if (lang != null) setPreferredAudioLanguages(lang, "en") })
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
    private fun writeTree(ctx: Context, tree: Uri, m: DownloadMeta, video: File) {
        val r = ctx.contentResolver
        val root = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, android.provider.DocumentsContract.getTreeDocumentId(tree))
        val (_, base) = place(m)
        val dir = android.provider.DocumentsContract.createDocument(r, root, android.provider.DocumentsContract.Document.MIME_TYPE_DIR, clean(m.card.title)) ?: root
        fun copy(name: String, mime: String, f: File) {
            val uri = android.provider.DocumentsContract.createDocument(r, dir, mime, name) ?: error("can't create $name")
            r.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out, 1 shl 20) } }
        }
        copy("$base.mp4", "video/mp4", video)
        subtitleFiles(m).forEach { (f, name) -> runCatching { copy(name, "text/vtt", f) } }
    }

    private fun publish(ctx: Context, m: DownloadMeta, video: File) {
        val (folder, base) = place(m)
        write(ctx, folder, "$base.mp4", "video/mp4", video)
        // Video is safe now: a subtitle that can't be written must not make the whole export run again
        subtitleFiles(m).forEach { (f, name) ->
            runCatching { write(ctx, folder, name, if (f.extension == "srt") "application/x-subrip" else "text/vtt", f) }
                .onFailure { Log.w(TAG, "subtitle $name not copied: ${it.message}") }
        }
    }

    private fun write(ctx: Context, folder: String, name: String, mime: String, src: File) {
        if (Build.VERSION.SDK_INT >= 29) {
            val r = ctx.contentResolver
            val coll: Uri = if (mime.startsWith("video")) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                            else MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val rel = "${Environment.DIRECTORY_MOVIES}/$folder/"
            // Replace our own earlier copy of the same file (re-download), never someone else's
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
                r.openOutputStream(uri)!!.use { out -> src.inputStream().use { it.copyTo(out, 1 shl 20) } }
                r.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) { r.delete(uri, null, null); throw e }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), folder).apply { mkdirs() }
            val out = File(dir, name)
            src.copyTo(out, overwrite = true)
            android.media.MediaScannerConnection.scanFile(ctx, arrayOf(out.absolutePath), null, null)
        }
    }
}
