package com.anees.novamobile.data.sources.anime

import android.util.Log
import com.anees.novamobile.data.sources.vegamovies.VmHttp
import com.anees.novamobile.data.sources.vegamovies.matchScore
import com.anees.novamobile.ui.player.StreamExtractor
import com.anees.novamobile.ui.player.WebStreamSniffer
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * AniZone (anizone.to, approach from the Nuvio AniZone provider): one HLS with many audio
 * renditions (Nov 2026, Frieren E1: Hindi, Tamil, English, Japanese, German, Spanish, French).
 * Search page → anime slug → /anime/<slug>/<episode> → the vidstack player's master playlist.
 */
object AniZoneSource {
    private const val TAG = "NovaSrc"
    private const val BASE = "https://anizone.to"
    private val HEADERS = VmHttp.commonHeaders + ("Referer" to "$BASE/")
    private const val MIN_MATCH = 0.6

    suspend fun resolve(t: StreamExtractor.Target): WebStreamSniffer.Sniffed? {
        val title = t.title?.trim()?.takeIf { it.isNotBlank() } ?: return null
        // AniList episode numbers when the card is an anime; series cards keep TMDB's
        val ep = if (t.isMovie) 1 else t.aniEpisode ?: t.episode
        val wantSeason = if (t.isMovie || t.aniEpisode != null) 1 else t.season
        val query = title.substringBefore(':').replace(Regex("(?i)season.*"), "").trim().ifBlank { title }
        val cards = search(query).ifEmpty { if (query != title) search(title) else emptyList() }
        val seasonTag = Regex("""(?i)(season\s*$wantSeason\b|\b$wantSeason(st|nd|rd|th)\s*season|part\s*$wantSeason\b)""")
        val best = cards.map { (slug, titles) ->
            val score = titles.maxOfOrNull { maxOf(matchScore(title, it, null, null), matchScore(query, it, null, null)) } ?: 0.0
            val seasonOk = if (wantSeason <= 1) titles.none { Regex("(?i)season\\s*[2-9]|[2-9](nd|rd|th) season").containsMatchIn(it) }
                else titles.any { seasonTag.containsMatchIn(it) }
            Triple(slug, titles, score + if (seasonOk) 0.5 else 0.0)
        }.filter { it.third >= MIN_MATCH + 0.5 }.maxByOrNull { it.third }
            ?: run { Log.i(TAG, "anizone: no match for \"$title\" S$wantSeason"); return null }
        val html = VmHttp.get("$BASE/anime/${best.first}/$ep", HEADERS, 10_000) ?: return null
        val data = Regex("""vidstackPlayer\(JSON\.parse\('((?:[^'\\]|\\.)*)'\)\)""").find(html)?.groupValues?.get(1)
            ?.let { runCatching { JSONObject(unescape(it)) }.getOrNull() }
        val src = data?.optString("src")?.replace("\\/", "/")?.takeIf { it.startsWith("http") }
            ?: Regex("""https://[^"']+/master\.m3u8""").find(html)?.value ?: return null
        // Its subtitles are .ass (styled): the player reads WebVTT/SRT, so OpenSubtitles fills in
        val subs = data?.optJSONArray("subtitles")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("file") } }
            .orEmpty().map { it.replace("\\/", "/") }.filter { it.endsWith(".vtt") || it.endsWith(".srt") }.take(4)
        Log.i(TAG, "anizone: \"${best.second.firstOrNull()}\" ep $ep")
        return WebStreamSniffer.Sniffed(src, mapOf("Referer" to "$BASE/"), VmHttp.UA, subs)
    }

    /** slug → every title the site lists for it. */
    private suspend fun search(q: String): List<Pair<String, List<String>>> {
        val html = VmHttp.get("$BASE/anime?search=${URLEncoder.encode(q, "UTF-8")}&sort=title-asc", HEADERS, 10_000) ?: return emptyList()
        val raw = Regex("""items:\s*JSON\.parse\('((?:[^'\\]|\\.)*)'\)""").find(html)?.groupValues?.get(1) ?: return emptyList()
        val arr = runCatching { JSONArray(unescape(raw)) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { o ->
            val slug = o.optString("slug").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val titles = listOfNotNull(o.optString("main_title").takeIf { it.isNotBlank() }) +
                (o.optJSONObject("title_list")?.let { tl -> tl.keys().asSequence().mapNotNull { tl.optString(it).takeIf { v -> v.isNotBlank() } }.toList() }.orEmpty())
            slug to titles.distinct()
        }
    }

    /** Body of a JS '…' string literal: \uXXXX, \/, \', \" and \\ escapes. */
    private fun unescape(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                val n = s[i + 1]
                val code = if (n == 'u' && i + 5 < s.length) s.substring(i + 2, i + 6).toIntOrNull(16) else null
                if (code != null) { out.append(code.toChar()); i += 6; continue }
                out.append(when (n) { 'n' -> '\n'; 't' -> '\t'; else -> n }); i += 2; continue
            }
            out.append(c); i++
        }
        return out.toString()
    }
}
