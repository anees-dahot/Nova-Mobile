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

    /**
     * Everything is stored as WebVTT, so saved downloads (always played as VTT) work for SubRip
     * sources too: SRT differs only by the header and the comma before milliseconds.
     */
    private fun toVtt(text: String): String {
        val t = text.trimStart()
        if (t.startsWith("WEBVTT")) return t
        return "WEBVTT\n\n" + t.replace(Regex("""(\d{2}:\d{2}:\d{2}),(\d{3})"""), "$1.$2")
    }

    /**
     * Karaoke song lyrics (converted from styled .ass subs) arrive as one cue per syllable, all at the
     * same time with their positions lost, so they stack into a tall column of "yu / shi / ri…".
     * A time slot holding 4+ tiny cues is karaoke: drop it, keep normal dialogue.
     */
    fun dropKaraoke(blocks: List<String>): List<String> {
        fun timing(b: String) = b.lines().firstOrNull { it.contains("-->") }?.substringBefore(" line")?.substringBefore(" position")?.trim()
        fun text(b: String) = b.lines().filterNot { it.contains("-->") || it.trim().matches(Regex("\\d+")) }.joinToString(" ").replace(Regex("<[^>]+>|\\{[^}]*\\}"), "").trim()
        val groups = blocks.groupBy { timing(it) }
        val karaoke = groups.filter { (t, g) -> t != null && g.size >= 4 && g.count { text(it).length <= 4 } >= g.size * 3 / 4 }.keys
        return if (karaoke.isEmpty()) blocks else blocks.filterNot { timing(it) in karaoke }
    }

    /** Saved subtitle files from before the karaoke fix: clean them once when played offline. */
    fun fixFile(f: java.io.File) = runCatching {
        val raw = f.readText(); if (!raw.contains("-->")) return@runCatching
        val blocks = raw.replace("\r\n", "\n").split(Regex("\n{2,}"))
        val kept = dropKaraoke(blocks)
        if (kept.size != blocks.size) { f.writeText(kept.joinToString("\n\n")); Log.i("NovaTV", "subtitle: ${blocks.size - kept.size} karaoke cues removed") }
    }

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

    /** The cleaned WebVTT file the player reads for [url] (fetched now if it isn't cached yet). Blocking. */
    fun cleaned(ctx: Context, url: String, client: OkHttpClient, headers: Map<String, String>): File = clean(ctx, url, client, headers)

    private fun clean(ctx: Context, url: String, client: OkHttpClient, headers: Map<String, String>): File {
        val dir = File(ctx.cacheDir, "subs").apply { mkdirs() }
        val out = File(dir, "${url.hashCode()}.vtt")
        if (out.exists() && out.length() > 20) return out   // an old empty file is fetched again
        fun fetch(h: Map<String, String>): String? = runCatching {
            val req = Request.Builder().url(url).apply { h.forEach { (k, v) -> header(k, v) } }.build()
            val bytes = client.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null } ?: return null
            // OpenSubtitles hands out gzip files
            val plain = if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte())
                java.util.zip.GZIPInputStream(bytes.inputStream()).readBytes() else bytes
            String(plain, Charsets.UTF_8).removePrefix("\uFEFF").takeIf { it.contains("-->") }
        }.getOrNull()
        // Stream headers first (some hosts need the Referer); subtitle CDNs that refuse them get a plain retry
        val raw = fetch(headers) ?: fetch(emptyMap())
        if (raw == null) {
            // Not cached: a failed fetch (timeout, 403, error page) used to stick as an empty track for good
            Log.w("NovaTV", "subtitle unavailable: ${url.take(120)}")
            return File(dir, "unavailable.vtt").apply { writeText("WEBVTT\n\n") }
        }

        val text = if (raw.contains("#xywh") || Regex("\\.(jpe?g|png|webp)\\b").findAll(raw).count() > 3) {
            "WEBVTT\n\n"   // preview thumbnails, not subtitles
        } else {
            val blocks = raw.replace("\r\n", "\n").split(Regex("\n{2,}"))
            val kept = blocks.filterNot { b -> b.contains("-->") && b.lines().filter { !it.contains("-->") }.any { AD.containsMatchIn(it) } }
            Log.i("NovaTV", "subtitle cleaned: ${blocks.size - kept.size} ad cues removed")
            toVtt(dropKaraoke(kept).joinToString("\n\n"))
        }
        out.writeText(text)
        return out
    }
}
