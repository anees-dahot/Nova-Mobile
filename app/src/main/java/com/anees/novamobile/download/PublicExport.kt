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

    /** Queue one finished download (safe to call many times). */
    fun enqueue(ctx: Context, id: String) = main.post {
        if (exported(ctx, id) || id == running || id in queue) return@post
        queue.addLast(id)
        next(ctx.applicationContext)
    }

    /** Finished downloads from before this feature, or whose export was interrupted. */
    fun catchUp(ctx: Context) = NovaDownloads.all(ctx)
        .filter { it.state == Download.STATE_COMPLETED && !exported(ctx, it.request.id) }
        .forEach { enqueue(ctx, it.request.id) }

    private fun next(ctx: Context) {
        if (running != null) return
        val id = queue.removeFirstOrNull() ?: return
        val d = NovaDownloads.get(ctx, id)
        val m = d?.let { DownloadMeta.of(it) }
        if (d == null || m == null || d.state != Download.STATE_COMPLETED) { next(ctx); return }
        if (Build.VERSION.SDK_INT < 29 && ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "no storage permission, $id kept in app only"); next(ctx); return
        }
        running = id
        val tmp = File(ctx.cacheDir, "export_${id.hashCode()}.mp4").apply { delete() }
        val transformer = Transformer.Builder(ctx)
            .setAssetLoaderFactory(assetLoader(ctx))
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    Thread {
                        val ok = runCatching { publish(ctx, m, tmp) }.onFailure { Log.w(TAG, "publish $id failed", it) }.isSuccess
                        tmp.delete()
                        main.post {
                            if (ok) prefs(ctx).edit().putBoolean(id, true).apply()
                            running = null; next(ctx)
                        }
                    }.start()
                }
                override fun onError(composition: Composition, result: ExportResult, e: ExportException) {
                    Log.w(TAG, "remux $id failed", e)
                    tmp.delete(); running = null; next(ctx)
                }
            })
            .build()
        transformer.start(d.request.toMediaItem(), tmp.absolutePath)
    }

    /**
     * Reads the saved download (its stream keys keep it to the downloaded renditions) and, since
     * an MP4 carries one audio track here, keeps the language from Settings.
     */
    private fun assetLoader(ctx: Context): androidx.media3.transformer.AssetLoader.Factory {
        val lang = com.anees.novamobile.data.local.LocalStore.settings().audioLang.takeIf { it != "orig" }
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

    private fun publish(ctx: Context, m: DownloadMeta, video: File) {
        val (folder, base) = place(m)
        write(ctx, folder, "$base.mp4", "video/mp4", video)
        val subs = (m.subtitles.orEmpty() + listOfNotNull(m.subtitle)).distinct().map(::File).filter { it.exists() }
        subs.forEachIndexed { i, f ->
            val lang = m.subtitleLangs?.getOrNull(m.subtitles.orEmpty().indexOf(f.path))?.takeIf { it.isNotBlank() }
            val ext = f.extension.ifEmpty { "srt" }
            val tag = lang ?: if (subs.size > 1) "$i" else null
            write(ctx, folder, if (tag != null) "$base.$tag.$ext" else "$base.$ext", if (ext == "srt") "application/x-subrip" else "text/vtt", f)
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
