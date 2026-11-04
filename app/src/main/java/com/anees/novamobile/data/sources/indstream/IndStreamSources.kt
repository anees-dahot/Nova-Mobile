package com.anees.novamobile.data.sources.indstream

/*
 * Ported from Indflix (https://github.com/dipender98/Indflix), GPL-3.0.
 * Original: IndStream/src/main/kotlin/com/indstream/StreamEngine.kt
 *   (resolveNetmirrorDirect, netmirrorDubLabel, resolveBraflix/braflixSource, VaPlayer JSON API)
 *   and IndStream/src/main/kotlin/com/indstream/ServerRegistry.kt (endpoint templates).
 * Adapted: one Sniffed per source instead of a RawStream list (the player picks one link),
 * NetMirror chooses the dub matching the app's audio setting instead of returning every dub,
 * HTTP via the shared OkHttp client (VmHttp.get) with coroutine cancellation.
 */

import android.util.Log
import com.anees.novamobile.data.local.LocalStore
import com.anees.novamobile.data.sources.vegamovies.VmHttp
import com.anees.novamobile.ui.player.StreamExtractor
import com.anees.novamobile.ui.player.WebStreamSniffer
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

object IndStreamSources {

    private const val TAG = "NovaTV"
    private val UA = VmHttp.UA

    /** TMDB → IMDB ids learned from NetMirror/Braflix answers (VaPlayer only takes IMDB). */
    private val imdbCache = ConcurrentHashMap<Int, String>()

    private fun headers(referer: String, origin: String? = null) = buildMap {
        put("User-Agent", UA); put("Referer", referer)
        if (origin != null) put("Origin", origin)
    }

    private fun json(text: String?): JSONObject? = text?.let { runCatching { JSONObject(it) }.getOrNull() }

    // ── NetMirror (net79.cc): TMDB id → signed mp4s, one subject per dub ─────────────

    private const val NM_BASE = "https://net79.cc"
    private const val NM_PLAY_REFERER = "https://videodownloader.site/"

    /** App audio setting → NetMirror variant language ("Hindi dub" → "hindi"). */
    private val DUB_NAMES = mapOf("hi" to listOf("hindi"), "ur" to listOf("urdu"), "en" to listOf("english", "eng"))

    suspend fun netmirror(t: StreamExtractor.Target): WebStreamSniffer.Sniffed? = coroutineScope {
        if (!t.isMovie && (t.season <= 0 || t.episode <= 0)) return@coroutineScope null
        val type = if (t.isMovie) "movie" else "tv"
        val h = headers("$NM_BASE/")
        val tv = if (t.isMovie) "" else "&se=${t.season}&ep=${t.episode}"
        suspend fun embed(dub: Pair<String, String>?): JSONObject? {
            val q = "?type=$type$tv" + (dub?.let { "&dub=${it.first}&dubdp=${it.second}" } ?: "")
            return json(VmHttp.get("$NM_BASE/api/embed-tmdb/${t.tmdbId}$q", h, 12_000))
        }
        val rootA = async { embed(null) }
        val varsA = async {
            val vq = if (t.isMovie) "" else "?se=${t.season}&ep=${t.episode}"
            json(VmHttp.get("$NM_BASE/api/variants-tmdb/$type/${t.tmdbId}$vq", h, 6_000))
        }
        val root = rootA.await()?.takeIf { it.optBoolean("ok", false) }
        root?.optString("imdb")?.takeIf { it.startsWith("tt") }?.let { imdbCache[t.tmdbId] = it }

        // The wanted dub, when this title has one
        val want = DUB_NAMES[LocalStore.settings().audioLang].orEmpty()
        val dub = if (want.isEmpty()) null else runCatching {
            val arr = varsA.await()?.optJSONArray("variants") ?: return@runCatching null
            (0 until arr.length()).asSequence().mapNotNull { arr.optJSONObject(it) }.firstOrNull { v ->
                val lang = Regex("""^(.*\S)\s+dub$""", RegexOption.IGNORE_CASE)
                    .find(v.optString("language").trim())?.groupValues?.get(1)?.lowercase()
                lang != null && lang in want
            }?.let { it.optString("dubSubjectId") to it.optString("detailPath") }
                ?.takeIf { it.first.isNotBlank() && it.second.isNotBlank() }
        }.getOrNull()
        varsA.cancel()
        val chosen = dub?.let { embed(it)?.takeIf { r -> r.optBoolean("ok", false) } } ?: root
            ?: return@coroutineScope null

        val url = bestMp4(chosen) ?: return@coroutineScope null
        Log.i(TAG, "netmirror: ${chosen.optString("title")}${if (dub != null) " (dub)" else ""}")
        WebStreamSniffer.Sniffed(url, mapOf("Referer" to NM_PLAY_REFERER), UA, captions(chosen))
    }

    /** Highest resolution at or under the Max Quality setting; the lone `mp4` field otherwise. */
    private fun bestMp4(root: JSONObject): String? {
        val cap = LocalStore.settings().maxQuality
        val arr = root.optJSONArray("streams")
        val all = (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optJSONObject(it) }
            .mapNotNull { o -> o.optString("url").takeIf { it.startsWith("http") }?.let { it to o.optInt("resolution", 0) } }
        val pick = all.filter { it.second <= cap }.maxByOrNull { it.second } ?: all.minByOrNull { it.second }
        return pick?.first ?: root.optString("mp4").takeIf { it.startsWith("http") }
    }

    /** English captions first; NetMirror lists them as site-relative proxy paths. */
    private fun captions(root: JSONObject): List<String> {
        val arr = root.optJSONArray("captions") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            .sortedByDescending { it.optString("lang").startsWith("en") }
            .mapNotNull { o -> o.optString("url").takeIf { it.isNotBlank() }?.let { if (it.startsWith("http")) it else NM_BASE + it } }
            .take(4)
    }

    // ── VaPlayer: IMDB id → HLS master playlists ────────────────────────────────────

    private const val VA_REFERER = "https://nextgencloudfabric.com/"

    suspend fun vaplayer(t: StreamExtractor.Target): WebStreamSniffer.Sniffed? {
        val imdb = imdbOf(t) ?: return null
        val q = if (t.isMovie) "imdb=$imdb&type=movie" else "imdb=$imdb&type=tv&season=${t.season}&episode=${t.episode}"
        val data = json(VmHttp.get("https://streamdata.vaplayer.ru/api.php?$q", headers(VA_REFERER), 12_000))
            ?.optJSONObject("data") ?: return null
        val urls = data.optJSONArray("stream_urls") ?: return null
        val url = (0 until urls.length()).map { urls.optString(it) }.firstOrNull { it.startsWith("http") } ?: return null
        Log.i(TAG, "vaplayer: ${data.optString("file_name")}")
        return WebStreamSniffer.Sniffed(url, mapOf("Referer" to VA_REFERER), UA, emptyList())
    }

    /** The card's IMDB id, else one learned from NetMirror (cached, or a quick lookup). */
    private suspend fun imdbOf(t: StreamExtractor.Target): String? {
        t.imdbId?.takeIf { it.startsWith("tt") }?.let { return it }
        imdbCache[t.tmdbId]?.let { return it }
        val type = if (t.isMovie) "movie" else "tv"
        val imdb = json(VmHttp.get("$NM_BASE/api/embed-tmdb/${t.tmdbId}?type=$type", headers("$NM_BASE/"), 8_000))
            ?.optString("imdb")?.takeIf { it.startsWith("tt") } ?: return null
        imdbCache[t.tmdbId] = imdb
        return imdb
    }

    // ── Braflix: TMDB id → DASH manifest behind its own cookie-signing proxy ──────────

    private const val BF_BASE = "https://braflix.dev"

    suspend fun braflix(t: StreamExtractor.Target): WebStreamSniffer.Sniffed? {
        if (!t.isMovie && (t.season <= 0 || t.episode <= 0)) return null
        val path = if (t.isMovie) "/api/play/movie/${t.tmdbId}" else "/api/play/tv/${t.tmdbId}/${t.season}/${t.episode}"
        val h = headers("$BF_BASE/", BF_BASE)
        var result: JSONObject? = null
        for (attempt in 0..2) {                       // a "preparing" answer turns "ready" within seconds
            val root = json(VmHttp.get(BF_BASE + path, h, 12_000)) ?: return null
            result = root.optJSONObject("result")
            if (result?.optJSONObject("file") != null || root.optString("status") != "preparing") break
            delay(2_000)
        }
        val r = result ?: return null
        r.optJSONObject("media")?.optString("imdbId")?.takeIf { it.startsWith("tt") }?.let { imdbCache[t.tmdbId] = it }
        val stream = r.optJSONObject("file")?.optString("stream")?.takeIf { it.isNotBlank() } ?: return null
        val url = if (stream.startsWith("http")) stream else BF_BASE + stream
        Log.i(TAG, "braflix: ${r.optJSONObject("source")?.optString("quality")}")
        return WebStreamSniffer.Sniffed(url, mapOf("Referer" to "$BF_BASE/", "Origin" to BF_BASE), UA, emptyList())
    }
}
