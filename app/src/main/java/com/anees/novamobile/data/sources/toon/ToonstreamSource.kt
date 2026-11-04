package com.anees.novamobile.data.sources.toon

import android.util.Log
import com.anees.novamobile.data.sources.vegamovies.VmHttp
import com.anees.novamobile.data.sources.vegamovies.matchScore
import com.anees.novamobile.ui.player.StreamExtractor
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Toonstream: anime and cartoons with Hindi/Tamil/Telugu/English/Japanese in one stream ("Multi"
 * servers). Its episode pages list third-party players (filesforever, upns, vidmoly, …), so this
 * only finds the player page; the hidden WebView sniffs the stream from it like any embed source.
 */
object ToonstreamSource {
    private const val TAG = "NovaSrc"
    /** Mirrors; .one redirects to the live one (Nov 2026: toonstream.us). */
    private val DOMAINS = listOf("https://toonstream.us", "https://toonstream.vip", "https://toonstream.one")
    private var live: String? = null
    private const val MIN_MATCH = 0.6

    /** Player page for the episode (or movie), or null when Toonstream doesn't have it. */
    suspend fun page(t: StreamExtractor.Target): String? {
        val title = t.title?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val q = URLEncoder.encode(title, "UTF-8")
        var base: String? = null
        var json: String? = null
        for (d in listOfNotNull(live) + DOMAINS.filter { it != live }) {
            json = VmHttp.get("$d/search/all?q=$q", VmHttp.commonHeaders + ("Referer" to "$d/"), 8_000)?.takeIf { it.trimStart().startsWith("{") }
            if (json != null) { base = d; live = d; break }
        }
        if (base == null || json == null) return null
        val rows = runCatching { JSONObject(json).optJSONArray("data") }.getOrNull() ?: return null
        val kind = if (t.isMovie) "movie" else "series"
        val best = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
            .filter { it.optString("type").startsWith(kind) || (t.isMovie && it.optString("type").startsWith("movies")) }
            .map { it to matchScore(title, it.optString("title"), null, null) }
            .maxByOrNull { it.second }
        if (best == null || best.second < MIN_MATCH) return null
        val path = best.first.optString("url").takeIf { it.startsWith("/") } ?: return null
        val pageUrl = if (t.isMovie) base + path
            else "$base/episode/${path.trimEnd('/').substringAfterLast('/')}-${t.season}x${t.episode}/"
        val html = VmHttp.get(pageUrl, VmHttp.commonHeaders + ("Referer" to "$base/"), 10_000) ?: return null
        // Players in page order; YouTube trailers aren't streams
        val players = Regex("""data-src="(https?://[^"]+)"""").findAll(html).map { it.groupValues[1] }
            .filterNot { it.contains("youtube", true) }.distinct().toList()
        Log.i(TAG, "toonstream: \"${best.first.optString("title")}\" ${players.size} player(s)")
        return players.firstOrNull()
    }
}
