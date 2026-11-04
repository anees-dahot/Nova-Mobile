package com.anees.novamobile.ui.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Subtitles from these sources come with junk: a "thumbnails.vtt" preview file (image paths that
 * show up as text) and ad cues like "Advertise your product… OpenSubtitles.org".
 *
 * Subtitle URLs are passed to ExoPlayer as `novasub://…`; when (and only when) a subtitle track
 * is turned on, this downloads it on the loader thread, strips ad cues and plays the clean copy.
 * Zero cost at startup.
 */
@UnstableApi
object SubtitleCleaner {
    private const val SCHEME = "novasub"
    private val AD = Regex(
        "opensubtitles|advertise your|subtitles? (by|from|downloaded)|synced (and|&) corrected|corrected by|" +
        "ripped by|encoded by|vip member|subscene|yify|yts\\.|addic7ed|podnapisi|subdl|www\\.|https?://|\\.(com|org|net)\\b",
        RegexOption.IGNORE_CASE
    )

    /** Two-letter language guessed from a subtitle URL ("…/eng.vtt" → "en"), or null. */
    fun langOf(url: String): String? =
        Regex("""[/_\-.](en|eng|english|ar|ara|arabic|fr|es|de|ur|hi|hin|hindi)[/_\-.]""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1)?.lowercase()?.take(2)

    fun isThumbnailTrack(url: String) = Regex("thumb|sprite|storyboard|preview", RegexOption.IGNORE_CASE).containsMatchIn(url)

    fun wrap(url: String): Uri = Uri.parse("$SCHEME://sub?u=" + Uri.encode(url))

    /** Data source that resolves novasub:// to a cleaned local file; everything else passes through. */
    fun factory(ctx: Context, http: OkHttpDataSource.Factory, client: OkHttpClient, headers: Map<String, String>, bypassProxy: Boolean = true) =
        ResolvingDataSource.Factory(
            // PNG-disguised video chunks are unwrapped before the HLS extractor sees them
            DataSource.Factory { PngStripDataSource(DefaultDataSource.Factory(ctx, http).createDataSource()) }
        ) { spec: DataSpec ->
            if (spec.uri.scheme != SCHEME) { if (bypassProxy) spec.withUri(StreamExtractor.unwrapProxy(spec.uri)) else spec }   // skip slow proxies
            else spec.withUri(Uri.fromFile(clean(ctx, spec.uri.getQueryParameter("u") ?: "", client, headers)))
        }

    /** For downloads: clean a subtitle file and copy it next to the video. */
    fun saveTo(ctx: Context, url: String, client: OkHttpClient, headers: Map<String, String>, out: File): Boolean =
        runCatching { clean(ctx, url, client, headers).copyTo(out, overwrite = true); out.length() > 20 }.getOrDefault(false)

    private fun clean(ctx: Context, url: String, client: OkHttpClient, headers: Map<String, String>): File {
        val dir = File(ctx.cacheDir, "subs").apply { mkdirs() }
        val out = File(dir, "${url.hashCode()}.${if (url.lowercase().contains(".srt")) "srt" else "vtt"}")
        if (out.exists()) return out
        val raw = runCatching {
            val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
            client.newCall(req).execute().use { it.body?.string() } ?: ""
        }.getOrDefault("")

        val text = if (raw.contains("#xywh") || Regex("\\.(jpe?g|png|webp)\\b").findAll(raw).count() > 3) {
            "WEBVTT\n\n"   // preview thumbnails, not subtitles
        } else {
            val blocks = raw.replace("\r\n", "\n").split(Regex("\n{2,}"))
            val kept = blocks.filterNot { b -> b.contains("-->") && b.lines().filter { !it.contains("-->") }.any { AD.containsMatchIn(it) } }
            Log.i("NovaTV", "subtitle cleaned: ${blocks.size - kept.size} ad cues removed")
            kept.joinToString("\n\n")
        }
        out.writeText(text)
        return out
    }
}
