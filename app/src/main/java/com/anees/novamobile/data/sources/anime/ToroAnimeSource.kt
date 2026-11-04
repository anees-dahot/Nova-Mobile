package com.anees.novamobile.data.sources.anime

/*
 * Hindi-dub anime sites on the "torofilm" WordPress theme (AnimeSalt, Anime World India).
 * Chain, verified Nov 2026: ?s= search → /series/ page → season list via admin-ajax
 * (action_select_season) → /episode/<slug>-<S>x<absolute ep>/ → FirePlayer iframe
 * (/video/<32-hex id>) → POST /player/index.php?do=getVideo → one HLS master carrying
 * every dub (Hindi, Tamil, Telugu, Malayalam, Bengali, English, Japanese).
 */

import android.util.Log
import com.anees.novamobile.data.sources.vegamovies.VmHttp
import com.anees.novamobile.data.sources.vegamovies.matchScore
import com.anees.novamobile.ui.player.StreamExtractor
import com.anees.novamobile.ui.player.WebStreamSniffer
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder

class ToroAnimeSource(private val name: String, seed: String) {

    @Volatile private var base = seed

    private data class Hit(val title: String, val url: String, val isSeries: Boolean)

    private val headers get() = VmHttp.commonHeaders

    /** Anime only (AniList cards carry a MAL id): any other title answers "not here" at once. */
    suspend fun resolve(t: StreamExtractor.Target): WebStreamSniffer.Sniffed? {
        if (t.malId == null) return null
        val title = t.title?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val hit = search(title, series = !t.isMovie) ?: return null
        val pageHtml = VmHttp.get(hit.url, headers, 10_000) ?: return null
        val episodeHtml = if (t.isMovie) pageHtml else {
            val ep = episodeUrl(pageHtml, hit.url, t.season, t.episode) ?: return null
            VmHttp.get(ep, headers, 10_000) ?: return null
        }
        val stream = firePlayer(episodeHtml) ?: return null
        Log.i(TAG, "$name: \"${hit.title}\" S${t.season}E${t.episode}")
        return stream
    }

    // ── Search ────────────────────────────────────────────────────────────────

    private suspend fun search(title: String, series: Boolean): Hit? {
        val queries = listOf(title, title.replace(Regex("""[:\-–(].*$"""), "").trim()).distinct().filter { it.length > 1 }
        for (q in queries) {
            val res = VmHttp.getFull("$base/?s=${URLEncoder.encode(q, "UTF-8")}", headers, 10_000) ?: continue
            origin(res.second)?.let { if (it != base) { Log.i(TAG, "$name: domain $base → $it"); base = it } }
            val hits = Jsoup.parse(res.first, res.second).select("article").mapNotNull { a ->
                val href = a.select("a[href]").map { it.absUrl("href").ifBlank { it.attr("href") } }
                    .firstOrNull { it.contains("/series/") || it.contains("/movies/") } ?: return@mapNotNull null
                val name = a.selectFirst(".entry-title")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Hit(name, href, href.contains("/series/"))
            }
            hits.filter { it.isSeries == series }
                .map { it to matchScore(title, it.title, null, null) }
                .filter { it.second >= MIN_MATCH }
                .maxByOrNull { it.second }?.first?.let { return it }
        }
        return null
    }

    // ── Episodes ──────────────────────────────────────────────────────────────

    /**
     * Seasons follow TVDB numbering like our anime targets, but episode numbers are absolute
     * ("2x33" is S2's first): take the Nth episode of the season, else an exact number match.
     */
    private suspend fun episodeUrl(seriesHtml: String, seriesUrl: String, season: Int, episode: Int): String? {
        val post = Regex("""data-post="(\d+)"""").find(seriesHtml)?.groupValues?.get(1)
        val seasonHtml = post?.let {
            VmHttp.post("$base/wp-admin/admin-ajax.php",
                mapOf("action" to "action_select_season", "season" to season.toString(), "post" to it),
                headers + mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to seriesUrl), 10_000)
        }.orEmpty()
        val eps = (EPISODE.findAll(seasonHtml) + EPISODE.findAll(seriesHtml))
            .map { Triple(it.groupValues[0], it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
            .filter { it.second == season }
            .distinctBy { it.third }
            .sortedBy { it.third }
            .toList()
        if (eps.isEmpty()) return null
        return (eps.getOrNull(episode - 1) ?: eps.firstOrNull { it.third == episode })?.first
    }

    // ── FirePlayer ────────────────────────────────────────────────────────────

    private suspend fun firePlayer(html: String): WebStreamSniffer.Sniffed? {
        val frames = Jsoup.parse(html).select("iframe").flatMap { listOf(it.attr("src"), it.attr("data-src")) }
            .map { it.trim() }.filter { FIRE_ID.containsMatchIn(it) }.distinct()
        for (frame in frames) {
            val id = FIRE_ID.find(frame)?.groupValues?.get(1) ?: continue
            val host = origin(frame) ?: continue
            val body = VmHttp.post("$host/player/index.php?data=$id&do=getVideo",
                mapOf("hash" to id, "r" to "$base/"),
                headers + mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to frame), 10_000) ?: continue
            val json = runCatching { JSONObject(body) }.getOrNull() ?: continue
            val url = json.optString("securedLink").ifBlank { json.optString("videoSource") }
                .takeIf { it.startsWith("http") } ?: continue
            val referer = "$host/"
            // The link is short-lived and signed: one fetch proves it plays before the race picks it
            val playlist = VmHttp.get(url, headers + ("Referer" to referer), 6_000) ?: continue
            if (!playlist.trimStart().startsWith("#EXTM3U")) continue
            return WebStreamSniffer.Sniffed(url, mapOf("Referer" to referer), VmHttp.UA, emptyList())
        }
        return null
    }

    private fun origin(url: String) = Regex("""^https?://[^/]+""").find(url)?.value

    companion object {
        private const val TAG = "NovaTV"
        private const val MIN_MATCH = 0.5
        private val EPISODE = Regex("""https?://[^"'\s<>]+/episode/[^"'\s<>/]*?-(\d+)x(\d+)/?""")
        private val FIRE_ID = Regex("""/video/([a-f0-9]{32})""")

        val animeSalt = ToroAnimeSource("AnimeSalt", "https://animesalt.cx")
        val animeWorld = ToroAnimeSource("AnimeWorld", "https://watchanimeworld.one")
    }
}
